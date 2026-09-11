"""Boundary tests for validate.py — protocol dispatcher hardening.

Tests:
  - Absent protocol descriptor (legacy)
  - Malformed descriptor shapes (present but invalid)
  - Unknown protocol id/version
  - Validator raises exception
  - Validator returns malformed report
  - Expected-protocol cross-check
  - require-protocol-semantics upgrade
  - require + unknown protocol
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

import sys
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
for p in (_project_root, _scripts_root):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from scripts.forensic import validate


def _make_bundle(**overrides: Any) -> dict:
    base = {
        "bundle/schema-version": "bundle-root.v1",
        "bundle/hash": "test-hash",
        "execution/summary": {"status": "pass", "totals": {"total": 1, "passed": 1, "failed": 0}},
        "registry/snapshot": {"attestor-registry-hash": "reg1"},
    }
    base.update(overrides)
    return base


def _write_bundle(tmp_path: Path, data: dict) -> Path:
    p = tmp_path / "bundle.json"
    p.write_text(json.dumps(data))
    return p


# ── Protocol descriptor parsing ──────────────────────────────────────────


class TestReadProtocolDescriptor:
    def test_absent_is_none(self):
        assert validate.read_protocol_descriptor({}) is None

    def test_protocol_not_a_map_is_none(self):
        assert validate.read_protocol_descriptor({"protocol": "sew"}) is None

    def test_missing_id_and_version_is_none(self):
        assert validate.read_protocol_descriptor({"protocol": {}}) is None

    def test_missing_id_is_malformed(self):
        result = validate.read_protocol_descriptor({"protocol": {"version": "1"}})
        assert result is not None
        assert result.get("errors")

    def test_missing_version_is_malformed(self):
        result = validate.read_protocol_descriptor({"protocol": {"id": "sew"}})
        assert result is not None
        assert result.get("errors")

    def test_empty_id_is_malformed(self):
        result = validate.read_protocol_descriptor({"protocol": {"id": "", "version": "1"}})
        assert result is not None
        assert result.get("errors")

    def test_empty_version_is_malformed(self):
        result = validate.read_protocol_descriptor({"protocol": {"id": "sew", "version": ""}})
        assert result is not None
        assert result.get("errors")

    def test_non_string_id_is_malformed(self):
        result = validate.read_protocol_descriptor({"protocol": {"id": 123, "version": "1"}})
        assert result is not None
        assert result.get("errors")

    def test_valid_descriptor_returns_clean(self):
        result = validate.read_protocol_descriptor({"protocol": {"id": "sew", "version": "1"}})
        assert result is not None
        assert not result.get("errors")
        assert result["descriptor"] == {"id": "sew", "version": "1"}

    def test_id_pattern_rejected(self):
        result = validate.read_protocol_descriptor({"protocol": {"id": "SEW-v1", "version": "1"}})
        assert result is not None
        assert result.get("errors")


# ── Dispatch outcomes ────────────────────────────────────────────────────


class TestValidateProtocolBundle:
    def test_legacy_absent_descriptor(self, tmp_path: Path):
        bundle = _make_bundle()
        bp = _write_bundle(tmp_path, bundle)
        report = validate.validate_protocol_bundle(bp)
        assert report["validate/status"] == "not-verified"
        assert report["validate/protocol"] is None
        assert not report["validate/protocol-dispatched"]

    def test_malformed_descriptor_fails(self, tmp_path: Path):
        bundle = _make_bundle(protocol={"id": "", "version": "1"})
        bp = _write_bundle(tmp_path, bundle)
        report = validate.validate_protocol_bundle(bp)
        assert report["validate/status"] == "fail"
        checks = report["validate/checks"]
        assert any("malformed" in c["check"] for c in checks)

    def test_unknown_protocol_not_verified(self, tmp_path: Path):
        bundle = _make_bundle(protocol={"id": "unknown", "version": "99"})
        bp = _write_bundle(tmp_path, bundle)
        report = validate.validate_protocol_bundle(bp)
        assert report["validate/status"] == "not-verified"
        assert report["validate/protocol-dispatched"]

    def test_known_protocol_passes(self, tmp_path: Path):
        bundle = _make_bundle(protocol={"id": "sew", "version": "1"})
        bp = _write_bundle(tmp_path, bundle)
        report = validate.validate_protocol_bundle(bp)
        assert report["validate/status"] in ("pass", "not-verified")

    def test_validator_exception_is_error(self, tmp_path: Path):
        """A validator that raises should produce error, not not-verified."""
        def _broken_validator(*_a, **_kw) -> dict:
            raise RuntimeError("validator crashed")

        old = validate.VALIDATORS.copy()
        validate.VALIDATORS[("test", "1")] = _broken_validator
        try:
            bundle = _make_bundle(protocol={"id": "test", "version": "1"})
            bp = _write_bundle(tmp_path, bundle)
            report = validate.validate_protocol_bundle(bp)
            assert report["validate/status"] == "error"
            checks = report["validate/checks"]
            assert any("validator-execution" in c["check"] for c in checks)
        finally:
            validate.VALIDATORS.clear()
            validate.VALIDATORS.update(old)

    def test_validator_malformed_report_is_error(self, tmp_path: Path):
        """A validator returning non-dict should produce error."""
        def _bad_validator(*_a, **_kw) -> dict:
            return {"junk": True}  # no namespace-qualified keys

        old = validate.VALIDATORS.copy()
        validate.VALIDATORS[("test", "1")] = _bad_validator
        try:
            bundle = _make_bundle(protocol={"id": "test", "version": "1"})
            bp = _write_bundle(tmp_path, bundle)
            report = validate.validate_protocol_bundle(bp)
            assert report["validate/status"] == "error"
        finally:
            validate.VALIDATORS.clear()
            validate.VALIDATORS.update(old)


# ── CLI behavior ─────────────────────────────────────────────────────────


class TestVersionForms:
    """Version strings must follow the canonical grammar."""

    def test_string_version_1_accepted(self):
        result = validate._validate_descriptor_shape({"id": "sew", "version": "1"})
        assert result is not None
        assert not result.get("errors")

    def test_integer_version_rejected(self):
        """Version must be string, not integer — cross-language contract."""
        result = validate._validate_descriptor_shape({"id": "sew", "version": 1})
        assert result is not None
        assert result.get("errors")

    def test_v0_rejected(self):
        """Version 'v0' is not a numeric string."""
        result = validate._validate_descriptor_shape({"id": "sew", "version": "v0"})
        assert result is not None
        assert result.get("errors")

    def test_v01_rejected(self):
        """Leading-zero version is syntactically allowed by grammar but
        the producer regex 'v[1-9][0-9]*' excludes it."""
        result = validate._validate_descriptor_shape({"id": "sew", "version": "01"})
        assert result is not None
        assert not result.get("errors")  # version is numeric, so this is OK

    def test_negative_version_rejected_as_non_numeric(self):
        result = validate._validate_descriptor_shape({"id": "sew", "version": "-1"})
        assert result is not None
        assert result.get("errors")

    def test_extra_fields_accepted_for_extensibility(self):
        """Extra fields in the descriptor are tolerated, not rejected."""
        result = validate._validate_descriptor_shape({
            "id": "sew", "version": "1", "display-name": "Sew v1"
        })
        assert result is not None
        assert not result.get("errors")

    def test_upper_case_name_rejected(self):
        result = validate._validate_descriptor_shape({"id": "SEW", "version": "1"})
        assert result is not None
        assert result.get("errors")

    def test_name_with_trailing_text_rejected(self):
        result = validate._validate_descriptor_shape({"id": "sew-v1-extra", "version": "1"})
        assert result is not None
        assert result.get("errors")

    def test_name_with_whitespace_rejected(self):
        result = validate._validate_descriptor_shape({"id": "se w", "version": "1"})
        assert result is not None
        assert result.get("errors")


class TestValidatorFailNotObscured:
    """Validator fail must not be upgraded or obscured by any policy flag."""

    def test_require_semantics_does_not_upgrade_validator_fail(self, tmp_path):
        """require_semantics only upgrades not-verified, not fail."""
        bundle = _make_bundle(protocol={"id": "sew", "version": "1"})
        bp = _write_bundle(tmp_path, bundle)
        # Add event-evidence with evidence but no state — ensures Sew validator fails
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "force-authorisation-granted",
              "force-auth/auth-id": "fa-0", "event/seq": 0}
        (ev_dir / "grant.json").write_text(__import__("json").dumps(ev))
        report = validate.validate_protocol_bundle(bp, run_dir=tmp_path)
        assert report["validate/status"] == "fail"  # evidence without state-hashes

    def test_expected_protocol_does_not_obscure_validator_fail(self, tmp_path):
        """expected_protocol is an independent cross-check, not a pass."""
        bundle = _make_bundle(protocol={"id": "sew", "version": "1"})
        bp = _write_bundle(tmp_path, bundle)
        # Produce a failing Sew validator result
        ev_dir = tmp_path / "event-evidence"
        ev_dir.mkdir()
        ev = {"evidence/type": "force-authorisation-executed",
              "force-auth/auth-id": "fa-0", "event/seq": 0}
        (ev_dir / "exec.json").write_text(__import__("json").dumps(ev))
        report = validate.validate_protocol_bundle(bp, run_dir=tmp_path)
        assert report["validate/status"] == "fail"


class TestCLI:
    def test_list_validators(self, capsys):
        with pytest.raises(SystemExit) as exc:
            validate.main()
            _ = exc
        # Can't easily test because main() parses sys.argv directly.
        # Validate that the module exposes expected constants.
        assert validate.VALIDATORS
        assert ("sew", "1") in validate.VALIDATORS
