#!/usr/bin/env python3
"""End-to-end smoke test for the Unlimited-OCR worker.

Points at either a local dev server or a deployed RunPod endpoint and asserts that a
page image comes back as non-empty Markdown.

Local (on a CUDA box, from backend/)::

    python handler.py --rp_serve_api --rp_api_port 8000
    python test_local.py --url http://127.0.0.1:8000/runsync --image sample.png

Deployed::

    python test_local.py --endpoint <ENDPOINT_ID> --api-key $RUNPOD_API_KEY \\
        --image sample.png

Unlike the app, this uses the synchronous ``/runsync`` route for brevity; it will time
out at 90 seconds against a cold worker, which is expected — re-run once it is warm.
"""

from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import sys
import time
import urllib.error
import urllib.request

POLL_INTERVAL_SECONDS = 3
DEFAULT_TIMEOUT_SECONDS = 900


def encode_image(path: str) -> str:
    mime = mimetypes.guess_type(path)[0] or "image/png"
    with open(path, "rb") as handle:
        return f"data:{mime};base64,{base64.b64encode(handle.read()).decode()}"


def post(url: str, payload: dict, api_key: str | None, timeout: int) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    if api_key:
        request.add_header("Authorization", f"Bearer {api_key}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read())


def get(url: str, api_key: str | None, timeout: int) -> dict:
    request = urllib.request.Request(url, method="GET")
    if api_key:
        request.add_header("Authorization", f"Bearer {api_key}")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read())


def run_async(endpoint: str, api_key: str, payload: dict, timeout: int) -> dict:
    """Submit + poll, mirroring what the Android client does."""
    base = f"https://api.runpod.ai/v2/{endpoint}"
    job = post(f"{base}/run", payload, api_key, timeout=60)
    job_id = job.get("id")
    if not job_id:
        raise SystemExit(f"no job id in submit response: {job}")

    print(f"submitted job {job_id}")
    deadline = time.monotonic() + timeout
    last_status = None
    while time.monotonic() < deadline:
        time.sleep(POLL_INTERVAL_SECONDS)
        job = get(f"{base}/status/{job_id}", api_key, timeout=60)
        status = job.get("status")
        if status != last_status:
            print(f"  status: {status}")
            last_status = status
        if status in ("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"):
            return job
    raise SystemExit(f"timed out after {timeout}s waiting for job {job_id}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", action="append", required=True,
                        help="page image; repeat the flag for multi-page parsing")
    parser.add_argument("--url", help="full URL of a local /runsync dev server")
    parser.add_argument("--endpoint", help="RunPod endpoint id (deployed mode)")
    parser.add_argument("--api-key", help="RunPod API key (deployed mode)")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    args = parser.parse_args()

    if not args.url and not (args.endpoint and args.api_key):
        parser.error("pass either --url, or both --endpoint and --api-key")

    images = [encode_image(path) for path in args.image]
    payload = {
        "input": {
            "mode": "single" if len(images) == 1 else "multi",
            "images": images,
        },
    }
    print(f"sending {len(images)} page(s) in {payload['input']['mode']} mode")

    started = time.monotonic()
    try:
        if args.url:
            response = post(args.url, payload, args.api_key, timeout=args.timeout)
        else:
            response = run_async(args.endpoint, args.api_key, payload, args.timeout)
    except urllib.error.HTTPError as exc:
        print(f"FAIL: HTTP {exc.code}: {exc.read().decode(errors='replace')}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"FAIL: could not reach the worker: {exc.reason}", file=sys.stderr)
        return 1

    elapsed = time.monotonic() - started
    output = response.get("output", response)

    if isinstance(output, dict) and output.get("error"):
        print(f"FAIL: worker returned an error: {output['error']}", file=sys.stderr)
        return 1

    markdown = (output or {}).get("markdown", "") if isinstance(output, dict) else ""
    if not markdown.strip():
        print(f"FAIL: empty markdown in response: {json.dumps(response)[:500]}", file=sys.stderr)
        return 1

    print(f"\nOK in {elapsed:.1f}s — {len(markdown)} chars, "
          f"{len((output or {}).get('pages', []))} page section(s)\n")
    print(markdown[:2000])
    if len(markdown) > 2000:
        print(f"\n… {len(markdown) - 2000} more chars")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
