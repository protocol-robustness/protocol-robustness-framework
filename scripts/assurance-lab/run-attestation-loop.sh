#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="${1:-/opt/prf}"
ARTIFACT_ROOT="${2:-target}"
DISTRIBUTION="${3:-prf}"
POLICY_FILE="${4:-/secure/operator-policy/trust-policy.edn}"
PRIVATE_KEY="${5:-/secure/operator-keys/release-signing-key}"
RELEASE_METADATA="${6:-{:release/id \"2026.08.11\" :release/channel :stable}}"

echo "=== Run full attestation loop: build -> verify (expect fail) -> sign -> verify (expect pass) ==="

cd "$PROJECT_DIR"

echo ""
echo "--- Step 1: Validate command registry ---"
bb commands validate

echo ""
echo "--- Step 2: Build attestation bundles ---"
bb build:attest

echo ""
echo "--- Step 3: Verify unsigned bundle against active policy (should fail) ---"
set +e
bb build:attest:verify "$ARTIFACT_ROOT/default-build-attestation-$DISTRIBUTION.edn" "$ARTIFACT_ROOT" "$DISTRIBUTION" "$POLICY_FILE"
VERIFY_EXIT=$?
set -e
if [[ $VERIFY_EXIT -ne 0 ]]; then
  echo "Expected failure confirmed (unsigned bundle cannot authorize release)."
else
  echo "WARNING: Verification passed on unsigned bundle — check policy status." >&2
fi

echo ""
echo "--- Step 4: Sign the bundle ---"
bb build:attest:sign \
  "$ARTIFACT_ROOT/default-build-attestation-$DISTRIBUTION.edn" \
  "$DISTRIBUTION" \
  "$(basename "$(dirname "$PRIVATE_KEY")")" \
  "$PRIVATE_KEY" \
  "$RELEASE_METADATA"

echo ""
echo "--- Step 5: Verify signed bundle against active policy (should pass) ---"
bb build:attest:verify "$ARTIFACT_ROOT/default-build-attestation-$DISTRIBUTION.edn" "$ARTIFACT_ROOT" "$DISTRIBUTION" "$POLICY_FILE"

echo ""
echo "=== Attestation loop complete ==="
