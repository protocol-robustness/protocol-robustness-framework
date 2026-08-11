#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="${1:-/opt/prf}"
POLICY_SRC="$PROJECT_DIR/resources/prf/release/trust-policy.edn"
POLICY_DEST="${2:-/secure/operator-policy/trust-policy.edn}"
PUBLIC_KEY_FILE="${3:-}"

echo "=== Create active trust policy from template ==="

if [[ ! -f "$POLICY_SRC" ]]; then
  echo "ERROR: Template policy not found at $POLICY_SRC" >&2
  exit 1
fi

if [[ -z "$PUBLIC_KEY_FILE" ]]; then
  echo "ERROR: Public key file argument required (3rd arg)." >&2
  echo "Usage: $0 <project-dir> <policy-dest> <public-key-file>" >&2
  exit 1
fi

if [[ ! -f "$PUBLIC_KEY_FILE" ]]; then
  echo "ERROR: Public key file not found: $PUBLIC_KEY_FILE" >&2
  exit 1
fi

PUBLIC_KEY_HEX="$(cut -d' ' -f2 "$PUBLIC_KEY_FILE")"
KEY_ID="$(basename "$(dirname "$PUBLIC_KEY_FILE")")"

echo "Using public key: $PUBLIC_KEY_HEX"
echo "Key ID: $KEY_ID"

sudo mkdir -p "$(dirname "$POLICY_DEST")"

sudo tee "$POLICY_DEST" > /dev/null <<EOF
{:schema-version "prf-release-trust-policy.v1"
 :policy-id :release/assurance-lab-v1
 :policy/status :active
 :policy-version 1
 :trusted-keys
 [{:key-id "$KEY_ID"
   :status :active
   :public-key "$PUBLIC_KEY_HEX"}]
 :requirements
 {:distribution
  {:prf {:minimum-valid-signatures 1}
   :sew {:minimum-valid-signatures 1}}}
 :canonicalization
 {:payload-profile "prf-release-attestation-payload.v1"}}
EOF

sudo chmod 0644 "$POLICY_DEST"

echo "Active policy written to $POLICY_DEST"
echo ""
echo "Verify with:"
echo "  bb build:attest:verify target/default-build-attestation-prf.edn target prf $POLICY_DEST"
