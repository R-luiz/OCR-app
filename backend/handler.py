"""RunPod Serverless worker for Baidu Unlimited-OCR.

Takes one job shape::

    {"input": {
        "images": ["data:image/jpeg;base64,..." | "<bare base64>", ...],
        "long_context": true
    }}

``long_context`` is optional and only matters with more than one image. Any other key
is ignored, so older clients that still send ``mode`` and image-size settings keep
working.

and returns::

    {"markdown": "...", "pages": [{"index": 0, "markdown": "..."}], "empty_pages": [],
     "page_mode": "per_page", "model": "...", "elapsed_ms": 1234, "warnings": ["..."]}

(``warnings`` is left out when there are none) or ``{"error": "..."}``, which RunPod
turns into a FAILED job.

The model emits a layout stream, not Markdown; :mod:`postprocess` converts it.

``pages`` contains one entry per input image, in order. ``empty_pages`` lists the
zero-based indexes with no text; ``markdown`` joins only the recognized content.

Multi-page requests go to the model's long-context path in windows of at most
``LONG_CONTEXT_WINDOW_PAGES`` pages. If a window raises, is cut off, or returns the
wrong number of page markers, it is parsed page by page instead and reported in
``warnings`` so callers can distinguish the result from an uninterrupted pass.

vLLM is imported lazily so this module can be imported (and its pure helpers tested)
on a machine with no GPU.
"""

from __future__ import annotations

import base64
import binascii
import io
import os
import time
from dataclasses import dataclass
from typing import Any

from PIL import Image

import postprocess

MODEL_NAME = os.environ.get("MODEL_NAME", "baidu/Unlimited-OCR")
# The checkpoint calls its model type "unlimited_ocr"; vLLM registers "unlimited-ocr".
VLLM_MODEL_TYPE = "unlimited-ocr"
MAX_MODEL_LEN = int(os.environ.get("MAX_MODEL_LEN", "32768"))
GPU_MEMORY_UTILIZATION = float(os.environ.get("GPU_MEMORY_UTILIZATION", "0.85"))
MAX_PAGES = int(os.environ.get("MAX_PAGES", "64"))

# Bounds on a single page, applied before any large allocation. The app sends
# 1024px JPEGs a few hundred KB in size, so these are generous for legitimate
# traffic while refusing decompression bombs.
MAX_IMAGE_BYTES = int(os.environ.get("MAX_IMAGE_BYTES", str(25 * 1024 * 1024)))
MAX_IMAGE_PIXELS = int(os.environ.get("MAX_IMAGE_PIXELS", str(80_000_000)))

# Pillow's own guard, as a second line of defence for any path that reaches
# decoding without going through decode_image().
Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS

IMAGE_TOKEN = "<image>"
PROMPT_SINGLE = f"{IMAGE_TOKEN}document parsing."
MULTI_INSTRUCTION = "Multi page parsing."


def build_multi_prompt(page_count: int) -> str:
    """One ``<image>`` per page, then the instruction.

    This is the format the model's own processor requires, read out of the image
    rather than inferred: ``DeepseekOCRProcessor.tokenize_with_images`` opens with

        assert conversation.count(self.image_token) == len(images)

    and splits the prompt on that token to pair each text run with its image.
    ``DeepseekOCRDummyInputsBuilder.get_dummy_text`` builds ``image_token *
    num_images`` the same way, with no separator between placeholders.
    """
    return IMAGE_TOKEN * max(page_count, 1) + MULTI_INSTRUCTION


# Whether multi-page requests go through the model's long-context path — every page
# in one generate() call, so the decoder carries context across page boundaries —
# rather than being inferred a page at a time. Settable per request; this is the
# default when the request says nothing.
LONG_CONTEXT_DEFAULT = os.environ.get("LONG_CONTEXT_MULTI", "1").lower() not in (
    "0", "false", "no",
)

# Most pages sent to the long-context path in one generate() call. A longer document is
# split into windows (see page_windows) so each pass stays well inside the model's
# context instead of running into its limit.
LONG_CONTEXT_WINDOW_PAGES = max(1, int(os.environ.get("LONG_CONTEXT_WINDOW_PAGES", "12")))

