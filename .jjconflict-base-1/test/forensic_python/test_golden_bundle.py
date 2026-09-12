"""Golden bundle cross-language compatibility test.

Extracts a committed Clojure-produced forensic bundle and verifies it
through the same public ``verify.verify_run()`` entry point that an
external reviewer would use.

The fixture is a minimal but realistic forensic run directory packaged
as a gzipped tarball and signed with a known Ed25519 keypair.  Both
the raw-hex and SSH-format public keys are committed alongside it.

Test layers:
  1. Golden bundle passes verification with native Python Ed25519
  2. Golden bundle passes verification with SSH-format public key
  3. Mutation of any single record/file/field causes rejection
"""

from __future__ import annotations

import json
import shutil
import tarfile
from pathlib import Path
from typing import Any

import pytest

import sys
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
for p in (_project_root, _scripts_root):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from forensic import verify

FIXTURES = Path(__file__).parent / "fixtures"
GOLDEN_TARBALL = FIXTURES / "golden-bundle.tar.gz"
PUBKEY_HEX = FIXTURES / "golden-ed25519.pub"
PUBKEY_SSH = FIXTURES / "golden-ed25519-ssh.pub"
BUNDLE_DIR = "golden-bundle"


@pytest.fixture(scope="module")
def golden_bundle(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Extract the golden bundle tarball once per module."""
    extract_dir = tmp_path_factory.mktemp("golden-test")
    with tarfile.open(GOLDEN_TARBALL, "r:gz") as tf:
        tf.extractall(extract_dir, filter="data")
    return extract_dir / BUNDLE_DIR


# ── Layer 1: golden bundle passes verification ────────────────────────────


class TestGoldenBundleAccepts:
    def test_passes_with_hex_key(self, golden_bundle: Path):
        """Bundle passes with raw-hex Ed25519 public key (native Python path)."""
        report = verify.verify_run(str(golden_bundle), public_key_path=str(PUBKEY_HEX))
        assert report.status == "pass", (
            f"Expected pass with hex key, got {report.status}: "
            f"fail_count={report.summary['fail']}"
        )

    def test_passes_with_ssh_key(self, golden_bundle: Path):
        """Bundle passes with SSH-format Ed25519 public key."""
        report = verify.verify_run(str(golden_bundle), public_key_path=str(PUBKEY_SSH))
        assert report.status == "pass", (
            f"Expected pass with SSH key, got {report.status}: "
            f"fail_count={report.summary['fail']}"
        )

    def test_no_signature_key_is_informational(self, golden_bundle: Path):
        """Without --public-key, the signature check is informational."""
        report = verify.verify_run(str(golden_bundle))
        sig_check = next(
            c for c in report.checks
            if c["check/key"] == "bundle-signature"
        )
        assert sig_check["check/status"] == "info"
        assert report.status == "pass"

    def test_all_required_checks_pass(self, golden_bundle: Path):
        """Every required check has status pass."""
        report = verify.verify_run(str(golden_bundle), public_key_path=str(PUBKEY_HEX))
        required_fails = [
            c for c in report.checks
            if c["check/severity"] == "required" and c["check/status"] == "fail"
        ]
        assert len(required_fails) == 0, (
            f"Required checks failing: {[c['check/key'] for c in required_fails]}"
        )


# ── Layer 2: mutation rejection ──────────────────────────────────────────


def _copy_golden(golden_bundle: Path, tmp_path: Path, name: str = "mutated") -> Path:
    dst = Path(tmp_path / name)
    shutil.copytree(str(golden_bundle), str(dst))
    return dst


def _load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text())


def _write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, indent=2))


class TestMutationRejection:
    def test_bundle_hash_modified(self, golden_bundle: Path, tmp_path: Path):
        bundle = _copy_golden(golden_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["bundle/hash"] = "f" * 64
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        assert report.status == "fail"

    def test_signature_modified(self, golden_bundle: Path, tmp_path: Path):
        bundle = _copy_golden(golden_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["bundle/signature"] = "f" * 128
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        assert report.status == "fail"

    def test_overview_hash_modified_triggers_hash_check(self, golden_bundle: Path, tmp_path: Path):
        """Changing overview/hash ref in bundle root breaks bundle self-hash."""
        bundle = _copy_golden(golden_bundle, tmp_path)
        root = _load_json(bundle / "run-bundle-root.json")
        root["overview/hash"] = "e" * 64
        _write_json(bundle / "run-bundle-root.json", root)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        # Bundle root self-hash check detects the mismatch
        bh_check = next(c for c in report.checks if c["check/key"] == "bundle-root-hash")
        assert bh_check["check/status"] == "fail"

    def test_overview_content_modified_triggers_hash_check(self, golden_bundle: Path, tmp_path: Path):
        """Changing overview content without updating hash → hash mismatch."""
        bundle = _copy_golden(golden_bundle, tmp_path)
        ov = _load_json(bundle / "run-overview.json")
        ov["exit-code"] = 1
        _write_json(bundle / "run-overview.json", ov)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        # Overview self-hash recomputation detects the mismatch
        oh_check = next(c for c in report.checks if c["check/key"] == "overview-hash")
        assert oh_check["check/status"] == "fail"

    def test_preflight_status_changed_fails(self, golden_bundle: Path, tmp_path: Path):
        """Changing preflight to fail triggers hard failure."""
        bundle = _copy_golden(golden_bundle, tmp_path)
        pf = _load_json(bundle / "preflight-report.json")
        pf["preflight/status"] = "fail"
        _write_json(bundle / "preflight-report.json", pf)
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        assert report.status == "fail"

    def test_claim_hash_corrupted_fails(self, golden_bundle: Path, tmp_path: Path):
        """Tampering a claim file content without updating hash fails."""
        bundle = _copy_golden(golden_bundle, tmp_path)
        for f in (bundle / "claims").iterdir():
            if f.suffix == ".json":
                c = _load_json(f)
                c["result/status"] = "tampered"
                _write_json(f, c)
                break
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        assert report.status == "fail"

    def test_attestation_hash_corrupted_fails(self, golden_bundle: Path, tmp_path: Path):
        """Tampering an attestation content without updating hash fails."""
        bundle = _copy_golden(golden_bundle, tmp_path)
        for f in (bundle / "attestations").iterdir():
            if f.suffix == ".json":
                a = _load_json(f)
                a["attestation/signed-at"] = "2000-01-01T00:00:00Z"
                _write_json(f, a)
                break
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        assert report.status == "fail"

    def test_evidence_dag_empty_is_informational(self, golden_bundle: Path, tmp_path: Path):
        """Empty evidence-dag/ is informational (no inventory JSON)."""
        bundle = _copy_golden(golden_bundle, tmp_path)
        for f in (bundle / "evidence-dag").iterdir():
            if f.suffix == ".edn":
                f.unlink()
                break
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        assert report.status == "pass"

    def test_anchor_removed_is_informational(self, golden_bundle: Path, tmp_path: Path):
        """Missing anchor-cursor.json is informational (pre-Phase C)."""
        bundle = _copy_golden(golden_bundle, tmp_path)
        (bundle / "anchors" / "anchor-cursor.json").unlink()
        report = verify.verify_run(str(bundle), public_key_path=str(PUBKEY_HEX))
        assert report.status == "pass"


# ── Layer 3: structural edge cases ────────────────────────────────────────


class TestStructuralEdgeCases:
    def test_missing_required_file_fails(self, golden_bundle: Path, tmp_path: Path):
        bundle = _copy_golden(golden_bundle, tmp_path)
        (bundle / "preflight-report.json").unlink()
        report = verify.verify_run(str(bundle))
        assert report.status == "fail"

    def test_empty_claims_directory_fails(self, golden_bundle: Path, tmp_path: Path):
        bundle = _copy_golden(golden_bundle, tmp_path)
        for f in (bundle / "claims").iterdir():
            f.unlink()
        report = verify.verify_run(str(bundle))
        assert report.status == "fail"

    def test_empty_attestations_directory_fails(self, golden_bundle: Path, tmp_path: Path):
        bundle = _copy_golden(golden_bundle, tmp_path)
        for f in (bundle / "attestations").iterdir():
            f.unlink()
        report = verify.verify_run(str(bundle))
        assert report.status == "fail"

    def test_wrong_public_key_rejected(self, golden_bundle: Path, tmp_path: Path):
        import hashlib
        wrong_key = tmp_path / "wrong.pub"
        wrong_key.write_text(hashlib.sha256(b"wrong").hexdigest())
        report = verify.verify_run(str(golden_bundle), public_key_path=str(wrong_key))
        assert report.status == "fail"
        sig_check = next(
            c for c in report.checks
            if c["check/key"] == "bundle-signature"
        )
        assert sig_check["check/status"] == "fail"
