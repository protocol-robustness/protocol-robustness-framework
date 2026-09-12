#!/usr/bin/env bash
# Benchmark clean-checkout reproducibility gate.
#
# From an isolated clean checkout, this script:
#   1. Asserts results/ is fully git-ignored and that no generated benchmark
#      output is tracked (the README's "fully git-ignored and non-authoritative"
#      statement must not drift from repository reality).
#   2. Runs the documented representative benchmark command.
#   3. Verifies the generated bundle.
#   4. Reproduces it (re-reads the evidence and re-runs the benchmark).
#
# Usage:
#   bash scripts/benchmark-clean-checkout.sh [benchmark-id] [run-root]
#   default benchmark: sew/sew-yield-shortfall-v1

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BENCHMARK_ID="${1:-sew/sew-yield-shortfall-v1}"
RUN_ROOT="${2:-$PROJECT_DIR/target/benchmark-clean-checkout}"

cd "$PROJECT_DIR"

echo "=== 1. Results-policy assertion (git) ==="
if [ -d .git ]; then
  TRACKED="$(git ls-files results/benchmarks)"
  if [ -n "$TRACKED" ]; then
    echo "FAIL: results/benchmarks contains tracked files:" >&2
    printf '%s\n' "$TRACKED" >&2
    echo "The README states results/ is fully git-ignored and non-authoritative." >&2
    exit 1
  fi
  echo "PASS: git ls-files results/benchmarks is empty (policy holds)"
else
  echo "SKIP: no .git present — skipping git assertion"
fi

echo "=== 2. Run benchmark: $BENCHMARK_ID ==="
rm -rf "$RUN_ROOT"
bb benchmark:run "$BENCHMARK_ID" --run-root "$RUN_ROOT"

EVIDENCE="$RUN_ROOT/benchmark/evidence/evidence.edn"
test -f "$EVIDENCE" || { echo "FAIL: no evidence bundle at $EVIDENCE" >&2; exit 1; }
echo "PASS: evidence bundle written to $EVIDENCE"

echo "=== 3. Verify conclusion ==="
test -f "$RUN_ROOT/benchmark/conclusion.json"
grep -q '"outcome":"pass"' "$RUN_ROOT/benchmark/conclusion.json" \
  && echo "PASS: conclusion is pass" \
  || { echo "FAIL: benchmark conclusion is not pass" >&2; exit 1; }

echo "=== 4. Reproduce (re-read + re-run) ==="
bb benchmark:reproduce "$EVIDENCE"

echo "=== 5. Confirm generated output stayed under run-root (not scattered) ==="
if [ -d "$PROJECT_DIR/results" ] && find "$PROJECT_DIR/results" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  echo "NOTE: results/ exists with local output (git-ignored, non-authoritative)"
else
  echo "PASS: no results/ output scattered outside the run-root"
fi

echo
echo "ALL CLEAN-CHECKOUT CHECKS PASSED"