# Baidu's inference examples use these for both Transformers and SGLang.
NGRAM_SIZE = 35
NGRAM_WINDOW_SINGLE = 128
NGRAM_WINDOW_MULTI = 1024

# How many times a page that came back empty is re-run on its own before the result
# is reported as missing. Two is enough for the batching flake seen in practice while
# bounding the worst case at three passes over a single page.
EMPTY_PAGE_RETRIES = 2


class InvalidInput(ValueError):
    """Raised for a malformed job payload; surfaced to the client as an error."""


@dataclass
class OcrRequest:
    images: list[Image.Image]
    # True only with several images, when the request asks for it (or says nothing and
    # LONG_CONTEXT_DEFAULT is on). A single image always takes the per-page path.
    long_context: bool = False


# --------------------------------------------------------------------------------------
# Pure helpers (no GPU, no vLLM) — these are what tests exercise.
# --------------------------------------------------------------------------------------


def decode_image(encoded: str) -> Image.Image:
    """Decodes a bare base64 string or a ``data:`` URL into an RGB image."""
    if not isinstance(encoded, str) or not encoded.strip():
        raise InvalidInput("images entries must be non-empty strings")

    payload = encoded.strip()
    if payload.startswith("data:"):
        _, _, payload = payload.partition(",")
        if not payload:
            raise InvalidInput("data URL contained no base64 payload")

    try:
        raw = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise InvalidInput(f"could not base64-decode image: {exc}") from exc

    if len(raw) > MAX_IMAGE_BYTES:
        raise InvalidInput(
            f"image is {len(raw)} bytes, limit is {MAX_IMAGE_BYTES}"
        )

    try:
        image = Image.open(io.BytesIO(raw))
    except Exception as exc:  # Pillow raises a wide variety here.
        raise InvalidInput(f"could not decode image bytes: {exc}") from exc

    # Check dimensions from the header before load() allocates anything: a few KB
    # of crafted PNG can otherwise declare a gigapixel canvas and exhaust the
    # worker's memory, taking the GPU down with it.
    width, height = image.size
    if width * height > MAX_IMAGE_PIXELS:
        raise InvalidInput(
            f"image is {width}x{height} ({width * height} pixels), limit is {MAX_IMAGE_PIXELS}"
        )

    try:
        image.load()
    except Exception as exc:  # noqa: BLE001 - truncated or malformed payloads
        raise InvalidInput(f"could not read image data: {exc}") from exc

    # The model expects 3-channel input; incoming PNGs may carry alpha or be grayscale.
    return image.convert("RGB")


def parse_request(job_input: Any) -> OcrRequest:
    """Validates and normalizes a job payload into an :class:`OcrRequest`."""
    if not isinstance(job_input, dict):
        raise InvalidInput("input must be an object")

    images = job_input.get("images")
    if not isinstance(images, list) or not images:
        raise InvalidInput("input.images must be a non-empty array")
    if len(images) > MAX_PAGES:
        raise InvalidInput(f"input.images holds {len(images)} pages, limit is {MAX_PAGES}")

    decoded = [decode_image(entry) for entry in images]
    long_context = len(decoded) > 1 and bool(
        job_input.get("long_context", LONG_CONTEXT_DEFAULT)
    )
    return OcrRequest(images=decoded, long_context=long_context)


PAGE_SEPARATOR = "\n\n"


def empty_page_indexes(markdowns: list[str]) -> list[int]:
    """Indexes of pages that produced no text."""
    return [index for index, markdown in enumerate(markdowns) if not markdown.strip()]


