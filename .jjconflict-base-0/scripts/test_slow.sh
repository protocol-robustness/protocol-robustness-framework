#!/usr/bin/env bash
# Slow test runner - runs tests that take > 60 seconds

cd "$(dirname "$0")/.."
source "$(dirname "$0")/monte_carlo.sh"

ARTIFACT_DIR="$(python3 -c "from scripts.evidence_config import EvidenceConfig; print(EvidenceConfig().artifact_dir)" 2>/dev/null)" || ARTIFACT_DIR="results/test-artifacts"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
FAILURES=0
TARGETS_COMPLETED=0
TARGETS_PASSED=0

mkdir -p "$ARTIFACT_DIR"

echo "Running SLOW test suite (long-running tests)..."
echo "========================================"

# Run slow tests only
run_test() {
  local name="$1"
  local cmd="$2"
  echo "  [$(date +%H:%M:%S)] Running $name..."
  local t0=$(date +%s)
  eval "$cmd" >"$ARTIFACT_DIR/${name}-${RUN_ID}.log" 2>&1
  local code=$?
  local t1=$(date +%s)
  local dur_ms=$(( (t1 - t0) * 1000 ))

  TARGETS_COMPLETED=$((TARGETS_COMPLETED + 1))
  if [ $code -eq 0 ]; then
    TARGETS_PASSED=$((TARGETS_PASSED + 1))
    echo "  [$(date +%H:%M:%S)] ✓ $name completed in ${dur_ms}ms ($TARGETS_PASSED/$TARGETS_COMPLETED)"
  else
    echo "  [$(date +%H:%M:%S)] ✗ $name failed after ${dur_ms}ms ($TARGETS_PASSED/$TARGETS_COMPLETED)"
    FAILURES=$((FAILURES + 1))
  fi
}

# Slow tests (> 60 seconds)
run_test "monte-carlo" "./scripts/test.sh monte-carlo"
# Add other slow tests here as needed
# run_test "long-horizon" "./scripts/test.sh long-horizon"

echo ""
echo "========================================"
echo "SLOW TEST SUITE COMPLETE"
echo "  Targets: $TARGETS_COMPLETED completed"
echo "  Passed: $TARGETS_PASSED"
echo "  Failed: $((TARGETS_COMPLETED - TARGETS_PASSED))"
echo "  Duration: > 60 seconds each (expected)"
if [ $FAILURES -gt 0 ]; then
  echo "  Exit code: 1 (failures detected)"
  exit 1
else
  echo "  Exit code: 0 (all passed)"
  exit 0
fi
