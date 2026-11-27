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
     "page_mode": "per_page", "long_context": {"windows": 1, "parsed": 1},
     "model": "...", "elapsed_ms": 1234,
     "stats": [{"kind": "window", "prompts": 1, "prompt_tokens": 870,
                "output_tokens": 412, "ms": 5120}],
     "warnings": ["..."]}

(``warnings`` is left out when there are none) or ``{"error": "..."}``, which RunPod
turns into a FAILED job.

The model emits a layout stream, not Markdown; :mod:`postprocess` converts it.

``pages`` contains one entry per input image, in order. ``empty_pages`` lists the
zero-based indexes with no text; ``markdown`` joins only the recognized content.

Only JPEG, PNG and WebP pages are decoded, and the size of every page of a job is
checked from the image headers before any of them is decoded.

Multi-page requests go to the model's long-context path in windows of at most
``LONG_CONTEXT_WINDOW_PAGES`` pages, all submitted in one generate() call. A window
that is cut off or returns the wrong number of page markers, and every window when
that call raises, is parsed page by page instead (one batch for the whole job) and
reported in ``warnings`` so callers can distinguish the result from an uninterrupted
pass. ``long_context`` counts the windows sent to that path and those it parsed.

``stats`` has one entry per generate() call: its kind ("window", "pages" or "retry"),
prompts, prompt and output tokens, and wall time.

