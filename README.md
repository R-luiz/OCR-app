# OCR App

An Android OCR app with two engines behind one interface:

- **Quick scan** — Google ML Kit Text Recognition v2, fully on-device. Instant, free,
  works offline, ~4 MB bundled into the APK.
- **Document parsing** — Baidu [Unlimited-OCR](https://github.com/baidu/Unlimited-OCR)
  running on your own RunPod Serverless GPU endpoint. Returns layout-aware Markdown with
  tables, and parses a whole multi-page document in a single request.

Document parsing degrades to Quick scan (with a visible notice) whenever the backend is
unconfigured or unreachable, so the app is always useful.

```
android/   Kotlin + Jetpack Compose app
backend/   RunPod Serverless worker (vLLM + Unlimited-OCR)
```

---

## Why this split

Unlimited-OCR is a ~3.3B-parameter vision-language model: DeepSeek-OCR's `DeepEncoder`
(SAM-ViT + CLIP-ViT cascade, 16× token compression, 1024px page → 256 visual tokens)
feeding a DeepSeek-V2 MoE decoder whose attention is replaced by Reference Sliding Window
Attention. That R-SWA decoder keeps the KV cache flat across pages, which is what lets it
parse dozens of pages in one 32K-context pass. bf16 weights are ~6.67 GB.

**It cannot be the app's only engine.** Baidu's supported runtimes — Transformers, vLLM,
SGLang — are all CUDA-only. Mainline llama.cpp does carry the architecture (`deepseek2-ocr`
in `gguf-py/gguf/constants.py`) and community GGUF quants exist, so an on-device build is
technically possible; but a Q4 quant needs ~3–4 GB resident RAM, a ~2.5 GB first-run
download, and delivers minutes-per-page on a flagship. That is not a camera-to-text
experience, so on-device Unlimited-OCR is left out of v1. The `OcrEngine` interface has
room for it as a third implementation.

**Fly.io is not an option for the GPU.** Fly deprecated GPUs
[as of 2026-07-31](https://community.fly.io/t/gpu-migration-fly-io-gpus-will-be-deprecated-as-of-july-31-2026/27110);
no GPU tier remains at any size. A CPU-only Fly machine small enough to be cheap cannot
even hold the weights, and a machine large enough to hold them still takes minutes per
page. Hence RunPod Serverless, which bills per second and scales to zero.

---

## Backend

### Build and push

```bash
cd backend
docker build -t <your-registry>/unlimited-ocr-worker:latest .
docker push <your-registry>/unlimited-ocr-worker:latest
```

The image is based on `vllm/vllm-openai:unlimited-ocr`, which Baidu publishes with the
architecture already patched into vLLM. For Hopper GPUs (H100/H200) build against the
CUDA 12.9 variant instead:

```bash
docker build --build-arg BASE_IMAGE=vllm/vllm-openai:unlimited-ocr-cu129 .
```

### Create the RunPod endpoint

1. RunPod → Serverless → New Endpoint, pointing at your image.
2. **GPU:** 48 GB (L40S) recommended for 32K-context headroom. 24 GB is workable for
   single-page scans.
3. **Attach a Network Volume.** `HF_HOME` is set to `/runpod-volume/huggingface-cache`, so
   with a volume attached the ~6.7 GB of weights are downloaded once instead of on every
   cold start (a 60–90 s penalty per start otherwise).
4. Idle timeout ~60 s, max workers 1 to begin with.
5. Copy the **endpoint ID**, and create an API key under Settings.

Environment variables the worker honours: `MODEL_NAME`, `MAX_MODEL_LEN` (default 32768),
`GPU_MEMORY_UTILIZATION` (0.85), `MAX_PAGES` (64), `PRELOAD_MODEL` (`1` loads the model at
worker startup rather than inside the first request).

### Job shape

```json
{"input": {
  "mode": "single",
  "images": ["data:image/jpeg;base64,..."],
  "base_size": 1024, "image_size": 640, "crop_mode": true
}}
```

`single` uses Baidu's "gundam" configuration (640px crops over a 1024px base);
`multi` uses "base" (flat 1024px) and sends every page in one job. Response:

```json
{"markdown": "...", "pages": [{"index": 0, "markdown": "..."}],
 "model": "baidu/Unlimited-OCR", "elapsed_ms": 1234}
```

### Test it

```bash
cd backend
python -m pytest test_handler.py -q          # pure helpers, no GPU needed

# against a deployed endpoint
python test_local.py --endpoint <ENDPOINT_ID> --api-key $RUNPOD_API_KEY --image page.png

# against a local dev server on a CUDA box
python handler.py --rp_serve_api --rp_api_port 8000
python test_local.py --url http://127.0.0.1:8000/runsync --image page.png
```

---

## Android app

### Configure

Create `android/local.properties` (gitignored):

```properties
sdk.dir=/path/to/Android/sdk
runpod.endpointId=<your endpoint id>
runpod.apiKey=<your api key>
```

Both RunPod values are optional — leave them out and the app runs Quick scan only. They
can also be entered at runtime under **Settings**, which overrides the build-time values
and stores them in `EncryptedSharedPreferences`.

### Build

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Requires the Android SDK (compileSdk 35) and JDK 17+. `minSdk` is 28, which lets image
decoding go through a single `ImageDecoder` path with correct EXIF handling.

### How a scan flows

`PageLoader` turns camera captures, picked images, and PDF pages (via the platform
`PdfRenderer`, composited onto white) into a list of `PageImage`. `OcrRepository` routes
that list to the selected `OcrEngine`. `RemoteOcrEngine` downscales each page to a 1024px
long edge at JPEG q85, submits one RunPod job with `POST /v2/{id}/run`, then polls
`/status/{jobId}` with backoff — the synchronous `/runsync` route is deliberately unused
because it gives up at 90 seconds, which a cold start alone can exceed. Results are stored
in Room with a thumbnail on disk; the Result screen renders Markdown (including tables) or
offers an editable raw view.

---

## Verification status

Verified in this environment:

- `backend/test_handler.py` — **33 tests pass** (input parsing, base64/data-URL decoding,
  special-token cleanup, page splitting, the no-repeat-ngram logits processor).
- `MarkdownParser` and `MarkdownToPlainText` — **19 tests pass**, compiled and run on the
  JVM.
- The whole remote pipeline (`OcrEngine`, `RunPodCredentials`, `ImageNormalizer`,
  `RunPodApi`, `RemoteOcrEngine`, `MarkdownToPlainText`) **type-checks clean** against real
  Retrofit, OkHttp, kotlinx-serialization and an Android 13 platform jar.

Not verified here: the Compose UI, Hilt wiring, Room, ML Kit, and the Robolectric-based
tests (`RemoteOcrEngineTest`, `ImageNormalizerTest`, `ScanDaoTest`). Building those needs
`dl.google.com` and `maven.google.com`, both of which were blocked by this environment's
egress policy. Run `./gradlew assembleDebug testDebugUnitTest` on a machine with the
Android SDK before trusting them; the dependency versions in
`android/gradle/libs.versions.toml` may also need bumping to whatever is current.
