#!/usr/bin/env python3
"""
Verify the integrity and authenticity of an evidence bundle.
1. Verifies artifact registry integrity (SHA256).
2. Verifies registry binding to evidence envelope (registry_sha256 in envelope).
3. Verifies Ed25519 signature of the envelope.
"""

import argparse
import hashlib
import json
import pathlib
import sys
import subprocess

from evidence_config import EvidenceConfig
_cfg = EvidenceConfig()

def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    ap = argparse.ArgumentParser(description="Verify evidence bundle.")
    ap.add_argument("--bundle-dir", required=True, help="Bundle directory")
    ap.add_argument("--public-key", required=True, help="Public key path for signature verification")
    args = ap.parse_args()

    bundle = pathlib.Path(args.bundle_dir)
    registry_file = bundle / "test-artifacts.json"
    envelope_file = bundle / _cfg.artifact("envelope")["file"]
    signature_file = bundle / _cfg.artifact("signature")["file"]

    # 1. Verify Artifact Registry Integrity
    registry = json.loads(registry_file.read_text())
    for art in registry["artifacts"]:
        p = bundle / art["path"]
        if not p.exists():
            print(f"Error: Artifact {art['id']} missing: {p}")
            sys.exit(1)
        if sha256_file(p) != art["sha256"]:
            print(f"Error: Artifact {art['id']} hash mismatch: {p}")
            sys.exit(1)
    
    print("Artifact registry integrity verified.")

    # 2. Verify Registry Binding to Envelope
    envelope = json.loads(envelope_file.read_text())
    if sha256_file(registry_file) != envelope["registry_sha256"]:
        print("Error: Registry hash does not match envelope binding.")
        sys.exit(1)
    
    print("Registry bound to envelope verified.")

    # 3. Verify Signature (External subprocess call to verify Ed25519)
    # Using 'openssl pkeyutl' for Ed25519 verification
    sig_data = json.loads(signature_file.read_text())
    sig_hex = sig_data["signature"]
    
    # Write sig to temp file
    sig_bin = bytes.fromhex(sig_hex)
    sig_tmp = bundle / "sig.bin"
    sig_tmp.write_bytes(sig_bin)

    try:
        # openssl dgst -verify pubkey.pem -signature sig.bin envelope.json
        subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", args.public_key, "-signature", str(sig_tmp), str(envelope_file)],
            check=True, capture_output=True
        )
        print("Signature verified.")
    except subprocess.CalledProcessError:
        print("Error: Signature verification failed.")
        sys.exit(1)
    finally:
        sig_tmp.unlink()

    print("Bundle verified successfully.")
    sys.exit(0)

if __name__ == "__main__":
    sys.exit(main())
