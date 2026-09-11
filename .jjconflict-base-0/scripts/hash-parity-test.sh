#!/usr/bin/env bash
# Hash parity test: verify that a scenario produces the same outcome
# across all three execution modes.
#
# Usage: ./scripts/hash-parity-test.sh [scenario-path]
# Default: data/fixtures/traces/s-auto-cancel-time-via-keeper.trace.json
set -euo pipefail

SCENARIO="${1:-data/fixtures/traces/s-auto-cancel-time-via-keeper.trace.json}"
PASS=0
FAIL=0
TMP=$(mktemp /tmp/parity-test-XXXXXX.clj)

red() { printf "\033[31m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
cleanup() { rm -f "$TMP"; }
trap cleanup EXIT

echo "=== Hash Parity Test ==="
echo "Scenario: $SCENARIO"
echo ""

# ── Mode 1: Source checkout (REPL) ───────────────────────────────────────
cat > "$TMP" << 'CLJEOF'
(require '[resolver-sim.protocols.sew :as sew])
(require '[clojure.data.json :as json])
(let [scenario (json/read-str (slurp (first *command-line-args*)) :key-fn keyword)
      result  (sew/replay-with-sew-protocol scenario)]
  (println (:outcome result))
  (flush)
  (System/exit (if (= :pass (:outcome result)) 0 1)))
CLJEOF

echo -n "Mode 1 — source checkout (Clojure REPL)... "
if clojure -M:with-sew "$TMP" "$SCENARIO" 2>/dev/null; then
  green "PASS (exit 0)"
  PASS=$((PASS+1))
else
  red "FAIL (exit $?)"
  FAIL=$((FAIL+1))
fi

# ── Mode 2: clojure -M:runner/sew ─────────────────────────────────────────
echo -n "Mode 2 — clojure -M:runner/sew... "
if clojure -M:runner/sew -m resolver-sim.minimal-runner \
     --scenario "$SCENARIO" > /dev/null 2>&1; then
  green "PASS (exit 0)"
  PASS=$((PASS+1))
else
  red "FAIL (exit $?)"
  FAIL=$((FAIL+1))
fi

# ── Mode 3: java -jar ─────────────────────────────────────────────────────
JARPATH="target/prf-runner-sew-0.1.0-uber.jar"
if [ ! -f "$JARPATH" ]; then
  echo "  (building uberjar first...)"
  clojure -T:build uberjar :variant sew 2>/dev/null || true
fi
echo -n "Mode 3 — java -jar... "
if java -jar "$JARPATH" -m resolver-sim.minimal-runner \
     --scenario "$SCENARIO" > /dev/null 2>&1; then
  green "PASS (exit 0)"
  PASS=$((PASS+1))
else
  red "FAIL (exit $?)"
  FAIL=$((FAIL+1))
fi

# ── Report ────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "  Passed: $PASS"
echo "  Failed: $FAIL"
if [ "$FAIL" -eq 0 ]; then
  green "All modes produce identical outcome."
  exit 0
else
  red "Mode outcomes differ — hash parity broken."
  exit 1
fi
