#!/usr/bin/env bash
# Review gate — run yield tests, then canonical or stdout-only backstop.
# Usage: ./scripts/review/backstop.sh          (default gate, stdout only)
#        ./scripts/review/backstop.sh fast    (fast gate, stdout only)
#        ./scripts/review/backstop.sh full    (authoritative gate with evidence)
#
# backstop         — routine code-quality and invariant gate; no bundle
# backstop:full    — authoritative gate; produces and verifies canonical evidence

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

# Default gate: stdout-only CLI backstop (no canonical bundle)
if [ "$MODE" = "default" ]; then
  echo "▶ backstop: CLI default gate (stdout only)"
  clojure -M:cli/sew backstop || FAILED=$((FAILED + 1))
fi

# Full gate: authoritative canonical evidence run
if [ "$MODE" = "full" ]; then
  RUN_ROOT="${PRF_INVARIANTS_RUN_ROOT:-artf/runs/invariants/$(date -u +%Y%m%dT%H%M%SZ)}"

  echo "▶ backstop: canonical invariants run"
  echo "  run-root: $RUN_ROOT"
  if clojure -M:cli/sew invariants run --run-root "$RUN_ROOT"; then
    echo "  ✓ invariants run completed"
  else
    echo "  ✗ invariants run failed"
    FAILED=$((FAILED + 1))
  fi

  echo "▶ backstop: verify run"
  if clojure -M:cli/sew verify-run --run-root "$RUN_ROOT"; then
    echo "  ✓ verify-run passed"
  else
    echo "  ✗ verify-run failed"
    FAILED=$((FAILED + 1))
  fi

  # Also run the additional full-gate checks
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
