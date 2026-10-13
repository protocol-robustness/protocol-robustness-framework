#!/usr/bin/env bash
# Measure wall-clock + peak memory for run-sew-tests modes over the full unit
# manifest.  Run with no other test jobs active for meaningful numbers.
#
# Usage:
#   ./scripts/measure_sew_perf.sh [runs-per-mode]
#
# Writes per-mode timing lines into results/soak/perf-<mode>.txt (appended)
# and prints a summary.  Uses /usr/bin/time if present.

cd "$(dirname "$0")/.."

RUNS="${1:-3}"
JOBS="${PARALLEL_TEST_JOBS:-4}"

which /usr/bin/time >/dev/null 2>&1 || { echo "ERROR: /usr/bin/time not found"; exit 1; }

measure() {
  local mode="$1" runs="$2"
  echo "=== $mode x $runs (jobs=$JOBS) ==="
  local outfile="results/soak/perf-$mode.txt"
  mkdir -p results/soak
  : > "$outfile"
  for i in $(seq 1 "$runs"); do
    /usr/bin/time -v \
      env SEW_TEST_MODE="$mode" PARALLEL_TEST_JOBS="$JOBS" \
      clojure -M:test:with-sew -m scripts.run-sew-tests unit \
      > /dev/null 2> "$outfile.time"
    local wall mem
    wall=$(grep -m1 "Elapsed (wall clock) time" "$outfile.time" | sed -E 's/.*time \(h:mm:ss or m:ss\): //')
    mem=$(grep -m1 "Maximum resident set size" "$outfile.time" | grep -oE "[0-9]+")
    echo "$mode run#$i wall=$wall peakRSS_MB=$((mem/1024))" | tee -a "$outfile"
    rm -f "$outfile.time"
  done
}

measure shared-sequential "$RUNS"
measure isolated-parallel "$RUNS"

echo ""
echo "perf lines written to results/soak/perf-*.txt"
