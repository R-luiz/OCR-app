"""Reads the model's own multimodal prompt contract out of the running image.

Run inside the worker image by .github/workflows/backend-introspect.yml. Exists
because the multi-page assertion --

    AssertionError: Failed to apply prompt replacement for mm_items['image'][1]

-- means the prompt did not carry a placeholder vLLM could bind the second image
to, and inferring the required format from the error text alone produced a fix
that did not work. The processor itself is the authority; ask it.

The authority is specifically *vLLM's* processor for this architecture, not the
Transformers remote code: vLLM ships its own implementation and that is what
serves requests. The Transformers path is probed too, but only opportunistically
-- its modeling file imports addict and matplotlib, which the image does not
carry, so AutoConfig raises on import. That must not take the rest of the probe
down with it, which is what the first version did: one ImportError and the whole
run ended having printed nothing.
"""

import inspect
import json
import os
import traceback

MODEL = os.environ.get("MODEL_NAME", "/opt/models/unlimited-ocr")

# Filled in by dump_config; the vLLM lookup needs it.
architectures: list[str] = []


def attempt(title: str, fn) -> None:
    """Runs a probe step, reporting failure without aborting the whole run."""
    print(f"\n{'=' * 70}\n{title}\n{'=' * 70}")
    try:
        fn()
    except Exception:  # noqa: BLE001 - a failing section must not hide the others
        traceback.print_exc()


def dump_config() -> None:
    """Reads config.json directly rather than through AutoConfig.

    AutoConfig would route through trust_remote_code and raise on the missing
    addict/matplotlib imports before printing anything.
    """
    global architectures
    with open(os.path.join(MODEL, "config.json")) as handle:
        cfg = json.load(handle)

    architectures = cfg.get("architectures") or []
    print("architectures:", architectures)
    for key in (
        "model_type", "image_token", "image_token_id", "image_token_index",
        "num_image_tokens", "max_position_embeddings",
    ):
        if key in cfg:
            print(f"config.{key}:", cfg[key])
    vision = cfg.get("vision_config")
    if isinstance(vision, dict):
        print("vision_config keys:", sorted(vision))


def dump_tokenizer() -> None:
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(MODEL, trust_remote_code=True)
    for probe in ("<image>", "<image><image>", "<image>\n<image>"):
        print(f"tokenize {probe!r}:", tok(probe, add_special_tokens=False)["input_ids"])
    print("image-ish special tokens:",
          [t for t in tok.all_special_tokens if "im" in t.lower()])
    print("added vocab containing 'image':",
          {k: v for k, v in tok.get_added_vocab().items() if "image" in k.lower()})


def dump_token_files() -> None:
    """Fallback for when the tokenizer will not load: these files are plain JSON."""
    for name in ("added_tokens.json", "special_tokens_map.json", "tokenizer_config.json"):
        path = os.path.join(MODEL, name)
        if not os.path.exists(path):
            continue
        with open(path) as handle:
            data = json.load(handle)
        interesting = {
            key: value for key, value in data.items()
            if "image" in str(key).lower() or "image" in str(value).lower()
        }
        print(f"{name}:", interesting or "<nothing image-related>")


def _model_module():
    from vllm.model_executor.models.registry import ModelRegistry

    if not architectures:
        raise RuntimeError("no architectures from config.json; cannot resolve a model class")
    model_cls, _ = ModelRegistry.resolve_model_cls(architectures[0])
    return model_cls, inspect.getmodule(model_cls)


def dump_vllm_processing() -> None:
    """vLLM's own processor for this architecture — the thing that actually runs."""
    model_cls, module = _model_module()
    print("model class:", model_cls)
    print("module:", module.__name__, module.__file__)

    # The prompt contract lives in whichever class builds the prompt updates. Dump
    # every candidate rather than guessing which one this architecture uses.
    wanted = (
        "_get_prompt_updates", "_get_prompt_replacements", "get_replacement",
        "_get_mm_fields_config", "get_dummy_text", "_call_hf_processor",
        "apply", "get_num_image_tokens", "get_image_size_with_most_features",
    )
    for attr in dir(module):
        obj = getattr(module, attr)
        if not inspect.isclass(obj):
            continue
        if not any(tag in attr for tag in ("Processor", "ProcessingInfo", "DummyInputs")):
            continue
        for meth in wanted:
            fn = getattr(obj, meth, None)
            if fn is None:
                continue
            print(f"\n--- {attr}.{meth} ---")
            try:
                print(inspect.getsource(fn))
            except Exception as exc:  # noqa: BLE001 - best effort
                print("  <no source>", exc)


def dump_module_source() -> None:
    """Last resort: the whole vLLM model module.

    When the class-name heuristics find nothing, the prompt contract is still in
    here somewhere, and a few hundred lines of source beats another blind guess
    costing a rebuild and a redeploy.
    """
    _, module = _model_module()
    print(inspect.getsource(module))


attempt("config.json", dump_config)
attempt("tokenizer", dump_tokenizer)
attempt("token files", dump_token_files)
attempt("vLLM processing for this architecture", dump_vllm_processing)
attempt("full vLLM model module source", dump_module_source)
