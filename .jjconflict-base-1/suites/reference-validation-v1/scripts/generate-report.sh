#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACTUAL="$ROOT/actual"
EXPECTED="$ROOT/expected"
REPORT="$ROOT/reports/reference-validation-v1.md"
MATRIX="$ROOT/reports/security-evidence-matrix.md"

[[ -f "$ACTUAL/summary.json" ]] || { echo "No actual/summary.json — run the suite first (make reference-validation-v1)"; exit 1; }

PASSED=$(python3 -c "import json; print(json.load(open('$ACTUAL/summary.json'))['passed'])")
FAILED=$(python3 -c "import json; print(json.load(open('$ACTUAL/summary.json'))['failed'])")
TOTAL=$(python3 -c "import json; print(json.load(open('$ACTUAL/summary.json'))['scenario_count'])")

if [[ -f "$EXPECTED/summary.json" ]]; then
  E_PASSED=$(python3 -c "import json; print(json.load(open('$EXPECTED/summary.json'))['passed'])")
else
  E_PASSED="N/A"
fi

cat > "$REPORT" << MD
# Reference Validation Suite v1 Report

## Summary

- Suite: reference-validation-v1
- Version: 1.3.0
- Status: $( [[ "$FAILED" -eq 0 ]] && echo "PASS" || echo "FAIL" )
- Scenarios: $TOTAL
- Passed: $PASSED
- Failed: $FAILED
- Inconclusive: 0
- Expected passed: $E_PASSED
- Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)

## Claims tested

$(python3 -c "
import json
sc = json.load(open('$ACTUAL/scenario-results.json'))
print('| Claim | Scenario | Status |')
print('|---|---|---|')
for r in sc['results']:
    print(f'| {r[\"scenario_id\"]} | {r[\"primary_claim\"]} | {r[\"status\"]} |')
")

## Invariant summary

See \`actual/invariants.json\`.

## Economic checks

See \`actual/economic-results.json\`.

## Limitations

See \`docs/limitations.md\`.

## Reproduction instructions

\`\`\`bash
make clean-reference-validation-v1
make reference-validation-v1
make verify-reference-validation-v1
\`\`\`
MD

python3 -c "
import json
em = json.load(open('$ACTUAL/evidence-matrix.json'))
sc = {r['scenario_id']: r for r in json.load(open('$ACTUAL/scenario-results.json'))['results']}
inv = {i['invariant_id']: i for i in json.load(open('$ACTUAL/invariants.json'))['invariants']}
print('# Security Evidence Matrix')
print()
print('| Claim | Threat | Invariant | Scenario | Status | Artifact |')
print('|---|---|---|---|---|---|')
for c in em['claims']:
    inv_id = c.get('invariants', [''])[0] if c.get('invariants') else ''
    print(f'| {c[\"claim_id\"]} | {c[\"threat\"]} | {inv_id} | {\", \".join(c[\"scenarios\"])} | {c[\"status\"]} | actual/evidence-matrix.json |')
" > "$MATRIX"

echo "Generated reports in $ROOT/reports"
