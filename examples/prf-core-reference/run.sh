#!/usr/bin/env bash
# Execute this reference using the built core distribution only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
JAR="$REPO_ROOT/target/prf.jar"
ACTUAL="$ROOT/actual"

if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR. Build it with: clojure -T:build uberjar :variant prf" >&2
  exit 2
fi

rm -rf "$ACTUAL"
mkdir -p "$ACTUAL"
run_adapter() { java -cp "$JAR" clojure.main -m resolver-sim.reference.bounded-transfer "$@"; }

run_adapter package "$ROOT/scenarios/bounded-transfer-pass.edn" "$ACTUAL/pass.package.edn"
run_adapter package "$ROOT/scenarios/bounded-transfer-fail.edn" "$ACTUAL/fail.package.edn"
run_adapter verify "$ACTUAL/pass.package.edn"
run_adapter verify "$ACTUAL/fail.package.edn"

diff -u "$ROOT/expected/pass.package.edn" "$ACTUAL/pass.package.edn"
diff -u "$ROOT/expected/fail.package.edn" "$ACTUAL/fail.package.edn"
echo "PASS prf-core-reference: accepted and semantic-failure packages verified with target/prf.jar only"
