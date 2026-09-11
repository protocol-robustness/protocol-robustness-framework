#!/usr/bin/env bash
# Realized-allocation-statement conformance gate.
#
# Flow:
#   1. Run the native Rust realized-statement kernel on the canonical fixture.
#   2. Assert the six roots + statement root match the Clojure oracle golden
#      values (condition D).
#   3. Assert a malformed input fails closed (no partial statement).
#
# The golden values below are generated from the Clojure
# resolver-sim.allocation.realized-statement producer for the a-vs-b-plus-c
# all-active fixture.
set -euo pipefail

DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="${REALIZED_KERNEL_BIN:-$DEMO_ROOT/coprocessor/target/release/realized-statement-kernel}"
INPUT="$DEMO_ROOT/scenarios/allocation/a-vs-b-plus-c/realized-statement-input.json"

if [[ ! -x "$BIN" ]]; then
  echo "realized-conformance: kernel binary not found at $BIN (build: cargo build --release -p allocation-kernel --bin realized-statement-kernel)" >&2
  exit 4
fi

out="$(python3 - "$BIN" "$INPUT" <<'PYEOF'
import json, subprocess, sys
binary, path = sys.argv[1], sys.argv[2]
with open(path) as f:
    doc = json.load(f)
p = subprocess.run([binary], input=json.dumps(doc), capture_output=True, text=True)
if p.returncode not in (0, 1):
    sys.exit(f"kernel exit {p.returncode}: {p.stderr}")
print(json.dumps(json.loads(p.stdout), sort_keys=True))
PYEOF
)"

GOLDEN=$(cat <<'EOF'
{"all-active": true, "allocation-context-root": "5155de4de58f55c1437d52d831cd3c747eea30e8455c3a7945dc715a6907a0b5", "allocation-policy-root": "798399d750475539bb518657121104c3c4ddea934cb0d61c044699f6671b64cb", "fail-action-policy-root": "10ff923ee0517c2e1dfbbb208946fe0834a8992273ebd983c2c2cd59658c08e7", "realized-results-root": "f0ba9de83a691600c73de0ddde4f8d1ab673ba4942a72340c782a5a350278b83", "request-set-root": "9c495e37e9844035bd5273dac30682bfb99293c1f380034a69107e8765076114", "result/status": "passing", "round-lifecycle-root": "1e7d41793ce424f39e2b2afc83dbb5c528cdd96a746d0a48949f1db5845b4a4a", "schema-version": "realized-allocation-statement.v1", "statement-root": "c22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd81"}
EOF
)

if [[ "$out" != "$GOLDEN" ]]; then
  echo "REALIZED CONFORMANCE FAILED" >&2
  echo "expected: $GOLDEN" >&2
  echo "actual:   $out" >&2
  exit 1
fi
echo "REALIZED CONFORMANCE PASS: statement roots match the Clojure oracle (all-active fixture)."

# Fail-closed check: malformed input must produce a rejected envelope, never a
# partial statement.
malformed="$(python3 - "$BIN" <<'PYEOF'
import json, subprocess, sys
binary = sys.argv[1]
doc = {"available": "10"}  # missing allocation-context
p = subprocess.run([binary], input=json.dumps(doc), capture_output=True, text=True)
print(p.stdout.strip())
PYEOF
)"
if [[ "$malformed" != *'"result/status":"rejected"'* ]]; then
  echo "REALIZED CONFORMANCE FAILED: malformed input did not fail closed" >&2
  echo "got: $malformed" >&2
  exit 1
fi
echo "REALIZED CONFORMANCE PASS: malformed input fails closed."
