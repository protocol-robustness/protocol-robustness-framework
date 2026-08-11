#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KEYS_DIR="${1:-/secure/operator-keys}"
KEY_BASE="$KEYS_DIR/release-signing-key"

echo "=== Generate test Ed25519 signing keypair ==="

sudo mkdir -p "$KEYS_DIR"
sudo chmod 0700 "$KEYS_DIR"

if [[ -f "$KEY_BASE" ]]; then
  echo "Key already exists at $KEY_BASE — skipping generation."
  echo "Public key:"
  sudo cat "${KEY_BASE}.pub"
  exit 0
fi

sudo ssh-keygen -t ed25519 -f "$KEY_BASE" -C "assurance-lab-signer" -N "" -q
sudo chmod 600 "$KEY_BASE"
sudo chmod 644 "${KEY_BASE}.pub"

echo "Generated keypair:"
echo "  Private: $KEY_BASE"
echo "  Public:  ${KEY_BASE}.pub"
echo ""
echo "Public key material (copy this into the trust policy):"
sudo cat "${KEY_BASE}.pub" | cut -d' ' -f2
