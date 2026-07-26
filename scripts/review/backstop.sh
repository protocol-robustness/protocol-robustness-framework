#!/usr/bin/env bash
# Review gate — run yield tests then the CLI backstop.
# Usage: ./scripts/review/backstop.sh [full]
#        ./scripts/review/backstop.sh fast
#        ./scripts/review/backstop.sh
#
# Each step is independent: failures in one do not block subsequent steps.
# Lock acquisition times out after 120s to avoid hanging on stale locks.

MODE="${1:-default}"
LOCK="scripts/with-test-artifact-lock.sh"
FAILED=0

run() {
  local label="$1"; shift
  echo "▶ backstop: $label"
  if timeout 180 "$LOCK" "$@"; then
    echo "  ✓ $label"
  else
    echo "  ✗ $label (exit code $?)"
    FAILED=$((FAILED + 1))
  fi
}

if [ "$MODE" = "fast" ]; then
  exec clojure -M:cli/sew backstop fast
fi

run "yield unit tests"   ./scripts/test.sh yield
run "yield scenarios"    ./scripts/test.sh yield-scenarios

echo "▶ backstop: CLI default gate"
clojure -M:cli/sew backstop || FAILED=$((FAILED + 1))

if [ "$MODE" = "full" ]; then
  run "notebooks"          bb test:notebooks
  run "forensic Python"    bb test:forensic-python
  run "community tests"    bb test:community
  run "portability smoke"  bash scripts/portability-smoke-test.sh
fi

if [ "$FAILED" -gt 0 ]; then
  echo "BACKSTOP ${MODE} FAILED - ${FAILED} failure(s)"
  exit 1
fi
echo "BACKSTOP ${MODE} PASSED"
