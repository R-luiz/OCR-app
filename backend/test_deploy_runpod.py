"""Deployment CLI contracts against stubbed Runpod responses, without network access."""

from __future__ import annotations

import contextlib
import copy
import io
import json
import sys
import types
import urllib.request

import pytest

import deploy_runpod


@pytest.fixture(autouse=True)
def api_key(monkeypatch):
    monkeypatch.setenv("RUNPOD_API_KEY", "test-key")
    # The script hides account details when it runs in GitHub Actions, as these tests
    # do in CI; each test that cares sets it.
    monkeypatch.delenv("GITHUB_ACTIONS", raising=False)


@pytest.fixture
def fake_runpod(monkeypatch):
    """Stands in for the `runpod` package, which main() imports lazily."""
    module = types.ModuleType("runpod")
    module.api_key = None
    module.get_endpoints = lambda: [
        {"id": "abc123", "name": deploy_runpod.ENDPOINT_NAME},
    ]
    monkeypatch.setitem(sys.modules, "runpod", module)
    return module


def run_main(monkeypatch, *argv: str) -> int:
    monkeypatch.setattr(sys, "argv", ["deploy_runpod.py", *argv])
    return deploy_runpod.main()


def test_blank_health_check_still_checks_health(monkeypatch, fake_runpod):
    """The deploy workflow passes its optional endpoint_id input through verbatim, so
    a blank value reaches the script. It must resolve, not fall through to a dry run."""
    called: list[str] = []
    monkeypatch.setattr(
        deploy_runpod, "health_check", lambda endpoint_id, key: called.append(endpoint_id) or 0,
    )

    assert run_main(monkeypatch, "--health-check", "") == 0
    assert called == ["abc123"]


def test_explicit_endpoint_id_is_used_verbatim(monkeypatch, fake_runpod):
    called: list[str] = []
    monkeypatch.setattr(
        deploy_runpod, "health_check", lambda endpoint_id, key: called.append(endpoint_id) or 0,
    )

    assert run_main(monkeypatch, "--health-check", "other99") == 0
    assert called == ["other99"]


def test_blank_purge_queue_resolves_too(monkeypatch, fake_runpod):
    called: list[str] = []
    monkeypatch.setattr(
        deploy_runpod, "purge_queue", lambda endpoint_id, key: called.append(endpoint_id) or 0,
    )

    assert run_main(monkeypatch, "--purge-queue", "") == 0
    assert called == ["abc123"]


def test_missing_endpoint_fails_instead_of_deploying(monkeypatch, fake_runpod):
    """With no endpoint to resolve to, the run must fail — never silently continue
    into the deploy path, which provisions billable GPU capacity."""
    fake_runpod.get_endpoints = lambda: []
    monkeypatch.setattr(
        deploy_runpod,
        "deploy",
        lambda *args, **kwargs: pytest.fail("deploy must not run for a read-only action"),
    )

    assert run_main(monkeypatch, "--health-check", "") == 1


def test_no_read_only_flag_reaches_the_dry_run(monkeypatch, fake_runpod):
    """Omitting the flags entirely leaves args at None, which is still the dry run."""
    assert run_main(monkeypatch, "--image", "registry/worker:new") == 0


@pytest.mark.parametrize(
    "argv", [[], ["--dry-run"], ["--confirm"], ["--confirm", "--smoke-test", "page.png"]],
)
def test_deploy_and_dry_run_need_an_explicit_image(monkeypatch, fake_runpod, argv):
    with pytest.raises(SystemExit) as exit_info:
        run_main(monkeypatch, *argv)
    assert exit_info.value.code == 2


def test_smoke_test_expect_needs_one_text_per_image(monkeypatch, fake_runpod):
    with pytest.raises(SystemExit) as exit_info:
        run_main(
            monkeypatch, "--confirm", "--image", "registry/worker:new",
            "--smoke-test", "a.png", "b.png", "--smoke-test-expect", "A",
        )
    assert exit_info.value.code == 2


@pytest.mark.parametrize("pages", ["0", "65"])
def test_rejects_unsupported_smoke_test_sizes_before_deploy(monkeypatch, fake_runpod, pages):
    with pytest.raises(SystemExit) as exit_info:
        run_main(
            monkeypatch, "--confirm", "--image", "registry/worker:new",
            "--smoke-test", "a.png", "--smoke-test-pages", pages,
        )
    assert exit_info.value.code == 2


