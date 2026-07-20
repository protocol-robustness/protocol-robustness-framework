#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
ACTUAL="$ROOT/actual"
EXPECTED="$ROOT/expected"

SUITE="${ROOT##*/}"

# ── Stale hash sidecar guard ─────────────────────────────────────────────
stale="$(find "$EXPECTED" -name '*.json.sha256' -maxdepth 3 2>/dev/null | head -5)"
if [[ -n "$stale" ]]; then
  echo "FAIL $SUITE: stale *.json.sha256 orphan files detected (use basename.sha256 instead):"
  echo "$stale"
  exit 1
fi

canon() {
  python3 - <<'PY' "$1"
import json,sys
p=sys.argv[1]
obj=json.load(open(p))
print(json.dumps(obj, separators=(",",":"), sort_keys=True))
PY
}

report_json_diff() {
  local expected_file="$1"
  local actual_file="$2"
  python3 "$REPO_ROOT/scripts/json_diff_report.py" "$expected_file" "$actual_file" || true
}

# ── Pass count check ────────────────────────────────────────────────────

SUMMARY="$ACTUAL/summary.json"
echo "DEBUG: Checking for $SUMMARY"
ls -l "$SUMMARY"
[[ -f "$SUMMARY" ]] || { echo "FAIL missing summary.json (ACTUAL: $ACTUAL)"; exit 1; }

PASSED=$(python3 -c "import json; print(json.load(open('$SUMMARY'))['passed'])")
FAILED=$(python3 -c "import json; print(json.load(open('$SUMMARY'))['failed'])")
TOTAL=$(python3 -c "import json; print(json.load(open('$SUMMARY'))['scenario_count'])")

if [[ "$FAILED" -ne 0 ]]; then
  echo "FAIL $SUITE: $FAILED/$TOTAL scenarios failed"
  exit 1
fi
if [[ "$PASSED" -ne "$TOTAL" ]]; then
  echo "FAIL $SUITE: passed $PASSED != total $TOTAL"
  exit 1
fi
if [[ "$PASSED" -lt "$TOTAL" ]]; then
  echo "FAIL $SUITE: passed $PASSED < total $TOTAL"
  exit 1
fi
echo "PASS count: $PASSED/$TOTAL scenarios passed"

# ── Per-scenario pass-count assertions ──────────────────────────────────

SCENARIO_RESULTS="$ACTUAL/scenario-results.json"
python3 - "$SCENARIO_RESULTS" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
errors = []
for r in data.get("results", []):
    sid = r.get("scenario_id", "?")
    ef = r.get("expectations_failed", -1)
    invf = r.get("invariants_failed", -1)
    status = r.get("status", "?")
    if ef > 0:
        errors.append(f"{sid}: {ef} expectation(s) failed")
    if invf > 0:
        errors.append(f"{sid}: {invf} invariant(s) failed")
    if status != "pass":
        errors.append(f"{sid}: status is '{status}' not 'pass'")
if errors:
    print("FAIL per-scenario checks:")
    for e in errors:
        print(" -", e)
    sys.exit(1)
else:
    print(f"PASS per-scenario: {len(data.get('results', []))} scenario(s) all passed (0 expectations failed, 0 invariants failed)")
PY

# ── JSON content and hash verification ──────────────────────────────────

FILES=(summary scenario-results invariants economic-results evidence-matrix)
for base in "${FILES[@]}"; do
  [[ -f "$ACTUAL/$base.json" ]] || { echo "FAIL missing actual file: $base.json"; exit 1; }
  [[ -f "$EXPECTED/$base.json" ]] || { echo "FAIL missing expected file: $base.json"; exit 1; }
  [[ -f "$EXPECTED/$base.sha256" ]] || { echo "FAIL missing expected hash: $base.sha256"; exit 1; }

  a="$(canon "$ACTUAL/$base.json")"
  e="$(canon "$EXPECTED/$base.json")"
  if [[ "$a" != "$e" ]]; then
    echo "FAIL canonical content mismatch: $base.json"
    report_json_diff "$EXPECTED/$base.json" "$ACTUAL/$base.json"
    exit 1
  fi

  ACTUAL_HASH=$(sha256sum "$ACTUAL/$base.json" | awk '{print $1}')
  EXPECTED_HASH=$(tr -d '[:space:]' < "$EXPECTED/$base.sha256")
  [[ "$ACTUAL_HASH" == "$EXPECTED_HASH" ]] || { echo "FAIL hash mismatch: $base.json"; exit 1; }
done

echo "PASS verify-$SUITE"

# ── Artifact Registry Emission ──────────────────────────────────────────
echo "Emitting artifact registry..."
python3 "$REPO_ROOT/scripts/write_scenario_run_manifest.py" \
    --scenario "$SUITE" \
    --suite "$SUITE" \
    --status "pass" \
    --artifact-dir "$ACTUAL" \
    --registry-level CORE || true

echo "PASS all integrity and authenticity checks."
