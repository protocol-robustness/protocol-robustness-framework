#!/usr/bin/env python3
"""Generate the committed golden forensic bundle fixture.

Produces a minimal but realistic forensic run bundle signed with a known
Ed25519 keypair.  The output is a gzipped tarball committed to the repo
for cross-language golden-bundle compatibility tests.

Usage:
    python3 scripts/forensic/_generate_golden_bundle.py
"""

from __future__ import annotations

import hashlib
import json
import tarfile
import tempfile
from pathlib import Path

import sys
_project_root = Path(__file__).resolve().parent.parent.parent
# Need both roots: project root for `scripts.forensic.identity` and
# scripts root for `from forensic import xxx` cross-references
_scripts_root = _project_root / "scripts"
for p in (_project_root, _scripts_root):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
from forensic.signatures import generate_seed_keypair, seed_keypair_to_b64
from nacl.signing import SigningKey
from nacl.encoding import Base64Encoder, HexEncoder


FIXTURE_DIR = _project_root / "test" / "forensic_python" / "fixtures"
BUNDLE_NAME = "golden-bundle.tar.gz"
PUBKEY_NAME = "golden-ed25519.pub"


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical_json(data: dict, exclude: list[str]) -> bytes:
    return json.dumps(
        {k: v for k, v in data.items() if k not in exclude},
        indent=2, default=str, sort_keys=True,
    ).encode("utf-8")


def _write_sealed(path: Path, data: dict, exclude_keys: list[str]) -> str:
    can = _canonical_json(data, exclude_keys)
    h = _sha256_hex(can)
    data = dict(data)
    for k in exclude_keys:
        data[k] = h
    path.write_text(json.dumps(data, indent=2, default=str, sort_keys=True))
    return h


