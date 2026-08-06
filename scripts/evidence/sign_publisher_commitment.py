#!/usr/bin/env python3
"""Sign a test-artifacts registry with the publisher commitment scheme.

Builds the same commitment preimage the verifier reconstructs
(``scripts/validate/publisher_commitment.py``) and writes a signed
publisher manifest (``publication.json``) next to the registry.  Both sides
recompute the run-manifest sha256 from disk and use the registry's declared
artifact hashes, so a signature produced here verifies there without drift.

Usage:
    python3 scripts/evidence/sign_publisher_commitment.py \
        --registry results/test-artifacts/test-artifacts.json \
        --run-manifest results/test-artifacts/test-run.json \
        --policy config/publisher/publisher-policy.json \
        --private-key-seed-hex <64-hex> \
        --publisher-id <publisher> \
        --key-id <key> \
        --output results/test-artifacts/publication.json

The private key is an Ed25519 seed (32 raw bytes, 64 hex chars).  Keep it out
of argv in production (read from env/file); this CLI accepts the hex directly
for tooling only.
"""

from __future__ import annotations

import argparse
import datetime
import json
import pathlib
import sys

import nacl.signing

_EVIDENCE_DIR = pathlib.Path(__file__).resolve().parent
_VALIDATE_DIR = _EVIDENCE_DIR.parent / "validate"
sys.path.insert(0, str(_VALIDATE_DIR))

from publisher_commitment import (  # noqa: E402
    DEFAULT_ALGORITHM,
    PUBLISHER_DOMAIN,
    PUBLISHER_MANIFEST_SCHEMA,
    PUBLISHER_POLICY_SCHEMA,
    commitment_digest,
    commitment_preimage,
    policy_hash,
    sha256_file,
)


def sign_publisher_commitment(
    registry: dict,
    run_manifest_file: pathlib.Path,
    policy: dict,
    private_key_seed_hex: str,
    publisher_id: str,
    key_id: str,
    created_at: str | None = None,
) -> dict:
    """Build and sign the publisher manifest for a registry.

    Returns the envelope dict (``publication.json`` content).  The private key
    is used to sign the reconstructed commitment digest; the envelope carries
    no artifact list, run id, or digest that the verifier trusts — it
    reconstructs all of them.
    """
    if policy.get("schema_version") != PUBLISHER_POLICY_SCHEMA:
        raise ValueError(
            f"policy schema_version must be {PUBLISHER_POLICY_SCHEMA!r}, "
            f"got {policy.get('schema_version')!r}"
        )
    if len(private_key_seed_hex) != 64:
        raise ValueError("private_key_seed_hex must be 64 hex chars (32-byte Ed25519 seed)")
    seed = bytes.fromhex(private_key_seed_hex)
    signing_key = nacl.signing.SigningKey(seed)

    run_sha = sha256_file(run_manifest_file)
    if run_sha is None:
        raise FileNotFoundError(f"cannot re-hash run manifest {run_manifest_file}")

    base_dir = run_manifest_file.parent
    recomputed_artifacts: dict[str, str] = {}
    for art in registry.get("artifacts") or []:
        art_path = base_dir / art["path"]
        art_sha = sha256_file(art_path)
        if art_sha is None:
            raise FileNotFoundError(f"cannot re-hash artifact {art.get('id')!r} at {art_path}")
        recomputed_artifacts[art["id"]] = art_sha

    phash = policy_hash(policy)
    preimage = commitment_preimage(
        registry,
        str(registry.get("run_manifest", {}).get("path") or run_manifest_file),
        run_sha,
        policy["policy_id"],
        phash,
        recomputed_artifacts,
    )
    digest = commitment_digest(preimage)
    signature = signing_key.sign(digest.encode("utf-8")).signature.hex()

    return {
        "schema_version": PUBLISHER_MANIFEST_SCHEMA,
        "domain": PUBLISHER_DOMAIN,
        "algorithm": DEFAULT_ALGORITHM,
        "publisher_id": publisher_id,
        "key_id": key_id,
        "policy": {"id": policy["policy_id"], "hash": phash},
        "claimed_digest_hex": digest,
        "signature_hex": signature,
        "created_at": created_at or datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Sign a test-artifacts registry.")
    ap.add_argument("--registry", required=True, help="test-artifacts.json path")
    ap.add_argument("--run-manifest", required=True, help="test-run.json path")
    ap.add_argument("--policy", required=True, help="publisher policy JSON path")
    ap.add_argument("--private-key-seed-hex", required=True, help="Ed25519 seed (64 hex)")
    ap.add_argument("--publisher-id", required=True, help="publisher identifier")
    ap.add_argument("--key-id", required=True, help="key identifier in the policy")
    ap.add_argument("--output", default="publication.json", help="output envelope path")
    args = ap.parse_args()

    registry = json.loads(pathlib.Path(args.registry).read_text())
    policy = json.loads(pathlib.Path(args.policy).read_text())
    envelope = sign_publisher_commitment(
        registry,
        pathlib.Path(args.run_manifest),
        policy,
        args.private_key_seed_hex,
        args.publisher_id,
        args.key_id,
    )
    out = pathlib.Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(envelope, indent=2) + "\n")
    print(f"Signed publisher manifest: {out}")
    print(f"commitment digest: {envelope['claimed_digest_hex']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
