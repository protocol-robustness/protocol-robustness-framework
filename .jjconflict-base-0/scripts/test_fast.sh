#!/usr/bin/env bash
# Fast test runner - runs only tests that complete in < 60 seconds

cd "$(dirname "$0")/.."
source "$(dirname "$0")/monte_carlo.sh"
source "$(dirname "$0")/test.sh"

ARTIFACT_DIR="$(python3 -c "from scripts.evidence_config import EvidenceConfig; print(EvidenceConfig().artifact_dir)" 2>/dev/null)" || ARTIFACT_DIR="results/test-artifacts"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
FAILURES=0
TARGETS_COMPLETED=0
TARGETS_PASSED=0

mkdir -p "$ARTIFACT_DIR"

echo "Running FAST test suite (target: < 60 seconds)..."
echo "========================================"

# Run fast tests only
run_test() {
  local name="$1"
  local cmd="$2"
  local log_file="$ARTIFACT_DIR/${name}-${RUN_ID}.log"
  echo "  [$(date +%H:%M:%S)] Running $name..."
  local t0=$(date +%s)
  
  # Run the test command in background with the lock
  scripts/with-test-artifact-lock.sh eval "$cmd" >"$log_file" 2>&1 &
  local pid=$!
  
  # Tail the log file in real-time and parse the progress
  tail -n +1 -f "$log_file" --pid=$pid 2>/dev/null | parse_progress "$name"
  
  # Wait for command completion
  wait $pid
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

# Fast tests (all < 60 seconds)
run_test "unit" "run_unit"
run_test "generators" "run_generators"
run_test "contracts" "run_contracts"
run_test "invariants" "run_invariants"
run_test "suites" "run_suites"
run_test "reference-validation" "run_reference_validation"

echo ""
echo "========================================"
echo "FAST TEST SUITE COMPLETE"
echo "  Targets: $TARGETS_COMPLETED completed"
echo "  Passed: $TARGETS_PASSED"
echo "  Failed: $((TARGETS_COMPLETED - TARGETS_PASSED))"
echo "  Duration: < 90 seconds (target)"
if [ $FAILURES -gt 0 ]; then
  echo "  Exit code: 1 (failures detected)"
  exit 1
else
  echo "  Exit code: 0 (all passed)"
  exit 0
fi
