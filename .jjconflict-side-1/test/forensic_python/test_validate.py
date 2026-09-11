"""Tests for scripts/forensic/validate_sew.py — Sew protocol-semantic validation."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from scripts.forensic.validate_sew import (
    SEW_EVENT_REASONS,
    canonical_protocol_state_hash,
    find_sew_evidence_files,
    validate_protocol_bundle,
    validate_bundle_root,
    validate_evidence_lifecycle,
    validate_protocol_state,
)


def _make_bundle(has_proto_state: bool = False,
                 force_auth_hash: str = "abc123def456",
                 consumed_hash: str = "consumed789") -> dict:
    """Build a minimal Sew bundle root dict for testing."""
    bundle = {
        "bundle/schema-version": "bundle-root.v1",
        "bundle/hash": "test-hash",
        "protocol": {"id": "sew", "version": "1"},
        "execution/summary": {"status": "pass", "totals": {"passed": 1, "failed": 0, "total": 1}},
        "registry/snapshot": {"attestor-registry-hash": "reg1"},
    }
    if has_proto_state:
        bundle["protocol/state-hashes"] = {
            "force-authorisations/hash": force_auth_hash,
            "force-authorisations/consumed-hash": consumed_hash,
        }
    return bundle


class TestValidateBundleRoot:
    def test_skips_when_no_protocol_state(self):
        bundle = _make_bundle(False)
        checks = validate_bundle_root(bundle)
        assert len(checks) == 1
        assert checks[0]["check"] == "protocol-state-hashes-present"
        assert checks[0]["status"] == "skip"

    def test_passes_with_valid_hashes(self):
        bundle = _make_bundle(True)
        checks = validate_bundle_root(bundle)
        assert len(checks) == 3
        assert checks[0]["status"] == "pass"
        assert checks[1]["status"] == "pass"
        assert checks[2]["status"] == "pass"

    def test_fails_when_fa_hash_empty(self):
        bundle = _make_bundle(True, force_auth_hash="")
        checks = validate_bundle_root(bundle)
        fa_check = [c for c in checks if "hash-well-formed" in c["check"]
                     and "force-authorisations" in c["check"]
                     and "consumed" not in c["check"]][0]
        assert fa_check["status"] == "fail"

    def test_fails_when_consumed_hash_empty(self):
        bundle = _make_bundle(True, consumed_hash="")
        checks = validate_bundle_root(bundle)
        consumed_check = [c for c in checks if "consumed" in c["check"]][0]
        assert consumed_check["status"] == "fail"


class TestValidateProtocolState:
    def test_requires_witness_when_requested(self):
        checks = validate_protocol_state(_make_bundle(True), require_witness=True)
        assert checks[0]["status"] == "fail"

    def test_rejects_orphan_consumption(self):
        bundle = _make_bundle(True)
        bundle["protocol/state"] = {
            "force-authorisations": {},
            "force-authorisations/consumed": {"fa-0": {"held-adjustment/id": "missing"}},
            "held-adjustments": [],
        }
        checks = validate_protocol_state(bundle, require_witness=True)
        assert checks[0]["status"] == "fail"

    def test_rejects_state_witness_hash_mismatch(self):
        bundle = _make_bundle(True)
        bundle["protocol/state"] = {
            "force-authorisations": {},
            "force-authorisations/consumed": {},
            "held-adjustments": [],
        }
        bundle["protocol/state-witness-hash"] = "tampered"
        checks = validate_protocol_state(bundle, require_witness=True)
        assert checks[0]["status"] == "fail"
        assert checks[0]["details"][0]["type"] == "state-witness-hash-mismatch"

    def test_scenario_scoped_duplicate_auth_ids_are_unambiguous(self):
        record = {
            "authorization/id": "fa-0",
            "authorization/status": "active",
            "authorization/scope": {"workflow-id": 0},
            "authorization/scope-hash": "scope-hash",
        }
        bundle = _make_bundle(True)
        bundle["protocol/state"] = {
            "force-authorisations": {"scenario-a": {"fa-0": record},
                                       "scenario-b": {"fa-0": dict(record)}},
            "force-authorisations/consumed": {},
            "held-adjustments": {},
        }
        bundle["protocol/state-witness-hash"] = canonical_protocol_state_hash(bundle["protocol/state"])
        checks = validate_protocol_state(bundle, require_witness=True)
        assert checks[0]["status"] == "pass"


class TestFindEvidenceFiles:
    def test_returns_empty_when_no_event_dir(self, tmp_path):
        assert find_sew_evidence_files(tmp_path) == []

    def test_finds_force_auth_events(self, tmp_path):
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "force-authorisation-granted",
              "force-auth/auth-id": "fa-0", "event/seq": 0}
        (ev_dir / "grant.json").write_text(json.dumps(ev))
        result = find_sew_evidence_files(tmp_path)
        assert len(result) == 1
        assert result[0]["type"] == "force-authorisation-granted"

    def test_skips_non_force_auth_events(self, tmp_path):
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "escrow-released", "event/seq": 0}
        (ev_dir / "release.json").write_text(json.dumps(ev))
        assert find_sew_evidence_files(tmp_path) == []


class TestValidateEvidenceLifecycle:
    def test_empty_events_returns_no_checks(self):
        assert validate_evidence_lifecycle([]) == []

    def test_grant_then_execute_passes(self):
        events = [
            {"type": "force-authorisation-granted", "auth-id": "fa-0", "seq": 0},
            {"type": "force-authorisation-executed", "auth-id": "fa-0", "seq": 1},
        ]
        checks = validate_evidence_lifecycle(events)
        fails = [c for c in checks if c["status"] == "fail"]
        assert len(fails) == 0

    def test_execute_without_grant_fails(self):
        events = [
            {"type": "force-authorisation-executed", "auth-id": "fa-0", "seq": 0},
        ]
        checks = validate_evidence_lifecycle(events)
        fails = [c for c in checks if c["status"] == "fail"]
        assert len(fails) == 1
        assert "execute-without-grant" in fails[0]["check"]

    def test_double_execute_fails(self):
        events = [
            {"type": "force-authorisation-granted", "auth-id": "fa-0", "seq": 0},
            {"type": "force-authorisation-executed", "auth-id": "fa-0", "seq": 1},
            {"type": "force-authorisation-executed", "auth-id": "fa-0", "seq": 2},
        ]
        checks = validate_evidence_lifecycle(events)
        fails = [c for c in checks if c["status"] == "fail"]
        assert len(fails) == 1
        assert "double-execute" in fails[0]["check"]


class TestValidateProtocolBundle:
    def test_pass_with_proto_state_and_no_evidence(self, tmp_path):
        bundle = _make_bundle(True)
        bundle_path = tmp_path / "bundle.json"
        bundle_path.write_text(json.dumps(bundle))
        report = validate_protocol_bundle(bundle_path)
        assert report["sew/status"] == "pass"

    def test_fail_when_evidence_but_no_proto_state(self, tmp_path):
        bundle = _make_bundle(False)
        bundle_path = tmp_path / "bundle.json"
        bundle_path.write_text(json.dumps(bundle))
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "force-authorisation-granted",
              "force-auth/auth-id": "fa-0", "event/seq": 0}
        (ev_dir / "grant.json").write_text(json.dumps(ev))
        report = validate_protocol_bundle(bundle_path, run_dir=tmp_path)
        assert report["sew/status"] == "fail"
        fails = [c for c in report["sew/checks"] if c["status"] == "fail"]
        assert any("sew-evidence-without-state-hashes" in c["check"] for c in fails)

    def test_pass_with_evidence_and_proto_state(self, tmp_path):
        bundle = _make_bundle(True)
        bundle["protocol/state"] = {
            "force-authorisations": {
                "fa-0": {
                    "authorization/id": "fa-0",
                    "authorization/status": "active",
                    "authorization/scope": {"workflow-id": 0},
                    "authorization/scope-hash": "scope-hash",
                }
            },
            "force-authorisations/consumed": {},
            "held-adjustments": [],
        }
        bundle["protocol/state-witness-hash"] = canonical_protocol_state_hash(bundle["protocol/state"])
        bundle_path = tmp_path / "bundle.json"
        bundle_path.write_text(json.dumps(bundle))
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "force-authorisation-granted",
              "force-auth/auth-id": "fa-0", "event/seq": 0}
        (ev_dir / "grant.json").write_text(json.dumps(ev))
        report = validate_protocol_bundle(bundle_path, run_dir=tmp_path)
        assert report["sew/status"] == "pass"
