#!/usr/bin/env bash
# Lint: flag inline #{:released :refunded :resolved} literals in src/ that
# should reference t/terminal-states instead.
#
# Excludes:
#   types.clj — authoritative (def terminal-states ...) + escrow-states enum
#   state_machine.clj:43 — allowed-transitions graph (structural, not terminal-state concept)
#   invariants.cli:249 — persisted-escrow-states (includes :pending :disputed, different set)

set -o pipefail

matches=$(grep -rn '#{:released :refunded :resolved}' src/ --include='*.clj' \
  | grep -v 'types.clj' \
  | grep -v 'state_machine.clj:43' \
  | grep -v 'invariants.clj:249' \
  || true)

if [ -z "$matches" ]; then
  echo "terminal-states lint: PASS"
  exit 0
else
  echo "terminal-states lint: FAIL — inline terminal-state set literals found:"
  echo "$matches"
  echo ""
  echo "Use t/terminal-states instead of inlining #{:released :refunded :resolved}"
  exit 1
fi
