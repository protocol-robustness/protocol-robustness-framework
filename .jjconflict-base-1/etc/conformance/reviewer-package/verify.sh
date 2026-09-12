#!/usr/bin/env sh
# One-command verification for the Sew trace-equivalence attestation bundle.
# Requires: node (>= 20) for verify3.mjs, or python3 for bundle_verify.py.
set -eu
cd "$(dirname "$0")"
if command -v node >/dev/null 2>&1; then
  node ../../scripts/verify3.mjs verify bundle.json
elif command -v python3 >/dev/null 2>&1; then
  python3 ../../scripts/bundle_verify.py bundle.json
else
  echo "error: need node or python3 to verify" >&2
  exit 2
fi
