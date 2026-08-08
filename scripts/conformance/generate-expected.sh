#!/usr/bin/env bash
# Assemble the a-vs-b-plus-c scenario into a kernel input document and generate
# expected-public-values.json through the pinned PRF JAR.
set -euo pipefail

DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCENARIO="$DEMO_ROOT/scenarios/allocation/a-vs-b-plus-c"
PRF_RUNNER="$DEMO_ROOT/prf-runner/run-prf.sh"
OUT="$SCENARIO/expected-public-values.json"

python3 - "$SCENARIO" "$OUT" "$PRF_RUNNER" <<'PYEOF'
import json, sys, subprocess, os

scenario_dir = sys.argv[1]
out_path = sys.argv[2]
prf = sys.argv[3]

scenario = json.load(open(os.path.join(scenario_dir, "scenario.json")))
claimants = json.load(open(os.path.join(scenario_dir, "claimants.json")))
outcomes = json.load(open(os.path.join(scenario_dir, "outcomes.json")))
rates = json.load(open(os.path.join(scenario_dir, "proposed-rates.json")))
policy = json.load(open(os.path.join(scenario_dir, "policy.json")))

input_doc = {
    "allocation-id": scenario["scenario-id"],
    "kernel-version": scenario["kernel-version"],
    "selection-algorithm": scenario["selection-algorithm"],
    "policy": policy,
    "claimants": claimants,
    "outcomes": outcomes,
    "proposed-rates": rates,
    "capacity": scenario["capacity"],
    "total-eligible-weight": scenario["total-eligible-weight"],
    "exact-pro-rata-denominator": scenario["exact-pro-rata-denominator"],
    "authoritative-randomness": scenario["authoritative-randomness"],
}

# Persist the assembled kernel input so the native/SP1 paths share one file.
input_path = os.path.join(scenario_dir, "kernel-input.json")
with open(input_path, "w") as f:
    json.dump(input_doc, f, indent=2, sort_keys=True)
    f.write("\n")
print(f"wrote {input_path}")

result = subprocess.run(
    [prf, "allocation", "verify-proposal"],
    input=json.dumps(input_doc), capture_output=True, text=True)
if result.returncode != 0:
    sys.exit(f"PRF kernel failed: {result.returncode}\n{result.stderr}")

expected = json.loads(result.stdout)
with open(out_path, "w") as f:
    json.dump(expected, f, indent=2, sort_keys=True)
    f.write("\n")
print(f"wrote {out_path}")
PYEOF
