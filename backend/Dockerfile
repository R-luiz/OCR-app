# syntax=docker/dockerfile:1
# Baidu publishes a vLLM image with the Unlimited-OCR architecture already patched in,
# so there is no reason to build vLLM from source here.
#
#   vllm/vllm-openai:unlimited-ocr        CUDA 13.0 (default)
#   vllm/vllm-openai:unlimited-ocr-cu129  CUDA 12.9, for Hopper (H100/H200)
#
# Both are pinned by digest: a tag can be repointed upstream, and a fixed base also keeps
# the weights layers below cached from one build to the next. To move to a newer base,
# resolve the tag's digest (docker buildx imagetools inspect <tag>) and replace it here.
# Override at build time when targeting Hopper:
#   docker build --build-arg BASE_IMAGE=vllm/vllm-openai:unlimited-ocr-cu129@sha256:e45cf562cbc885af2531f576e79377069e0a6f0af42cdad7658b8494185a1143 .
ARG BASE_IMAGE=vllm/vllm-openai:unlimited-ocr@sha256:542961a42d9183813819a23ef3a8b50bfb4f5ef7b0fb4f8e4f56edd8445efb18
FROM ${BASE_IMAGE}

ENV PYTHONUNBUFFERED=1

WORKDIR /app

# Bake the ~6.7 GB of weights into the image at build time — the CI runner has network
# access here, unlike a serverless worker at cold start. The earlier design downloaded
# them on first use and leaned on an optional network volume to avoid repeating that on
# every cold start; baking them in makes the volume unnecessary and the cold start
# self-contained. (This was first written to explain the crash-loop, which it did not:
# that was the missing "python" binary below. Kept because the cold-start win is real.)
#
# These layers come before the requirements and the code, and depend only on the base
# image and MODEL_REVISION, so a build that changes the worker reuses them from cache
# and a host that pulled the previous image fetches only the small layers on top.
#
# Only what vLLM loads is staged, from a fixed commit of baidu/Unlimited-OCR: configs,
# tokenizer, chat template, the safetensors index and the shard it names, and the MIT
# license that has to ship with the weights. Left out: model.safetensors (a second copy
# of that shard, 6.7 GB vLLM never reads), the Python modeling files that
# trust_remote_code=False never imports, the sglang wheel, the paper and README assets.
ARG MODEL_REVISION=8249d20b2822a7d8a4c22c8d8c9069b5ac36bda3
ENV MODEL_NAME=/opt/models/unlimited-ocr
RUN python3 - <<'PY'
import json
import os
import shutil
from pathlib import Path

from huggingface_hub import snapshot_download

model = Path(os.environ["MODEL_NAME"])
snapshot_download(
    repo_id="baidu/Unlimited-OCR",
    revision=os.environ["MODEL_REVISION"],
    allow_patterns=["*.json", "model-*-of-*.safetensors", "chat_template.jinja", "LICENSE"],
    local_dir=model,
)
# Download bookkeeping, not part of the model.
shutil.rmtree(model / ".cache", ignore_errors=True)

index = json.loads((model / "model.safetensors.index.json").read_text(encoding="utf-8"))
shards = set(index["weight_map"].values())
missing = sorted(shard for shard in shards if not (model / shard).is_file())
assert not missing, f"shards named in the index were not staged: {missing}"
assert not (model / "model.safetensors").exists(), "the duplicate model.safetensors was staged"
print("staged", sorted(path.name for path in model.iterdir()))
PY

ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

# vLLM's tokenizer loader rereads this file without LLM's hf_overrides.
# Use the native type so it selects the checkpoint's ByteLevel tokenizer
# instead of the Llama tokenizer named in the upstream metadata.
RUN python3 - <<'PY'
import json
import os
from pathlib import Path

path = Path(os.environ["MODEL_NAME"]) / "config.json"
config = json.loads(path.read_text(encoding="utf-8"))
if config["model_type"] == "unlimited_ocr":
    config["model_type"] = "unlimited-ocr"
    path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
PY

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY handler.py postprocess.py ocr_logits.py /app/

# The base image's entrypoint starts vLLM's OpenAI server; RunPod needs our worker
# loop, so ENTRYPOINT is cleared and CMD is exec'd directly — which means CMD has to
# name an interpreter that actually exists. This image ships "python3" but no plain
# "python", and that mismatch showed up only at runtime as a bare "worker exited with
# exit code 127": no traceback, nothing in the log naming the missing binary, and the
# container died before reaching a line of handler.py. Every worker crash-looped on it.
#
# So: pin the interpreter to a fixed absolute path, and prove here — at build time,
# where a failure is loud and immediate — that it runs and that the worker's imports
# resolve. A regression now breaks the build instead of silently killing the endpoint.
RUN ln -sf "$(command -v python3)" /usr/local/bin/ocr-worker-python \
 && /usr/local/bin/ocr-worker-python -c "import runpod, PIL.Image; print('interpreter + deps OK')" \
 && /usr/local/bin/ocr-worker-python -c "import sys, PIL; sys.exit(f'Pillow {PIL.__version__} is older than 13.0, which fixes the decoder advisories open against 12.x' if tuple(map(int, PIL.__version__.split('.')[:2])) < (13, 0) else 0)" \
 && /usr/local/bin/ocr-worker-python -c "import handler; print('handler imports OK')"

RUN /usr/local/bin/ocr-worker-python - <<'PY'
from multiprocessing.reduction import ForkingPickler
from pathlib import Path

from tokenizers import Tokenizer
from tokenizers.decoders import DecodeStream
from vllm.tokenizers.registry import get_tokenizer
from vllm.transformers_utils.config import get_config
from vllm.transformers_utils.configs.unlimited_ocr import UnlimitedOCRConfig

from handler import MODEL_NAME, build_multi_prompt
from ocr_logits import NoRepeatNGramAdapter

assert isinstance(get_config(MODEL_NAME, trust_remote_code=False), UnlimitedOCRConfig)
print("native OCR model configuration OK")

tokenizer = get_tokenizer(MODEL_NAME, trust_remote_code=False)
reference = Tokenizer.from_file(str(Path(MODEL_NAME) / "tokenizer.json"))
prompt = build_multi_prompt(2)
assert tokenizer.encode(prompt, add_special_tokens=False) == reference.encode(
    prompt, add_special_tokens=False
).ids
sample = "<PAGE><|ref|>title<|/ref|>Facture d’été\n128,40 € — 多页"
ids = reference.encode(sample, add_special_tokens=False).ids
stream = DecodeStream(ids=tokenizer.encode(prompt), skip_special_tokens=False)
assert "".join(stream.step(tokenizer.backend_tokenizer, token) or "" for token in ids) == sample
print("OCR tokenizer preserves prompt IDs, Unicode and layout markers")

assert ForkingPickler.loads(ForkingPickler.dumps(NoRepeatNGramAdapter)) is NoRepeatNGramAdapter
print("OCR logits processor is spawn-serializable")
PY

ENTRYPOINT []
CMD ["/usr/local/bin/ocr-worker-python", "-u", "/app/handler.py"]
