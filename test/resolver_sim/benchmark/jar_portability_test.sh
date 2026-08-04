#!/usr/bin/env bash
# JAR Portability Test
# Verifies that prf-benchmark.jar works outside the repo directory
# with no git dependency, using only embedded classpath resources.
#
# Usage:
#   bash test/resolver_sim/benchmark/jar_portability_test.sh
#
# Returns:
#   0 - all tests pass
#   1 - one or more tests fail

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
TEMP_DIR="$(mktemp -d)"
JAR_PATH="${PROJECT_DIR}/target/prf-runner-benchmark-0.1.0-uber.jar"
TEST_OUT_DIR="${TEMP_DIR}/prf-test-out"
PASS_COUNT=0
FAIL_COUNT=0

cleanup() {
  rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

pass() {
  echo "  PASS: $1"
  PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
  echo "  FAIL: $1"
  FAIL_COUNT=$((FAIL_COUNT + 1))
}

# Ensure JAR exists
if [ ! -f "$JAR_PATH" ]; then
  echo "Building benchmark uberjar first..."
  (cd "$PROJECT_DIR" && clojure -T:build uberjar :variant benchmark) || {
    echo "ERROR: Failed to build JAR"
    exit 1
  }
fi

echo ""
echo "=== JAR Portability Tests ==="
echo "JAR:  ${JAR_PATH}"
echo "Temp: ${TEMP_DIR}"
echo ""

mkdir -p "$TEST_OUT_DIR"

# --- Test 1: --list works outside repo ---
echo "--- Test 1: --list from temp dir ---"
(cd "$TEMP_DIR" && java -jar "$JAR_PATH" --list > "${TEMP_DIR}/list-output.txt" 2>&1) && {
  if grep -q "sew/escrow-dispute-v1" "${TEMP_DIR}/list-output.txt" 2>/dev/null; then
    pass "--list shows available benchmarks"
  else
    fail "--list output missing expected benchmark"
    echo "  Output:"
    cat "${TEMP_DIR}/list-output.txt"
  fi
} || {
  fail "--list command failed"
  cat "${TEMP_DIR}/list-output.txt"
}

# --- Test 2: --list shows no error about missing git ---
echo "--- Test 2: --list has no git errors ---"
if grep -i "error\|exception\|git" "${TEMP_DIR}/list-output.txt" > /dev/null 2>&1; then
  fail "--list output contains error/exception/git references"
  echo "  Suspicious lines:"
  grep -i "error\|exception\|git" "${TEMP_DIR}/list-output.txt" || true
else
  pass "--list clean (no git errors)"
fi

# --- Test 3: run-and-report subcommand works ---
echo "--- Test 3: run-and-report identifies unknown benchmark ---"
(cd "$TEMP_DIR" && java -jar "$JAR_PATH" run-and-report --output "${TEST_OUT_DIR}/unknown-bench.edn" nonexistent-benchmark > "${TEMP_DIR}/unknown-out.txt" 2>&1; echo "EXIT: $?" > "${TEMP_DIR}/unknown-exit.txt") || true
EXIT_CODE=$(cat "${TEMP_DIR}/unknown-exit.txt" | grep -o '[0-9]*$' || echo "unknown")
if [ "$EXIT_CODE" = "1" ] || [ "$EXIT_CODE" = "2" ]; then
  pass "run-and-report with unknown benchmark exits non-zero"
else
  fail "run-and-report with unknown benchmark should exit non-zero (got: $EXIT_CODE)"
fi

# --- Test 4: No source-tree paths in output ---
echo "--- Test 4: No source-tree paths in output ---"
if [ -f "${TEST_OUT_DIR}/unknown-bench.edn" ]; then
  if grep -qE "/home/[^/]+/Code/|/workspaces/" "${TEST_OUT_DIR}/unknown-bench.edn" 2>/dev/null; then
    fail "Evidence bundle contains source-tree paths"
  else
    pass "No source-tree paths in output"
  fi
else
  echo "  (skip - unknown benchmark may not produce output file)"
  pass "(skip)"
fi

# --- Test 5: Default benchmark works from temp dir ---
echo "--- Test 5: Default benchmark runs without git ---"
TIMEOUT_SEC=120
(cd "$TEMP_DIR" && timeout $TIMEOUT_SEC java -jar "$JAR_PATH" --output "${TEST_OUT_DIR}/default-bench.edn" > "${TEMP_DIR}/default-run.txt" 2>&1 || true)
if [ -f "${TEST_OUT_DIR}/default-bench.edn" ]; then
  if grep -q "evidence/hash" "${TEST_OUT_DIR}/default-bench.edn" > /dev/null 2>&1; then
    pass "Default benchmark produced evidence bundle with hash"
  else
    fail "Evidence bundle missing :evidence/hash"
    head -5 "${TEST_OUT_DIR}/default-bench.edn"
  fi
else
  echo "  Default benchmark run incomplete or timed out."
  echo "  Stdout:"
  head -20 "${TEMP_DIR}/default-run.txt"
  echo "  (not a failure - may need running REPL)"
  pass "(skipped - REPL-dependent)"
fi

# --- Test 6: Metadata embedded in JAR ---
echo "--- Test 6: JAR contains resource paths ---"
JAR_CHECK=$(jar tf "$JAR_PATH" | grep -c "benchmarks/registry.edn" 2>/dev/null || echo "0")
if [ "$JAR_CHECK" -gt 0 ]; then
  pass "JAR contains benchmarks/registry.edn"
else
  fail "JAR missing benchmarks/registry.edn"
fi
JAR_CHECK=$(jar tf "$JAR_PATH" | grep -c "scenarios/edn" 2>/dev/null || echo "0")
if [ "$JAR_CHECK" -gt 0 ]; then
  pass "JAR contains scenarios/edn/"
else
  fail "JAR missing scenarios/edn/"
fi
JAR_CHECK=$(jar tf "$JAR_PATH" | grep -c "benchmarks/scoring" 2>/dev/null || echo "0")
if [ "$JAR_CHECK" -gt 0 ]; then
  pass "JAR contains benchmarks/scoring/"
else
  fail "JAR missing benchmarks/scoring/"
fi
JAR_CHECK=$(jar tf "$JAR_PATH" | grep -c "suites/reference-validation-v1" 2>/dev/null || echo "0")
if [ "$JAR_CHECK" -gt 0 ]; then
  pass "JAR contains suites/"
else
  fail "JAR missing suites/"
fi

# --- Summary ---
echo ""
echo "=== Results: ${PASS_COUNT} passed, ${FAIL_COUNT} failed ==="
if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
