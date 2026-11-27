# OCR App

Android OCR with two modes:

- **Quick scan** uses the bundled ML Kit Latin-script recognizer. Images and PDFs
  stay on the device; no account, download or connection required.
- **Document parsing** sends pages to a RunPod Serverless endpoint running
  [Baidu Unlimited-OCR](https://github.com/baidu/Unlimited-OCR). It returns
  Markdown with headings and tables. The app reaches the hosted endpoint through
  the gateway at `ocr.robinluiz.fr` after a GitHub sign-in, or your own endpoint
  with your own RunPod key (Settings → Advanced). If the endpoint fails or is not
  configured, the app runs Quick scan and saves a notice with the result.

## Scan, correct and share

1. Tap **New scan** and choose the engine. Take a photo, select up to 20 images,
   or import a PDF. To collect photos first, turn on **Several pages**: the batch
   shows a thumbnail per page, which you can open to keep or delete, and **Scan**
   sends the batch. A scan accepts up to 64 pages; a truncated import is reported
   in the result.
2. A Document-parsing scan keeps running when you switch apps. If you allow
   notifications, the progress notification shows the elapsed time and a
   **Cancel** button; if the scan finishes in the background, tap **Scan ready**
   to open its text. An idle server can take up to 10 minutes to start.
3. For a Markdown result, use **Rendered** to read it or **Raw** to correct it.
   Copy and share use the view on screen, and an unsaved correction survives
   rotation. Use the result menu to rename or delete the scan.
4. When Document parsing fell back to Quick scan, the scan's pages are kept for
   7 days, and the result menu offers **Retry with Document parsing**. A
   successful retry replaces the text; a failed one keeps it.
5. Find a saved scan by its title or recognized text; the search ignores case and
   accents. Engine, page count and notices are kept in the scan history.
   Upgrading an installation signed with the same key preserves the existing Room
   database.

The home screen also opens the QR scanner and QR generator. Generated codes can
be shared or saved as PNG.

Photos are taken at the size recognition uses, and PDF pages are rasterized one
at a time at the resolution each mode needs. Remote scans use RunPod's async
`/run` API. The app splits documents into consecutive jobs at the upload limit
(2 MiB through the hosted gateway); the model cannot carry context between jobs.
Cancelling asks RunPod to stop the active job when its ID is known; work already
done may still be billed.

## Build Android

Requires JDK 17 and Android SDK 35. Minimum Android version: 9 (API 28).

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest  # emulator or device; real ML Kit and PdfRenderer
```

On Windows, use `gradlew.bat`. The debug APK is in `app/build/outputs/apk/debug/`.
Optionally put `runpod.endpointId` and `runpod.apiKey` in the gitignored
`android/local.properties` **for local debug builds only**; those values can be
extracted from the debug APK, so do not distribute it. The app stores an endpoint
entered in **Settings → Advanced**, and the gateway session, in encrypted
preferences; a connection test does not save anything.

`./gradlew assembleRelease` produces a minified APK with no RunPod credentials,
in `app/build/outputs/apk/release/`, for `arm64-v8a` and `armeabi-v7a` together.
Pass `-Pocr.releaseAbis=<abi>` for one ABI, such as `x86_64` for a local
emulator. Set `OCR_KEYSTORE_FILE`, `OCR_KEYSTORE_PASSWORD`, `OCR_KEY_ALIAS` and
`OCR_KEY_PASSWORD` to sign with your release key; without them, local builds use
debug signing for sideloading.

The manual **Release APK** workflow runs on `main` only and needs a
`v<versionName>` tag. It builds one APK per ABI without secrets, signs them in a
separate job that alone holds `OCR_KEYSTORE_BASE64`, `OCR_KEYSTORE_PASSWORD`,
`OCR_KEY_ALIAS` and `OCR_KEY_PASSWORD`, then publishes only APKs whose signing
certificate is the release certificate pinned in `release.yml`. Use the same key
for updates: Android cannot install a differently signed release over an earlier
one, and the sign-in link only opens the app when its certificate is the one the
gateway lists. Share or copy anything you need to keep before uninstalling:
uninstalling removes the local scan history.

## Configure Document parsing

### Hosted endpoint (GitHub sign-in)

In **Settings**, tap **Sign in with GitHub**, approve on GitHub, and the browser
returns to the app. There is no endpoint ID or API key to enter. The session
lasts 7 days; signing in again, on any device, ends the previous session, and
**Disconnect** requests revocation and removes the session from the device.
Revocation is subject to Cloudflare KV propagation. Only the GitHub account
configured in the gateway is admitted.

The gateway (`gateway/`) is a Cloudflare Worker. It holds the RunPod key and
relays the app's four calls (`run`, `status`, `cancel`, `health`) to one fixed
endpoint. A `run` body must be `{"input": {...}}`; the gateway adds the
execution timeout and TTL itself. Its parts:

- A GitHub App with the callback URL `https://ocr.robinluiz.fr/auth/callback`, no
  permissions, no webhook, and Device Flow off. Its client ID is in
  `gateway/wrangler.jsonc` and `ManagedSignIn.kt`.
- `/.well-known/assetlinks.json`, listing the release and debug signing
  certificates (`ASSET_LINKS` in `gateway/src/index.js`), so Android opens the
  callback in the app.
- Vars `GITHUB_CLIENT_ID`, `GITHUB_OWNER_ID` (the admitted account's numeric ID)
  and `RUNPOD_ENDPOINT_ID`.
- The KV namespace `SESSION_STATE`, which records which sessions are void, and
  the rate limit `AUTH_LIMITER` on `/auth/token` and `/auth/signout`.
- Secrets `RUNPOD_API_KEY` (a restricted key scoped to the endpoint),
  `OCR_SESSION_KEY` (signs sessions; changing it signs every device out) and
  `GITHUB_CLIENT_SECRET`.

```bash
cd gateway
npm test
npx wrangler@4 secret put RUNPOD_API_KEY   # also OCR_SESSION_KEY, GITHUB_CLIENT_SECRET
CLOUDFLARE_ACCOUNT_ID=<account id> npx wrangler@4 deploy
```

`wrangler.jsonc` explains each setting. A fork serving its own domain also
changes the host in `OcrBackends.kt`, `AndroidManifest.xml` and
`gateway/src/index.js`.

### Your own endpoint

1. Point a RunPod **Serverless** endpoint at
   `ghcr.io/r-luiz/ocr-app/unlimited-ocr-worker:<commit-sha>`. The **Backend
   image** workflow publishes that tag for worker changes on `main` and moves
   `:latest` along with it.
2. A 48 GB GPU (such as an L40S) gives headroom for the 32K context. Start with one
   maximum worker; the idle timeout trades warm-start latency against GPU cost.
   No network volume is needed: the image contains the model weights.
3. In the app's **Settings → Advanced**, enter the endpoint ID and an API key,
   preferably a restricted key with access to that endpoint only. Test the
   connection and save. A successful connection test checks endpoint health, not
   OCR inference.

### Deploy from GitHub Actions

Set `RUNPOD_API_KEY` as a repository secret and run **Deploy RunPod endpoint**
from `main`. It deploys the commit-specific `image_tag` (by default the commit
the workflow runs on) and updates an existing endpoint in place.
`deploy-and-smoke-test` then OCRs the test PDF, two pages by default
(`smoke_test_pages`), and checks the recognized text; if the smoke test fails,
the endpoint goes back to the image it ran before. Workers get 900 seconds to
pull the image and start (`init_timeout`), and a job 15 minutes to run.
For the default CUDA 13 image, use `min_cuda_version=13.0` and 48 GB GPU pools
such as `gpu_ids=ADA_48_PRO,AMPERE_48`. After changing only host placement, use
`recycle-workers` if an idle worker remains on the previous hardware; the
command refuses while a worker is processing jobs.

If a worker fails before taking a job, `diagnose-startup` runs a smoke test
against the current image with model loading inside the request, then restores
the endpoint's original worker environment. Pass the endpoint ID; the smoke test
uses the same two-page PDF.

To build an image yourself:

```bash
docker build -t <registry>/unlimited-ocr-worker:<tag> ./backend
docker push <registry>/unlimited-ocr-worker:<tag>
```

The Dockerfile pins the vLLM base image by digest and the model by
`MODEL_REVISION`. For Hopper GPUs, the Dockerfile lists the pinned CUDA 12.9
value for `--build-arg BASE_IMAGE`.
The worker uses vLLM's native Unlimited-OCR configuration rather than executing
the checkpoint's Transformers modeling code. The image build normalizes the
bundled checkpoint's model type so vLLM selects its ByteLevel tokenizer. It
checks the configuration and a Unicode OCR sample offline before publishing.
The worker also disables prefix and image-processor caching per the
[vLLM serving recipe](https://recipes.vllm.ai/baidu/Unlimited-OCR.md): a page's
image embeddings differ between single-page crop mode and multi-page mode.

### Worker contract

```json
{"input":{"images":["data:image/jpeg;base64,...","data:image/jpeg;base64,..."],"long_context":true}}
```

Pages must be JPEG, PNG or WebP; their sizes are checked from the image headers
before any page is decoded. `long_context` is optional. By default, multi-page
jobs try joint inference in windows of up to 12 pages. If a window fails, is cut
off, or lacks reliable page boundaries, the worker processes that window page by
page and returns a warning. Context does not carry across windows or jobs.

```json
{
  "markdown": "# Invoice",
  "pages": [{"index":0,"markdown":"# Invoice"},{"index":1,"markdown":""}],
  "empty_pages": [1],
  "page_mode": "per_page",
  "long_context": {"windows": 1, "parsed": 1},
  "model": "baidu/Unlimited-OCR",
  "elapsed_ms": 1234,
  "stats": [{"kind": "window", "prompts": 1, "prompt_tokens": 870, "output_tokens": 412, "ms": 5120}]
}
```

`pages` and `empty_pages` use zero-based indexes within the job. `markdown`
contains only recognized content, not warnings or placeholders for empty pages.
`long_context` counts the windows sent to joint inference and those it parsed.
`stats` has one entry per model call. `warnings` is present when a pass has to
fall back or a page was cut off. Errors are returned as `{"error":"..."}`.

Worker settings include `MODEL_NAME`, `MAX_MODEL_LEN` (32768),
`GPU_MEMORY_UTILIZATION` (0.85), `MAX_PAGES` (64), `LONG_CONTEXT_MULTI` (enabled),
`LONG_CONTEXT_WINDOW_PAGES` (12), `PAGE_MAX_TOKENS` (8192, the output cap of a
single-page pass), `MAX_IMAGE_BYTES` (25 MiB), `MAX_IMAGE_PIXELS` (16 MP per
page), `MAX_TOTAL_PIXELS` (64 × 2048² per job) and `PRELOAD_MODEL` (`1`).

### Verify the worker

The worker runs on Python 3.12.

```bash
cd backend
python -m pip install -r requirements.txt pytest
python -m pytest test_handler.py test_postprocess.py test_deploy_runpod.py -q
```

The unit tests do not load the model. On a CUDA dev server, or against a
deployed endpoint, pass one `--image` per page and confirm the expected text.
Testing a deployed endpoint submits a billable job.

```bash
# Start the local server in a separate shell on a CUDA machine:
python handler.py --rp_serve_api --rp_api_port 8000

python test_local.py --url http://127.0.0.1:8000/runsync --image page.png --image page2.png
python test_local.py --endpoint <ENDPOINT_ID> --api-key "$RUNPOD_API_KEY" --image page.png
```
