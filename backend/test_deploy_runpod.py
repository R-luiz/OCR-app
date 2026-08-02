"""Tests for the argument dispatch in deploy_runpod.py.

These cover the read-only actions specifically, because the failure mode there is
silent: a health check that never contacted the endpoint still exited 0 and turned
the CI job green. Nothing here touches the network — the `runpod` SDK is stubbed.
"""

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


ENDPOINT = {
    "id": "abc123",
    "name": deploy_runpod.ENDPOINT_NAME,
    "templateId": "tpl1",
    "gpuIds": "ADA_48_PRO",
    "networkVolumeId": None,
    "locations": None,
    "idleTimeout": 60,
    "scalerType": "QUEUE_DELAY",
    "scalerValue": 4,
    "workersMin": 0,
    "workersMax": 1,
}


@pytest.fixture
def captured_mutation(monkeypatch):
    """Captures the GraphQL string _save_endpoint sends, without sending it."""
    sent: list[str] = []

    graphql = types.ModuleType("runpod.api.graphql")
    graphql.run_graphql_query = lambda mutation: (
        sent.append(mutation) or {"data": {"saveEndpoint": {"id": "abc123"}}}
    )
    monkeypatch.setitem(sys.modules, "runpod.api.graphql", graphql)
    return sent


def test_save_endpoint_always_sets_flashboot(captured_mutation):
    """saveEndpoint replaces its whole input rather than patching it, and RunPod's
    endpoint query never returns flashBootType — so a save that omits it silently
    turns FlashBoot off. That happened on every recycle-workers run, and FlashBoot is
    what keeps the cold start on an ~8.8 GB image from being the app's longest wait."""
    deploy_runpod._save_endpoint(dict(ENDPOINT), workers_max=1)

    assert "flashBootType: FLASHBOOT" in captured_mutation[0]


def test_save_endpoint_can_change_the_idle_timeout(captured_mutation):
    deploy_runpod._save_endpoint(dict(ENDPOINT), workers_max=1, idle_timeout=300)

    assert "idleTimeout: 300" in captured_mutation[0]


def test_save_endpoint_keeps_the_existing_idle_timeout_by_default(captured_mutation):
    """Draining workers must not quietly rewrite unrelated configuration."""
    deploy_runpod._save_endpoint(dict(ENDPOINT), workers_max=0)

    assert "idleTimeout: 60" in captured_mutation[0]
