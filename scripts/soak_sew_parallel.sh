#!/usr/bin/env bash
# Soak comparison for the run-sew-tests execution modes (review §9/§10).
#
# Runs:
#   shared-sequential    × SOAK_S (default 2)
#   isolated-sequential  × SOAK_F (default 3)
#   isolated-parallel    × SOAK_P (default 8, one scheduling seed each)
# then compares normalized semantic fingerprints:
#   - each mode must be internally consistent across its runs
#   - isolated-parallel must equal isolated-sequential
#   - shared-sequential vs isolated-sequential is reported as a coupling flag
#     (warn, not gate)
#
# Env controls:
#   SOAK_S, SOAK_F, SOAK_P   run counts
#   SOAK_JOBS                parallel worker cap (default 4)
#   SOAK_SEEDS               whitespace-separated seeds for parallel runs
#   SOAK_NS                  optional SEW_TEST_NS_LIST subset (e.g.
#                            '[a-test b-test]') for fast soak smoke runs
#
# All diagnostics (results files, console logs, exit codes, metadata) are
# retained in results/soak/<timestamp>/ whether the soak passes or fails.
#
# Usage:
#   ./scripts/soak_sew_parallel.sh

cd "$(dirname "$0")/.."

SOAK_S="${SOAK_S:-2}"
SOAK_F="${SOAK_F:-3}"
SOAK_P="${SOAK_P:-8}"
SOAK_JOBS="${SOAK_JOBS:-4}"
SEEDS="${SOAK_SEEDS:-7 11 13 17 23 31 41 43}"

RUN_ROOT="results/soak/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RUN_ROOT"
echo "soak run dir: $RUN_ROOT"
echo "  S=$SOAK_S F=$SOAK_F P=$SOAK_P jobs=$SOAK_JOBS seeds=$SEEDS"
if [ -n "${SOAK_NS:-}" ]; then
  echo "  namespace subset: $SOAK_NS"
fi

run_one() {
  local mode="$1" idx="$2" seed="$3"
  local base="results-$mode-$idx.edn"
  printf '{:mode "%s" :seed %s :started "%s" :jobs "%s"}\n' \
    "$mode" "${seed:-nil}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$SOAK_JOBS" \
    > "$RUN_ROOT/meta-$mode-$idx.edn"
  echo "  [$mode #$idx seed=${seed:-none}] running..."

  local envs=(SEW_TEST_MODE="$mode"
              SEW_TEST_RESULTS_FILE="$RUN_ROOT/$base"
              SEW_TEST_RUN_ID="soak-$mode-$idx-${seed:-none}"
              SEW_TEST_LEAK_CHECK=1
              PARALLEL_TEST_JOBS="$SOAK_JOBS")
  if [ -n "$seed" ]; then
    envs+=(SEW_TEST_SEED="$seed")
  fi
  if [ -n "${SOAK_NS:-}" ]; then
    envs+=(SEW_TEST_NS_LIST="$SOAK_NS")
  fi

  env "${envs[@]}" clojure -M:test:with-sew -m scripts.run-sew-tests unit \
    > "$RUN_ROOT/console-$mode-$idx.log" 2>&1
  local rc=$?
  echo "$rc" > "$RUN_ROOT/exit-$mode-$idx.txt"
  echo "  [$mode #$idx] exit=$rc results=$RUN_ROOT/$base"
  if [ "$rc" -ne 0 ] && [ ! -f "$RUN_ROOT/$base" ]; then
    echo "  ERROR: run $mode #$idx failed before writing results. Aborting soak." >&2
    echo "  See $RUN_ROOT/console-$mode-$idx.log" >&2
    exit 2
  fi
}

i=0
while [ "$i" -lt "$SOAK_S" ]; do run_one shared-sequential "$i" ""; i=$((i+1)); done
i=0
while [ "$i" -lt "$SOAK_F" ]; do run_one isolated-sequential "$i" ""; i=$((i+1)); done
j=0
for s in $SEEDS; do
  [ "$j" -ge "$SOAK_P" ] && break
  run_one isolated-parallel "$j" "$s"
  j=$((j+1))
done

echo ""
echo "Comparing fingerprints in $RUN_ROOT ..."
clojure -M:test:with-sew -m scripts.soak-compare "$RUN_ROOT"
rc=$?
if [ "$rc" -ne 0 ]; then
  echo "SOAK FAILED — diagnostics retained in $RUN_ROOT" >&2
  exit 1
fi
echo "SOAK PASSED — diagnostics retained in $RUN_ROOT"
