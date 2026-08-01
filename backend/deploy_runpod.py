#!/usr/bin/env python3
"""Create or update the Unlimited-OCR endpoint on RunPod.

Everything the deployment needs is derivable from the repo except one thing: a
RunPod API key, which is tied to an account with billing attached. Supply it as
RUNPOD_API_KEY and this script does the rest.

    python deploy_runpod.py --dry-run          # inspect account, create nothing
    python deploy_runpod.py --confirm          # create/update the endpoint
    python deploy_runpod.py --confirm --smoke-test path/to/page.png

Idempotent by endpoint name: if an endpoint called ocr-app-unlimited-ocr already
exists, its template is repointed at the current image instead of a second
billable endpoint being created.
"""

from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import os
import sys
import time

DEFAULT_IMAGE = "ghcr.io/r-luiz/ocr-app/unlimited-ocr-worker:latest"
ENDPOINT_NAME = "ocr-app-unlimited-ocr"
TEMPLATE_NAME = "ocr-app-unlimited-ocr-template"

# Priority list: Ada 48 GB (L40S / A6000 Ada) first, Ampere 48 GB as fallback.
# 48 GB gives headroom for the 32K context; 24 GB only fits single-page work.
DEFAULT_GPU_IDS = "ADA_48_PRO,AMPERE_48"

# The image alone is ~8.8 GB compressed and unpacks to appreciably more, and
# without a network volume the 6.7 GB of weights land on container disk too.
DEFAULT_CONTAINER_DISK_GB = 60

POLL_INTERVAL_SECONDS = 5


class DeployError(RuntimeError):
    pass


def require_api_key() -> str:
    key = os.environ.get("RUNPOD_API_KEY", "").strip()
    if not key:
        raise DeployError(
            "RUNPOD_API_KEY is not set.\n"
            "  Local:  export RUNPOD_API_KEY=...\n"
            "  CI:     add it under Settings -> Secrets and variables -> Actions.\n"
            "Create a key at https://console.runpod.io/user/settings."
        )
    return key


def find_endpoint(runpod, name: str) -> dict | None:
    for endpoint in runpod.get_endpoints():
        if endpoint.get("name") == name:
            return endpoint
    return None


def summarize_account(runpod) -> None:
    endpoints = runpod.get_endpoints()
    print(f"account has {len(endpoints)} serverless endpoint(s)")
    for endpoint in endpoints:
        print(f"  - {endpoint.get('name')} ({endpoint.get('id')})")

    try:
        gpus = runpod.get_gpus()
        print(f"{len(gpus)} GPU type(s) visible to this account")
    except Exception as exc:  # noqa: BLE001 - informational only
        print(f"could not list GPU types: {exc}")


def deploy(runpod, args) -> str:
    existing = find_endpoint(runpod, ENDPOINT_NAME)

    print(f"creating template from image {args.image}")
    template = runpod.create_template(
        name=f"{TEMPLATE_NAME}-{int(time.time())}",
        image_name=args.image,
        is_serverless=True,
        container_disk_in_gb=args.container_disk_gb,
        env={
            "MODEL_NAME": "baidu/Unlimited-OCR",
            "MAX_MODEL_LEN": "32768",
            # Only meaningful when a network volume is attached; harmless otherwise.
            "HF_HOME": "/runpod-volume/huggingface-cache",
        },
    )
    template_id = template["id"]
    print(f"template {template_id}")

    if existing:
        endpoint_id = existing["id"]
        print(f"endpoint {ENDPOINT_NAME} already exists ({endpoint_id}); repointing it")
        runpod.update_endpoint_template(endpoint_id=endpoint_id, template_id=template_id)
        return endpoint_id

    print(f"creating endpoint {ENDPOINT_NAME} on {args.gpu_ids}")
    endpoint = runpod.create_endpoint(
        name=ENDPOINT_NAME,
        template_id=template_id,
        gpu_ids=args.gpu_ids,
        network_volume_id=args.network_volume_id,
        idle_timeout=args.idle_timeout,
        workers_min=0,
        workers_max=args.workers_max,
        flashboot=True,
    )
    return endpoint["id"]


