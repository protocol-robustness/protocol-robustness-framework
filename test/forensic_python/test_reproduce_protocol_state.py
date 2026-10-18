"""Tests for reproduce.py protocol/state-hashes comparison."""

from __future__ import annotations

# Ensure scripts/ is importable
import sys
import json
import subprocess
from pathlib import Path
import pytest
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
if str(_scripts_root) not in sys.path:
    sys.path.insert(0, str(_scripts_root))

from scripts.forensic.reproduce import (
    FIELD_CLASSIFICATION,
    classify_field,
    compare_bundle_fields,
)
from scripts.forensic import reproduce


@pytest.mark.parametrize("execution_exit", [0, 7])
def test_reproduction_isolates_artifacts_and_requires_execution_success(tmp_path, monkeypatch, execution_exit):
    checkout = tmp_path / "checkout"
    checkout.mkdir()
    original = tmp_path / "original"
    original.mkdir()
    output = tmp_path / "reproductions"
    existing_artifacts = checkout / "results"
    existing_artifacts.mkdir()
    sentinel = existing_artifacts / "keep.txt"
    sentinel.write_text("preserve")
    bundle = _bundle()
    (original / "clojure-bundle-root.json").write_text(json.dumps(bundle))
    monkeypatch.chdir(checkout)
    monkeypatch.setenv("PRF_ARTIFACT_DIR", str(existing_artifacts))
    monkeypatch.setattr(reproduce, "current_code_hash", lambda root: ("source-hash", "algorithm", ["src"]))

    def execute(cmd, **kwargs):
        artifact_root = Path(kwargs["env"]["PRF_ARTIFACT_DIR"])
        assert artifact_root.name == "workspace"
        assert artifact_root.is_dir()
        assert artifact_root.is_relative_to(output)
        destination = Path(cmd[cmd.index("--output-file") + 1])
        assert artifact_root.parent == destination.parent
        destination.write_text(json.dumps(bundle))
        (artifact_root / "evidence.txt").write_text("new evidence")
        return subprocess.CompletedProcess(cmd, execution_exit)

    monkeypatch.setattr(reproduce.subprocess, "run", execute)
    result = reproduce.reproduce_run(original, output)
    assert result == (0 if execution_exit == 0 else 1)
    report = json.loads(next(output.rglob("reproduce-report.json")).read_text())
    assert report["reproduce/exit-code"] == execution_exit
    if execution_exit:
        assert report["reproduce/verdict"] == "reproduce-failed"
    assert sentinel.read_text() == "preserve"
    assert list(existing_artifacts.iterdir()) == [sentinel]


def _bundle(proto: dict | None = None) -> dict:
    """Build a minimal bundle root with optional protocol state hashes."""
    b = {
        "bundle/hash": "test-hash",
        "bundle/schema-version": "bundle-root.v1",
        "execution/summary": {"status": "pass", "totals": {"passed": 1, "failed": 0, "total": 1}},
        "registry/snapshot": {"attestor-registry-hash": "reg1"},
    }
    if proto is not None:
        b["protocol/state-hashes"] = proto
    return b


class TestFieldClassification:
    def test_fa_hash_is_core(self):
        assert classify_field("protocol/state-hashes/force-authorisations/hash") == "core"

    def test_fa_consumed_hash_is_core(self):
        assert classify_field("protocol/state-hashes/force-authorisations/consumed-hash") == "core"


class TestCompareBundleFields:
    def test_matching_protocol_state(self):
        proto = {
            "force-authorisations/hash": "hash123",
            "force-authorisations/consumed-hash": "consumed456",
        }
        a = _bundle(proto)
        b = _bundle(dict(proto))
        checks = compare_bundle_fields(a, b, None)
        proto_checks = [c for c in checks if "protocol/state-hashes" in c["field"]]
        assert len(proto_checks) == 2
        assert all(c["match"] is True for c in proto_checks)
        assert all(c["classification"] == "core" for c in proto_checks)

    def test_mismatched_protocol_state(self):
        a = _bundle({"force-authorisations/hash": "hash123",
                      "force-authorisations/consumed-hash": "consumed456"})
        b = _bundle({"force-authorisations/hash": "DIFFERENT",
                      "force-authorisations/consumed-hash": "consumed456"})
        checks = compare_bundle_fields(a, b, None)
        proto_checks = [c for c in checks if "protocol/state-hashes" in c["field"]]
        fa_check = [c for c in proto_checks if "force-authorisations/hash" in c["field"]][0]
        assert fa_check["match"] is False
        assert fa_check["classification"] == "core"

    def test_missing_protocol_state_in_new(self):
        a = _bundle({"force-authorisations/hash": "hash123",
                      "force-authorisations/consumed-hash": "consumed456"})
        b = _bundle(None)
        checks = compare_bundle_fields(a, b, None)
        proto_checks = [c for c in checks if "protocol/state-hashes" in c["field"]]
        assert len(proto_checks) == 2
        assert all(c["match"] is None for c in proto_checks)
        assert all(c["reason"] == "missing/reproduced-field" for c in proto_checks)

    def test_missing_protocol_state_in_orig(self):
        a = _bundle(None)
        b = _bundle({"force-authorisations/hash": "hash123",
                      "force-authorisations/consumed-hash": "consumed456"})
        checks = compare_bundle_fields(a, b, None)
        proto_checks = [c for c in checks if "protocol/state-hashes" in c["field"]]
        assert len(proto_checks) == 2
        assert all(c["match"] is None for c in proto_checks)
        assert all(c["reason"] == "missing/original-field" for c in proto_checks)
