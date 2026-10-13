#!/usr/bin/env bash
# Realized-allocation-statement conformance gate.
#
# Runs every public realized partial-fill input against the native kernel and
# compares the complete compact projection with its checked-in expected output.
# A malformed input must also fail closed.
set -euo pipefail

DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="${REALIZED_KERNEL_BIN:-$DEMO_ROOT/coprocessor/target/release/realized-statement-kernel}"
PACKAGE_ROOT="$DEMO_ROOT/etc/conformance/cleanroom/realized-partial-fill-v1"

if [[ ! -x "$BIN" ]]; then
  echo "realized-conformance: kernel binary not found at $BIN (build: cargo build --release -p allocation-kernel --bin realized-statement-kernel)" >&2
  exit 4
fi

run_case() {
  local input="$1"
  local expected="$2"
  local label="$3"
  python3 - "$BIN" "$input" "$expected" "$label" <<'PYEOF'
import json
import subprocess
import sys

binary, input_path, expected_path, label = sys.argv[1:]
with open(input_path) as f:
    document = json.load(f)
with open(expected_path) as f:
    expected = json.load(f)

process = subprocess.run(
    [binary], input=json.dumps(document), capture_output=True, text=True
)
if process.returncode not in (0, 1):
    raise SystemExit(f"{label}: kernel exit {process.returncode}: {process.stderr}")
actual = json.loads(process.stdout)
if actual != expected:
    raise SystemExit(
        f"{label}: REALIZED CONFORMANCE FAILED\n"
        f"expected: {json.dumps(expected, sort_keys=True)}\n"
        f"actual:   {json.dumps(actual, sort_keys=True)}"
    )
print(f"REALIZED CONFORMANCE PASS: {label}")
PYEOF
}

run_case \
  "$PACKAGE_ROOT/public/a-vs-b-plus-c.input.json" \
  "$PACKAGE_ROOT/public-expected/a-vs-b-plus-c.output.json" \
  "a-vs-b-plus-c"
run_case \
  "$PACKAGE_ROOT/public/a-vs-b-plus-c-75.input.json" \
  "$PACKAGE_ROOT/public-expected/a-vs-b-plus-c-75.output.json" \
  "a-vs-b-plus-c-75"

run_rejection_case() {
  local input="$1"
  local expected="$2"
  local label="$3"
  python3 - "$BIN" "$input" "$expected" "$label" <<'PYEOF'
import json
import subprocess
import sys

binary, input_path, expected_path, label = sys.argv[1:]
with open(input_path) as f:
    document = json.load(f)
with open(expected_path) as f:
    expected = json.load(f)
process = subprocess.run(
    [binary], input=json.dumps(document), capture_output=True, text=True
)
if process.returncode != 1:
    raise SystemExit(f"{label}: expected rejected exit 1, got {process.returncode}")
actual = json.loads(process.stdout)
if actual != expected:
    raise SystemExit(
        f"{label}: REJECTION CONFORMANCE FAILED\n"
        f"expected: {json.dumps(expected, sort_keys=True)}\n"
        f"actual:   {json.dumps(actual, sort_keys=True)}"
    )
print(f"REALIZED CONFORMANCE PASS: {label} rejected as expected")
PYEOF
}

run_rejection_case \
  "$PACKAGE_ROOT/public-negative/missing-context.input.json" \
  "$PACKAGE_ROOT/public-negative-expected/missing-context.output.json" \
  "missing-context"
run_rejection_case \
  "$PACKAGE_ROOT/public-negative/available-exceeds-requested.input.json" \
  "$PACKAGE_ROOT/public-negative-expected/available-exceeds-requested.output.json" \
  "available-exceeds-requested"
run_rejection_case \
  "$PACKAGE_ROOT/public-negative/unknown-claim.input.json" \
  "$PACKAGE_ROOT/public-negative-expected/unknown-claim.output.json" \
  "unknown-claim"
run_rejection_case \
  "$PACKAGE_ROOT/public-negative/unsupported-policy.input.json" \
  "$PACKAGE_ROOT/public-negative-expected/unsupported-policy.output.json" \
  "unsupported-policy"

# Fail-closed check: malformed input must produce a rejected envelope, never a
# partial statement.
malformed="$(python3 - "$BIN" <<'PYEOF'
import json
import subprocess
import sys

process = subprocess.run(
    [sys.argv[1]],
    input=json.dumps({"available": "10"}),
    capture_output=True,
    text=True,
)
print(process.stdout.strip())
PYEOF
)"
if [[ "$malformed" != *'"result/status":"rejected"'* ]]; then
  echo "REALIZED CONFORMANCE FAILED: malformed input did not fail closed" >&2
  echo "got: $malformed" >&2
  exit 1
fi
echo "REALIZED CONFORMANCE PASS: malformed input fails closed."
