#!/usr/bin/env bash
# Release acceptance test for both supported distributions.
#
# Builds (if necessary) and invokes each unified CLI JAR from a fresh external
# working directory. The framework-only JAR must not advertise Sew commands;
# the full Sew JAR must create scenario and benchmark artifacts only at their
# declared --run-root locations.
#
# Usage:
#   bash scripts/portability-smoke-test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PRF_JAR_PATH="$PROJECT_DIR/target/prf.jar"
SEW_JAR_PATH="$PROJECT_DIR/target/prf-runner-sew-0.1.0-uber.jar"
TEMP_DIR="$(mktemp -d)"
CWD_DIR="$TEMP_DIR/external-cwd"
SCENARIO_ROOT="$TEMP_DIR/scenario-run"
BENCHMARK_ROOT="$TEMP_DIR/benchmark-run"

cleanup() {
  rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

require_absent() {
  local path="$1"
  if [ -e "$path" ]; then
    echo "FAIL: undeclared output exists: $path" >&2
    find "$path" -maxdepth 3 -print >&2 || true
    exit 1
  fi
}

verify_completion_hashes() {
  local root="$1"
  local completion="$root/completion.json"
  local registry_ref validation_ref registry_hash validation_hash
  registry_ref="$(sed -n 's/.*"artifact_registry_ref":"\([^"]*\)".*/\1/p' "$completion" | sed 's#\\/#/#g')"
  validation_ref="$(sed -n 's/.*"registry_validation_ref":"\([^"]*\)".*/\1/p' "$completion" | sed 's#\\/#/#g')"
  registry_hash="$(sed -n 's/.*"artifact_registry_sha256":"sha256:\([0-9a-f]*\)".*/\1/p' "$completion")"
  validation_hash="$(sed -n 's/.*"registry_validation_sha256":"sha256:\([0-9a-f]*\)".*/\1/p' "$completion")"

  test -n "$registry_ref"
  test -n "$validation_ref"
  test -n "$registry_hash"
  test -n "$validation_hash"
  test "$registry_hash" = "$(sha256sum "$root/$registry_ref" | awk '{print $1}')"
  test "$validation_hash" = "$(sha256sum "$root/$validation_ref" | awk '{print $1}')"
}

if [ ! -f "$PRF_JAR_PATH" ]; then
  echo "Building framework-only JAR..."
  (cd "$PROJECT_DIR" && clojure -T:build uberjar :variant prf)
fi
if [ ! -f "$SEW_JAR_PATH" ]; then
  echo "Building Sew uberjar..."
  (cd "$PROJECT_DIR" && clojure -T:build uberjar :variant sew)
fi

mkdir -p "$CWD_DIR"

echo "=== Supported JAR release acceptance ==="
echo "PRF JAR: $PRF_JAR_PATH"
echo "Sew JAR: $SEW_JAR_PATH"
echo "External CWD: $CWD_DIR"

(
  cd "$CWD_DIR"
  java -jar "$PRF_JAR_PATH" help > "$TEMP_DIR/prf-help.txt"
  grep -q "PRF CLI" "$TEMP_DIR/prf-help.txt"
  if grep -q "run-scenario\|run-benchmark" "$TEMP_DIR/prf-help.txt"; then
    echo "FAIL: framework-only JAR advertises Sew commands" >&2
    exit 1
  fi

  java -jar "$SEW_JAR_PATH" help > "$TEMP_DIR/sew-help.txt"
  grep -q "run-scenario" "$TEMP_DIR/sew-help.txt"
  grep -q "run-benchmark" "$TEMP_DIR/sew-help.txt"
  java -jar "$SEW_JAR_PATH" \
    run-scenario classpath:scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
    --run-root "$SCENARIO_ROOT"
  java -jar "$SEW_JAR_PATH" \
    verify-scenario --run-root "$SCENARIO_ROOT"
  java -jar "$SEW_JAR_PATH" \
    run-benchmark sew/sew-force-authorisation-custody-v1 \
    --run-root "$BENCHMARK_ROOT"
  java -jar "$SEW_JAR_PATH" \
    verify-benchmark --run-root "$BENCHMARK_ROOT"
)

test -f "$SCENARIO_ROOT/completion.json"
test -f "$BENCHMARK_ROOT/completion.json"
verify_completion_hashes "$SCENARIO_ROOT"
verify_completion_hashes "$BENCHMARK_ROOT"

require_absent "$CWD_DIR/results"
require_absent "$CWD_DIR/prf-runs"
require_absent "$CWD_DIR/prf-artifacts"
require_absent "$CWD_DIR/target"

# The external CWD is intentionally empty: all execution artifacts belong to
# one of the explicit canonical roots above.
if [ -n "$(find "$CWD_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "FAIL: external CWD contains undeclared files" >&2
  find "$CWD_DIR" -maxdepth 3 -print >&2
  exit 1
fi

echo "PASS: framework-only JAR has the unified CLI and does not advertise Sew commands"
echo "PASS: full Sew JAR runs bundled scenario and benchmark without CWD scatter"
echo "PASS: completion records commit to final registry and validation report hashes"
echo "PASS: built Sew JAR verifies completed scenario evidence-chain and benchmark assurance bundles"
