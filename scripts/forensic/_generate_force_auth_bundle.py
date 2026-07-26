#!/usr/bin/env python3
"""Generate a force-authorisation golden bundle fixture.

Produces a forensic run bundle with force-authorisation evidence events and a
protocol/state witness, signed with a known Ed25519 keypair. Used to validate
that verify.py's force-auth lifecycle checks correctly accept a valid bundle.
"""

from __future__ import annotations

import hashlib
import json
import tarfile
import tempfile
from pathlib import Path

import sys
_project_root = Path(__file__).resolve().parent.parent.parent
_scripts_root = _project_root / "scripts"
for p in (_project_root, _scripts_root):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from nacl.signing import SigningKey
from nacl.encoding import Base64Encoder, HexEncoder

FIXTURE_DIR = _project_root / "test" / "forensic_python" / "fixtures"
BUNDLE_NAME = "golden-force-auth-bundle.tar.gz"


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical_json(data: dict, exclude: list[str]) -> bytes:
    return json.dumps(
        {k: v for k, v in data.items() if k not in exclude},
        indent=2, default=str, sort_keys=True,
    ).encode("utf-8")


def generate(tmpdir: Path) -> Path:
    run_dir = tmpdir / "golden-force-auth-bundle"
    run_dir.mkdir(parents=True, exist_ok=True)

    for d in ("evidence-dag", "claims", "attestations", "anchors", "event-evidence"):
        (run_dir / d).mkdir()

    # ── Preflight ──────────────────────────────────────────────────────────
    (run_dir / "preflight-report.json").write_text(json.dumps({
        "preflight/schema-version": "preflight.v1",
        "preflight/status": "pass",
        "preflight/summary": {"pass": 1, "fail": 0, "warning": 0, "info": 0},
        "preflight/checks": [],
    }, indent=2))

    # ── Source snapshot ────────────────────────────────────────────────────
    (run_dir / "source-snapshot.json").write_text(json.dumps({
        "source/tree-hash": "f" * 64,
        "source/tree-hash-algorithm": "source-tree-hash.v1",
        "source/commit": "forceauth0001",
        "source/dirty?": False,
        "source/hash": "e" * 64,
        "source/hash-algorithm": "sha256",
        "source/hash-roots": ["src"],
    }, indent=2))

    # ── Environment ────────────────────────────────────────────────────────
    (run_dir / "environment.json").write_text(json.dumps({
        "os": "linux", "python_version": "3.12", "timestamp": "2026-01-01T00:00:00Z",
    }, indent=2))

    # ── Input manifest ─────────────────────────────────────────────────────
    (run_dir / "input-manifest.json").write_text(json.dumps({
        "run-request": "force-auth-run-request.edn",
        "run-timestamp": "2026-01-01T00:00:00Z",
        "producer": "resolver-sim",
    }, indent=2))

    # ── Run overview ───────────────────────────────────────────────────────
    overview = {
        "overview/schema-version": "run-overview.v1",
        "overview/hash": None,
        "run-id": "golden-force-auth-test",
        "run-timestamp": "2026-01-01T00:00:00Z",
        "exit-code": 0,
        "elapsed-ms": 567,
        "status": "pass",
    }
    exclude_oh = ["overview/hash"]
    can_oh = _canonical_json(overview, exclude_oh)
    oh = _sha256_hex(can_oh)
    overview["overview/hash"] = oh
    (run_dir / "run-overview.json").write_text(json.dumps(overview, indent=2))

    # ── Force-authorisation evidence events ────────────────────────────────
    # Lifecycle: grant(seq=1) → execute(seq=2)
    ev_events = [
        {
            "evidence/type": "force-authorisation-granted",
            "force-auth/auth-id": "fa-integrity-001",
            "force-auth/authoriser": "resolver-sim.test-authority",
            "force-auth/scope": {"workflow-id": 1, "limit": 1000},
            "event/seq": 1,
            "event/timestamp": "2026-01-01T00:00:01Z",
        },
        {
            "evidence/type": "force-authorisation-executed",
            "force-auth/auth-id": "fa-integrity-001",
            "force-auth/authoriser": "resolver-sim.test-authority",
            "force-auth/execution-hash": "deadbeef",
            "event/seq": 2,
            "event/timestamp": "2026-01-01T00:00:02Z",
        },
    ]
    for i, ev in enumerate(ev_events):
        (run_dir / "event-evidence" / f"force-auth-event-{i}.json").write_text(
            json.dumps(ev, indent=2))

    # ── Bundle root with protocol/state-hashes and protocol/state witness ──
    # Build a valid protocol/state witness that matches the evidence events.
    fa_record = {
        "authorization/id": "fa-integrity-001",
        "authorization/status": "consumed",
        "consumed?": True,
        "authorization/scope": {"workflow-id": 1, "limit": 1000},
        "authorization/scope-hash": _sha256_hex(json.dumps({"workflow-id": 1, "limit": 1000}, sort_keys=True).encode()),
    }
    fa_consumed = {
        "held-adjustment/id": "adj-fa-integrity-001",
        "held-adjustment/amount": 1000,
        "held-adjustment/currency": "USD",
    }
    held_adjustment = {
        "held-adjustment/id": "adj-fa-integrity-001",
        "held-adjustment/type": "force-authorisation",
        "held-adjustment/amount": 1000,
        "held-adjustment/currency": "USD",
        "authorization/provenance": {
            "authorization/id": "fa-integrity-001",
            "authorization/scope-hash": fa_record["authorization/scope-hash"],
        },
    }

    protocol_state = {
        "force-authorisations": {"fa-integrity-001": fa_record},
        "force-authorisations/consumed": {"fa-integrity-001": fa_consumed},
        "held-adjustments": [held_adjustment],
    }
    state_witness_hash = _sha256_hex(
        json.dumps(protocol_state, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))

    # Generate Ed25519 keypair for signing
    seed = SigningKey.generate()
    seed_bytes = bytes(seed)
    pub_raw = bytes(seed.verify_key)

    bundle_root = {
        "bundle/schema-version": "bundle-root.v1",
        "bundle/id": None,
        "bundle/hash": None,
        "bundle/signature": None,
        "bundle/signing-key-id": "force-auth-test-key",
        "bundle/timestamp": "2026-01-01T00:00:00Z",
        "run/exit-code": 0,
        "run/status": "pass",
        "protocol": {"id": "sew", "version": "1"},
        "overview/hash": overview["overview/hash"],
        "preflight": {"status": "pass", "summary": {"pass": 1, "fail": 0, "warning": 0, "info": 0}},
        "isolation/grade": "D",
        "protocol/state-hashes": {
            "force-authorisations/hash": _sha256_hex(json.dumps(fa_record, sort_keys=True).encode()),
            "force-authorisations/consumed-hash": _sha256_hex(json.dumps(fa_consumed, sort_keys=True).encode()),
        },
        "protocol/state": protocol_state,
        "protocol/state-witness-hash": state_witness_hash,
    }

    # Compute self-hash and sign
    exclude_bh = ["bundle/id", "bundle/hash", "bundle/signature", "bundle/signing-key-id"]
    can_bh = _canonical_json(bundle_root, exclude_bh)
    bundle_hash = _sha256_hex(can_bh)
    bundle_root["bundle/id"] = bundle_hash
    bundle_root["bundle/hash"] = bundle_hash

    signing_key = SigningKey(seed_bytes)
    msg = bundle_hash.encode("utf-8")
    signed = signing_key.sign(msg)
    sig_hex = HexEncoder.encode(signed.signature).decode("ascii")
    bundle_root["bundle/signature"] = sig_hex

    (run_dir / "run-bundle-root.json").write_text(
        json.dumps(bundle_root, indent=2, default=str, sort_keys=True))

    # ── Results summary ───────────────────────────────────────────────────
    (run_dir / "results-summary.json").write_text(json.dumps({
        "results/schema-version": "results-summary.v1",
        "results/status": "pass",
        "results/suite-key": "golden-force-auth",
    }, indent=2))

    # ── Anchor ─────────────────────────────────────────────────────────────
    (run_dir / "anchors/anchor-cursor.json").write_text(json.dumps({
        "anchor/schema-version": "anchor-cursor.v1",
        "anchor/type": "local-proof",
        "anchor/target": "file:///golden-force-auth-bundle",
        "anchor/timestamp": "2026-01-01T00:00:00Z",
    }, indent=2))

    # ── Claim and attestation ──────────────────────────────────────────────
    claim = {
        "result/schema-version": "forensic-claim-result.v1",
        "result/hash": None,
        "result/claim-id": "force-auth-integrity",
        "result/category": "audit",
        "result/status": "pass",
        "result/evaluated-at": "2026-01-01T00:00:00Z",
        "result/description": "Force-authorisation lifecycle integrity verified",
    }
    exclude_ch = ["result/hash"]
    can_ch = _canonical_json(claim, exclude_ch)
    claim_hash = _sha256_hex(can_ch)
    claim["result/hash"] = claim_hash
    (run_dir / "claims" / f"claim-result-{claim_hash[:16]}.json").write_text(
        json.dumps(claim, indent=2))

    attest = {
        "attestation/schema-version": "forensic-attestation.v1",
        "attestation/id": None,
        "attestation/hash": None,
        "attestation/subject-kind": "claim-result",
        "attestation/subject-hash": claim_hash,
        "attestation/claim-id": "force-auth-integrity",
        "attestation/claim-result": "verified",
        "attestation/attestor-id": "self:force-auth-bundle",
        "attestation/signed-at": "2026-01-01T00:00:00Z",
        "attestation/provenance": {},
    }
    exclude_ah = ["attestation/id", "attestation/hash", "attestation/signature"]
    can_ah = _canonical_json(attest, exclude_ah)
    attest_hash = _sha256_hex(can_ah)
    attest["attestation/id"] = attest_hash
    attest["attestation/hash"] = attest_hash
    (run_dir / "attestations" / f"attestation-{attest_hash[:16]}.json").write_text(
        json.dumps(attest, indent=2))

    # ── Evidence DAG node ─────────────────────────────────────────────────
    (run_dir / "evidence-dag" / "node-force-auth.edn").write_text(
        "{:node-hash \"force-auth-root\" :parent-hashes []}")

    # ── Public key ─────────────────────────────────────────────────────────
    pub_hex_path = FIXTURE_DIR / "golden-force-auth-ed25519.pub"
    pub_hex_path.write_text(pub_raw.hex())
    print(f"Wrote {pub_hex_path}")

    # ── Bundle tarball ────────────────────────────────────────────────────
    tarball_path = tmpdir / BUNDLE_NAME
    with tarfile.open(tarball_path, "w:gz") as tf:
        tf.add(run_dir, arcname="golden-force-auth-bundle")

    return tarball_path


def main():
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tarball_path = generate(Path(tmp))
        (FIXTURE_DIR / BUNDLE_NAME).write_bytes(tarball_path.read_bytes())
        print(f"Wrote {FIXTURE_DIR / BUNDLE_NAME} ({tarball_path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