def smoke_test(endpoint_id: str, api_key: str, image_path: str, timeout: int) -> int:
    """Submit one real page and wait for markdown — the first proof inference works."""
    import urllib.error
    import urllib.request

    mime = mimetypes.guess_type(image_path)[0] or "image/png"
    with open(image_path, "rb") as handle:
        encoded = base64.b64encode(handle.read()).decode()

    payload = {
        "input": {
            "mode": "single",
            "images": [f"data:{mime};base64,{encoded}"],
        },
    }
    base = f"https://api.runpod.ai/v2/{endpoint_id}"
    headers = {"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}

    def post(url, body):
        request = urllib.request.Request(
            url, data=json.dumps(body).encode(), headers=headers, method="POST"
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read())

    def get(url):
        request = urllib.request.Request(url, headers=headers, method="GET")
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read())

    print(f"submitting {image_path} to {endpoint_id}")
    job = post(f"{base}/run", payload)
    job_id = job.get("id")
    if not job_id:
        print(f"FAIL: no job id in {job}", file=sys.stderr)
        return 1

    print(f"job {job_id}; first run pulls ~8.8 GB of image and 6.7 GB of weights")
    deadline = time.monotonic() + timeout
    last_status = None
    while time.monotonic() < deadline:
        time.sleep(POLL_INTERVAL_SECONDS)
        try:
            job = get(f"{base}/status/{job_id}")
        except urllib.error.URLError as exc:
            print(f"  (transient: {exc.reason})")
            continue

        status = job.get("status")
        if status != last_status:
            print(f"  status: {status}")
            last_status = status
        if status in ("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"):
            break
    else:
        print(f"FAIL: timed out after {timeout}s", file=sys.stderr)
        return 1

    if job.get("status") != "COMPLETED":
        print(f"FAIL: job ended {job.get('status')}: {json.dumps(job)[:600]}", file=sys.stderr)
        return 1

    output = job.get("output") or {}
    if output.get("error"):
        print(f"FAIL: worker error: {output['error']}", file=sys.stderr)
        return 1

    markdown = output.get("markdown", "")
    if not markdown.strip():
        print(f"FAIL: empty markdown: {json.dumps(job)[:600]}", file=sys.stderr)
        return 1

    print(f"\nOK — {len(markdown)} chars of markdown\n")
    print(markdown[:2000])
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default=DEFAULT_IMAGE)
    parser.add_argument("--gpu-ids", default=DEFAULT_GPU_IDS)
    parser.add_argument("--network-volume-id", default=os.environ.get("RUNPOD_NETWORK_VOLUME_ID") or None)
    parser.add_argument("--container-disk-gb", type=int, default=DEFAULT_CONTAINER_DISK_GB)
    parser.add_argument("--idle-timeout", type=int, default=60)
    parser.add_argument("--workers-max", type=int, default=1)
    parser.add_argument("--dry-run", action="store_true", help="inspect the account, create nothing")
    parser.add_argument(
        "--confirm",
        action="store_true",
        help="required to create or modify anything; this provisions billable GPU capacity",
    )
    parser.add_argument("--smoke-test", metavar="IMAGE", help="after deploying, OCR this page")
    parser.add_argument("--smoke-test-timeout", type=int, default=1800)
    args = parser.parse_args()

    try:
        api_key = require_api_key()
    except DeployError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    import runpod

    runpod.api_key = api_key

    if args.dry_run or not args.confirm:
        print("DRY RUN — nothing will be created.\n")
        try:
            summarize_account(runpod)
        except Exception as exc:  # noqa: BLE001 - a bad key or an outage both land here
            print(
                f"error: could not reach the RunPod API: {type(exc).__name__}: {exc}\n"
                "Check that RUNPOD_API_KEY is valid and that outbound HTTPS to\n"
                "api.runpod.io is permitted from this machine.",
                file=sys.stderr,
            )
            return 1
        print(f"\nwould deploy {args.image}")
        print(f"  endpoint name     {ENDPOINT_NAME}")
        print(f"  gpu               {args.gpu_ids}")
        print(f"  container disk    {args.container_disk_gb} GB")
        print(f"  network volume    {args.network_volume_id or '(none — weights re-download on every cold start)'}")
        print(f"  idle timeout      {args.idle_timeout}s")
        print(f"  workers           0..{args.workers_max}")
        if not args.dry_run:
            print("\nRe-run with --confirm to apply. This provisions billable GPU capacity.")
        return 0

    if not args.network_volume_id:
        print(
            "warning: no network volume. Every cold start will re-download ~6.7 GB of\n"
            "         weights. Create one in the RunPod console and pass\n"
            "         --network-volume-id / RUNPOD_NETWORK_VOLUME_ID.\n"
        )

    try:
        endpoint_id = deploy(runpod, args)
    except Exception as exc:  # noqa: BLE001 - surface the provider's message verbatim
        print(f"error: deployment failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    print(f"\nendpoint id: {endpoint_id}")
    print("Put this and your API key into the app under Settings, or into")
    print("android/local.properties as runpod.endpointId / runpod.apiKey.")

    if args.smoke_test:
        return smoke_test(endpoint_id, api_key, args.smoke_test, args.smoke_test_timeout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
