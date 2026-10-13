#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACTUAL="$ROOT/actual"
REPORT="$ROOT/reports/summary.md"

mkdir -p "$ROOT/reports"

cat > "$REPORT" <<EOF
# Sew Domain Reference Suite — Summary

Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")

## Results

$(python3 -c "
import json
s = json.load(open('$ACTUAL/summary.json'))
print(f'- **Status**: {s[\"status\"]}')
print(f'- **Scenarios**: {s[\"scenario_count\"]}')
print(f'- **Passed**: {s[\"passed\"]}')
print(f'- **Failed**: {s[\"failed\"]}')
print(f'- **Version**: {s[\"suite_version\"]}')
")

## Per-Scenario Detail

$(python3 -c "
import json
sr = json.load(open('$ACTUAL/scenario-results.json'))
for r in sr['results']:
    print(f'- **{r[\"scenario_id\"]}**: status={r[\"status\"]}, '
          f'expectations_failed={r[\"expectations_failed\"]}, '
          f'invariants_failed={r[\"invariants_failed\"]}')
")
EOF

echo "Report written to $REPORT"
