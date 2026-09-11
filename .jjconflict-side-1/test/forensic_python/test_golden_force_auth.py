"""Golden force-authorisation bundle cross-layer validation test.

Extracts a committed forensic bundle with force-authorisation evidence events
and a protocol/state witness, then verifies it through ``verify.verify_run()``.

Tests:
  1. Valid force-auth bundle passes all 6 force-auth lifecycle checks
  2. Mutation of evidence event causes force-auth lifecycle failure
  3. Mutation of protocol/state-hashes causes structural failure
  4. Mutation of protocol/state witness causes state-witness failure
  5. Missing event-evidence with protocol/state-hashes is detected
"""

from __future__ import annotations

import json
import shutil
import tarfile
from pathlib import Path

import pytest

import sys
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
for p in (_project_root, _scripts_root):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from forensic import verify

FIXTURES = Path(__file__).parent / "fixtures"
TARBALL = FIXTURES / "golden-force-auth-bundle.tar.gz"
PUBKEY = FIXTURES / "golden-force-auth-ed25519.pub"
BUNDLE_DIR = "golden-force-auth-bundle"


@pytest.fixture(scope="module")
def force_auth_bundle(tmp_path_factory: pytest.TempPathFactory) -> Path:
    extract_dir = tmp_path_factory.mktemp("force-auth-test")
    with tarfile.open(TARBALL, "r:gz") as tf:
        tf.extractall(extract_dir, filter="data")
    return extract_dir / BUNDLE_DIR


def _copy_bundle(src: Path, tmp_path: Path) -> Path:
    dst = Path(tmp_path / "mutated")
    shutil.copytree(str(src), str(dst))
    return dst


def _load_json(path: Path) -> dict:
    return json.loads(path.read_text())


def _write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2))


def _get_protocol_checks(report) -> list[dict]:
    return [c for c in report.checks
            if c["check/key"] in (
                "protocol-state-hashes-present",
                "force-authorisations-hash-well-formed",
                "force-authorisations-consumed-hash-well-formed",
                "force-authorisation-state-witness-consistent",
                "force-authorisation-evidence-state-consistent",
                "protocol-semantics-summary",
            ) or c["check/key"].startswith("sew-")
            or c["check/key"].startswith("execute-without-grant")
            or c["check/key"].startswith("double-execute")
            or c["check/key"].startswith("execute-before-grant")
            or c["check/key"].startswith("revoke-before-execute")
            or c["check/key"].startswith("grant-without-execute")
            or c["check/key"] == "protocol-identity-missing"]


# ── Layer 1: valid bundle ─────────────────────────────────────────────────


class TestForceAuthBundleAccepts:
    def test_bundle_passes(self, force_auth_bundle: Path):
        """Valid Sew force-auth bundle passes all checks."""
        report = verify.verify_run(str(force_auth_bundle), public_key_path=str(PUBKEY))
        assert report.status == "pass", f"Expected pass, got {report.status}"
        proto_checks = _get_protocol_checks(report)
        for c in proto_checks:
            assert c["check/status"] in ("pass", "info", "not-verified"), (
                f"Protocol check failed: {c['check/key']}: {c['check/message']}"
            )

    def test_all_protocol_checks_present(self, force_auth_bundle: Path):
        """All expected Sew protocol-semantic checks are present."""
        report = verify.verify_run(str(force_auth_bundle), public_key_path=str(PUBKEY))
        keys = {c["check/key"] for c in _get_protocol_checks(report)}
        expected = {
            "protocol-state-hashes-present",
            "force-authorisations-hash-well-formed",
            "force-authorisations-consumed-hash-well-formed",
            "force-authorisation-state-witness-consistent",
            "force-authorisation-evidence-state-consistent",
            "protocol-semantics-summary",
        }
        missing = expected - keys
        assert not missing, f"Missing protocol checks: {missing}"


# ── Layer 2: mutation tests ──────────────────────────────────────────────


