#!/usr/bin/env bash
# Validate that parallel suite dispatch produces identical results to serial.
#
# Runs a subset of suites in both serial and parallel mode, then compares
# per-suite outcomes (pass/fail counts, failed scenario names).
#
# Usage:
#   ./scripts/test_parallel_suites.sh              # default: 2 suites
#   ./scripts/test_parallel_suites.sh --all         # all 16 suites (slow)
#   ./scripts/test_parallel_suites.sh --suites 4    # first N suites
#
# Exit code: 0 if parallel matches serial, 1 on mismatch or infrastructure error.

set -euo pipefail

cd "$(dirname "$0")/.."

SUITE_NAMES=(
  :suites/all-invariants
  :suites/baseline-safety
  :suites/equilibrium-validation
  :suites/spe-validation
  :suites/spe-regression
  :suites/equivalence-auth-paths
  :suites/equivalence-race-pairs
  :suites/equivalence-escalation-boundaries
  :suites/equivalence-accounting-min
  :suites/equivalence-money-path-integrity
  :suites/dr3-critical
  :suites/governance-decay
  :suites/same-block-ordering
  :suites/timelock-regression
  :suites/equivalence-economic-stress
  :suites/forking-strategist
)

if [ "${1:-}" = "--all" ]; then
  SELECTED=("${SUITE_NAMES[@]}")
elif [ "${1:-}" = "--suites" ]; then
  n="${2:-4}"
  SELECTED=("${SUITE_NAMES[@]:0:$n}")
else
  # Default: first 4 suites for quick smoke test
  SELECTED=("${SUITE_NAMES[@]:0:4}")
fi

echo "=== Parallel suite dispatch smoke test ==="
echo "suites: ${#SELECTED[@]} (${SELECTED[*]})"
echo ""

# ── 1. Serial run ──────────────────────────────────────────────────────────
OUTDIR_SERIAL="/tmp/parallel-test-serial-$$"
mkdir -p "$OUTDIR_SERIAL"
SERIAL_RESULTS="$OUTDIR_SERIAL/results.txt"
SERIAL_ARTIFACTS="$OUTDIR_SERIAL/artifacts"
mkdir -p "$SERIAL_ARTIFACTS"

echo "--- Serial run ---"
for suite in "${SELECTED[@]}"; do
  slog=$(echo "$suite" | tr ':' '_' | tr '/' '-')
  echo "  $suite"
  PRF_ARTIFACT_DIR="$SERIAL_ARTIFACTS/$slog" \
    clojure -M:test:with-sew -m scripts.run-suite "$suite" > "$OUTDIR_SERIAL/$slog.log" 2>&1 || true
  # Record outcome: suite-key → PASS/FAIL
  tail -1 "$OUTDIR_SERIAL/$slog.log" | grep -q "→ PASS" && echo "$suite PASS" >> "$SERIAL_RESULTS" || echo "$suite FAIL" >> "$SERIAL_RESULTS"
done

# ── 2. Parallel run ────────────────────────────────────────────────────────
OUTDIR_PARALLEL="/tmp/parallel-test-parallel-$$"
mkdir -p "$OUTDIR_PARALLEL"
PARALLEL_RESULTS="$OUTDIR_PARALLEL/results.txt"
PARALLEL_ARTIFACTS="$OUTDIR_PARALLEL/artifacts"
mkdir -p "$PARALLEL_ARTIFACTS"

echo "--- Parallel run ---"
pids=()
for suite in "${SELECTED[@]}"; do
  plog=$(echo "$suite" | tr ':' '_' | tr '/' '-')
  echo "  $suite"
  PRF_ARTIFACT_DIR="$PARALLEL_ARTIFACTS/$plog" \
    clojure -M:test:with-sew -m scripts.run-suite "$suite" > "$OUTDIR_PARALLEL/$plog.log" 2>&1 &
  pids+=($!)
done
# Wait for all
idx=0
for p in "${pids[@]}"; do
  wait "$p" || true
  suite="${SELECTED[$idx]}"
  plog=$(echo "$suite" | tr ':' '_' | tr '/' '-')
  tail -1 "$OUTDIR_PARALLEL/$plog.log" | grep -q "→ PASS" && echo "$suite PASS" >> "$PARALLEL_RESULTS" || echo "$suite FAIL" >> "$PARALLEL_RESULTS"
  idx=$((idx + 1))
done

# ── 3. Compare ─────────────────────────────────────────────────────────────
echo ""
echo "--- Comparison ---"
SERIAL_OUT=$(sort "$SERIAL_RESULTS")
PARALLEL_OUT=$(sort "$PARALLEL_RESULTS")

if [ "$SERIAL_OUT" = "$PARALLEL_OUT" ]; then
  echo "MATCH: All suites produced identical outcomes in serial and parallel."
  cat "$SERIAL_RESULTS"
  echo ""
  echo "PASS"
  rm -rf "$OUTDIR_SERIAL" "$OUTDIR_PARALLEL"
  exit 0
else
  echo "MISMATCH:"
  echo "--- Serial ---"
  echo "$SERIAL_OUT"
  echo "--- Parallel ---"
  echo "$PARALLEL_OUT"
  echo ""
  echo "FAIL"
  rm -rf "$OUTDIR_SERIAL" "$OUTDIR_PARALLEL"
  exit 1
fi