def test_actions_logs_hold_no_account_inventory_or_balance(monkeypatch, fake_runpod, capsys):
    monkeypatch.setenv("GITHUB_ACTIONS", "true")
    fake_runpod.get_user = lambda: {"clientBalance": 12.34}

    assert run_main(monkeypatch, "--dry-run", "--image", "registry/worker:new") == 0

    out = capsys.readouterr().out
    assert "account has 1 serverless endpoint(s)" in out
    assert "abc123" not in out
    assert "12.34" not in out


def test_outside_actions_the_dry_run_lists_the_account(monkeypatch, fake_runpod, capsys):
    fake_runpod.get_user = lambda: {"clientBalance": 12.34}

    assert run_main(monkeypatch, "--dry-run", "--image", "registry/worker:new") == 0

    out = capsys.readouterr().out
    assert "abc123" in out
    assert "$12.34" in out


@pytest.fixture
def rest_endpoint(monkeypatch, fake_runpod):
    endpoint = {
        "id": "abc123",
        "name": deploy_runpod.ENDPOINT_NAME,
        "image": "registry/worker:old",
        "gpu": {"pools": ["ADA_48_PRO"], "count": 1},
        "networkVolumes": ["volume-1"],
        "workers": {"min": 0, "max": 2, "idleTimeout": 60, "initTimeout": 120},
        "scaling": {"type": "QUEUE_DELAY", "queueDelay": 4},
        "flashboot": "OFF",
    }
    fake_runpod.get_endpoints = lambda: [endpoint]

    def rest_request(method, path, *, json=None):
        assert path == "/v2/serverless/abc123"
        if method == "GET":
            return copy.deepcopy(endpoint)
        assert method == "PATCH"
        changes = dict(json)
        endpoint["workers"].update(changes.pop("workers", {}))
        endpoint["gpu"].update(changes.pop("gpu", {}))
        endpoint.update(changes)
        return endpoint

    rest = types.ModuleType("runpod.api.rest")
    rest.run_rest_request = rest_request
    monkeypatch.setitem(sys.modules, "runpod.api.rest", rest)
    return endpoint


def test_deploy_updates_existing_rest_endpoint_without_recreating_it(monkeypatch, rest_endpoint):
    assert run_main(
        monkeypatch, "--confirm", "--image", "registry/worker:release", "--idle-timeout", "180",
    ) == 0
    assert rest_endpoint["image"] == "registry/worker:release"
    assert rest_endpoint["disk"] == 80
    assert rest_endpoint["workers"] == {"min": 0, "max": 1, "idleTimeout": 180, "initTimeout": 900}
    assert rest_endpoint["timeout"] == 900_000
    assert rest_endpoint["flashboot"] == "FLASHBOOT"
    assert rest_endpoint["gpu"] == {"pools": ["ADA_48_PRO"], "count": 1}
    assert rest_endpoint["networkVolumes"] == ["volume-1"]


def test_deploy_can_move_existing_endpoint_to_a_different_gpu_pool(monkeypatch, rest_endpoint):
    rest_endpoint["gpu"] = {"pools": ["BLACKWELL_48"], "count": 1, "minCudaVersion": "12.0"}

    assert run_main(
        monkeypatch, "--confirm", "--image", "registry/worker:new", "--gpu-ids", "ADA_48_PRO, AMPERE_48",
        "--init-timeout", "720", "--min-cuda-version", "13.0",
    ) == 0
    assert rest_endpoint["gpu"] == {
        "pools": ["ADA_48_PRO", "AMPERE_48"], "count": 1, "minCudaVersion": "13.0",
    }
    assert rest_endpoint["workers"]["initTimeout"] == 720


def test_deploy_keeps_request_count_scaling(monkeypatch, rest_endpoint):
    rest_endpoint["scaling"] = {"type": "REQUEST_COUNT", "requestCount": 2}
    del rest_endpoint["workers"]["idleTimeout"]

    assert run_main(monkeypatch, "--confirm", "--image", "registry/worker:new") == 0
    assert rest_endpoint["scaling"] == {"type": "REQUEST_COUNT", "requestCount": 2}
    assert "idleTimeout" not in rest_endpoint["workers"]


