# syntax=docker/dockerfile:1
# Baidu publishes a vLLM image with the Unlimited-OCR architecture already patched in,
# so there is no reason to build vLLM from source here.
#
#   vllm/vllm-openai:unlimited-ocr        CUDA 13.0 (default)
#   vllm/vllm-openai:unlimited-ocr-cu129  CUDA 12.9, for Hopper (H100/H200)
#
# Override at build time when targeting Hopper:
#   docker build --build-arg BASE_IMAGE=vllm/vllm-openai:unlimited-ocr-cu129 .
ARG BASE_IMAGE=vllm/vllm-openai:unlimited-ocr
FROM ${BASE_IMAGE}

ENV PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# Bake the ~6.7 GB of weights into the image at build time — the CI runner has network
# access here, unlike a serverless worker at cold start. The earlier design downloaded
# them on first use and leaned on an optional network volume to avoid repeating that on
# every cold start; baking them in makes the volume unnecessary and the cold start
# self-contained. (This was first written to explain the crash-loop, which it did not:
# that was the missing "python" binary below. Kept because the cold-start win is real.)
RUN python3 -c "from huggingface_hub import snapshot_download; \
    snapshot_download(repo_id='baidu/Unlimited-OCR', local_dir='/opt/models/unlimited-ocr')"

ENV MODEL_NAME=/opt/models/unlimited-ocr \
    HF_HUB_OFFLINE=1 \
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
