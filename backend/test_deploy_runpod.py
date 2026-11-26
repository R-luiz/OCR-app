"""Deployment CLI contracts against stubbed Runpod responses, without network access."""

from __future__ import annotations

import sys
import types

import pytest

import deploy_runpod


@pytest.fixture(autouse=True)
def api_key(monkeypatch):
    monkeypatch.setenv("RUNPOD_API_KEY", "test-key")


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
    assert run_main(monkeypatch) == 0


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

    def patch(method, path, *, json):
        assert (method, path) == ("PATCH", "/v2/serverless/abc123")
        changes = dict(json)
        endpoint["workers"].update(changes.pop("workers"))
        endpoint["gpu"].update(changes.pop("gpu", {}))
        endpoint.update(changes)
        return endpoint

    rest = types.ModuleType("runpod.api.rest")
    rest.run_rest_request = patch
    monkeypatch.setitem(sys.modules, "runpod.api.rest", rest)
    return endpoint


def test_deploy_updates_existing_rest_endpoint_without_recreating_it(monkeypatch, rest_endpoint):
    assert run_main(
        monkeypatch, "--confirm", "--image", "registry/worker:release", "--idle-timeout", "180",
    ) == 0
    assert rest_endpoint["image"] == "registry/worker:release"
    assert rest_endpoint["disk"] == 80
    assert rest_endpoint["workers"] == {"min": 0, "max": 1, "idleTimeout": 180, "initTimeout": 120}
    assert rest_endpoint["flashboot"] == "FLASHBOOT"
    assert rest_endpoint["gpu"] == {"pools": ["ADA_48_PRO"], "count": 1}
    assert rest_endpoint["networkVolumes"] == ["volume-1"]


def test_deploy_can_move_existing_endpoint_to_a_different_gpu_pool(monkeypatch, rest_endpoint):
    rest_endpoint["gpu"] = {"pools": ["BLACKWELL_48"], "count": 1, "minCudaVersion": "13.0"}

    assert run_main(
        monkeypatch, "--confirm", "--gpu-ids", "ADA_48_PRO, AMPERE_48", "--init-timeout", "720",
    ) == 0
    assert rest_endpoint["gpu"] == {
        "pools": ["ADA_48_PRO", "AMPERE_48"], "count": 1, "minCudaVersion": "13.0",
    }
    assert rest_endpoint["workers"]["initTimeout"] == 720


def test_deploy_keeps_request_count_scaling(monkeypatch, rest_endpoint):
    rest_endpoint["scaling"] = {"type": "REQUEST_COUNT", "requestCount": 2}
    del rest_endpoint["workers"]["idleTimeout"]

    assert run_main(monkeypatch, "--confirm") == 0
    assert rest_endpoint["scaling"] == {"type": "REQUEST_COUNT", "requestCount": 2}
    assert "idleTimeout" not in rest_endpoint["workers"]


def test_recycle_restores_limits_when_health_check_fails(monkeypatch, rest_endpoint):
    rest_endpoint["workers"]["min"] = 1

    def failed_health(*args):
        workers = rest_endpoint["workers"]
        assert (workers["min"], workers["max"]) == (0, 0)
        raise OSError("unreachable")

    monkeypatch.setattr(deploy_runpod, "_worker_counts", failed_health)
    with pytest.raises(OSError, match="unreachable"):
        run_main(monkeypatch, "--recycle-workers", "abc123")

    assert rest_endpoint["workers"] == {"min": 1, "max": 2, "idleTimeout": 60, "initTimeout": 120}
    assert rest_endpoint["flashboot"] == "OFF"
