#!/usr/bin/env bash
# test_public_demo_drift.sh — P1 generator/check distinction.
#
# Locks the two drift classes WITHOUT relying on git:
#   A. committed artifact is corrupted (file-side)  -> check must fail
#   B. committed artifact is stale relative to the executable output
#      (the committed bytes are valid but no longer equal a fresh generation)
#      -> check must fail
#   C. committed artifact is missing                -> check must fail
#   D. pristine committed artifact                  -> check must pass
#
# Each case runs the exact production check path
# (clojure -M:test -m scripts.demos.export-public-demo --check) against a temp
# copy, so generation can never overwrite the artifact it is comparing.
#
# Usage: scripts/test_public_demo_drift.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DEMO_ID="liquidity-shortfall"
RUN_CHECK=(clojure -M:test -m scripts.demos.export-public-demo --check --id "${DEMO_ID}" --out)
COMMITTED="site/generated/demos/${DEMO_ID}.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0

expect_fail() {
  local label="$1" file="$2"
  if "${RUN_CHECK[@]}" "$file" >/dev/null 2>&1; then
    echo "FAIL ($label): check unexpectedly passed"
    FAIL=$((FAIL+1))
  else
    echo "OK   ($label): check failed as expected"
    PASS=$((PASS+1))
  fi
}

# D. pristine baseline
"${RUN_CHECK[@]}" "$COMMITTED" >/dev/null 2>&1 \
  && { echo "OK   (baseline): pristine artifact passes"; PASS=$((PASS+1)); } \
  || { echo "FAIL (baseline): pristine artifact should pass"; FAIL=$((FAIL+1)); }

# A. corrupt a committed JSON byte
cp "$COMMITTED" "$TMP/corrupt.json"
python3 - "$TMP/corrupt.json" <<'EOF'
import sys
p = sys.argv[1]
b = bytearray(open(p,'rb').read())
b[10] ^= 0xFF
open(p,'wb').write(b)
EOF
expect_fail "class A: corrupted committed artifact" "$TMP/corrupt.json"

# B. stale committed artifact: valid JSON whose conservation is from another
#    execution (regeneration produces different bytes, so check must fail)
cp "$COMMITTED" "$TMP/stale.json"
python3 - "$TMP/stale.json" <<'EOF'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d['conservation']['requested'] = 110
json.dump(d, open(p,'w'))
EOF
expect_fail "class B: stale committed artifact (another execution's conservation)" "$TMP/stale.json"

# C. missing artifact
expect_fail "class C: missing artifact" "$TMP/does-not-exist.json"

echo
echo "drift-class tests: ${PASS} passed, ${FAIL} failed"
[ "$FAIL" -eq 0 ]
