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

# Fallback only. CI passes --image with an immutable per-commit tag instead: ":latest"
# is mutable, so it can still resolve to the previous build while a rebuild is in
# flight, and a warm worker that already pulled it will not fetch it again — a deploy
# that appears to succeed while changing nothing at all.
DEFAULT_IMAGE = "ghcr.io/r-luiz/ocr-app/unlimited-ocr-worker:latest"
ENDPOINT_NAME = "ocr-app-unlimited-ocr"
TEMPLATE_NAME = "ocr-app-unlimited-ocr-template"

# Priority list: Ada 48 GB (L40S / A6000 Ada) first, Ampere 48 GB as fallback.
# 48 GB gives headroom for the 32K context; 24 GB only fits single-page work.
DEFAULT_GPU_IDS = "ADA_48_PRO,AMPERE_48"

# The image bakes in the ~6.7 GB of model weights at build time (see backend/Dockerfile),
# on top of the ~8.8 GB compressed base image, so container disk needs headroom for both.
DEFAULT_CONTAINER_DISK_GB = 80

POLL_INTERVAL_SECONDS = 5

# RunPod refuses endpoint creation below this, and the error only surfaces after a
# template has already been made. Checking up front keeps dry-run honest.
MIN_BALANCE_USD = 0.01


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


# Keys RunPod has used for the account's spendable balance.
_BALANCE_KEYS = ("clientBalance", "currentBalance", "balance")


def account_balance(runpod) -> float | None:
    """Spendable balance, or None if the API does not report one."""
    try:
        user = runpod.get_user() or {}
    except Exception:  # noqa: BLE001 - treated as "unknown", never fatal
        return None
    for key in _BALANCE_KEYS:
        value = user.get(key)
        if isinstance(value, (int, float)):
            return float(value)
    return None


def check_balance(runpod) -> str | None:
    """Returns an error message when the account cannot fund an endpoint."""
    balance = account_balance(runpod)
    if balance is None:
        return None
    if balance < MIN_BALANCE_USD:
        return (
            f"RunPod account balance is ${balance:.2f}. Creating an endpoint requires "
            f"at least ${MIN_BALANCE_USD:.2f}.\n"
            "Add credit at https://console.runpod.io/user/billing and re-run."
        )
    return None


