#!/usr/bin/env bash
# Run the pinned PRF JAR allocation commands.
# Verifies the JAR SHA-256 against artifact-lock.json before invoking.
set -euo pipefail

DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${PRF_JAR:-$DEMO_ROOT/target/prf-runner-sew-0.1.0-uber.jar}"
LOCK="$DEMO_ROOT/prf-runner/artifact-lock.json"

expected_sha="$(python3 -c "import json;print(json.load(open('$LOCK'))['artifact_sha256'])")"
actual_sha="$(sha256sum "$JAR" | cut -d' ' -f1)"

if [[ "$expected_sha" != "$actual_sha" ]]; then
  echo "PRF JAR SHA-256 mismatch: expected $expected_sha, got $actual_sha" >&2
  exit 3
fi

exec java -cp "$JAR" clojure.main -m resolver-sim.cli.main "$@"
