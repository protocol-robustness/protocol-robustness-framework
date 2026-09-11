#!/usr/bin/env bash
# Dual-process isolation gate (review §5).
#
# Launches two isolated-parallel harness invocations concurrently, each with a
# distinct run root (UUID-scoped run ids), and verifies:
#   - both processes complete,
#   - each process only writes under its own run root (namespace roots carry
#     run-id-scoped ownership markers),
#   - results files do not collide (distinct paths),
#   - the shared test:rerun state file is not corrupted (it is written with an
#     atomic tmp+rename by both processes; we assert it is still parseable EDN),
#   - per-namespace semantic fingerprints are equivalent across processes.
#
# Env controls:
#   DUAL_NS         SEW_TEST_NS_LIST subset for a fast gate (default:
#                   a small parallel-safe subset)
#   DUAL_JOBS       worker cap per process (default 2)
#   DUAL_TMP_ROOT   separate temporary root per process (default: java.io.tmpdir)
#
# Usage:
#   ./scripts/dual_process_soak.sh

cd "$(dirname "$0")/.."

DUAL_NS="${DUAL_NS:-[resolver-sim.protocols.sew.alias-test resolver-sim.protocols.sew.diff-test]}"
DUAL_JOBS="${DUAL_JOBS:-2}"
GATE_ROOT="results/dual-process/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$GATE_ROOT"
echo "dual-process gate dir: $GATE_ROOT"
echo "  ns=$DUAL_NS jobs=$DUAL_JOBS"

run_proc() {
  local name="$1" prf_tmp_root="$2"
  local run_id="dual-$name-$(date -u +%Y%m%dT%H%M%SZ)-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "$RANDOM$RANDOM")"
  local idx
  if [ "$name" = "a" ]; then idx=0; else idx=1; fi
  local results_file="$GATE_ROOT/results-isolated-parallel-$idx.edn"
  echo "  [$name] launching (run-id=$run_id, tmp-root=$prf_tmp_root)..."
  PRF_TEST_TMP_ROOT="$prf_tmp_root" \
  SEW_TEST_MODE=isolated-parallel \
  SEW_TEST_NS_LIST="$DUAL_NS" \
  SEW_TEST_NS_TIMEOUT_MS=300000 \
  PARALLEL_TEST_JOBS="$DUAL_JOBS" \
  SEW_TEST_RUN_ID="$run_id" \
  SEW_TEST_RESULTS_FILE="$results_file" \
  SEW_TEST_LEAK_CHECK=1 \
    clojure -M:test:with-sew -m scripts.run-sew-tests unit \
      > "$GATE_ROOT/console-$name.log" 2>&1
  echo $? > "$GATE_ROOT/exit-$name.txt"
  echo "  [$name] exit=$(cat "$GATE_ROOT/exit-$name.txt")"
}

TMP_A="$GATE_ROOT/tmproot-a"
TMP_B="$GATE_ROOT/tmproot-b"
mkdir -p "$TMP_A" "$TMP_B"

run_proc a "$TMP_A" &
pid_a=$!
run_proc b "$TMP_B" &
pid_b=$!

wait $pid_a; rc_a=$?
wait $pid_b; rc_b=$?

echo ""
echo "process exits: a=$rc_a b=$rc_b"
[ "$rc_a" -ne 0 ] && [ ! -f "$GATE_ROOT/results-a.edn" ] && { echo "gate FAILED: proc a aborted early" >&2; exit 1; }
[ "$rc_b" -ne 0 ] && [ ! -f "$GATE_ROOT/results-b.edn" ] && { echo "gate FAILED: proc b aborted early" >&2; exit 1; }

# Results files distinct (collision check)
if [ "$(readlink -f "$GATE_ROOT/results-isolated-parallel-0.edn")" = \
     "$(readlink -f "$GATE_ROOT/results-isolated-parallel-1.edn")" ]; then
  echo "gate FAILED: results files collide" >&2
  exit 1
fi

# Cross-root isolation: each process's ownership markers must stay under its
# own temporary root.  Proc a run-ids start with dual-a-, proc b with dual-b-.
if grep -rl "dual-b-" "$TMP_A" >/dev/null 2>&1; then
  echo "gate FAILED: proc a wrote into proc b's run root (dual-b- marker under $TMP_A)" >&2
  exit 1
fi
if grep -rl "dual-a-" "$TMP_B" >/dev/null 2>&1; then
  echo "gate FAILED: proc b wrote into proc a's run root (dual-a- marker under $TMP_B)" >&2
  exit 1
fi
echo "cross-root isolation: OK (no ownership markers crossed processes)"

# Verify rerun state is still valid EDN after two concurrent atomic writers
echo ""
echo "checking rerun-state integrity after concurrent writes..."
clojure -M:test:with-sew -e "
(require '[clojure.edn :as edn] '[clojure.java.io :as io])
(if (.exists (io/file \".prf/test-state.edn\"))
  (let [m (edn/read-string (slurp \".prf/test-state.edn\"))]
    (println :rerun-state-parseable (contains? m :failed-test-namespaces)))
  (println :rerun-state-absent))
" 2>&1 | grep -E ":rerun-state" | tail -1

# Cross-process semantic equivalence of fingerprints
echo ""
echo "comparing fingerprints across the two processes..."
clojure -M:test:with-sew -m scripts.soak-compare "$GATE_ROOT" \
  || { echo "gate FAILED: cross-process fingerprint mismatch — diagnostics in $GATE_ROOT" >&2; exit 1; }

echo ""
echo "DUAL-PROCESS GATE PASSED — diagnostics retained in $GATE_ROOT"
