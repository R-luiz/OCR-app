"""RunPod Serverless worker for Baidu Unlimited-OCR.

Exposes a single job shape::

    {"input": {
        "mode": "single" | "multi",
        "images": ["data:image/jpeg;base64,...", ...],
        "base_size": 1024,
        "image_size": 640,
        "crop_mode": true
    }}

and returns::

    {"markdown": "...", "pages": [{"index": 0, "markdown": "..."}], "model": "...",
     "elapsed_ms": 1234}

The two modes mirror the configurations Baidu documents: "gundam" (640px crops over a
1024px base) for a single image, and "base" (flat 1024px) for multi-page parsing, which
is the path Unlimited-OCR's R-SWA decoder is designed for — every page goes into one
32K-context request rather than one request per page.

vLLM is imported lazily so this module can be imported (and its pure helpers tested)
on a machine with no GPU.
"""

from __future__ import annotations

import base64
import binascii
import io
import os
import re
import time
from dataclasses import dataclass, field
from typing import Any, Iterable

from PIL import Image

MODEL_NAME = os.environ.get("MODEL_NAME", "baidu/Unlimited-OCR")
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

    vLLM binds each supplied image to its own placeholder in the prompt, so a prompt
    carrying a single ``<image>`` alongside N images fails on the second one with
    ``Failed to apply prompt replacement for mm_items['image'][1]`` — which is exactly
    what every multi-page request did until this was fixed.
    """
    return IMAGE_TOKEN * max(page_count, 1) + MULTI_INSTRUCTION

# Baidu's inference examples use these for both Transformers and SGLang.
NGRAM_SIZE = 35
NGRAM_WINDOW_SINGLE = 128
NGRAM_WINDOW_MULTI = 1024

_SPECIAL_TOKEN_RE = re.compile(r"<\|[^|>]*\|>")
# Best-effort page delimiters for multi-page output. Verify against real output from
# your deployment before relying on the per-page split; the full `markdown` field is
# always authoritative.
_PAGE_BREAK_RE = re.compile(r"\n*(?:\f|<\|page_break\|>|<page_break>)\n*")


class InvalidInput(ValueError):
    """Raised for a malformed job payload; surfaced to the client as an error."""


@dataclass
class OcrRequest:
    mode: str
    images: list[Image.Image]
    base_size: int = 1024
    image_size: int = 1024
    crop_mode: bool = False
    prompt: str = PROMPT_SINGLE
    ngram_window: int = NGRAM_WINDOW_MULTI
    warnings: list[str] = field(default_factory=list)


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

    mode = job_input.get("mode") or ("single" if len(decoded) == 1 else "multi")
    if mode not in ("single", "multi"):
        raise InvalidInput("input.mode must be 'single' or 'multi'")

    warnings: list[str] = []
    if mode == "single" and len(decoded) > 1:
        # Cropped single-image mode has no defined behavior across several pages.
        mode = "multi"
        warnings.append("mode 'single' with multiple images; used 'multi' instead")

    if mode == "single":
        return OcrRequest(
            mode=mode,
            images=decoded,
            base_size=int(job_input.get("base_size", 1024)),
            image_size=int(job_input.get("image_size", 640)),
            crop_mode=bool(job_input.get("crop_mode", True)),
            prompt=PROMPT_SINGLE,
            ngram_window=NGRAM_WINDOW_SINGLE,
            warnings=warnings,
        )

    return OcrRequest(
        mode=mode,
        images=decoded,
        base_size=int(job_input.get("base_size", 1024)),
        # Multi-page parsing is only defined for the flat 1024px configuration.
        image_size=1024,
        crop_mode=False,
        prompt=build_multi_prompt(len(decoded)),
        ngram_window=NGRAM_WINDOW_MULTI,
        warnings=warnings,
    )


def clean_output(text: str) -> str:
    """Strips the special tokens that ``skip_special_tokens=False`` leaves behind."""
    return _SPECIAL_TOKEN_RE.sub("", text).strip()


def split_pages(markdown: str, expected: int) -> list[dict[str, Any]]:
    """Best-effort per-page split; returns ``[]`` when the split looks unreliable."""
    if expected <= 1:
        return [{"index": 0, "markdown": markdown}] if markdown else []

    parts = [part.strip() for part in _PAGE_BREAK_RE.split(markdown)]
    parts = [part for part in parts if part]
    if len(parts) != expected:
        return []
    return [{"index": i, "markdown": part} for i, part in enumerate(parts)]


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

    Pure glue, kept separate from the vLLM adapter below so it stays testable on a
    machine with no vLLM installed.
    """
    window = (extra_args or {}).get(EXTRA_ARG_NGRAM_WINDOW)
    if window is None:
        return None
    return NoRepeatNGramLogitsProcessor(NGRAM_SIZE, int(window))


