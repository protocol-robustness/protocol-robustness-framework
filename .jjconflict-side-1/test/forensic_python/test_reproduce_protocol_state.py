"""Tests for reproduce.py protocol/state-hashes comparison."""

from __future__ import annotations

# Ensure scripts/ is importable
import sys
from pathlib import Path
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
if str(_scripts_root) not in sys.path:
    sys.path.insert(0, str(_scripts_root))

from scripts.forensic.reproduce import (
    FIELD_CLASSIFICATION,
    classify_field,
    compare_bundle_fields,
)


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