def test_diagnose_startup_restores_worker_environment_after_failed_ocr(monkeypatch, rest_endpoint):
    original = {"MAX_MODEL_LEN": "8192", "PRELOAD_MODEL": "1"}
    rest_endpoint["env"] = original.copy()

    def failed_ocr(endpoint_id, api_key, paths, timeout, pages, expect):
        assert endpoint_id == "abc123"
        assert rest_endpoint["env"] == {**original, "PRELOAD_MODEL": "0"}
        return 1

    monkeypatch.setattr(deploy_runpod, "smoke_test", failed_ocr)

    assert run_main(
        monkeypatch, "--confirm", "--diagnose-startup", "abc123", "--smoke-test", "page.png",
    ) == 1
    assert rest_endpoint["env"] == original
    assert rest_endpoint["image"] == "registry/worker:old"


def test_a_new_endpoint_gets_both_timeouts(monkeypatch, fake_runpod):
    fake_runpod.get_endpoints = lambda: []
    fake_runpod.create_template = lambda **kwargs: {"id": "template-1"}
    fake_runpod.create_endpoint = lambda **kwargs: {"id": "new-1"}
    patches = []
    rest = types.ModuleType("runpod.api.rest")
    rest.run_rest_request = lambda method, path, *, json=None: patches.append((method, path, json)) or {}
    monkeypatch.setitem(sys.modules, "runpod.api.rest", rest)

    assert run_main(monkeypatch, "--confirm", "--image", "registry/worker:new") == 0
    assert patches == [
        ("PATCH", "/v2/serverless/new-1", {"workers": {"initTimeout": 900}, "timeout": 900_000}),
    ]


def test_actions_logs_mask_endpoint_ids_before_printing_them(monkeypatch, rest_endpoint, capsys):
    monkeypatch.setenv("GITHUB_ACTIONS", "true")

    assert run_main(monkeypatch, "--confirm", "--image", "registry/worker:new") == 0

    lines = capsys.readouterr().out.splitlines()
    assert next(line for line in lines if "abc123" in line) == "::add-mask::abc123"


DEPLOY_AND_SMOKE_TEST = ("--confirm", "--image", "registry/worker:new", "--smoke-test", "page.png")


def _fake_smoke_test(monkeypatch, rest_endpoint, outcome):
    """Replaces smoke_test with one that returns ``outcome`` (or raises it) and records
    the image the endpoint ran while it was tested."""
    tested = []

    def smoke_test(endpoint_id, api_key, paths, timeout, pages, expect):
        tested.append(rest_endpoint["image"])
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    monkeypatch.setattr(deploy_runpod, "smoke_test", smoke_test)
    return tested


def test_a_failed_smoke_test_rolls_the_endpoint_back(monkeypatch, rest_endpoint, capsys):
    tested = _fake_smoke_test(monkeypatch, rest_endpoint, 1)

    assert run_main(monkeypatch, *DEPLOY_AND_SMOKE_TEST) == 1
    assert tested == ["registry/worker:new"]
    assert rest_endpoint["image"] == "registry/worker:old"
    assert "rolled back to registry/worker:old" in capsys.readouterr().out


def test_a_smoke_test_that_raises_rolls_back_too(monkeypatch, rest_endpoint):
    _fake_smoke_test(monkeypatch, rest_endpoint, OSError("unreachable"))

    with pytest.raises(OSError, match="unreachable"):
        run_main(monkeypatch, *DEPLOY_AND_SMOKE_TEST)
    assert rest_endpoint["image"] == "registry/worker:old"


def test_no_rollback_leaves_the_new_image_to_diagnose(monkeypatch, rest_endpoint):
    _fake_smoke_test(monkeypatch, rest_endpoint, 1)

    assert run_main(monkeypatch, *DEPLOY_AND_SMOKE_TEST, "--no-rollback") == 1
    assert rest_endpoint["image"] == "registry/worker:new"


def test_a_passing_smoke_test_keeps_the_new_image(monkeypatch, rest_endpoint):
    _fake_smoke_test(monkeypatch, rest_endpoint, 0)

    assert run_main(monkeypatch, *DEPLOY_AND_SMOKE_TEST) == 0
    assert rest_endpoint["image"] == "registry/worker:new"


def _output(*pages, long_context=None):
    """A worker result with one page per text in ``pages``."""
    output = {
        "markdown": "\n\n".join(pages),
        "pages": [{"index": index, "markdown": text} for index, text in enumerate(pages)],
    }
    if long_context is not None:
        output["long_context"] = long_context
    return output


def test_verify_output_cycles_the_expected_text_like_the_pages():
    output = _output("Acme invoice", "page two", "Acme again", long_context={"windows": 1, "parsed": 1})

    assert deploy_runpod.verify_output(output, 3, ["Acme", "two"]) == []
    assert deploy_runpod.verify_output(output, 3, ["two", "Acme"]) == [
        "page 1 does not contain 'two'",
        "page 2 does not contain 'Acme'",
        "page 3 does not contain 'two'",
    ]