def page_windows(count: int, size: int) -> list[range]:
    """Splits ``range(count)`` into the fewest consecutive windows of at most ``size``.

    Sizes are as equal as possible, larger windows first: 13 pages with a size of 12 is
    7 + 6, not 12 + 1, because a lone trailing page would lose the cross-page context
    the long-context pass exists for.
    """
    if size < 1:
        raise ValueError(f"window size must be at least 1, got {size}")
    if count <= 0:
        return []
    window_count = -(-count // size)
    base, extra = divmod(count, window_count)
    windows = []
    start = 0
    for index in range(window_count):
        length = base + (1 if index < extra else 0)
        windows.append(range(start, start + length))
        start += length
    return windows


# Key under SamplingParams.extra_args carrying the per-request n-gram window. vLLM's
# V1 engine registers logits processors at engine construction, not per request, so
# extra_args is the only per-request channel left to vary the window between the
# single-page (128) and multi-page (1024) configurations.
EXTRA_ARG_NGRAM_WINDOW = "ngram_window"


class NoRepeatNGramLogitsProcessor:
    """Bans n-grams already seen in a trailing window of the generated sequence.

    Reimplements the behavior of SGLang's ``DeepseekOCRNoRepeatNGramLogitProcessor``
    as a request-level callable ``(output_token_ids, logits) -> logits``, the shape
    vLLM V1's ``AdapterLogitsProcessor`` wraps. Long OCR generations otherwise fall
    into repetition loops, which is why Baidu's own examples always enable it.
    """

    def __init__(self, ngram_size: int = NGRAM_SIZE, window_size: int = NGRAM_WINDOW_MULTI):
        self.ngram_size = ngram_size
        self.window_size = window_size

    def __call__(self, token_ids: list[int], logits):
        n = self.ngram_size
        if n < 2 or len(token_ids) < n:
            return logits

        window = token_ids[-self.window_size:] if self.window_size > 0 else token_ids
        if len(window) < n:
            return logits

        prefix = tuple(window[-(n - 1):])
        tail = prefix[-1]
        limit = len(window) - n + 1

        for i in range(limit):
            # Cheap guard before the tuple comparison: the vast majority of positions
            # fail on the last prefix token alone.
            if window[i + n - 2] != tail:
                continue
            if tuple(window[i:i + n - 1]) == prefix:
                logits[window[i + n - 1]] = float("-inf")

        return logits


def request_ngram_processor(extra_args: dict | None) -> NoRepeatNGramLogitsProcessor | None:
    """Builds the per-request processor from ``SamplingParams.extra_args``, or None.

    Kept separate from vLLM so the request-level behavior is testable without a GPU.
    """
    window = (extra_args or {}).get(EXTRA_ARG_NGRAM_WINDOW)
    if window is None:
        return None
    return NoRepeatNGramLogitsProcessor(NGRAM_SIZE, int(window))


# --------------------------------------------------------------------------------------
# vLLM engine
# --------------------------------------------------------------------------------------

_engine = None


def get_engine():
    """Builds the vLLM engine once per worker process and keeps it warm."""
    global _engine
    if _engine is None:
        from vllm import LLM
        from ocr_logits import NoRepeatNGramAdapter

        _engine = LLM(
            model=MODEL_NAME,
            trust_remote_code=False,
            hf_overrides={"model_type": VLLM_MODEL_TYPE},
            dtype="bfloat16",
            max_model_len=MAX_MODEL_LEN,
            gpu_memory_utilization=GPU_MEMORY_UTILIZATION,
            limit_mm_per_prompt={"image": MAX_PAGES},
            logits_processors=[NoRepeatNGramAdapter],
        )
    return _engine


def _sampling_params(ngram_window: int):
    from vllm import SamplingParams

    return SamplingParams(
        temperature=0.0,
        # None means "generate until EOS or the context is full". An explicit cap of
        # MAX_MODEL_LEN here would be rejected outright: current vLLM refuses any
        # request whose prompt tokens + max_tokens exceed max_model_len rather than
        # clamping, and a multimodal prompt always occupies tokens.
        max_tokens=None,
        skip_special_tokens=False,
        extra_args={EXTRA_ARG_NGRAM_WINDOW: ngram_window},
    )


@dataclass
class Completion:
    text: str
    # finish_reason == "length": generation stopped at the context limit, so the text
    # ends mid-document.
    truncated: bool


def _generate(prompts: list[dict[str, Any]], ngram_window: int) -> list[Completion]:
    outputs = get_engine().generate(prompts, _sampling_params(ngram_window))

    # generate() returns one output per prompt, in the order submitted. Assert that
    # rather than assume it: a mismatch would silently drop or duplicate a page, and
    # the result would still look like a plausible document.
    if len(outputs) != len(prompts):
        raise RuntimeError(f"expected {len(prompts)} completions, got {len(outputs)}")

    return [
        Completion(
            text=output.outputs[0].text,
            truncated=getattr(output.outputs[0], "finish_reason", None) == "length",
        )
        for output in outputs
    ]


def _convert(raw: str) -> str:
    """Markdown of a completion that holds one page.

    The model opens even a one-page generation with ``<PAGE>``, so the marker is split
    off rather than left to the converter; should the model emit more than one, nothing
    is lost.
    """
    pages = (postprocess.page_to_markdown(page) for page in postprocess.split_pages(raw))
    return PAGE_SEPARATOR.join(page for page in pages if page)


def run_per_page(
    images: list[Image.Image], first_page: int, warnings: list[str]
) -> list[str]:
    """Runs one prompt per page and returns their markdown, in page order.

    Every page goes through the single-image path, which is the configuration that
    actually works for independent pages (several images against one prompt is the
    long-context path's business). Boundaries are exact by construction, at the price
    of the cross-page context that path carries.

    The pages are submitted as one batch, so vLLM schedules them together on the
    already-loaded model rather than paying a cold start each. ``first_page`` is the
    zero-based number of ``images[0]`` in the whole request, for messages.
    """
    prompts = [
        {"prompt": PROMPT_SINGLE, "multi_modal_data": {"image": [image]}}
        for image in images
    ]
    completions = _generate(prompts, NGRAM_WINDOW_SINGLE)

    markdowns = []
    for index, (prompt, completion) in enumerate(zip(prompts, completions)):
        number = first_page + index + 1
        markdown = _convert(completion.text)

        # A batched run can return an empty completion for a page that parses perfectly
        # well on its own — a 4-page batch of one identical page came back with three
        # pages of text and one blank, and the batch's outputs vary between identical
        # inputs despite temperature=0. Re-run just the blank ones, alone, where that
        # variability does not apply. Retrying only the empty pages keeps the cost
        # proportional to the problem instead of redoing the document.
        attempts = 0
        while not markdown and attempts < EMPTY_PAGE_RETRIES:
            attempts += 1
            print(
                f"[unlimited-ocr] page {number} came back empty; "
                f"retrying it alone ({attempts}/{EMPTY_PAGE_RETRIES})",
                flush=True,
            )
            completion = _generate([prompt], NGRAM_WINDOW_SINGLE)[0]
            markdown = _convert(completion.text)

        if completion.truncated:
            warnings.append(f"page {number}: output was cut off at the context limit")
        markdowns.append(markdown)
    return markdowns


def run_long_context_window(
    images: list[Image.Image], first_page: int, warnings: list[str]
) -> list[str] | None:
    """Parses several pages in one generate() call.

    This is the path Unlimited-OCR is built around: the R-SWA decoder keeps the KV
    cache flat across pages, so one 32K-context pass can carry structure over a page
    break — a table split across two pages, a running header, numbering that only
    makes sense in sequence. Per-page inference cannot see any of that.

    Multi-image input is supported by the model's processor, which disables crop mode
    for it (``UnlimitedOCRProcessor.tokenize_with_images``) and bypasses the per-item
    processing cache to stay consistent with that. Both sides derive the crop flag
    from the image count, so they agree.

    The model marks each page with ``<PAGE>``. If the boundaries are not exact or
    the completion was cut off, return None so the caller re-parses the window.
    Returning a possibly incomplete document would hide the missing pages.
    """
    last_page = first_page + len(images)
    completion = _generate(
        [{
            "prompt": build_multi_prompt(len(images)),
            "multi_modal_data": {"image": list(images)},
        }],
        NGRAM_WINDOW_MULTI,
    )[0]

    segments = [] if completion.truncated else postprocess.split_pages(completion.text)
    reason = None
    if completion.truncated:
        reason = "hit the context limit"
    elif len(segments) != len(images):
        reason = f"returned {len(segments)} page sections instead of {len(images)}"

    if reason:
        message = (
            f"pages {first_page + 1}-{last_page}: the long-context pass {reason}, "
            "so these pages were parsed page by page"
        )
        print(f"[unlimited-ocr] {message}", flush=True)
        warnings.append(message)
        return None

    return [postprocess.page_to_markdown(segment) for segment in segments]


def run_inference(request: OcrRequest, warnings: list[str]) -> list[str]:
    """Parses the request and returns the Markdown for each input page, in order.

    Without ``long_context`` (always the case for a single image) the whole request is
    one per-page window. With it, the pages go through the long-context path window by
    window, falling back to per-page inference where that path fails. The fallback is
    not a way to hide a broken path: it is recorded in ``warnings`` and reported to the
    app, because losing cross-page context is a quality regression the caller deserves
    to know about — but losing the whole scan to it would be worse.

    If the long-context path raises, it is not tried again for the remaining windows:
    the failure is as likely to be the path as the window, and each retry would cost a
    full generation before failing the same way.
    """
    if not request.long_context:
        return run_per_page(request.images, 0, warnings)

    markdowns: list[str] = []
    long_context_works = True
    for pages in page_windows(len(request.images), LONG_CONTEXT_WINDOW_PAGES):
        images = request.images[pages.start : pages.stop]
        window_markdown = None
        # A window of one page has no context to carry; the single-page path is the
        # configuration the model is tuned for there.
        if long_context_works and len(images) > 1:
            try:
                window_markdown = run_long_context_window(images, pages.start, warnings)
            except Exception as exc:  # noqa: BLE001 - fall back, but say so
                long_context_works = False
                message = (
                    f"long-context parsing failed ({type(exc).__name__}: {exc}); "
                    "parsed page by page instead, so context does not carry across pages"
                )
                print(f"[unlimited-ocr] {message}", flush=True)
                warnings.append(message)
        if window_markdown is None:
            window_markdown = run_per_page(images, pages.start, warnings)
        markdowns.extend(window_markdown)
    return markdowns


# --------------------------------------------------------------------------------------
# RunPod entry point
# --------------------------------------------------------------------------------------


def handler(job: dict[str, Any]) -> dict[str, Any]:
    started = time.monotonic()
    try:
        request = parse_request(job.get("input"))
    except InvalidInput as exc:
        return {"error": str(exc)}

    warnings: list[str] = []
    try:
        markdowns = run_inference(request, warnings)
    except Exception as exc:  # noqa: BLE001 - surfaced to the client, not swallowed
        return {"error": f"inference failed: {type(exc).__name__}: {exc}"}

    result: dict[str, Any] = {
        "markdown": PAGE_SEPARATOR.join(markdown for markdown in markdowns if markdown),
        "pages": [{"index": i, "markdown": markdown} for i, markdown in enumerate(markdowns)],
        "empty_pages": empty_page_indexes(markdowns),
        "page_mode": "per_page",
        "model": MODEL_NAME,
        "elapsed_ms": int((time.monotonic() - started) * 1000),
    }
    if warnings:
        result["warnings"] = warnings
    return result


if __name__ == "__main__":
    import runpod

    print(
        "[unlimited-ocr] starting worker:",
        f"model={MODEL_NAME}, max_model_len={MAX_MODEL_LEN}, "
        f"hf_home={os.environ.get('HF_HOME', '<unset>')}",
    )
    if os.environ.get("PRELOAD_MODEL", "1") == "1":
        # Loading before the first job means the cold-start cost lands on worker
        # startup, where RunPod is already waiting, rather than inside a user request.
        get_engine()
    runpod.serverless.start({"handler": handler})
