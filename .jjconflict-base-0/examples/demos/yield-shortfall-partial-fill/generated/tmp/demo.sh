#!/usr/bin/env bash
set +e

# Terminal dimensions for stable recording
stty cols 120 rows 36 2>/dev/null

demo_exit=0


# ── intro ──
clear
echo
echo "  ╔══════════════════════════════════════════════╗"
echo "  ║                                              ║"
echo "  ║  Yield Shortfall: Partial Withdrawal during Liquidity Shortfall  ║"
echo "  ║                                              ║"
echo "  ╚══════════════════════════════════════════════╝"
echo
echo "A reproducible protocol simulation demonstrating shortfall behavior."
sleep 4.0

# ── setup ──
clear
echo
echo "── Scenario fixture ──"
echo "Two depositors (OwnerA, OwnerB) deposit into a shared yield module.
Governance sets a 50% liquidity shortfall. OwnerA withdraws during shortfall."
sleep 2.0
echo
printf '$ %s\n' 'bb demo:scenario-summary scenarios/Y02_vault-shortfall-partial-withdraw.json'
echo
sleep 2.0
cd '.' 2>/dev/null
(bb demo:scenario-summary scenarios/Y02_vault-shortfall-partial-withdraw.json) > >(tee demos/yield-shortfall-partial-fill/generated/outputs/setup.stdout.txt) 2> >(tee demos/yield-shortfall-partial-fill/generated/outputs/setup.stderr.txt >&2)
exit_code=$?
echo
printf '[exit code: %s]\n' "$exit_code"
if [ "$exit_code" -ne 0 ]; then
  echo "[section setup failed — continuing recording]"
  demo_exit=1
fi
sleep 7.0

# ── run ──
clear
echo
echo "── Run the simulation ──"
echo "The simulator executes the deterministic scenario and emits evidence artifacts."
sleep 2.0
echo
printf '$ %s\n' 'clojure -M:run -- --invariants --protocol yield-v1 --scenario scenarios/Y02_vault-shortfall-partial-withdraw.json --output-file demos/yield-shortfall-partial-fill/generated/replay-output.json'
echo
sleep 2.0
cd '.' 2>/dev/null
(clojure -M:run -- --invariants --protocol yield-v1 --scenario scenarios/Y02_vault-shortfall-partial-withdraw.json --output-file demos/yield-shortfall-partial-fill/generated/replay-output.json) > >(tee demos/yield-shortfall-partial-fill/generated/outputs/run.stdout.txt) 2> >(tee demos/yield-shortfall-partial-fill/generated/outputs/run.stderr.txt >&2)
exit_code=$?
echo
printf '[exit code: %s]\n' "$exit_code"
if [ "$exit_code" -ne 0 ]; then
  echo "[section run failed — continuing recording]"
  demo_exit=1
fi
sleep 5.0

# ── inspect ──
clear
echo
echo "── Inspect outcomes ──"
echo "Check the summary and claimable classification for invariant results and evidence."
sleep 2.0
echo
printf '$ %s\n' 'bb demo:scenario-summary scenarios/Y02_vault-shortfall-partial-withdraw.json'
echo
sleep 2.0
cd '.' 2>/dev/null
(bb demo:scenario-summary scenarios/Y02_vault-shortfall-partial-withdraw.json) > >(tee demos/yield-shortfall-partial-fill/generated/outputs/inspect.stdout.txt) 2> >(tee demos/yield-shortfall-partial-fill/generated/outputs/inspect.stderr.txt >&2)
exit_code=$?
echo
printf '[exit code: %s]\n' "$exit_code"
if [ "$exit_code" -ne 0 ]; then
  echo "[section inspect failed — continuing recording]"
  demo_exit=1
fi
sleep 8.0

# ── reproduce ──
echo
echo "═══════════════════════════════════════════════"
echo "  Demo complete. Reproduce:"
echo "    bb demo:record yield-shortfall-partial-fill"
echo "═══════════════════════════════════════════════"
echo
sleep 6.0