# --------------------------------------------------------------------------------------
# vLLM engine
# --------------------------------------------------------------------------------------

_engine = None


def _adapter_class():
    """The engine-level wrapper for the per-request n-gram processor.

    vLLM's V1 engine (the only engine in the image's 0.23 build — SamplingParams no
    longer accepts ``logits_processors``) takes processor *classes* at LLM
    construction and instantiates a request-level callable per request via
    ``new_req_logits_processor``. Defined lazily because importing vllm requires the
    GPU stack, and the pure helpers above must stay importable without it.
    """
    from vllm.v1.sample.logits_processor import AdapterLogitsProcessor

    class NoRepeatNGramAdapter(AdapterLogitsProcessor):
        def is_argmax_invariant(self) -> bool:
            # Banning tokens rewrites their logits to -inf, which can change the
            # argmax; claiming invariance would let vLLM skip this processor
            # entirely under greedy sampling — exactly our temperature=0 case.
            return False

        def new_req_logits_processor(self, params):
            return request_ngram_processor(getattr(params, "extra_args", None))

    return NoRepeatNGramAdapter


def get_engine():
    """Builds the vLLM engine once per worker process and keeps it warm."""
    global _engine
    if _engine is None:
        from vllm import LLM

        _engine = LLM(
            model=MODEL_NAME,
            trust_remote_code=True,
            dtype="bfloat16",
            max_model_len=MAX_MODEL_LEN,
            gpu_memory_utilization=GPU_MEMORY_UTILIZATION,
            limit_mm_per_prompt={"image": MAX_PAGES},
            logits_processors=[_adapter_class()],
        )
    return _engine


def run_inference(request: OcrRequest) -> str:
    from vllm import SamplingParams

    sampling = SamplingParams(
        temperature=0.0,
        # None means "generate until EOS or the context is full". An explicit cap of
        # MAX_MODEL_LEN here would be rejected outright: current vLLM refuses any
        # request whose prompt tokens + max_tokens exceed max_model_len rather than
        # clamping, and a multimodal prompt always occupies tokens.
        max_tokens=None,
        skip_special_tokens=False,
        extra_args={EXTRA_ARG_NGRAM_WINDOW: request.ngram_window},
    )

    outputs = get_engine().generate(
        {
            "prompt": request.prompt,
            "multi_modal_data": {"image": request.images},
        },
        sampling,
    )
    return outputs[0].outputs[0].text


# --------------------------------------------------------------------------------------
# RunPod entry point
# --------------------------------------------------------------------------------------


def handler(job: dict[str, Any]) -> dict[str, Any]:
    started = time.monotonic()
    try:
        request = parse_request(job.get("input"))
    except InvalidInput as exc:
        return {"error": str(exc)}

    try:
        raw = run_inference(request)
    except Exception as exc:  # noqa: BLE001 - surfaced to the client, not swallowed
        return {"error": f"inference failed: {type(exc).__name__}: {exc}"}

    markdown = clean_output(raw)
    result: dict[str, Any] = {
        "markdown": markdown,
        "pages": split_pages(markdown, len(request.images)),
        "model": MODEL_NAME,
        "elapsed_ms": int((time.monotonic() - started) * 1000),
    }
    if request.warnings:
        result["warnings"] = request.warnings
    return result


def _iter_startup_messages() -> Iterable[str]:
    yield f"model={MODEL_NAME}"
    yield f"max_model_len={MAX_MODEL_LEN}"
    yield f"hf_home={os.environ.get('HF_HOME', '<unset>')}"


if __name__ == "__main__":
    import runpod

    print("[unlimited-ocr] starting worker:", ", ".join(_iter_startup_messages()))
    if os.environ.get("PRELOAD_MODEL", "1") == "1":
        # Loading before the first job means the cold-start cost lands on worker
        # startup, where RunPod is already waiting, rather than inside a user request.
        get_engine()
    runpod.serverless.start({"handler": handler})
