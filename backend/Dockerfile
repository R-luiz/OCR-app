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

# Bake the ~6.7 GB of weights into the image at build time — the CI runner has
# network access here, unlike a serverless worker at cold start. Downloading them on
# first request instead (the earlier design, gated on an optional network volume)
# meant a worker's very first health check could still be mid-download when RunPod's
# timeout hit, so RunPod tore it down and restarted it into the same unfinished
# download forever: an unhealthy crash-loop rather than merely a slow cold start.
RUN python3 -c "from huggingface_hub import snapshot_download; \
    snapshot_download(repo_id='baidu/Unlimited-OCR', local_dir='/opt/models/unlimited-ocr')"

ENV MODEL_NAME=/opt/models/unlimited-ocr \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

COPY handler.py /app/handler.py

# The base image's entrypoint starts vLLM's OpenAI server; RunPod needs our worker loop.
ENTRYPOINT []
CMD ["python", "-u", "/app/handler.py"]
