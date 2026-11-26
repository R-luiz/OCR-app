# OCR App

Android OCR with two modes:

- **Quick scan** uses the bundled ML Kit Latin-script recognizer. Images and PDFs
  stay on the device; no account, download or connection required.
- **Document parsing** sends pages to your own RunPod Serverless endpoint running
  [Baidu Unlimited-OCR](https://github.com/baidu/Unlimited-OCR). It returns
  Markdown with headings and tables. If the endpoint fails or is not configured,
  the app runs Quick scan and saves a notice with the result.

## Scan, correct and share

1. Tap **New scan** and choose the engine. Take a photo, select up to 20 images,
   or import a PDF. To collect photos first, turn on **Several pages** and tap
   **Scan** when the batch is ready. A scan accepts up to 64 pages; a truncated
   import is reported in the result.
2. For a Markdown result, use **Rendered** to read it or **Raw** to correct it.
   Copy or share the text, or save your changes. Use the result menu to rename or
   delete the scan.
3. Find a saved scan by its title or recognized text. Engine, page count and
   notices are kept in the scan history. Upgrading an installation signed with
   the same key preserves the existing Room database.

The home screen also opens the QR scanner and QR generator. Generated codes can
be shared or saved as PNG.

PDF pages are rasterized one at a time at the resolution each mode needs. Remote
scans use RunPod's async `/run` API, with consecutive jobs when the upload would
exceed its payload limit. Cancelling asks RunPod to stop the active job when its
ID is known; work already done may still be billed.

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
extracted from the debug APK, so do not distribute it. The app stores credentials
entered in **Settings** in encrypted preferences, and a connection test does
not save them.

`./gradlew assembleRelease` produces a minified ARM APK with no RunPod
credentials, in `app/build/outputs/apk/release/`. For a local x86-64 emulator,
pass `-Pocr.releaseAbis=x86_64`. Set `OCR_KEYSTORE_FILE`,
`OCR_KEYSTORE_PASSWORD`, `OCR_KEY_ALIAS` and `OCR_KEY_PASSWORD` to sign with
your release key; without them, local builds use debug signing for sideloading.

The manual **Release APK** workflow requires the same signing secrets, with
`OCR_KEYSTORE_BASE64` in place of the file path, and a `v<versionName>` tag.
Use the same key for updates. Android cannot install a differently signed
release over an earlier debug APK. Share or copy anything you need to keep
before uninstalling: uninstalling removes the local scan history.

## Configure Document parsing

1. Point a RunPod **Serverless** endpoint at
   `ghcr.io/r-luiz/ocr-app/unlimited-ocr-worker:latest`. The **Backend image**
   workflow publishes a commit-specific tag as well; use it to pin a rollout.
2. A 48 GB GPU (such as an L40S) gives headroom for the 32K context. Start with one
   maximum worker; the idle timeout trades warm-start latency against GPU cost.
   No network volume is needed: the image contains the model weights.
3. In the app's **Settings**, enter the endpoint ID and API key, test the
   connection and save. A successful connection test checks endpoint health, not
   OCR inference.

For a rollout from GitHub Actions, set `RUNPOD_API_KEY` as a repository
secret and run **Deploy RunPod endpoint** with the release's commit-specific
`image_tag`. The workflow updates an existing endpoint in place. Use
`deploy-and-smoke-test` with `smoke_test_pages=2` to OCR the two distinct
pages of the test PDF on RunPod.

To build an image yourself:

```bash
docker build -t <registry>/unlimited-ocr-worker:latest ./backend
docker push <registry>/unlimited-ocr-worker:latest
```

On Hopper GPUs, use
`--build-arg BASE_IMAGE=vllm/vllm-openai:unlimited-ocr-cu129`.

### Worker contract

```json
{"input":{"images":["data:image/jpeg;base64,...","data:image/jpeg;base64,..."],"long_context":true}}
```

`long_context` is optional. By default, multi-page jobs try joint inference in
windows of up to 12 pages. If a window fails, is cut off, or lacks reliable
page boundaries, the worker processes that window page by page and returns a
warning. Context does not carry across windows or jobs.

```json
{
  "markdown": "# Invoice",
  "pages": [{"index":0,"markdown":"# Invoice"},{"index":1,"markdown":""}],
  "empty_pages": [1],
  "page_mode": "per_page",
  "model": "baidu/Unlimited-OCR",
  "elapsed_ms": 1234
}
```

`pages` and `empty_pages` use zero-based indexes within the job. `markdown`
contains only recognized content, not warnings or placeholders for empty pages.
`warnings` is present when a pass has to fall back or a page was cut off.
Errors are returned as `{"error":"..."}`.

Worker settings include `MODEL_NAME`, `MAX_MODEL_LEN` (32768),
`GPU_MEMORY_UTILIZATION` (0.85), `MAX_PAGES` (64), `LONG_CONTEXT_MULTI` (enabled),
`LONG_CONTEXT_WINDOW_PAGES` (12), and `PRELOAD_MODEL` (`1`).

### Verify the worker

```bash
cd backend
python -m pip install pillow pytest
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