def summarize_account(runpod) -> None:
    endpoints = runpod.get_endpoints()
    print(f"account has {len(endpoints)} serverless endpoint(s)")
    for endpoint in endpoints:
        print(f"  - {endpoint.get('name')} ({endpoint.get('id')})")

    balance = account_balance(runpod)
    if balance is None:
        print("account balance: not reported by the API")
    else:
        print(f"account balance: ${balance:.2f}")

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
        # MODEL_NAME/HF_HUB_OFFLINE are baked into the image itself (it points at the
        # local weights snapshot dir); overriding MODEL_NAME here would silently send
        # the worker back to downloading baidu/Unlimited-OCR from the Hub at startup.
        env={
            "MAX_MODEL_LEN": "32768",
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


def smoke_test(
    endpoint_id: str,
    api_key: str,
    image_path: str,
    timeout: int,
    pages: int = 1,
) -> int:
    """Submit real pages and wait for markdown — the proof that inference works.

    ``pages`` above 1 repeats the image to exercise the multi-page path, which is a
    genuinely different branch: a different prompt, image_size 1024, crop_mode off,
    and a much larger request body. Testing only a single page left that path
    unverified while the app used it for every PDF.
    """
    import urllib.error
    import urllib.request

    mime = mimetypes.guess_type(image_path)[0] or "image/png"
    with open(image_path, "rb") as handle:
        encoded = base64.b64encode(handle.read()).decode()

    data_url = f"data:{mime};base64,{encoded}"
    mode = "single" if pages == 1 else "multi"
    payload = {
        "input": {
            "mode": mode,
            "images": [data_url] * pages,
        },
    }
    body_mb = len(json.dumps(payload)) / (1024 * 1024)
    print(f"mode={mode}, pages={pages}, request body ~{body_mb:.1f} MB")
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

    # Non-empty markdown is not enough. Each page is parsed separately now, and a page
    # that came back blank is dropped from the joined text — leaving output that still
    # reads like a plausible document while silently missing a page. Check the count.
    returned = output.get("pages") or []
    blank = [page.get("index") for page in returned if not str(page.get("markdown", "")).strip()]
    print(f"\npages: sent {pages}, returned {len(returned)}, blank {blank or 'none'}")

    if len(returned) != pages:
        print(
            f"FAIL: sent {pages} pages but the response describes {len(returned)}",
            file=sys.stderr,
        )
        return 1
    if blank:
        print(
            f"FAIL: page(s) {blank} came back empty; their content is missing from the "
            "document even though the job succeeded",
            file=sys.stderr,
        )
        return 1

    print(f"OK — {len(markdown)} chars of markdown across {len(returned)} pages\n")
    print(markdown[:2000])
    return 0


def health_check(endpoint_id: str, api_key: str) -> int:
    """Reads worker counts without submitting a job — free, and fast enough to
    diagnose a stuck deploy in seconds instead of waiting out another 30-minute
    timeout. A job stuck at IN_QUEUE with 0 workers of any kind here means RunPod
    could not schedule a worker at all (usually no GPU availability for the
    requested type in your account/region), not merely a slow image pull."""
    import urllib.error
    import urllib.request

    url = f"https://api.runpod.ai/v2/{endpoint_id}/health"
    request = urllib.request.Request(
        url, headers={"Authorization": f"Bearer {api_key}"}, method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            health = json.loads(response.read())
    except urllib.error.HTTPError as exc:
        print(f"FAIL: HTTP {exc.code}: {exc.read().decode(errors='replace')}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"FAIL: could not reach the endpoint: {exc.reason}", file=sys.stderr)
        return 1

    print(json.dumps(health, indent=2))

    workers = health.get("workers", {})
    jobs = health.get("jobs", {})
    active = sum(workers.get(key, 0) for key in ("idle", "initializing", "ready", "running"))

    diagnoses = []
    if workers.get("unhealthy", 0) > 0:
        diagnoses.append(
            f"{workers['unhealthy']} worker(s) unhealthy — started but failing health "
            "checks, often a container crash-loop. Check the endpoint's logs in the "
            "RunPod console for the actual error.",
        )
    if workers.get("throttled", 0) > 0:
        diagnoses.append(
            f"{workers['throttled']} worker(s) throttled — a RunPod capacity/concurrency "
            "limit, not something in this repo.",
        )
    if active == 0 and not diagnoses:
        diagnoses.append(
            "Zero workers in every state. RunPod is not scheduling a worker at all for "
            "this GPU selection — check GPU availability for your account/region in the "
            "console, or try different --gpu-ids.",
        )

    if jobs.get("inQueue", 0) > 0 and active == 0:
        print(
            f"\n{jobs['inQueue']} job(s) queued with no active worker — this is the "
            "stuck state, not a slow cold start.",
            file=sys.stderr,
        )
    for diagnosis in diagnoses:
        print(f"  - {diagnosis}", file=sys.stderr)

    return 0


def resolve_endpoint_id(given: str, api_key: str) -> str:
    """Turns a possibly-blank endpoint ID into a real one by falling back to the
    endpoint this repo deploys.

    The blank case is not hypothetical: the deploy workflow passes its optional
    `endpoint_id` input through verbatim, so leaving it empty used to hand the
    read-only actions an empty string. That made the flag falsy, dropped the run
    into the dry-run path, and reported success for a health check that never
    contacted the endpoint.
    """
    if given:
        return given

    import runpod

    runpod.api_key = api_key
    existing = find_endpoint(runpod, ENDPOINT_NAME)
    if not existing:
        raise DeployError(
            f"no endpoint ID given and no endpoint named {ENDPOINT_NAME} on this "
            "account — pass the ID explicitly.",
        )
    print(f"no endpoint ID given; using {ENDPOINT_NAME} ({existing['id']})")
    return existing["id"]


def _find_endpoint_by_id(runpod, endpoint_id: str) -> dict:
    for endpoint in runpod.get_endpoints():
        if endpoint.get("id") == endpoint_id:
            return endpoint
    raise DeployError(f"endpoint {endpoint_id} not found on this account")


def _save_endpoint(endpoint: dict, workers_max: int) -> dict:
    """Re-saves an endpoint with a new workersMax, leaving everything else as-is.

    saveEndpoint replaces the whole input object rather than patching it, so every
    field is re-sent from the values the API just returned for this endpoint — the
    same field set runpod's own create_endpoint sends, plus ``id`` to target the
    existing record. Anything omitted here would be cleared, hence the round trip.
    """
    from runpod.api.graphql import run_graphql_query

    fields = [
        f'id: "{endpoint["id"]}"',
        f'name: "{endpoint["name"]}"',
        f'templateId: "{endpoint["templateId"]}"',
        f'gpuIds: "{endpoint["gpuIds"]}"',
        f'networkVolumeId: "{endpoint.get("networkVolumeId") or ""}"',
        f'locations: "{endpoint.get("locations") or ""}"',
        f"idleTimeout: {int(endpoint['idleTimeout'])}",
        f'scalerType: "{endpoint["scalerType"]}"',
        f"scalerValue: {int(endpoint['scalerValue'])}",
        f"workersMin: {int(endpoint['workersMin'])}",
        f"workersMax: {int(workers_max)}",
    ]
    if endpoint.get("gpuCount") is not None:
        fields.append(f"gpuCount: {int(endpoint['gpuCount'])}")

    mutation = (
        "mutation { saveEndpoint(input: { "
        + ", ".join(fields)
        + " }) { id name templateId workersMin workersMax } }"
    )
    return run_graphql_query(mutation)["data"]["saveEndpoint"]


def _worker_counts(endpoint_id: str, api_key: str) -> dict:
    import urllib.request

    request = urllib.request.Request(
        f"https://api.runpod.ai/v2/{endpoint_id}/health",
        headers={"Authorization": f"Bearer {api_key}"},
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read()).get("workers", {})


def recycle_workers(runpod, endpoint_id: str, api_key: str, timeout: int = 300) -> int:
    """Drains the endpoint's workers so the next request starts a fresh container.

    Repointing an endpoint at a new template does not restart the workers already
    running on the old one: a warm worker keeps serving its existing container, so a
    deploy can look successful while every request still runs the previous image.
    That is exactly what happened here — three deploys in a row answered from the
    same workerId, one of them 419 ms after submission, still raising the error the
    new image fixes. Scaling to zero terminates them; restoring the limit lets the
    next request provision one from the current template.
    """
    endpoint = _find_endpoint_by_id(runpod, endpoint_id)
    original_max = int(endpoint["workersMax"])
    print(f"endpoint {endpoint_id}: workersMax {original_max} -> 0 (draining)")

    _save_endpoint(endpoint, workers_max=0)

    deadline = time.monotonic() + timeout
    drained = False
    while time.monotonic() < deadline:
        counts = _worker_counts(endpoint_id, api_key)
        total = sum(int(v) for v in counts.values())
        print(f"  workers: {counts} (total {total})")
        if total == 0:
            drained = True
            break
        time.sleep(POLL_INTERVAL_SECONDS)

    # Restore the limit even if draining timed out: leaving workersMax at 0 would
    # silently disable the endpoint entirely.
    print(f"restoring workersMax to {original_max}")
    _save_endpoint(_find_endpoint_by_id(runpod, endpoint_id), workers_max=original_max)

    if not drained:
        print(
            f"FAIL: workers did not reach zero within {timeout}s; the endpoint is "
            "restored but may still hold a stale worker.",
            file=sys.stderr,
        )
        return 1

    print("OK — workers drained; the next request will start a fresh container.")
    return 0


def purge_queue(endpoint_id: str, api_key: str) -> int:
    """Clears jobs stuck in the endpoint's queue. Touches only the queue — no
    endpoint config, template, or worker settings change, so this is safe to run
    against a live billable endpoint. Useful when a worker is stuck unhealthy
    against an old job: RunPod keeps retrying that job on the same occupied
    worker slot, so a template update alone never gets a chance to take effect
    until the queue is cleared and the endpoint can scale back to zero."""
    import urllib.error
    import urllib.request

    url = f"https://api.runpod.ai/v2/{endpoint_id}/purge-queue"
    request = urllib.request.Request(
        url, headers={"Authorization": f"Bearer {api_key}"}, method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.loads(response.read())
    except urllib.error.HTTPError as exc:
        print(f"FAIL: HTTP {exc.code}: {exc.read().decode(errors='replace')}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"FAIL: could not reach the endpoint: {exc.reason}", file=sys.stderr)
        return 1

    print(json.dumps(result, indent=2))
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
    parser.add_argument(
        "--smoke-test-pages",
        type=int,
        default=1,
        help="repeat the page this many times; >1 exercises the multi-page path",
    )
    parser.add_argument(
        "--health-check",
        metavar="ENDPOINT_ID",
        help="read worker/queue counts for an existing endpoint and exit; creates nothing",
    )
    parser.add_argument(
        "--purge-queue",
        metavar="ENDPOINT_ID",
        help="clear stuck jobs from an existing endpoint's queue; touches only the queue",
    )
    parser.add_argument(
        "--recycle-workers",
        metavar="ENDPOINT_ID",
        help="drain the endpoint's workers so the next request starts a fresh container",
    )
    args = parser.parse_args()

    try:
        api_key = require_api_key()
    except DeployError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    # `is not None` rather than a truth test: these flags carry an endpoint ID, and
    # an empty one still means "the caller asked for this action". Treating "" as
    # "flag absent" silently ran a dry run instead and exited 0.
    read_only_action = next(
        (
            (name, value)
            for name, value in (
                ("health_check", args.health_check),
                ("purge_queue", args.purge_queue),
                ("recycle_workers", args.recycle_workers),
            )
            if value is not None
        ),
        None,
    )

    if read_only_action:
        action, given = read_only_action
        try:
            endpoint_id = resolve_endpoint_id(given, api_key)
            if action == "health_check":
                return health_check(endpoint_id, api_key)
            if action == "purge_queue":
                return purge_queue(endpoint_id, api_key)

            import runpod

            runpod.api_key = api_key
            return recycle_workers(runpod, endpoint_id, api_key)
        except DeployError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 1

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
        print(f"  network volume    {args.network_volume_id or '(none — not required; weights are baked into the image)'}")
        print(f"  idle timeout      {args.idle_timeout}s")
        print(f"  workers           0..{args.workers_max}")
        if not args.dry_run:
            print("\nRe-run with --confirm to apply. This provisions billable GPU capacity.")
        return 0

    # Fail before creating a template: RunPod's own balance error arrives only after
    # the template exists, leaving an orphan behind on every attempt.
    balance_error = check_balance(runpod)
    if balance_error:
        print(f"error: {balance_error}", file=sys.stderr)
        return 1

    try:
        endpoint_id = deploy(runpod, args)
    except Exception as exc:  # noqa: BLE001 - surface the provider's message verbatim
        print(f"error: deployment failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    print(f"\nendpoint id: {endpoint_id}")
    print("Put this and your API key into the app under Settings, or into")
    print("android/local.properties as runpod.endpointId / runpod.apiKey.")

    if args.smoke_test:
        return smoke_test(
            endpoint_id,
            api_key,
            args.smoke_test,
            args.smoke_test_timeout,
            args.smoke_test_pages,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
