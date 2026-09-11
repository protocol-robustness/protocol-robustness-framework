"""Shared fixtures for forensic Python unit tests.

Creates minimal forensic run bundles for testing verify.py, preflight.py, etc.
Each fixture produces a tmp_path-based run directory with the required structure.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import pytest


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical_json(data: dict, exclude: list[str]) -> bytes:
    return json.dumps(
        {k: v for k, v in data.items() if k not in exclude},
        indent=2, default=str, sort_keys=True).encode("utf-8")


def _write_sealed(path: Path, data: dict, exclude_keys: list[str]) -> str:
    """Write a self-referentially-hashed JSON file. Returns the hash."""
    can = _canonical_json(data, exclude_keys)
    h = _sha256_hex(can)
    data = dict(data)
    for k in exclude_keys:
        if k in ("bundle/id", "bundle/hash", "overview/hash",
                 "result/hash", "attestation/id", "attestation/hash"):
            data[k] = h
        elif k == "bundle/signature":
            pass  # leave as None
    path.write_text(json.dumps(data, indent=2, default=str, sort_keys=True))
    return h


def make_minimal_bundle(tmp_path: Path, **overrides: Any) -> Path:
    """Create a minimally valid forensic run bundle.

    Creates the required directory structure and minimal valid JSON files.
    Returns the run directory path.
    """
    run_dir = tmp_path / "forensic-run-test"
    run_dir.mkdir(parents=True, exist_ok=True)

    # Required directories
    for d in ("evidence-dag", "claims", "attestations", "anchors"):
        (run_dir / d).mkdir()

    # Minimal preflight report
    preflight = {
        "preflight/schema-version": "preflight.v1",
        "preflight/status": overrides.get("preflight_status", "pass"),
        "preflight/summary": {"pass": 1, "fail": 0, "warning": 0, "info": 0},
        "preflight/checks": [],
    }
    (run_dir / "preflight-report.json").write_text(json.dumps(preflight, indent=2))

    # Minimal source snapshot
    src = {"git_commit": "abc123", "dirty": False, "code-hash": "deadbeef",
           "code-hash-algorithm": "source-tree-hash.v1.path-content-sha256", "included-roots": ["src"],
           "source-hash": "deadbeef", "source-hash-algorithm": "source-tree-hash.v1.path-content-sha256",
           "source-hash-roots": ["src"],
           "byte-size": 100, "repo_root": str(run_dir)}
    (run_dir / "source-snapshot.json").write_text(json.dumps(src, indent=2))

    # Minimal environment
    env_info = {"os": "linux", "python_version": "3.11", "timestamp": "2026-01-01T00:00:00"}
    (run_dir / "environment.json").write_text(json.dumps(env_info, indent=2))

    # Input manifest
    im = {
        "run-request": "run-request.edn",
        "run-timestamp": "2026-01-01T00:00:00Z",
        "source-snapshot": src,
        "environment": env_info,
    }
    (run_dir / "input-manifest.json").write_text(json.dumps(im, indent=2))

    # Run overview with self-referential hash
    overview = {
        "overview/schema-version": "run-overview.v1",
        "overview/hash": None,
        "run-id": "test-run-001",
        "run-timestamp": "2026-01-01T00:00:00Z",
        "exit-code": 0,
        "elapsed-ms": 100,
        "status": "pass",
        "source": src,
    }
    _write_sealed(run_dir / "run-overview.json", overview, ["overview/hash"])

    # Bundle root with self-referential hash
    bundle_root = {
        "bundle/schema-version": "bundle-root.v1",
        "bundle/id": None,
        "bundle/hash": None,
        "bundle/signature": None,
        "bundle/signing-key-id": None,
        "bundle/timestamp": "2026-01-01T00:00:00Z",
        "run/exit-code": 0,
        "run/status": "pass",
        "overview/hash": "fake-overview-hash",
        "preflight": {"status": "pass", "summary": {"pass": 1, "fail": 0, "warning": 0, "info": 0}},
        "isolation/grade": "D",
    }
    _write_sealed(run_dir / "run-bundle-root.json", bundle_root,
                  ["bundle/id", "bundle/hash", "bundle/signature", "bundle/signing-key-id"])

    # Results summary
    rs = {"results/schema-version": "results-summary.v1",
          "results/status": "pass", "results/suite-key": "test"}
    (run_dir / "results-summary.json").write_text(json.dumps(rs, indent=2))

    # Anchor cursor (local-proof — matches current run.py behavior)
    anchor = {"anchor/schema-version": "anchor-cursor.v1", "anchor/type": "local-proof",
              "anchor/target": f"file://{run_dir}", "anchor/timestamp": "2026-01-01T00:00:00Z",
              "anchor/note": "Local timestamp — no external TSA configured"}
    (run_dir / "anchors/anchor-cursor.json").write_text(json.dumps(anchor, indent=2))

    return run_dir


def make_claim_file(run_dir: Path, claim_id: str = "test-claim",
                    status: str = "pass") -> Path:
    """Add a valid claim result file to an existing run bundle."""
    d = run_dir / "claims"
    d.mkdir(exist_ok=True)
    base = {
        "result/schema-version": "forensic-claim-result.v1",
        "result/hash": None,
        "result/claim-id": claim_id,
        "result/category": "audit",
        "result/status": status,
        "result/evaluated-at": "2026-01-01T00:00:00Z",
        "result/evidence-refs": [],
        "result/description": f"Test claim: {claim_id}",
        "result/assumptions": [],
        "result/falsified-if": None,
        "result/failure-detail": None,
        "result/confidence": None,
        "result/counterexamples": [],
        "result/inputs": {},
    }
    h = _write_sealed(d / "claim-result-tmp.json", base, ["result/hash"])
    # Rename to match hash prefix
    dst = d / f"claim-result-{h[:16]}.json"
    (d / "claim-result-tmp.json").rename(dst)
    return dst


def make_attestation_file(run_dir: Path, subject_hash: str = "abc123",
                          claim_result: str = "verified") -> Path:
    """Add a valid attestation file to an existing run bundle."""
    d = run_dir / "attestations"
    d.mkdir(exist_ok=True)
    base = {
        "attestation/schema-version": "forensic-attestation.v1",
        "attestation/id": None,
        "attestation/hash": None,
        "attestation/subject-kind": "claim-result",
        "attestation/subject-hash": subject_hash,
        "attestation/claim-id": "test-claim",
        "attestation/claim-result": claim_result,
        "attestation/attestor-id": "self:test-bundle",
        "attestation/signed-at": "2026-01-01T00:00:00Z",
        "attestation/provenance": {},
        "attestation/metadata": {},
    }
    h = _write_sealed(d / "attestation-tmp.json", base,
                      ["attestation/id", "attestation/hash", "attestation/signature"])
    dst = d / f"attestation-{h[:16]}.json"
    (d / "attestation-tmp.json").rename(dst)
    return dst


@pytest.fixture
def minimal_bundle(tmp_path: Path) -> Path:
    """Fixture: a minimally valid forensic run bundle directory."""
    return make_minimal_bundle(tmp_path)


@pytest.fixture
def bundle_with_claims(minimal_bundle: Path) -> Path:
    """Fixture: minimal bundle plus valid claim and attestation files."""
    make_claim_file(minimal_bundle, "test-claim-1", "pass")
    make_claim_file(minimal_bundle, "test-claim-2", "fail")
    make_attestation_file(minimal_bundle, "abc123", "verified")
    make_attestation_file(minimal_bundle, "def456", "rejected")
    return minimal_bundle