@pytest.mark.parametrize("symbol", ["\u0120", "\u010a"])
def test_verify_output_rejects_byte_level_token_symbols(symbol):
    (failure,) = deploy_runpod.verify_output(_output(f"Acme{symbol}Supplies"), 1)
    assert "byte-level token symbols" in failure


@pytest.mark.parametrize(
    "long_context",
    [None, "yes", {"windows": 0, "parsed": 0}, {"windows": 1, "parsed": 0}, {"windows": 2, "parsed": 1}],
)
def test_verify_output_requires_every_window_of_a_multi_page_job_to_parse(long_context):
    (failure,) = deploy_runpod.verify_output(_output("one", "two", long_context=long_context), 2)
    assert failure.startswith("long_context is")


def test_verify_output_checks_long_context_only_for_several_pages():
    assert deploy_runpod.verify_output(_output("one", "two", long_context={"windows": 1, "parsed": 1}), 2) == []
    assert deploy_runpod.verify_output(_output("one", long_context={"windows": 0, "parsed": 0}), 1) == []


@pytest.fixture
def runpod_job(monkeypatch, tmp_path):
    """Serves smoke_test one job that completes on the first poll; tests fill in
    ``job["output"]``."""
    job = {"status": "COMPLETED", "workerId": "worker-77", "output": {}}

    def urlopen(request, timeout):
        body = {"id": "job-1"} if request.full_url.endswith("/run") else job
        return contextlib.nullcontext(io.BytesIO(json.dumps(body).encode()))

    monkeypatch.setattr(urllib.request, "urlopen", urlopen)
    monkeypatch.setattr(deploy_runpod.time, "sleep", lambda seconds: None)
    page = tmp_path / "page.png"
    page.write_bytes(b"page")
    return types.SimpleNamespace(job=job, page=str(page))


def test_smoke_test_passes_a_document_with_the_expected_text(runpod_job):
    runpod_job.job["output"] = {
        **_output("Acme Supplies Ltd", "Second page of the document"),
        "long_context": {"windows": 1, "parsed": 1},
        "stats": [{"kind": "window", "prompts": 1, "prompt_tokens": 900, "output_tokens": 120, "ms": 4000}],
    }

    assert deploy_runpod.smoke_test(
        "abc123", "key", [runpod_job.page, runpod_job.page], 60, pages=2,
        expect=["Acme Supplies Ltd", "Second page of the document"],
    ) == 0


def test_smoke_test_fails_on_byte_level_symbols_and_masks_the_worker(monkeypatch, capsys, runpod_job):
    monkeypatch.setenv("GITHUB_ACTIONS", "true")
    runpod_job.job["output"] = _output("Acme\u0120Supplies\u0120Ltd")

    assert deploy_runpod.smoke_test("abc123", "key", [runpod_job.page], 60) == 1

    captured = capsys.readouterr()
    assert "::add-mask::worker-77" in captured.out
    assert "byte-level token symbols" in captured.err


def test_recycle_restores_limits_when_health_check_fails(monkeypatch, rest_endpoint):
    rest_endpoint["workers"]["min"] = 1

    def failed_health(*args):
        workers = rest_endpoint["workers"]
        if workers["max"] > 0:
            return {"running": 0}
        assert (workers["min"], workers["max"]) == (0, 0)
        raise OSError("unreachable")

    monkeypatch.setattr(deploy_runpod, "_worker_counts", failed_health)
    with pytest.raises(OSError, match="unreachable"):
        run_main(monkeypatch, "--recycle-workers", "abc123")

    assert rest_endpoint["workers"] == {"min": 1, "max": 2, "idleTimeout": 60, "initTimeout": 120}
    assert rest_endpoint["flashboot"] == "OFF"


def test_recycle_leaves_an_endpoint_with_running_jobs_alone(monkeypatch, rest_endpoint):
    observed_limits = []

    def worker_counts(*args):
        observed_limits.append(rest_endpoint["workers"]["max"])
        return {"running": 1}

    monkeypatch.setattr(deploy_runpod, "_worker_counts", worker_counts)
    assert run_main(monkeypatch, "--recycle-workers", "abc123") == 1
    assert rest_endpoint["workers"]["max"] == 2
    assert observed_limits == [2]
