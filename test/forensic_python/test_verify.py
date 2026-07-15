"""Unit tests for scripts/forensic/verify.py.

Tests the Phase C content validation for claims/ and attestations/ directories,
as well as the existing structural checks.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

# Ensure scripts/forensic is importable
import sys
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
if str(_scripts_root) not in sys.path:
    sys.path.insert(0, str(_scripts_root))

from forensic import verify
from tests.forensic_python.conftest import (make_minimal_bundle, make_claim_file,
                                             make_attestation_file)


# ── Claims Content Checks ────────────────────────────────────────────────


class TestCheckClaimsContent:
    def test_empty_claims_dir_fails(self, tmp_path: Path):
        """Phase C: empty claims/ directory must fail."""
        run_dir = make_minimal_bundle(tmp_path)
        # Remove the claims dir created by make_minimal_bundle
        claims_dir = run_dir / "claims"
        claims_dir.rmdir()
        results = verify.check_claims_content(run_dir)
        assert any(r.status == "fail" for r in results)

    def test_missing_claims_dir_fails(self, tmp_path: Path):
        """Phase C: missing claims/ directory must fail."""
        run_dir = make_minimal_bundle(tmp_path)
        import shutil
        shutil.rmtree(str(run_dir / "claims"))
        results = verify.check_claims_content(run_dir)
        assert any(r.status == "fail" for r in results)

    def test_valid_claim_passes(self, tmp_path: Path):
        """Phase C: a valid claim result file must pass all checks."""
        run_dir = make_minimal_bundle(tmp_path)
        make_claim_file(run_dir, "test-claim", "pass")
        results = verify.check_claims_content(run_dir)
        assert all(r.status == "pass" for r in results)

    def test_multiple_valid_claims_pass(self, tmp_path: Path):
        """Phase C: multiple valid claim result files must all pass."""
        run_dir = make_minimal_bundle(tmp_path)
        make_claim_file(run_dir, "claim-a", "pass")
        make_claim_file(run_dir, "claim-b", "fail")
        make_claim_file(run_dir, "claim-c", "inconclusive")
        results = verify.check_claims_content(run_dir)
        assert all(r.status == "pass" for r in results)
        assert len(results) == 3

    def test_invalid_schema_version_fails(self, tmp_path: Path):
        """Phase C: wrong schema version must fail."""
        run_dir = make_minimal_bundle(tmp_path)
        f = make_claim_file(run_dir, "test-claim", "pass")
        data = json.loads(f.read_text())
        data["result/schema-version"] = "wrong-schema.v1"
        f.write_text(json.dumps(data, indent=2))
        results = verify.check_claims_content(run_dir)
        assert any(r.status == "fail" for r in results)

    def test_corrupted_hash_fails(self, tmp_path: Path):
        """Phase C: corrupted hash must fail (tampered content, hash not updated)."""
        run_dir = make_minimal_bundle(tmp_path)
        f = make_claim_file(run_dir, "test-claim", "pass")
        data = json.loads(f.read_text())
        data["result/status"] = "manipulated"
        f.write_text(json.dumps(data, indent=2))
        results = verify.check_claims_content(run_dir)
        assert any(r.status == "fail" for r in results)

    def test_missing_hash_fails(self, tmp_path: Path):
        """Phase C: missing result/hash field must fail."""
        run_dir = make_minimal_bundle(tmp_path)
        f = make_claim_file(run_dir, "test-claim", "pass")
        data = json.loads(f.read_text())
        del data["result/hash"]
        f.write_text(json.dumps(data, indent=2))
        results = verify.check_claims_content(run_dir)
        assert any(r.status == "fail" for r in results)


# ── Attestations Content Checks ──────────────────────────────────────────


class TestCheckAttestationsContent:
    def test_empty_attestations_dir_fails(self, tmp_path: Path):
        """Phase C: empty attestations/ directory must fail."""
        run_dir = make_minimal_bundle(tmp_path)
        att_dir = run_dir / "attestations"
        att_dir.rmdir()
        results = verify.check_attestations_content(run_dir)
        assert any(r.status == "fail" for r in results)

    def test_valid_attestation_passes(self, tmp_path: Path):
        """Phase C: a valid attestation file must pass all checks."""
        run_dir = make_minimal_bundle(tmp_path)
        make_attestation_file(run_dir, "abc123", "verified")
        results = verify.check_attestations_content(run_dir)
        assert all(r.status == "pass" for r in results)

    def test_multiple_valid_attestations_pass(self, tmp_path: Path):
        """Phase C: multiple valid attestation files must all pass."""
        run_dir = make_minimal_bundle(tmp_path)
        make_attestation_file(run_dir, "abc", "verified")
        make_attestation_file(run_dir, "def", "reproduced")
        make_attestation_file(run_dir, "ghi", "rejected")
        results = verify.check_attestations_content(run_dir)
        assert all(r.status == "pass" for r in results)
        assert len(results) == 3

    def test_invalid_schema_version_fails(self, tmp_path: Path):
        """Phase C: wrong attestation schema version must fail."""
        run_dir = make_minimal_bundle(tmp_path)
        f = make_attestation_file(run_dir, "abc", "verified")
        data = json.loads(f.read_text())
        data["attestation/schema-version"] = "old-schema.v0"
        f.write_text(json.dumps(data, indent=2))
        results = verify.check_attestations_content(run_dir)
        assert any(r.status == "fail" for r in results)

    def test_corrupted_status_warns(self, tmp_path: Path):
        """Phase C: corrupted attestation claim-result triggers a warning (not fail)."""
        run_dir = make_minimal_bundle(tmp_path)
        f = make_attestation_file(run_dir, "abc", "verified")
        data = json.loads(f.read_text())
        data["attestation/claim-result"] = "unknown-status"
        f.write_text(json.dumps(data, indent=2))
        results = verify.check_attestations_content(run_dir)
        # The hash still matches (status is excluded from canonical projection),
        # but the claim-result is unexpected — this generates a warning
        assert any(r.status == "warning" for r in results)
        assert all(r.status != "fail" for r in results)


# ─── Bundle-level verify_run Integration ──────────────────────────────────


class TestVerifyRun:
    def test_missing_optional_file_is_informational(self, tmp_path: Path):
        """Optional artifacts must not be reported as failed verification checks."""
        run_dir = make_minimal_bundle(tmp_path)
        result = verify.check_exists(run_dir, "run-output.log", severity="info")
        assert result.status == "info"
        assert result.severity == "info"

    def test_minimal_bundle_passes(self, tmp_path: Path):
        """A minimally valid bundle must pass verify."""
        run_dir = make_minimal_bundle(tmp_path)
        # Add claims and attestations for Phase C
        make_claim_file(run_dir, "test-claim", "pass")
        make_attestation_file(run_dir, "abc123", "verified")
        report = verify.verify_run(str(run_dir))
        assert report.status == "pass", f"Expected pass, got {report.status}: {report.summary}"

    def test_bundle_with_claims_and_attestations(self, bundle_with_claims: Path):
        """A bundle with claims and attestations must pass."""
        report = verify.verify_run(str(bundle_with_claims))
        assert report.status == "pass", f"Expected pass, got {report.status}: {report.summary}"
        # Expect 0 failures, at least 20 passes (baseline + Phase C content checks)
        assert report.summary["fail"] == 0
        assert report.summary["pass"] >= 20

    def test_missing_required_file_fails(self, tmp_path: Path):
        """A bundle missing a required file must fail."""
        run_dir = make_minimal_bundle(tmp_path)
        # Remove a required file
        (run_dir / "preflight-report.json").unlink()
        make_claim_file(run_dir, "test-claim", "pass")
        make_attestation_file(run_dir, "abc", "verified")
        report = verify.verify_run(str(run_dir))
        assert report.status == "fail"

    def test_local_proof_anchor_passes(self, tmp_path: Path):
        """A bundle with local-proof anchor must pass anchor check."""
        run_dir = make_minimal_bundle(tmp_path)
        make_claim_file(run_dir, "c1", "pass")
        make_attestation_file(run_dir, "h1", "verified")
        report = verify.verify_run(str(run_dir))
        assert report.status == "pass"

    def test_rfc3161_anchor_without_token_fails(self, tmp_path: Path):
        """An anchor claiming rfc3161 type but missing token file must fail."""
        run_dir = make_minimal_bundle(tmp_path)
        make_claim_file(run_dir, "c1", "pass")
        make_attestation_file(run_dir, "h1", "verified")
        # Overwrite the anchor-cursor with a fake rfc3161 claim
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text('{"anchor/schema-version":"anchor-cursor.v1","anchor/type":"rfc3161","anchor/target":"file:///x","anchor/timestamp":"2026-01-01T00:00:00Z","anchor/tsa-token-path":"registry.tsr"}')
        result = verify.check_anchor_content(run_dir)
        assert result.status == "fail"

    def test_anchor_missing_json_ok(self, tmp_path: Path):
        """Missing anchor-cursor.json should be informational only."""
        run_dir = make_minimal_bundle(tmp_path)
        (run_dir / "anchors" / "anchor-cursor.json").unlink()
        result = verify.check_anchor_content(run_dir)
        assert result.status != "fail"

    def test_mock_anchor_ok(self, tmp_path: Path):
        """A bundle with mock anchor type must pass verify."""
        run_dir = make_minimal_bundle(tmp_path)
        # Override to mock type
        ac = run_dir / "anchors" / "anchor-cursor.json"
        ac.write_text('{"anchor/schema-version":"anchor-cursor.v1","anchor/type":"mock","anchor/target":"file:///x","anchor/timestamp":"2026-01-01T00:00:00Z"}')
        make_claim_file(run_dir, "c1", "pass")
        make_attestation_file(run_dir, "h1", "verified")
        report = verify.verify_run(str(run_dir))
        assert report.status == "pass"

    def test_mechanism_persistence_artifacts_are_informational(self, tmp_path: Path):
        """Derived mechanism artifacts should be reported but not required."""
        run_dir = make_minimal_bundle(tmp_path)
        make_claim_file(run_dir, "c1", "pass")
        make_attestation_file(run_dir, "h1", "verified")
        (run_dir / "mechanism-persistence-index.json").write_text(json.dumps({
            "schema-version": "mechanism-persistence-index.v1",
            "episodes": [],
        }, indent=2))
        (run_dir / "mechanism-persistence-summary.json").write_text(json.dumps({
            "schema-version": "mechanism-persistence-summary.v1",
            "episode-count": 0,
        }, indent=2))
        (run_dir / "mechanism-scenario-matrix.json").write_text(json.dumps({
            "schema-version": "mechanism-scenario-matrix.v1",
            "cells": {},
        }, indent=2))

        report = verify.verify_run(str(run_dir))

        mech_checks = [c for c in report.checks if c["check/key"] == "mechanism-persistence-artifacts"]
        assert mech_checks and mech_checks[0]["check/status"] == "info"
        assert "mechanism-persistence-index.v1" in mech_checks[0]["check/message"]
        assert report.status == "pass"

    def test_inventory_backed_evidence_dag_passes(self, tmp_path: Path):
        """A run with a semantically populated evidence-dag inventory must pass."""
        run_dir = make_minimal_bundle(tmp_path)
        make_claim_file(run_dir, "c1", "pass")
        make_attestation_file(run_dir, "h1", "verified")
        dag_dir = run_dir / "evidence-dag"
        dag_dir.mkdir(exist_ok=True)
        (dag_dir / "node-abc.edn").write_text("{:node-hash \"abc\" :parent-hashes []}")
        inventory = {
            "dag/schema-version": "evidence-dag-inventory.v0",
            "dag/phase": "B",
            "dag/semantic-status": "parsed",
            "dag/files": {"total": 1, "json": 0, "edn": 1, "unparsed": 0},
            "dag/hashes": {"algorithm": "sha256-file-bytes"},
            "dag/total-bytes": 34,
            "dag/file-hashes": [{"name": "node-abc.edn", "sha256": "deadbeef", "bytes": 34}],
            "dag/nodes": [{"file": "node-abc.edn",
                           "node-hash": "abc",
                           "execution-id": "execution/replay",
                           "execution-kind": "scenario-run",
                           "runner": "scenario-runner",
                           "result-status": "pass",
                           "parent-hashes": []}],
            "dag/parse-errors": [],
            "dag/edges": [],
            "dag/index": {
                "dag-index/schema-version": "evidence-dag-index.v0",
                "dag-index/nodes-by-hash": {
                    "abc": {"node-hash": "abc", "short-hash": "abc", "execution-id": "execution/replay", "result-status": "pass"},
                },
                "dag-index/children-by-parent": {},
                "dag-index/parents-by-child": {"abc": []},
                "dag-index/roots": ["abc"],
                "dag-index/leaves": ["abc"],
                "dag-index/by-execution-id": {"execution/replay": ["abc"]},
                "dag-index/by-status": {"pass": ["abc"]},
                "dag-index/short-hashes": {"abc": "abc"},
                "dag-index/summary": {
                    "node-count": 1, "root-count": 1, "leaf-count": 1,
                    "failure-count": 0, "error-count": 0, "orphan-count": 0,
                    "execution-id-counts": {"execution/replay": 1},
                    "status-counts": {"pass": 1},
                },
            },
        }
        (run_dir / "evidence-dag-inventory.json").write_text(json.dumps(inventory, indent=2))
        bundle_root = json.loads((run_dir / "run-bundle-root.json").read_text())
        bundle_root["execution/node-hash"] = "abc"
        bundle_root["execution/content-hash"] = "abc"
        (run_dir / "run-bundle-root.json").write_text(json.dumps(bundle_root, indent=2))
        report = verify.verify_run(str(run_dir))
        assert report.status == "pass"

    def test_inventory_backed_evidence_dag_rejects_unresolved_bundle_root(self, tmp_path: Path):
        """An evidence-dag inventory must fail when the bundle root points nowhere."""
        run_dir = make_minimal_bundle(tmp_path)
        make_claim_file(run_dir, "c1", "pass")
        make_attestation_file(run_dir, "h1", "verified")
        dag_dir = run_dir / "evidence-dag"
        dag_dir.mkdir(exist_ok=True)
        (dag_dir / "node-abc.edn").write_text("{:node-hash \"abc\" :parent-hashes []}")
        inventory = {
            "dag/schema-version": "evidence-dag-inventory.v0",
            "dag/phase": "B",
            "dag/semantic-status": "parsed",
            "dag/files": {"total": 1, "json": 0, "edn": 1, "unparsed": 0},
            "dag/hashes": {"algorithm": "sha256-file-bytes"},
            "dag/total-bytes": 34,
            "dag/file-hashes": [{"name": "node-abc.edn", "sha256": "deadbeef", "bytes": 34}],
            "dag/nodes": [{"file": "node-abc.edn",
                           "node-hash": "abc",
                           "execution-id": "execution/replay",
                           "execution-kind": "scenario-run",
                           "runner": "scenario-runner",
                           "result-status": "pass",
                           "parent-hashes": []}],
            "dag/parse-errors": [],
            "dag/edges": [],
            "dag/index": {
                "dag-index/schema-version": "evidence-dag-index.v0",
                "dag-index/nodes-by-hash": {
                    "abc": {"node-hash": "abc", "short-hash": "abc", "execution-id": "execution/replay", "result-status": "pass"},
                },
                "dag-index/children-by-parent": {},
                "dag-index/parents-by-child": {"abc": []},
                "dag-index/roots": ["abc"],
                "dag-index/leaves": ["abc"],
                "dag-index/by-execution-id": {"execution/replay": ["abc"]},
                "dag-index/by-status": {"pass": ["abc"]},
                "dag-index/short-hashes": {"abc": "abc"},
                "dag-index/summary": {
                    "node-count": 1, "root-count": 1, "leaf-count": 1,
                    "failure-count": 0, "error-count": 0, "orphan-count": 0,
                    "execution-id-counts": {"execution/replay": 1},
                    "status-counts": {"pass": 1},
                },
            },
        }
        (run_dir / "evidence-dag-inventory.json").write_text(json.dumps(inventory, indent=2))
        report = verify.verify_run(str(run_dir))
        assert report.status == "fail"