vLLM is imported lazily so this module can be imported (and its pure helpers tested)
on a machine with no GPU.
"""

from __future__ import annotations

import base64
import binascii
import io
import math
import os
import time
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

from PIL import Image, PngImagePlugin

import postprocess

MODEL_NAME = os.environ.get("MODEL_NAME", "baidu/Unlimited-OCR")
# The checkpoint calls its model type "unlimited_ocr"; vLLM registers "unlimited-ocr".
VLLM_MODEL_TYPE = "unlimited-ocr"
MAX_MODEL_LEN = int(os.environ.get("MAX_MODEL_LEN", "32768"))
GPU_MEMORY_UTILIZATION = float(os.environ.get("GPU_MEMORY_UTILIZATION", "0.85"))
MAX_PAGES = int(os.environ.get("MAX_PAGES", "64"))

# Bounds on the pages of a job, checked from the image headers before anything is
# decoded. The app sends JPEGs a few hundred KB in size, at most 1920 px on the long
# edge for one page and 1024 px for several (RemoteOcrEngine.kt), so its largest job,
# 64 pages, holds 67 MP.
MAX_IMAGE_BYTES = int(os.environ.get("MAX_IMAGE_BYTES", str(25 * 1024 * 1024)))
# One page: a 12 MP phone photo (4000 x 3000) fits, and so does MODEL_PAGE_PIXELS.
MAX_IMAGE_PIXELS = int(os.environ.get("MAX_IMAGE_PIXELS", str(16_000_000)))
# All the pages of a job: 64 pages of 2048 x 2048, about 0.8 GB once decoded to RGB.
# The per-page bound alone let 64 crafted pages of under 13 KB each decode to 14 GB.
MAX_TOTAL_PIXELS = int(os.environ.get("MAX_TOTAL_PIXELS", str(64 * 2048 * 2048)))

# The most the model reads from one page. vLLM's Unlimited-OCR processor resizes a
# single page to a grid of at most 32 tiles of 640 x 640 (plus a 1024 px overview), and
# each page of a multi-page prompt to 640 x 640. A decoded page above this size is
# shrunk to it, which keeps the grid the processor picks: that grid depends on the
# aspect ratio, and on the area only below this size.
MODEL_PAGE_PIXELS = 32 * 640 * 640

# The app sends JPEG and the deploy smoke test PNG; WebP is the other format phones and
# browsers produce. Every other Pillow decoder stays out of reach of a job payload.
IMAGE_FORMATS = ("JPEG", "PNG", "WEBP")

# Pillow's own guard, as a second line of defence for any path that reaches
# decoding without going through open_image().
Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS
# Pillow reads PNG text metadata independently of the pixel budget. OCR has no use
# for large text chunks; bound their cumulative decoded size per page as well.
PngImagePlugin.MAX_TEXT_MEMORY = 1024 * 1024

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

# Output cap of a single-page prompt. The n-gram filter only catches a loop whose period
# fits its window (NGRAM_WINDOW_SINGLE - NGRAM_SIZE + 1 = 94 tokens); a page caught in
# a longer loop, such as a repeated table row, would otherwise decode until the 32K
# context is full. Long-context windows keep no cap: their pages share the context.
PAGE_MAX_TOKENS = int(os.environ.get("PAGE_MAX_TOKENS", "8192"))


class InvalidInput(ValueError):
    """Raised for a malformed job payload; surfaced to the client as an error."""


@dataclass
class OcrRequest:
    images: list[Image.Image]
    # True only with several images, when the request asks for it (or says nothing and
    # LONG_CONTEXT_DEFAULT is on). A single image always takes the per-page path.
    long_context: bool = False


@dataclass
class JobReport:
    """What a job reports besides its pages."""

    warnings: list[str] = field(default_factory=list)
    # Long-context windows submitted, and those that came back with one section per page.
    windows: int = 0
    parsed: int = 0
    # One entry per generate() call; see _generate.
    stats: list[dict[str, Any]] = field(default_factory=list)


# --------------------------------------------------------------------------------------
# Pure helpers (no GPU, no vLLM) — these are what tests exercise.
# --------------------------------------------------------------------------------------


def open_image(encoded: str) -> Image.Image:
    """Reads the header of a page given as bare base64 or a ``data:`` URL.

    Nothing is decoded yet: the pixels are left to :func:`load_image`, so the size of
    every page of a job can be checked before any of them takes memory.
    """
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
        image = Image.open(io.BytesIO(raw), formats=IMAGE_FORMATS)
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
    return image


def load_image(image: Image.Image) -> Image.Image:
    """Decodes a page from :func:`open_image` into RGB of at most MODEL_PAGE_PIXELS.

    ``image`` is closed once converted, so its pixels are freed even while the caller
    still holds a reference to it.
    """
    width, height = image.size
    target = None
    if width * height > MODEL_PAGE_PIXELS:
        scale = math.sqrt(MODEL_PAGE_PIXELS / (width * height))
        target = (max(1, int(width * scale)), max(1, int(height * scale)))
        # JPEG only: decode at 1/2, 1/4 or 1/8 scale where that stays at or above the
        # target, so the full-size bitmap is never allocated. A no-op for other formats.
        image.draft("RGB", target)

    try:
        image.load()
    except Exception as exc:  # noqa: BLE001 - truncated or malformed payloads
        raise InvalidInput(f"could not read image data: {exc}") from exc

    # The model expects 3-channel input; incoming PNGs may carry alpha or be grayscale.
    rgb = image.convert("RGB")
    image.close()
    if target:
        rgb.thumbnail(target)
    return rgb


def decode_image(encoded: str) -> Image.Image:
    """Decodes a bare base64 string or a ``data:`` URL into an RGB image."""
    return load_image(open_image(encoded))


def parse_request(job_input: Any) -> OcrRequest:
    """Validates and normalizes a job payload into an :class:`OcrRequest`."""
    if not isinstance(job_input, dict):
        raise InvalidInput("input must be an object")

    images = job_input.get("images")
    if not isinstance(images, list) or not images:
        raise InvalidInput("input.images must be a non-empty array")
    if len(images) > MAX_PAGES:
        raise InvalidInput(f"input.images holds {len(images)} pages, limit is {MAX_PAGES}")

    # Every header is read before any page is decoded, so the size of the whole job is
    # checked while it is still only bytes.
    opened = [open_image(entry) for entry in images]
    total = sum(image.width * image.height for image in opened)
    if total > MAX_TOTAL_PIXELS:
        raise InvalidInput(
            f"input.images hold {total} pixels in all, limit is {MAX_TOTAL_PIXELS}"
        )

    decoded = [load_image(image) for image in opened]
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
        started = time.monotonic()
        from vllm import LLM
        from ocr_logits import NoRepeatNGramAdapter

        _engine = LLM(
            model=MODEL_NAME,
            trust_remote_code=False,
            hf_overrides={"model_type": VLLM_MODEL_TYPE},
            dtype="bfloat16",
            max_model_len=MAX_MODEL_LEN,
            gpu_memory_utilization=GPU_MEMORY_UTILIZATION,
            # Unlimited-OCR crops single images but not multi-image inputs. This
            # vLLM build keys both modes by image content; disabling both caches
            # gives each request its own image keys, as in the serving recipe.
            enable_prefix_caching=False,
            mm_processor_cache_gb=0,
            limit_mm_per_prompt={"image": MAX_PAGES},
            logits_processors=[NoRepeatNGramAdapter],
        )
        print(
            f"[unlimited-ocr] engine ready in {time.monotonic() - started:.1f}s",
            flush=True,
        )
    return _engine


def _sampling_params(ngram_window: int, max_tokens: int | None):
    from vllm import SamplingParams

    return SamplingParams(
        temperature=0.0,
        # None means "generate until EOS or the context is full", which long-context
        # windows need. A cap of MAX_MODEL_LEN would be rejected outright: current vLLM
        # refuses any request whose prompt tokens + max_tokens exceed max_model_len
        # rather than clamping, and a multimodal prompt always occupies tokens.
        max_tokens=max_tokens,
        skip_special_tokens=False,
        extra_args={EXTRA_ARG_NGRAM_WINDOW: ngram_window},
    )


@dataclass
class Completion:
    text: str
    # finish_reason == "length": generation stopped at max_tokens or the context limit,
    # so the text ends mid-document.
    truncated: bool


def _generate(
    prompts: list[dict[str, Any]],
    ngram_window: int,
    kind: str,
    report: JobReport,
    max_tokens: int | None = None,
) -> list[Completion]:
    """Runs one generate() call and records its size and duration in ``report.stats``.

    ``kind`` names the pass: "window" (long-context windows), "pages" (single-page
    prompts) or "retry" (a blank page run again on its own).
    """
    started = time.monotonic()
    outputs = get_engine().generate(prompts, _sampling_params(ngram_window, max_tokens))
    elapsed_ms = int((time.monotonic() - started) * 1000)

    # generate() returns one output per prompt, in the order submitted. Assert that
    # rather than assume it: a mismatch would silently drop or duplicate a page, and
    # the result would still look like a plausible document.
    if len(outputs) != len(prompts):
        raise RuntimeError(f"expected {len(prompts)} completions, got {len(outputs)}")

    report.stats.append({
        "kind": kind,
        "prompts": len(prompts),
        "prompt_tokens": sum(len(output.prompt_token_ids or ()) for output in outputs),
        "output_tokens": sum(len(output.outputs[0].token_ids or ()) for output in outputs),
        "ms": elapsed_ms,
    })
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
    images: list[Image.Image], pages: Sequence[int], report: JobReport
) -> list[str]:
    """Runs one prompt for each of ``pages`` (indexes into ``images``) and returns their
    markdown, in that order.

    Every page goes through the single-image path, which is the configuration that
    actually works for independent pages (several images against one prompt is the
    long-context path's business). Boundaries are exact by construction, at the price
    of the cross-page context that path carries.

    The pages are submitted as one batch, so vLLM schedules them together on the
    already-loaded model rather than paying a cold start each.
    """
    prompts = [
        {"prompt": PROMPT_SINGLE, "multi_modal_data": {"image": [images[page]]}}
        for page in pages
    ]
    completions = _generate(prompts, NGRAM_WINDOW_SINGLE, "pages", report, PAGE_MAX_TOKENS)

    markdowns = []
    for page, prompt, completion in zip(pages, prompts, completions):
        number = page + 1
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
            completion = _generate(
                [prompt], NGRAM_WINDOW_SINGLE, "retry", report, PAGE_MAX_TOKENS
            )[0]
            markdown = _convert(completion.text)

        if completion.truncated:
            report.warnings.append(
                f"page {number}: output reached the per-page output limit and was cut off"
            )
        markdowns.append(markdown)
    return markdowns


def run_long_context_windows(
    images: list[Image.Image], windows: list[range], report: JobReport
) -> list[list[str] | None]:
    """Parses each window of several pages with one prompt, all in one generate() call.

    This is the path Unlimited-OCR is built around: the R-SWA decoder keeps the KV
    cache flat across pages, so one 32K-context pass can carry structure over a page
    break — a table split across two pages, a running header, numbering that only
    makes sense in sequence. Per-page inference cannot see any of that.

    Multi-image input is supported by the model's processor, which disables crop mode
    for it (``UnlimitedOCRProcessor.tokenize_with_images``) and bypasses the per-item
    processing cache to stay consistent with that. Both sides derive the crop flag
    from the image count, so they agree.

    The windows are independent (context does not carry from one to the next), so they
    go in one call: vLLM decodes them side by side instead of one after another.

    The model marks each page with ``<PAGE>``. A window whose boundaries are not exact,
    or whose completion was cut off, comes back as None so the caller re-parses its
    pages. Returning a possibly incomplete document would hide the missing pages.
    """
    prompts = [
        {
            "prompt": build_multi_prompt(len(window)),
            "multi_modal_data": {"image": images[window.start : window.stop]},
        }
        for window in windows
    ]
    completions = _generate(prompts, NGRAM_WINDOW_MULTI, "window", report)

    results: list[list[str] | None] = []
    for window, completion in zip(windows, completions):
        segments = [] if completion.truncated else postprocess.split_pages(completion.text)
        reason = None
        if completion.truncated:
            reason = "hit the context limit"
        elif len(segments) != len(window):
            reason = f"returned {len(segments)} page sections instead of {len(window)}"

        if reason:
            message = (
                f"pages {window.start + 1}-{window.stop}: the long-context pass {reason}, "
                "so these pages were parsed page by page"
            )
            print(f"[unlimited-ocr] {message}", flush=True)
            report.warnings.append(message)
            results.append(None)
        else:
            results.append([postprocess.page_to_markdown(segment) for segment in segments])
    return results


def run_inference(request: OcrRequest, report: JobReport) -> list[str]:
    """Parses the request and returns the Markdown for each input page, in order.

    Without ``long_context`` (always the case for a single image) every page takes the
    per-page path. With it, the windows of several pages go through the long-context
    path, and the pages of the windows it fails on are parsed page by page, in one
    batch together with any window of a single page. The fallback is not a way to hide
    a broken path: it is recorded in ``report.warnings`` and reported to the app,
    because losing cross-page context is a quality regression the caller deserves to
    know about — but losing the whole scan to it would be worse.

    If the long-context call raises, every window counts as failed: the windows share
    that call, and trying them again would cost a full generation before failing the
    same way.
    """
    images = request.images
    if not request.long_context:
        return run_per_page(images, range(len(images)), report)

    markdowns: list[str | None] = [None] * len(images)
    # A window of one page has no context to carry; the single-page path is the
    # configuration the model is tuned for there.
    windows = [
        window
        for window in page_windows(len(images), LONG_CONTEXT_WINDOW_PAGES)
        if len(window) > 1
    ]
    if windows:
        report.windows = len(windows)
        try:
            results = run_long_context_windows(images, windows, report)
        except Exception as exc:  # noqa: BLE001 - fall back, but say so
            results = [None] * len(windows)
            message = (
                f"long-context parsing failed ({type(exc).__name__}: {exc}); "
                "parsed page by page instead, so context does not carry across pages"
            )
            print(f"[unlimited-ocr] {message}", flush=True)
            report.warnings.append(message)
        for window, pages in zip(windows, results):
            if pages is not None:
                report.parsed += 1
                markdowns[window.start : window.stop] = pages

    fallback = [index for index, markdown in enumerate(markdowns) if markdown is None]
    if fallback:
        for index, markdown in zip(fallback, run_per_page(images, fallback, report)):
            markdowns[index] = markdown
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

    report = JobReport()
    try:
        markdowns = run_inference(request, report)
    except Exception as exc:  # noqa: BLE001 - surfaced to the client, not swallowed
        return {"error": f"inference failed: {type(exc).__name__}: {exc}"}

    result: dict[str, Any] = {
        "markdown": PAGE_SEPARATOR.join(markdown for markdown in markdowns if markdown),
        "pages": [{"index": i, "markdown": markdown} for i, markdown in enumerate(markdowns)],
        "empty_pages": empty_page_indexes(markdowns),
        "page_mode": "per_page",
        "long_context": {"windows": report.windows, "parsed": report.parsed},
        "model": MODEL_NAME,
        "elapsed_ms": int((time.monotonic() - started) * 1000),
        "stats": report.stats,
    }
    if report.warnings:
        result["warnings"] = report.warnings
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
