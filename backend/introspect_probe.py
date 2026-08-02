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
    """Fallback for when the tokenizer will not load: these files are plain JSON.

    Only image-related *tokens* are printed. Matching on the stringified value of a
    top-level key instead dumped the entire added_tokens_decoder — thousands of
    placeholder tokens — because one entry buried inside it mentioned an image.
    """
    for name in ("added_tokens.json", "special_tokens_map.json", "tokenizer_config.json"):
        path = os.path.join(MODEL, name)
        if not os.path.exists(path):
            continue
        with open(path) as handle:
            data = json.load(handle)

        found: list[str] = []

        def walk(node, trail: str = "") -> None:
            if isinstance(node, dict):
                for key, value in node.items():
                    walk(value, f"{trail}.{key}" if trail else str(key))
            elif isinstance(node, list):
                for index, value in enumerate(node):
                    walk(value, f"{trail}[{index}]")
            elif "image" in str(node).lower():
                found.append(f"  {trail} = {node!r}")

        walk(data)
        print(f"{name}:")
        print("\n".join(found) if found else "  <nothing image-related>")


def _model_source_files() -> list[str]:
    """Finds vLLM's implementation of this architecture by searching its source.

    Deliberately not ModelRegistry.resolve_model_cls: its signature differs across
    vLLM releases (this build requires a model_config argument that earlier ones did
    not), and the whole point of this probe is to survive the version it is pointed
    at. A grep over the package has no API to break.
    """
    import vllm.model_executor.models as models_pkg

    if not architectures:
        raise RuntimeError("no architectures from config.json; nothing to search for")

    root = os.path.dirname(models_pkg.__file__)
    needle = architectures[0]
    hits = []
    for entry in sorted(os.listdir(root)):
        if not entry.endswith(".py"):
            continue
        path = os.path.join(root, entry)
        try:
            with open(path, encoding="utf-8") as handle:
                body = handle.read()
        except OSError:
            continue
        # registry.py maps every architecture name to a module, so it matches too;
        # it is worth printing, but the implementation file is the one that matters.
        if needle in body and entry != "registry.py":
            hits.append(path)
    return hits


def dump_registry_entry() -> None:
    """What vLLM's registry says this architecture maps to."""
    import vllm.model_executor.models as models_pkg

    path = os.path.join(os.path.dirname(models_pkg.__file__), "registry.py")
    needle = architectures[0] if architectures else ""
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if needle and needle in line:
                print(line.rstrip())


def dump_vllm_source() -> None:
    """The full source of vLLM's implementation for this architecture.

    Printed whole rather than filtered down to methods guessed by name: the prompt
    contract is what this probe exists to find, and a few hundred lines of source
    beats another blind guess costing a rebuild and a redeploy.
    """
    files = _model_source_files()
    if not files:
        print("no vLLM source file mentions", architectures[0] if architectures else "?")
        return
    for path in files:
        print(f"\n########## {path} ##########\n")
        with open(path, encoding="utf-8") as handle:
            print(handle.read())


attempt("config.json", dump_config)
attempt("tokenizer", dump_tokenizer)
attempt("token files", dump_token_files)
attempt("vLLM registry entry", dump_registry_entry)
attempt("vLLM implementation source", dump_vllm_source)
