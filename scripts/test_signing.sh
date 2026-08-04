#!/bin/bash
# Integration test for Signed Claims

set -e

# Setup
ARTIFACT_DIR="$(python3 -c "from scripts.evidence_config import EvidenceConfig; print(EvidenceConfig().artifact_dir)" 2>/dev/null)" || ARTIFACT_DIR="results/test-artifacts"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
# Create a dummy registry
echo '{"artifacts": []}' > "$ARTIFACT_DIR/test-artifacts.json"
REG_SHA=$(sha256sum "$ARTIFACT_DIR/test-artifacts.json" | cut -d ' ' -f 1)

# Create a claim
CLAIM='{"claim_id": "c001", "claim_type": "TEST", "claim_status": "PROPOSED", "claim_strength": "OBSERVATIONAL", "registry_sha256": "'$REG_SHA'", "scope": "scope", "limits": "limits", "references": ["ref1"]}'
echo "$CLAIM" > claim.json

# Sign it (using the existing infrastructure)
# NOTE: This requires test_key to exist. Creating dummy key if missing.
if [ ! -f test_key ]; then
    openssl genpkey -algorithm ed25519 -out test_key
    openssl pkey -in test_key -pubout -out keys/test-researcher.pub
fi

# Add to owners.json manually for the test
bb keys:add test-researcher keys/test-researcher.pub --name "Test" --email "test@example.com"

# Sign
bb evidence.clj claim:sign claim.json test_key

# Verify
python3 scripts/verify_claim.py --claim-file claim.json --bundle-dir "$ARTIFACT_DIR" --owners-file keys/owners.json

echo "Integration test passed."
rm claim.json claim.json.attestation.json sig.bin payload.json
