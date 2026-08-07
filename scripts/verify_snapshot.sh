#!/usr/bin/env bash
#
# verify_snapshot.sh — run the test suite with source-root provenance.
#
# Why: the working tree can be mutated by a background process (jj/editor
# snapshots) while the JVM loads files, producing flaky "failures" that are
# really file-read races. A green run against a mutating tree does not tell you
# which source tree the results describe.
#
# This script (minimum contract):
#   1. computes the source tree's content root (source-root) BEFORE running
#   2. runs each test namespace in its own JVM (the project's test-isolation
#      model — one JVM across namespaces leaks evidence/attestation registries
#      and produces order-dependent results)
#   3. recomputes the source-root AFTER running
#      - if it changed: verification status = :invalid-environment (exit 2),
#        NOT success and NOT silently retried
#   4. prints: source-root <X> → N tests → M assertions → F failures / E errors
#
# Why not a pure immutable copy: the Sew tests commit the VCS identity (from
# .jj) into their evidence chain, so a snapshot without a live .jj produces
# spurious invariant violations. The before/after root check still guarantees
# the reported source tree is the exact tree evaluated and that it did not
# change during the run.
#
# Usage:
#   scripts/verify_snapshot.sh [namespace ...]
#   (defaults to the yield + Sew verification set)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

source-root() {
  (cd "$1" && find src test protocols_src -type f \( -name '*.clj' -o -name '*.cljc' \) \
     | sort | xargs sha256sum | sha256sum | awk '{print $1}')
}

NS="${*:-resolver-sim.yield.liquid-lending-v2-test
      resolver-sim.yield.invariants-test
      resolver-sim.protocols.yield-test
      resolver-sim.yield.accounting-test
      resolver-sim.yield.accrual-test
      resolver-sim.yield.invariants-hardening-test
      resolver-sim.yield.partial-fill-test
      resolver-sim.yield.ops-liveness-test
      resolver-sim.yield.pro-rata-accounting-test
      resolver-sim.yield.lineage-conservation-test
      resolver-sim.yield.deferred-class-test
      resolver-sim.protocols.sew.accounting-test
      resolver-sim.protocols.sew.lifecycle-test
      resolver-sim.protocols.sew.replay-test
      resolver-sim.protocols.sew.resolver-yield-accrual-test
      resolver-sim.protocols.sew.yield.finalize-parity-test
      resolver-sim.protocols.sew.yield-solvency-test}"

BEFORE="$(source-root "$ROOT")"

TOTAL_T=0; TOTAL_P=0; TOTAL_F=0; TOTAL_E=0
for N in $NS; do
  OUT="$(cd "$ROOT" && timeout 1200 clojure -M:test \
        -e "(require '$N) (let [r (clojure.test/run-tests '$N)] (println :summary (select-keys r [:test :pass :fail :error])))" \
        2>&1 | grep ':summary' | tail -1 || true)"
  TEST="$(echo "$OUT" | grep -o ':test [0-9]*' | awk '{print $2}')"
  PASS="$(echo "$OUT" | grep -o ':pass [0-9]*' | awk '{print $2}')"
  FAIL="$(echo "$OUT" | grep -o ':fail [0-9]*' | awk '{print $2}')"
  ERROR="$(echo "$OUT" | grep -o ':error [0-9]*' | awk '{print $2}')"
  TEST="${TEST:-0}"; PASS="${PASS:-0}"; FAIL="${FAIL:-0}"; ERROR="${ERROR:-0}"
  TOTAL_T=$((TOTAL_T + TEST)); TOTAL_P=$((TOTAL_P + PASS))
  TOTAL_F=$((TOTAL_F + FAIL)); TOTAL_E=$((TOTAL_E + ERROR))
  printf '  %-70s %4d tests  %4d pass  %3d fail  %3d err\n' "$N" "$TEST" "$PASS" "$FAIL" "$ERROR"
done

AFTER="$(source-root "$ROOT")"
if [ "$BEFORE" != "$AFTER" ]; then
  echo "verification status: :invalid-environment (source tree mutated during run)"
  echo "  before: $BEFORE"
  echo "  after:  $AFTER"
  exit 2
fi

echo "source-root $BEFORE"
echo "  → $TOTAL_T tests → $TOTAL_P assertions → $((TOTAL_F + TOTAL_E)) failures/errors"
if [ "$TOTAL_F" != "0" ] || [ "$TOTAL_E" != "0" ]; then
  echo "verification status: :fail"
  exit 1
fi
echo "verification status: :pass"
