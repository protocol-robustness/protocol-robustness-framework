"""Unit tests for RFC 3161 timestamp anchoring.

Tests the anchor population logic (Step 6h in run.py) and the verify.py
anchor content validation (check_anchor_content).
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

import sys
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
if str(_scripts_root) not in sys.path:
    sys.path.insert(0, str(_scripts_root))

from forensic import verify
from test.forensic_python.conftest import make_minimal_bundle


# ── Anchor Population Tests ───────────────────────────────────────────────
# These test the logic from Step 6h of run.py: copying TSA artifacts from
# workspace to anchors/, and determining anchor type.


def _populate_anchor(run_dir: Path, workspace_dir: Path,
                     tsa_url: str | None = None) -> dict:
    """Simulate the anchor population logic from run.py Step 6h.
    Returns the anchor_cursor dict as it would be written."""
    tsa_anchor_type = "mock"
    anchor_tsa_url = None
    anchor_tsa_token = None
    if workspace_dir:
        for ext, name in [(".tsr", "registry.tsr"), (".tsq", "registry.tsq"),
                          (".json", "registry.tsa.json")]:
            src = workspace_dir / name
            if src.exists():
                shutil.copy2(str(src), str(run_dir / "anchors" / name))
        if (workspace_dir / "registry.tsa.json").exists():
            tsa_anchor_type = "rfc3161"
            try:
                tsa_meta = json.loads(
                    (workspace_dir / "registry.tsa.json").read_text())
                anchor_tsa_url = tsa_meta.get("timestamp/provider-url")
            except Exception:
                pass
            anchor_tsa_token = "registry.tsr"
        elif tsa_url:
            tsa_anchor_type = "tsa-requested-no-response"
        else:
            tsa_anchor_type = "local-proof"
    cursor = {
        "anchor/schema-version": "anchor-cursor.v1",
        "anchor/type": tsa_anchor_type,
        "anchor/target": f"file://{run_dir}",
        "anchor/timestamp": "2026-01-01T00:00:00Z",
    }
    if anchor_tsa_url:
        cursor["anchor/tsa-url"] = anchor_tsa_url
    if anchor_tsa_token:
        cursor["anchor/tsa-token-path"] = anchor_tsa_token
    return cursor


class TestAnchorPopulation:
    def test_no_workspace_produces_mock(self, tmp_path: Path):
        """No workspace dir → anchor type is mock."""
        run_dir = make_minimal_bundle(tmp_path)
        cursor = _populate_anchor(run_dir, None)
        assert cursor["anchor/type"] == "mock"

    def test_no_tsa_artifacts_produces_local_proof(self, tmp_path: Path):
        """Workspace exists but no TSA files → local-proof."""
        run_dir = make_minimal_bundle(tmp_path)
        workspace = tmp_path / "workspace"
        workspace.mkdir()
        cursor = _populate_anchor(run_dir, workspace)
        assert cursor["anchor/type"] == "local-proof"

    def test_tsa_configured_but_no_response(self, tmp_path: Path):
        """TSA URL configured but no artifacts produced → tsa-requested-no-response."""
        run_dir = make_minimal_bundle(tmp_path)
        workspace = tmp_path / "workspace"
        workspace.mkdir()
        cursor = _populate_anchor(run_dir, workspace, tsa_url="https://tsa.example.com/tsr")
        assert cursor["anchor/type"] == "tsa-requested-no-response"

    def test_tsa_artifacts_produce_rfc3161(self, tmp_path: Path):
        """TSA artifacts present → rfc3161 type with token path."""
        run_dir = make_minimal_bundle(tmp_path)
        workspace = tmp_path / "workspace"
        workspace.mkdir()
        tsa_json = {
            "timestamp/provider": "freetsa.org",
            "timestamp/provider-url": "https://freetsa.org/tsr",
            "timestamp/gen-time": "2026-01-01T00:00:00Z",
            "timestamp/serial": "12345",
        }
        (workspace / "registry.tsa.json").write_text(json.dumps(tsa_json))
        (workspace / "registry.tsr").write_bytes(b"fake-tsr-token")
        (workspace / "registry.tsq").write_bytes(b"fake-tsq-request")
        cursor = _populate_anchor(run_dir, workspace)
        assert cursor["anchor/type"] == "rfc3161"
        assert cursor["anchor/tsa-url"] == "https://freetsa.org/tsr"
        assert cursor["anchor/tsa-token-path"] == "registry.tsr"
        assert (run_dir / "anchors" / "registry.tsr").exists()
        assert (run_dir / "anchors" / "registry.tsq").exists()
        assert (run_dir / "anchors" / "registry.tsa.json").exists()

    def test_tsa_artifacts_copied_to_anchors(self, tmp_path: Path):
        """TSA artifacts are physically copied from workspace to anchors/."""
        run_dir = make_minimal_bundle(tmp_path)
        workspace = tmp_path / "workspace"
        workspace.mkdir()
        (workspace / "registry.tsr").write_bytes(b"\x00\x01\x02")
        (workspace / "registry.tsq").write_bytes(b"\x03\x04\x05")
        tsa_json = {"timestamp/provider-url": "https://example.com/tsr"}
        (workspace / "registry.tsa.json").write_text(json.dumps(tsa_json))
        _populate_anchor(run_dir, workspace)
        assert (run_dir / "anchors" / "registry.tsr").read_bytes() == b"\x00\x01\x02"
        assert (run_dir / "anchors" / "registry.tsq").read_bytes() == b"\x03\x04\x05"

    def test_partial_tsa_artifacts_still_rfc3161(self, tmp_path: Path):
        """Only registry.tsa.json exists (tsr missing) → still rfc3161 type."""
        run_dir = make_minimal_bundle(tmp_path)
        workspace = tmp_path / "workspace"
        workspace.mkdir()
        tsa_json = {"timestamp/provider-url": "https://example.com/tsr"}
        (workspace / "registry.tsa.json").write_text(json.dumps(tsa_json))
        cursor = _populate_anchor(run_dir, workspace)
        assert cursor["anchor/type"] == "rfc3161"
        assert cursor["anchor/tsa-token-path"] == "registry.tsr"
        # tsr may not exist yet — verify handles missing token
        assert not (run_dir / "anchors" / "registry.tsr").exists()


# ── Anchor Content Verification Tests ──────────────────────────────────────


class TestAnchorContentCheck:
    def test_mock_anchor_info(self, tmp_path: Path):
        """Mock anchor type → info severity, not fail."""
        run_dir = make_minimal_bundle(tmp_path)
        result = verify.check_anchor_content(run_dir)
        assert result.status != "fail"
        assert result.severity != "required"

    def test_local_proof_anchor_pass(self, tmp_path: Path):
        """local-proof anchor type → pass."""
        run_dir = make_minimal_bundle(tmp_path)
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text(json.dumps({
            "anchor/schema-version": "anchor-cursor.v1",
            "anchor/type": "local-proof",
            "anchor/target": "file:///x",
            "anchor/timestamp": "2026-01-01T00:00:00Z",
        }))
        result = verify.check_anchor_content(run_dir)
        assert result.status == "pass"

    def test_rfc3161_with_valid_token_pass(self, tmp_path: Path):
        """rfc3161 with present TSA token → pass."""
        run_dir = make_minimal_bundle(tmp_path)
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text(json.dumps({
            "anchor/schema-version": "anchor-cursor.v1",
            "anchor/type": "rfc3161",
            "anchor/target": "file:///x",
            "anchor/timestamp": "2026-01-01T00:00:00Z",
            "anchor/tsa-token-path": "registry.tsr",
            "anchor/tsa-url": "https://example.com/tsr",
        }))
        (run_dir / "anchors" / "registry.tsr").write_bytes(b"fake-token")
        (run_dir / "anchors" / "registry.tsa.json").write_text("{}")
        result = verify.check_anchor_content(run_dir)
        assert result.status == "pass"

    def test_rfc3161_without_token_fails(self, tmp_path: Path):
        """rfc3161 with missing TSA token → fail."""
        run_dir = make_minimal_bundle(tmp_path)
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text(json.dumps({
            "anchor/schema-version": "anchor-cursor.v1",
            "anchor/type": "rfc3161",
            "anchor/target": "file:///x",
            "anchor/timestamp": "2026-01-01T00:00:00Z",
            "anchor/tsa-token-path": "registry.tsr",
        }))
        result = verify.check_anchor_content(run_dir)
        assert result.status == "fail"

    def test_rfc3161_without_metadata(self, tmp_path: Path):
        """rfc3161 with token but no metadata → passes (metadata is optional check)."""
        run_dir = make_minimal_bundle(tmp_path)
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text(json.dumps({
            "anchor/schema-version": "anchor-cursor.v1",
            "anchor/type": "rfc3161",
            "anchor/target": "file:///x",
            "anchor/timestamp": "2026-01-01T00:00:00Z",
            "anchor/tsa-token-path": "registry.tsr",
        }))
        (run_dir / "anchors" / "registry.tsr").write_bytes(b"fake-token")
        result = verify.check_anchor_content(run_dir)
        assert result.status == "pass"

    def test_missing_anchor_cursor(self, tmp_path: Path):
        """Missing anchor-cursor.json → info, not fail."""
        run_dir = make_minimal_bundle(tmp_path)
        (run_dir / "anchors" / "anchor-cursor.json").unlink()
        result = verify.check_anchor_content(run_dir)
        assert result.status != "fail"

    def test_wrong_schema_version_warns(self, tmp_path: Path):
        """Wrong schema version → warning."""
        run_dir = make_minimal_bundle(tmp_path)
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text(json.dumps({
            "anchor/schema-version": "anchor-cursor.v0",
            "anchor/type": "local-proof",
            "anchor/target": "file:///x",
            "anchor/timestamp": "2026-01-01T00:00:00Z",
        }))
        result = verify.check_anchor_content(run_dir)
        assert result.status == "warning"

    def test_unknown_anchor_type(self, tmp_path: Path):
        """Unknown anchor type → info, not fail."""
        run_dir = make_minimal_bundle(tmp_path)
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text(json.dumps({
            "anchor/schema-version": "anchor-cursor.v1",
            "anchor/type": "custom-type",
            "anchor/target": "file:///x",
            "anchor/timestamp": "2026-01-01T00:00:00Z",
        }))
        result = verify.check_anchor_content(run_dir)
        assert result.status != "fail"
        assert result.message.startswith("anchor type=custom-type")


# ── Integration: verify_run with anchor ────────────────────────────────────


class TestVerifyRunWithAnchor:
    def test_rfc3161_anchor_in_verify_run_passes(self, tmp_path: Path):
        """Full verify_run with rfc3161 anchor and valid token passes."""
        run_dir = make_minimal_bundle(tmp_path)
        # Add claims + attestations for Phase C
        from test.forensic_python.conftest import make_claim_file, make_attestation_file
        make_claim_file(run_dir, "c1", "pass")
        make_attestation_file(run_dir, "h1", "verified")
        # Set up rfc3161 anchor
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text(json.dumps({
            "anchor/schema-version": "anchor-cursor.v1",
            "anchor/type": "rfc3161",
            "anchor/target": "file:///x",
            "anchor/timestamp": "2026-01-01T00:00:00Z",
            "anchor/tsa-token-path": "registry.tsr",
            "anchor/tsa-url": "https://example.com/tsr",
        }))
        (run_dir / "anchors" / "registry.tsr").write_bytes(b"fake-token")
        (run_dir / "anchors" / "registry.tsa.json").write_text("{}")
        report = verify.verify_run(str(run_dir))
        assert report.status == "pass"

    def test_rfc3161_without_token_in_verify_run_fails(self, tmp_path: Path):
        """Full verify_run with rfc3161 anchor but missing token fails."""
        run_dir = make_minimal_bundle(tmp_path)
        from test.forensic_python.conftest import make_claim_file, make_attestation_file
        make_claim_file(run_dir, "c1", "pass")
        make_attestation_file(run_dir, "h1", "verified")
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text(json.dumps({
            "anchor/schema-version": "anchor-cursor.v1",
            "anchor/type": "rfc3161",
            "anchor/target": "file:///x",
            "anchor/timestamp": "2026-01-01T00:00:00Z",
            "anchor/tsa-token-path": "registry.tsr",
        }))
        report = verify.verify_run(str(run_dir))
        assert report.status == "fail"
