#!/usr/bin/env python3
"""Verify a test-artifacts registry's publisher commitment.

Reconstructs the commitment preimage from already-validated data (registry +
run manifest, re-hashed from disk), recomputes the digest, verifies the
Ed25519 signature, and checks that the signing key is authorised by the
applied publisher policy.

Exit code 0 only for ``publisher-signature-valid``; any other reason is
fail-closed with a diagnosable reason printed on stdout.

Usage:
    python3 scripts/validate/verify_publisher_commitment.py \
        --registry results/test-artifacts/test-artifacts.json \
        --run-manifest results/test-artifacts/test-run.json \
        --publisher-manifest results/test-artifacts/publication.json \
        --publisher-policy config/publisher/publisher-policy.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

_EVIDENCE_DIR = pathlib.Path(__file__).resolve().parent.parent / "evidence"
sys.path.insert(0, str(_EVIDENCE_DIR))

from publisher_commitment import (  # noqa: E402
    REASON_VALID,
    verify_publisher_commitment,
)


def main() -> int:
    ap = argparse.ArgumentParser(description="Verify publisher commitment.")
    ap.add_argument("--registry", required=True, help="test-artifacts.json path")
    ap.add_argument("--run-manifest", required=True, help="test-run.json path")
    ap.add_argument("--publisher-manifest", required=True, help="publication.json path")
    ap.add_argument("--publisher-policy", required=True, help="publisher policy JSON path")
    args = ap.parse_args()

    registry_p = pathlib.Path(args.registry)
    run_p = pathlib.Path(args.run_manifest)
    env_p = pathlib.Path(args.publisher_manifest)
    policy_p = pathlib.Path(args.publisher_policy)

    for p in (registry_p, run_p, env_p, policy_p):
        if not p.exists():
            print(f"[publisher] FAIL: required file missing: {p}")
            return 1

    registry = json.loads(registry_p.read_text())
    envelope = json.loads(env_p.read_text())
    policy = json.loads(policy_p.read_text())

    result = verify_publisher_commitment(
        registry, run_p, envelope, policy, base_dir=registry_p.parent
    )
    if result["reason"] == REASON_VALID:
        print(f"[publisher] PASS: {result['detail']}")
        return 0
    print(f"[publisher] REJECT: {result['reason']} — {result['detail']}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