def generate(tmpdir: Path) -> tuple[Path, bytes, bytes]:
    run_dir = tmpdir / "golden-bundle"
    run_dir.mkdir(parents=True, exist_ok=True)

    for d in ("evidence-dag", "claims", "attestations", "anchors"):
        (run_dir / d).mkdir()

    # Preflight
    (run_dir / "preflight-report.json").write_text(json.dumps({
        "preflight/schema-version": "preflight.v1",
        "preflight/status": "pass",
        "preflight/summary": {"pass": 3, "fail": 0, "warning": 0, "info": 0},
        "preflight/checks": [
            {"check/key": "source-tree-hash", "check/status": "pass"},
            {"check/key": "deps-hash", "check/status": "pass"},
            {"check/key": "evidence-config-schema", "check/status": "pass"},
        ],
    }, indent=2))

    # Source snapshot
    (run_dir / "source-snapshot.json").write_text(json.dumps({
        "source/tree-hash": "a" * 64,
        "source/tree-hash-algorithm": "source-tree-hash.v1",
        "source/commit": "abc123def456",
        "source/dirty?": False,
        "source/hash": "b" * 64,
        "source/hash-algorithm": "sha256",
        "source/hash-roots": ["src"],
    }, indent=2))

    # Environment
    (run_dir / "environment.json").write_text(json.dumps({
        "os": "linux", "python_version": "3.12", "timestamp": "2026-01-01T00:00:00Z",
    }, indent=2))

    # Input manifest
    (run_dir / "input-manifest.json").write_text(json.dumps({
        "run-request": "run-request.edn",
        "run-timestamp": "2026-01-01T00:00:00Z",
        "producer": "resolver-sim",
    }, indent=2))

    # Run overview
    overview = {
        "overview/schema-version": "run-overview.v1",
        "overview/hash": None,
        "run-id": "golden-bundle-test",
        "run-timestamp": "2026-01-01T00:00:00Z",
        "exit-code": 0,
        "elapsed-ms": 1234,
        "status": "pass",
    }
    _write_sealed(run_dir / "run-overview.json", overview, ["overview/hash"])

    # Generate Ed25519 keypair and sign the bundle root
    seed, pk = generate_seed_keypair()
    seed_b64, pub_b64 = seed_keypair_to_b64(seed, pk)
    seed_raw = Base64Encoder.decode(seed_b64)
    pub_raw = Base64Encoder.decode(pub_b64)

    bundle_root = {
        "bundle/schema-version": "bundle-root.v1",
        "bundle/id": None,
        "bundle/hash": None,
        "bundle/signature": None,
        "bundle/signing-key-id": "golden-test-key",
        "bundle/timestamp": "2026-01-01T00:00:00Z",
        "run/exit-code": 0,
        "run/status": "pass",
        "overview/hash": None,
        "preflight": {
            "status": "pass",
            "summary": {"pass": 3, "fail": 0, "warning": 0, "info": 0},
        },
        "isolation/grade": "D",
    }

    # Compute self-hash (before signing)
    exclude = ["bundle/id", "bundle/hash", "bundle/signature", "bundle/signing-key-id"]
    can = _canonical_json(bundle_root, exclude)
    bundle_hash = _sha256_hex(can)
    bundle_root["bundle/id"] = bundle_hash
    bundle_root["bundle/hash"] = bundle_hash
    bundle_root["overview/hash"] = overview.get("overview/hash", "none")

    # Sign the hash string bytes
    signing_key = SigningKey(seed_raw)
    msg = bundle_hash.encode("utf-8")
    signed = signing_key.sign(msg)
    sig_hex = HexEncoder.encode(signed.signature).decode("ascii")
    bundle_root["bundle/signature"] = sig_hex

    (run_dir / "run-bundle-root.json").write_text(
        json.dumps(bundle_root, indent=2, default=str, sort_keys=True))

    # Results summary
    (run_dir / "results-summary.json").write_text(json.dumps({
        "results/schema-version": "results-summary.v1",
        "results/status": "pass",
        "results/suite-key": "golden-bundle",
    }, indent=2))

    # Anchor
    (run_dir / "anchors/anchor-cursor.json").write_text(json.dumps({
        "anchor/schema-version": "anchor-cursor.v1",
        "anchor/type": "local-proof",
        "anchor/target": "file:///golden-bundle",
        "anchor/timestamp": "2026-01-01T00:00:00Z",
        "anchor/note": "Golden fixture — no external TSA",
    }, indent=2))

    # Claim file
    claim = {
        "result/schema-version": "forensic-claim-result.v1",
        "result/hash": None,
        "result/claim-id": "golden-claim-integrity",
        "result/category": "audit",
        "result/status": "pass",
        "result/evaluated-at": "2026-01-01T00:00:00Z",
        "result/evidence-refs": [],
        "result/description": "Golden bundle: evidence chain integrity verified",
    }
    claim_hash = _write_sealed(
        run_dir / "claims" / "claim-result-golden.json", claim, ["result/hash"])
    dst = run_dir / "claims" / f"claim-result-{claim_hash[:16]}.json"
    (run_dir / "claims/claim-result-golden.json").rename(dst)

    # Attestation
    attest = {
        "attestation/schema-version": "forensic-attestation.v1",
        "attestation/id": None,
        "attestation/hash": None,
        "attestation/subject-kind": "claim-result",
        "attestation/subject-hash": claim_hash,
        "attestation/claim-id": "golden-claim-integrity",
        "attestation/claim-result": "verified",
        "attestation/attestor-id": "self:golden-bundle",
        "attestation/signed-at": "2026-01-01T00:00:00Z",
        "attestation/provenance": {},
    }
    attest_hash = _write_sealed(
        run_dir / "attestations" / "attestation-golden.json", attest,
        ["attestation/id", "attestation/hash", "attestation/signature"])
    dst = run_dir / "attestations" / f"attestation-{attest_hash[:16]}.json"
    (run_dir / "attestations/attestation-golden.json").rename(dst)

    # Evidence DAG node
    (run_dir / "evidence-dag" / "node-root.edn").write_text(
        "{:node-hash \"golden\" :parent-hashes []}")

    # Bundle as tarball
    tarball_path = tmpdir / BUNDLE_NAME
    with tarfile.open(tarball_path, "w:gz") as tf:
        tf.add(run_dir, arcname="golden-bundle")

    return tarball_path, pub_raw, seed_raw


def main():
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tarball_path, pub_raw, seed_raw = generate(Path(tmp))

        # Write tarball
        (FIXTURE_DIR / BUNDLE_NAME).write_bytes(tarball_path.read_bytes())
        print(f"Wrote {FIXTURE_DIR / BUNDLE_NAME} ({tarball_path.stat().st_size} bytes)")

        # Write public key as raw hex (native Python format for verify.py)
        pub_hex_path = FIXTURE_DIR / PUBKEY_NAME
        pub_hex_path.write_text(pub_raw.hex())
        print(f"Wrote {pub_hex_path} ({pub_raw.hex()[:16]}...)")

        # Also write a SSH-format public key for format coverage
        ssh_path = FIXTURE_DIR / "golden-ed25519-ssh.pub"
        import base64
        algo = b"ssh-ed25519"
        wire = (
            len(algo).to_bytes(4, "big") + algo +
            len(pub_raw).to_bytes(4, "big") + pub_raw
        )
        b64_body = base64.b64encode(wire).decode("ascii")
        ssh_path.write_text(f"ssh-ed25519 {b64_body} golden-test-key\n")
        print(f"Wrote {ssh_path} (SSH format)")


if __name__ == "__main__":
    main()
