#!/usr/bin/env python3
"""
Verify a research claim and its attestation against an evidence bundle.
1. Verifies the claim's integrity and signature.
2. Verifies the registry integrity (the bundle).
3. Verifies the binding between the claim and the evidence bundle.
"""

import argparse
import hashlib
import json
import pathlib
import sys
import subprocess

from evidence_config import EvidenceConfig
_cfg = EvidenceConfig()

def sha256_data(data: str) -> str:
    return hashlib.sha256(data.encode("utf-8")).hexdigest()

def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    ap = argparse.ArgumentParser(description="Verify research claim.")
    ap.add_argument("--claim-file", required=True, help="Claim JSON file")
    ap.add_argument("--bundle-dir", required=True, help="Evidence bundle directory")
    ap.add_argument("--owners-file", required=True, help="Owners JSON file")
    args = ap.parse_args()

    claim_path = pathlib.Path(args.claim_file)
    att_path = claim_path.with_suffix(".attestation.json")
    owners_data = json.loads(pathlib.Path(args.owners_file).read_text())["owners"]
    
    # 1. Verify Claim/Attestation Binding & Signature
    claim_data = json.loads(claim_path.read_text())
    claim_json = json.dumps(claim_data, sort_keys=True)
    claim_hash = hashlib.sha256(claim_json.encode("utf-8")).hexdigest()
    
    att_data = json.loads(att_path.read_text())
    if att_data["claim_sha256"] != claim_hash:
        print("Error: Claim contents do not match attestation binding.")
        sys.exit(1)

    # Validate Signer
    signer_id = att_data["signer_id"]
    if signer_id not in owners_data:
        print(f"Error: Unknown owner {signer_id}")
        sys.exit(1)
        
    owner = owners_data[signer_id]
    if owner["status"] == "revoked":
        print(f"Error: Signer {signer_id} is revoked")
        sys.exit(1)
        
    pub_key_path = pathlib.Path(owner["public_key"])
    if not pub_key_path.exists():
        print(f"Error: Missing public key for {signer_id}")
        sys.exit(1)
        
    if sha256_file(pub_key_path) != owner["key_fingerprint_sha256"]:
        print(f"Error: Fingerprint mismatch for {signer_id}")
        sys.exit(1)
        
    # Verify signature
    sig_hex = att_data["signature"]
    sig_bin = bytes.fromhex(sig_hex)
    sig_tmp = claim_path.parent / "sig.bin"
    sig_tmp.write_bytes(sig_bin)
    
    # Envelope-like payload
    payload = (claim_hash + att_data["attestation_type"]).encode("utf-8")
    payload_tmp = claim_path.parent / "payload.bin"
    payload_tmp.write_bytes(payload)

    try:
        subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(pub_key_path), "-signature", str(sig_tmp), str(payload_tmp)],
            check=True, capture_output=True
        )
    except subprocess.CalledProcessError:
        print("Error: Signature verification failed.")
        sys.exit(1)
    finally:
        sig_tmp.unlink()
        payload_tmp.unlink()

    # 2. Verify Evidence Bundle Integrity
    bundle = pathlib.Path(args.bundle_dir)
    registry_file = bundle / "test-artifacts.json"
    
    if claim_data["registry_sha256"] != sha256_file(registry_file):
        print("Error: Claim registry hash does not match bundle.")
        sys.exit(1)
    
    print("Claim verified successfully against bundle.")
    sys.exit(0)

if __name__ == "__main__":
    sys.exit(main())
