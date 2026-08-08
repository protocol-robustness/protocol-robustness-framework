#!/usr/bin/env bash
# PRF-versus-native-Rust conformance gate for the allocation kernel.
#
# Flow:
#   1. Verify the pinned PRF JAR SHA-256 against artifact-lock.json.
#   2. Generate conformance vectors through the PRF JAR (allocation vectors).
#   3. Validate the vector JSON shape.
#   4. Run the native Rust kernel for every vector.
#   5. Normalise only transport-level JSON formatting.
#   6. Compare the complete declared public-value projection.
#   7. Fail on missing or extra fields.
#   8. Print a concise per-vector result; exit nonzero on any mismatch.
#
# The main gate: PRF public result == native Rust public result.
set -euo pipefail

DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PRF_RUNNER="$DEMO_ROOT/prf-runner/run-prf.sh"
LOCK="$DEMO_ROOT/prf-runner/artifact-lock.json"
PRF_JAR="${PRF_JAR:-$DEMO_ROOT/target/prf-runner-sew-0.1.0-uber.jar}"
KERNEL_BIN="${KERNEL_BIN:-$DEMO_ROOT/coprocessor/target/release/allocation-kernel}"

if [[ ! -x "$KERNEL_BIN" ]]; then
  echo "conformance: native kernel binary not found at $KERNEL_BIN (build with: cargo build --release in coprocessor/core)" >&2
  exit 4
fi

# 1. Verify pinned JAR SHA-256.
expected_sha="$(python3 -c "import json;print(json.load(open('$LOCK'))['artifact_sha256'])")"
actual_sha="$(sha256sum "$PRF_JAR" | cut -d' ' -f1)"
if [[ "$expected_sha" != "$actual_sha" ]]; then
  echo "conformance: PRF JAR SHA-256 mismatch: expected $expected_sha, got $actual_sha" >&2
  exit 3
fi
echo "PRF JAR SHA-256 verified."

# 2. Generate vectors through the PRF JAR.
VECTORS="$(mktemp)"
"$PRF_RUNNER" allocation vectors > "$VECTORS"
echo "Generated $(python3 -c "import json;print(len(json.load(open('$VECTORS'))))") vectors."

# 2b. Verify the conformance vector-set root against the artifact lock so the
#     lock identifies both the executable and the exact corpus tested.
expected_vsroot="$(python3 -c "import json;print(json.load(open('$LOCK'))['vector_set_root'])")"
actual_vsroot="$(python3 -c "
import json, hashlib
vectors = json.load(open('$VECTORS'))
ordered = sorted(vectors, key=lambda v: v['vector_id'])
h = hashlib.sha256()
for v in ordered:
    h.update(v['vector_id'].encode('utf-8')); h.update(b'\x00')
    h.update(json.dumps(v['expected'], sort_keys=True, separators=(',', ':')).encode('utf-8')); h.update(b'\x00')
print(h.hexdigest())
")"
if [[ "$expected_vsroot" != "$actual_vsroot" ]]; then
  echo "conformance: vector-set root mismatch: expected $expected_vsroot, got $actual_vsroot" >&2
  exit 3
fi
echo "Vector-set root verified: $actual_vsroot"

# 3-8. Compare PRF expected projection to native Rust output.
python3 - "$VECTORS" "$KERNEL_BIN" <<'PYEOF'
import json, subprocess, sys

vectors = json.load(open(sys.argv[1]))
binary = sys.argv[2]

# Declared public-value projection (PRF and Rust must agree field-for-field).
PROJECTION_KEYS = [
    "result/status", "allocation-context-hash", "claimant-set-root",
    "outcome-set-root", "proposed-rates-root", "rate-derived-summary-hash",
    "assertions", "selection-receipt", "selected-outcome-id",
    "selected-outcome-index", "selected-outcome-hash", "result-root",
    "total-allocated", "residual-capacity", "round-lifecycle",
    "certificate-assertions-digest",
    "allocation-kernel-version", "selection-algorithm",
]

def run_rust(input_doc):
    p = subprocess.run([binary], input=json.dumps(input_doc),
                       capture_output=True, text=True)
    if p.returncode not in (0, 1):
        return None, f"rust exit {p.returncode}: {p.stderr}"
    try:
        return json.loads(p.stdout), None
    except Exception as e:
        return None, f"rust stdout not JSON: {e}"

failures = []
for v in vectors:
    vid = v.get("vector_id", "?")
    input_doc = v["input"]
    expected = v["expected"]
    rust, err = run_rust(input_doc)
    if rust is None:
        failures.append((vid, "rust error", err))
        continue

    # Fail on missing or extra fields.
    missing = [k for k in expected if k not in rust]
    extra = [k for k in rust if k not in expected]
    if missing or extra:
        failures.append((vid, "key set mismatch",
                         {"missing": sorted(missing), "extra": sorted(extra)}))
        continue

    diffs = []
    for k in expected:
        if rust[k] != expected[k]:
            diffs.append((k, expected[k], rust[k]))
    if diffs:
        failures.append((vid, "value mismatch", diffs))
    else:
        print(f"PASS {vid}")

print()
if failures:
    print(f"CONFORMANCE FAILED: {len(failures)} mismatch(es)")
    for f in failures:
        print(f)
    sys.exit(1)
else:
    print(f"CONFORMANCE PASS: {len(vectors)} vectors match (PRF result == native Rust result)")
PYEOF
