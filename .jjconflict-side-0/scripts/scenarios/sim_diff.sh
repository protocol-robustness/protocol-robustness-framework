#!/bin/bash
set -euo pipefail

# Usage: ./scripts/sim_diff.sh --baseline <commit-hash> --scenario <path> [--protocol <id>]
# Compare replay at baseline commit vs current HEAD using structural trace_compare.

BASELINE=""
SCENARIO=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --baseline) BASELINE="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --protocol) shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ -z "$BASELINE" || -z "$SCENARIO" ]]; then
  echo "Usage: ./scripts/sim_diff.sh --baseline <commit-hash> --scenario <path> [--protocol <id> (ignored)]"
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

TEMP_DIR="$(mktemp -d)"
mkdir -p results

echo "Checking out baseline $BASELINE to $TEMP_DIR..."
git worktree add -d "$TEMP_DIR" "$BASELINE"

cleanup() {
  echo "Cleaning up worktree..."
  git worktree remove "$TEMP_DIR" || true
}
trap cleanup EXIT

echo "Running baseline replay at $BASELINE..."
mkdir -p "$TEMP_DIR/results"
python3 "$REPO_ROOT/scripts/replay_once.py" "$SCENARIO" "$TEMP_DIR/results/baseline.json" "$TEMP_DIR"

echo "Running candidate replay at HEAD..."
python3 "$REPO_ROOT/scripts/replay_once.py" "$SCENARIO" "results/candidate.json" "$REPO_ROOT"

echo "Generating comparison report..."
python3 "$REPO_ROOT/scripts/trace_compare.py" \
  --baseline "$TEMP_DIR/results/baseline.json" \
  --candidate results/candidate.json \
  --out-dir results/sim-diff

echo ""
cat results/sim-diff/comparison.md
