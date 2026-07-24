#!/usr/bin/env bash
# Compact external-review acceptance gate for the canonical Sew JAR path.
#
# Usage:
#   bb build:sew
#   bash scripts/ef-review-acceptance.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$PROJECT_DIR/target/prf-runner-sew-0.1.0-uber.jar"
WORK="$(mktemp -d)"
CWD="$WORK/external-cwd"
SCENARIO_ROOT="$WORK/scenario"
BENCHMARK_ROOT="$WORK/benchmark"
MISSING_ROOT="$WORK/missing-evidence"
UNDECLARED_ROOT="$WORK/undeclared-evidence"

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

expect_fail() {
  if "$@"; then
    echo "FAIL: command unexpectedly succeeded: $*" >&2
    exit 1
  fi
}

require_absent() {
  if [ -e "$1" ]; then
    echo "FAIL: undeclared external-CWD output exists: $1" >&2
    find "$1" -maxdepth 3 -print >&2 || true
    exit 1
  fi
}

run_scenario() {
  java -jar "$JAR" run-scenario \
    classpath:scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
    --run-root "$1"
}

verify_scenario() {
  java -jar "$JAR" verify-scenario --run-root "$1"
}

if [ ! -f "$JAR" ]; then
  echo "Missing Sew JAR: $JAR. Run bb build:sew first." >&2
  exit 2
fi

mkdir -p "$CWD"
cd "$CWD"

echo "=== EF canonical review acceptance ==="

run_scenario "$SCENARIO_ROOT"
verify_scenario "$SCENARIO_ROOT"

a=$(python3 -c 'import json, os; root="'"$SCENARIO_ROOT"'"; x=json.load(open(root+"/manifest/diagnostic-summary.json")); assert x["scenario_id"]; refs=x["evidence"]; assert all(os.path.isfile(os.path.join(root, refs[k])) for k in ["trace_ref", "metrics_ref", "run_finalization_ref"]); print("scenario diagnostic references resolve")')
echo "$a"

java -jar "$JAR" run-benchmark force-authorisation-custody-v1 --run-root "$BENCHMARK_ROOT"
java -jar "$JAR" verify-benchmark --run-root "$BENCHMARK_ROOT"
python3 -c 'import json; x=json.load(open("'"$BENCHMARK_ROOT"'/completion.json")); assert x["lifecycle_status"] == "completed"; assert x["semantic_status"] in {"pass", "fail", "inconclusive"}; print("benchmark lifecycle and conclusion are distinct")'

# Completed roots are immutable by default.
expect_fail run_scenario "$SCENARIO_ROOT"

# Removing a registered artifact must fail independent scenario verification.
cp -a "$SCENARIO_ROOT" "$MISSING_ROOT"
rm "$MISSING_ROOT/manifest/diagnostic-summary.json"
expect_fail verify_scenario "$MISSING_ROOT"

# A forensic evidence file outside the committed evidence set must not be accepted.
cp -a "$SCENARIO_ROOT" "$UNDECLARED_ROOT"
FORENSIC_EVENT_DIR="$(find "$UNDECLARED_ROOT/scenarios" -type d -path "*/forensic/event-evidence" -print -quit)"
test -n "$FORENSIC_EVENT_DIR"
printf '{}' > "$FORENSIC_EVENT_DIR/undeclared-review-artifact.json"
expect_fail verify_scenario "$UNDECLARED_ROOT"

require_absent "$CWD/results"
require_absent "$CWD/prf-runs"
require_absent "$CWD/prf-artifacts"
require_absent "$CWD/target"

if [ -n "$(find "$CWD" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "FAIL: external CWD contains undeclared files" >&2
  find "$CWD" -maxdepth 3 -print >&2
  exit 1
fi

echo "PASS: external EF review path runs and verifies scenario and benchmark bundles"
echo "PASS: completed-root reuse, missing evidence, and undeclared evidence are rejected"
