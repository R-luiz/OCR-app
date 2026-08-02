"""Reads the model's own multimodal prompt contract out of the running image.

Run inside the worker image by .github/workflows/backend-introspect.yml. Exists
because the multi-page assertion --

    AssertionError: Failed to apply prompt replacement for mm_items['image'][1]

-- means the prompt did not carry a placeholder vLLM could bind the second image
to, and inferring the required format from the error text alone produced a fix
that did not work. The processor itself is the authority; ask it.
"""

import inspect, os, traceback

MODEL = os.environ.get("MODEL_NAME", "/opt/models/unlimited-ocr")

from transformers import AutoConfig, AutoProcessor, AutoTokenizer
cfg = AutoConfig.from_pretrained(MODEL, trust_remote_code=True)
arch = getattr(cfg, "architectures", None)
print("architectures:", arch)
for attr in ("image_token", "image_token_id", "image_token_index",
             "vision_config", "num_image_tokens"):
    if hasattr(cfg, attr):
        v = getattr(cfg, attr)
        print(f"config.{attr}:", type(v).__name__ if attr == "vision_config" else v)

tok = AutoTokenizer.from_pretrained(MODEL, trust_remote_code=True)
for probe in ("<image>", "<image><image>", "<image>\n<image>"):
    print(f"tokenize {probe!r}:", tok(probe, add_special_tokens=False)["input_ids"])
specials = [t for t in tok.all_special_tokens if "im" in t.lower() or "image" in t.lower()]
print("image-ish special tokens:", specials)
print("added_tokens containing 'image':",
      [t for t in getattr(tok, "get_added_vocab", dict)() if "image" in t.lower()])

# The authority on what the prompt must look like: vLLM's processor for this arch.
from vllm.multimodal import MULTIMODAL_REGISTRY
from vllm.model_executor.models.registry import ModelRegistry
name = arch[0] if arch else None
print("\n=== vLLM processing for", name, "===")
try:
    model_cls, _ = ModelRegistry.resolve_model_cls(name)
    print("model class:", model_cls)
    mod = inspect.getmodule(model_cls)
    print("module:", mod.__name__, mod.__file__)
    for attr in dir(mod):
        obj = getattr(mod, attr)
        if inspect.isclass(obj) and ("Processor" in attr or "ProcessingInfo" in attr
                                     or "DummyInputs" in attr):
            for meth in ("_get_prompt_updates", "_get_prompt_replacements",
                         "get_replacement", "_get_mm_fields_config"):
                fn = getattr(obj, meth, None)
                if fn is not None:
                    print(f"\n--- {attr}.{meth} ---")
                    try:
                        print(inspect.getsource(fn))
                    except Exception as e:
                        print("  <no source>", e)
except Exception:
    traceback.print_exc()
