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

ENV PYTHONUNBUFFERED=1 \
    HF_HOME=/runpod-volume/huggingface-cache \
    MODEL_NAME=baidu/Unlimited-OCR

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY handler.py /app/handler.py

# The base image's entrypoint starts vLLM's OpenAI server; RunPod needs our worker loop.
ENTRYPOINT []
CMD ["python", "-u", "/app/handler.py"]
