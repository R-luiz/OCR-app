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

PROMPT_SINGLE = "<image>document parsing."
PROMPT_MULTI = "<image>Multi page parsing."

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
    prompt: str = PROMPT_MULTI
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

    try:
        image = Image.open(io.BytesIO(raw))
        image.load()
    except Exception as exc:  # Pillow raises a wide variety here.
        raise InvalidInput(f"could not decode image bytes: {exc}") from exc

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
        prompt=PROMPT_MULTI,
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


class NoRepeatNGramLogitsProcessor:
    """Bans n-grams already seen in a trailing window of the generated sequence.

    Reimplements the behavior of SGLang's ``DeepseekOCRNoRepeatNGramLogitProcessor``
    against vLLM's logits-processor interface. Long OCR generations otherwise fall into
    repetition loops, which is why Baidu's own examples always enable it.

    Stateless by design: vLLM shares one ``SamplingParams`` across a request's steps and
    makes no promise about per-sequence processor instances.
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


# --------------------------------------------------------------------------------------
# vLLM engine
# --------------------------------------------------------------------------------------

_engine = None


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
        )
    return _engine


def run_inference(request: OcrRequest) -> str:
    from vllm import SamplingParams

    sampling = SamplingParams(
        temperature=0.0,
        max_tokens=MAX_MODEL_LEN,
        skip_special_tokens=False,
        logits_processors=[
            NoRepeatNGramLogitsProcessor(NGRAM_SIZE, request.ngram_window),
        ],
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