class TestForceAuthMutationRejection:
    def test_evidence_state_disagreement_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Evidence says granted but state says executed → cross-check failure."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        st = root.get("protocol/state", {})
        fas = st.get("force-authorisations", {})
        for fid in list(fas.keys()):
            fas[fid]["authorization/status"] = "active"
            fas[fid]["consumed?"] = False
        st["force-authorisations/consumed"] = {}
        st["held-adjustments"] = []
        root["protocol/state"] = st
        import hashlib
        root["protocol/state-witness-hash"] = hashlib.sha256(
            json.dumps(st, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        ).hexdigest()
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        evidence_check = next(
            c for c in _get_protocol_checks(report)
            if "evidence-state-consistent" in c["check/key"]
        )
        assert evidence_check["check/status"] == "fail"

    def test_tamper_state_witness_hash_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Tampering protocol/state-witness-hash causes witness mismatch."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["protocol/state-witness-hash"] = "f" * 64
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        witness_check = next(
            c for c in _get_protocol_checks(report)
            if "state-witness-consistent" in c["check/key"]
        )
        assert witness_check["check/status"] == "fail"

    def test_remove_state_hashes_with_evidence_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Evidence events without protocol/state-hashes causes hard failure."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        del root["protocol/state-hashes"]
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        assert report.status == "fail", "Expected fail after removing protocol/state-hashes"
        evidence_check = next(
            c for c in _get_protocol_checks(report)
            if "sew-evidence-without-state-hashes" in c["check/key"]
        )
        assert evidence_check["check/status"] == "fail"

    def test_tamper_state_witness_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Tampering the protocol/state witness causes hash mismatch."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        st = root.get("protocol/state", {})
        fas = st.get("force-authorisations", {})
        for fid in fas:
            fas[fid]["authorization/status"] = "revoked"
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        witness_check = next(
            c for c in _get_protocol_checks(report)
            if "state-witness-consistent" in c["check/key"]
        )
        assert witness_check["check/status"] == "fail"

    def test_execute_before_grant_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Reordering events so execute precedes grant causes lifecycle failure."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        ev_dir = bundle / "event-evidence"
        files = sorted(ev_dir.iterdir())
        if len(files) >= 2:
            ev0 = _load_json(files[0])
            ev1 = _load_json(files[1])
            ev0["event/seq"] = 5
            ev1["event/seq"] = 1
            _write_json(files[0], ev0)
            _write_json(files[1], ev1)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        proto_checks = _get_protocol_checks(report)
        assert any(c["check/status"] == "fail" for c in proto_checks), (
            "Expected fail after reordering evidence events"
        )

    def test_tamper_bundle_hash_still_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Tampering bundle hash (the usual structural check) still works."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["bundle/hash"] = "f" * 64
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        assert report.status == "fail"

    def test_wrong_public_key_rejected(self, force_auth_bundle: Path, tmp_path: Path):
        import hashlib
        wrong_key = tmp_path / "wrong.pub"
        wrong_key.write_text(hashlib.sha256(b"wrong").hexdigest())
        report = verify.verify_run(str(force_auth_bundle), public_key_path=str(wrong_key))
        assert report.status == "fail"
        sig_check = next(
            c for c in report.checks if c["check/key"] == "bundle-signature"
        )
        assert sig_check["check/status"] == "fail"


# ── Layer 3: protocol identity dispatch tests ─────────────────────────────


class TestProtocolIdentityDispatch:
    def test_bundle_without_protocol_id_is_not_verified(self, force_auth_bundle: Path, tmp_path: Path):
        """Legacy bundle without protocol identity → not-verified, not fail."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root.pop("protocol", None)
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        identity_check = next(
            (c for c in report.checks if c["check/key"] == "protocol-identity"),
            None
        )
        assert identity_check is not None, "Expected protocol-identity check"
        assert identity_check["check/status"] == "not-verified"

    def test_unknown_protocol_id_is_not_verified(self, force_auth_bundle: Path, tmp_path: Path):
        """Unknown protocol → not-verified."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["protocol"] = {"id": "unknown", "version": "99"}
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        lifecycle_check = next(
            (c for c in report.checks if c["check/key"] == "protocol-lifecycle"),
            None
        )
        assert lifecycle_check is not None, "Expected protocol-lifecycle check"
        assert lifecycle_check["check/status"] == "not-verified"

    def test_modified_protocol_id_breaks_bundle_hash(self, force_auth_bundle: Path, tmp_path: Path):
        """Changing protocol id invalidates the bundle self-hash."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["protocol"] = {"id": "sew", "version": "2"}
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        # Bundle root hash check should detect the mismatch
        bh_check = next(c for c in report.checks if c["check/key"] == "bundle-root-hash")
        assert bh_check["check/status"] == "fail"


# ── Layer 4: verification policy tests ────────────────────────────────────


class TestVerificationPolicy:
    def test_legacy_with_require_semantics_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Legacy bundle (no protocol) with --require-protocol-semantics → fail."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root.pop("protocol", None)
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY),
                                    require_protocol_semantics=True)
        assert report.status == "fail"
        summary = next(c for c in report.checks if c["check/key"] == "protocol-semantics-summary")
        assert summary["check/status"] == "fail"

    def test_unknown_protocol_with_require_semantics_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Unknown protocol with --require-protocol-semantics → fail."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["protocol"] = {"id": "unknown", "version": "99"}
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY),
                                    require_protocol_semantics=True)
        assert report.status == "fail"
        summary = next(c for c in report.checks if c["check/key"] == "protocol-semantics-summary")
        assert summary["check/status"] == "fail"

    def test_expected_protocol_mismatch_fails(self, force_auth_bundle: Path, tmp_path: Path):
        """Bundle declares sew/1 but expected sew/2 → fail."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["protocol"] = {"id": "sew", "version": "1"}
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY),
                                    expected_protocol=("sew", "2"))
        assert report.status == "fail"
        match_check = next(c for c in report.checks if c["check/key"] == "protocol-identity-match")
        assert match_check["check/status"] == "fail"

    def test_valid_sew_with_expected_protocol_passes(self, force_auth_bundle: Path, tmp_path: Path):
        """Bundle declares sew/1 and expected sew/1 → passes."""
        report = verify.verify_run(str(force_auth_bundle), public_key_path=str(PUBKEY),
                                    expected_protocol=("sew", "1"))
        assert report.status == "pass"

    def test_unknown_protocol_matching_expected_stays_not_verified(self, force_auth_bundle: Path, tmp_path: Path):
        """Bundle declares unknown-protocol/1 and expected matches — still not-verified.
        --expected-protocol does not imply that a validator exists."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["protocol"] = {"id": "unknown", "version": "1"}
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY),
                                    expected_protocol=("unknown", "1"))
        # Not fail — the identity matches. Not pass — no validator. Not-verified.
        assert "protocol-identity-match" not in [c["check/key"] for c in report.checks]
        summary = next(c for c in report.checks if c["check/key"] == "protocol-semantics-summary")
        assert summary["check/status"] == "not-verified"


# ── Layer 5: bundle without force auth ────────────────────────────────────


class TestNoForceAuthBundle:
    def test_regular_bundle_skips_gracefully(self, force_auth_bundle: Path, tmp_path: Path):
        """A bundle without Sew force-auth evidence passes Sew checks cleanly."""
        bundle = _copy_bundle(force_auth_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root.pop("protocol/state-hashes", None)
        root.pop("protocol/state", None)
        root.pop("protocol/state-witness-hash", None)
        _write_json(bundle / "run-bundle-root.json", root)
        ev_dir = bundle / "event-evidence"
        if ev_dir.exists():
            for f in ev_dir.iterdir():
                f.unlink()
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY))
        proto_checks = _get_protocol_checks(report)
        fails = [c for c in proto_checks if c["check/status"] == "fail"]
        assert len(fails) == 0, (
            f"Expected no Sew failures for bundle without force-auth, got: {fails}"
        )
