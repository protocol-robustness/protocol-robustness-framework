#!/usr/bin/env bash
# Monte Carlo and long-horizon phase runners.
# Extracted from scripts/test.sh for readability.
# Usage: source scripts/monte_carlo.sh; run_monte_carlo; run_long_horizon

run_monte_carlo() {
  # ──────────────────────────────────────────────────────────────────────────
  # HOW THE TWO SIMULATION ENGINES RELATE
  #
  # Engine 1 — Monte Carlo (stochastic + sim/economic|adversarial|governance/)
  # Engine 2 — Replay / Invariant (contract_model/ + protocols/sew/)
  #
  # This sweep runs representative phases for expected-value/regime checks.
  #
  # The CI gate runs 5 phases (O, P, AA, AD, F).  All are :analytic (closed-form
  # algebraic checks, not protocol-kernel evidence).  See
  # src/resolver_sim/core/phases.clj phase-evidence-tiers for the full registry.
  #
  # Phases NOT CI-gated but still :analytic: AB, AC, AE, AF, AG, AH, AI,
  # T, Y, Z, market-exit, phase-c-dr, phase-e-dr, phase-m-dr.
  # Phases :exploratory (not CI-gated): Q, R, U, V, W, X.
  # ──────────────────────────────────────────────────────────────────────────

  echo "Running Monte Carlo representative sweep (4 domains)..."
  echo ""
  echo "  Phase O  — Economic:    market exit cascade (honest vs malice profitability)"
  echo "  Phase P  — Adversarial: appeals falsification (difficulty/evidence/herding)"
  echo "  Phase AA — Governance:  governance-as-adversary (selective enforcement gaming)"
  echo "  Phase AD — Governance:  bandwidth floor safeguard (AA remediation)"
  echo "  Phase F  — Adversarial: collusion ring deterrence (waterfall slashing)"
  echo ""

  local mc_fail=0

  echo "── Phase O: Market Exit Cascade ──────────────────────────────────────────"
  clojure -M:run:with-sew -- -O -p data/params/phase-o-baseline.edn || mc_fail=$((mc_fail + 1))
  echo ""

  echo "── Phase P Lite: Appeals Falsification ───────────────────────────────────"
  clojure -M:run:with-sew -- -P -p data/params/phase-p-lite-baseline.edn || mc_fail=$((mc_fail + 1))
  echo ""

  echo "── Phase AA: Governance as Adversary ─────────────────────────────────────"
  clojure -M:run:with-sew -- -A -p data/params/phase-aa-governance.edn || mc_fail=$((mc_fail + 1))
  echo ""

  echo "── Phase AD: Governance Bandwidth Floor (AA safeguard) ───────────────────"
  clojure -M:run:with-sew -- -D -p data/params/phase-ad-governance-floor.edn || mc_fail=$((mc_fail + 1))
  echo ""

  echo "── Phase F: Collusion Ring Deterrence ────────────────────────────────────"
  clojure -M:run:with-sew -- -W -p data/params/phase-f-baseline.edn || mc_fail=$((mc_fail + 1))
  echo ""

  if [ "$mc_fail" -eq 0 ]; then
    echo "Monte Carlo sweep: all 5 phases PASSED"
  else
    echo "Monte Carlo sweep: $mc_fail phase(s) FAILED"
  fi

  return $mc_fail
}

run_long_horizon() {
  require_clojure || return $?
  echo "Running long-horizon coverage suite (extended epoch scenarios)..."

  local lh_fail=0
  local lh_risk_lines="$ARTIFACT_DIR/.risk-${RUN_ID}.lines"
  local lh_meta_file="$ARTIFACT_DIR/.long-horizon-${RUN_ID}.meta"
  : > "$lh_risk_lines"
  : > "$lh_meta_file"

  echo "── Phase T: Governance capture (100 epochs) ───────────────────────────────"
  clojure -M:run:with-sew -- -H -p data/params/phase-t-governance-capture.edn || lh_fail=$((lh_fail + 1))
  echo ""

  echo "── Phase AH: Trajectory sweep (100/500/1000 epochs, runtime-safe profile) ─"
  clojure -M:run:with-sew -- -U -p data/params/phase-ah-trajectory-sweep-long-horizon.edn || lh_fail=$((lh_fail + 1))
  echo ""

  echo "── Phase AI: Escalation trap (200 epochs) ─────────────────────────────────"
  local ai_log
  ai_log="$(mktemp)"
  clojure -M:run:with-sew -- -V -p data/params/phase-ai-escalation-trap.edn >"$ai_log" 2>&1 || lh_fail=$((lh_fail + 1))
  cat "$ai_log"
  if grep -Eq "Status: ❌ FAIL|✗ FAIL" "$ai_log"; then
    echo "HARD GATE: Phase AI reported critical failure; marking long-horizon as failed."
    echo "critical|phase-ai|AI_CRITICAL_FAILURE|Phase AI escalation trap indicates critical vulnerability" >> "$lh_risk_lines"
    lh_fail=$((lh_fail + 1))
  else
    echo "info|phase-ai|AI_PASS|Phase AI did not report critical failure markers" >> "$lh_risk_lines"
  fi
  rm -f "$ai_log"
  echo ""

  echo "── Phase Z: Legitimacy loop (100 epochs) ──────────────────────────────────"
  local z_log
  z_log="$(mktemp)"
  clojure -M:run:with-sew -- -Z -p data/params/phase-z-legitimacy.edn >"$z_log" 2>&1 || lh_fail=$((lh_fail + 1))
  cat "$z_log"
  if grep -Eq "Hypothesis holds\? ❌ NO|UNEXPECTED DEATH SPIRAL" "$z_log"; then
    echo "HARD GATE: Phase Z reported legitimacy instability; marking long-horizon as failed."
    echo "critical|phase-z|Z_LEGITIMACY_FAILURE|Phase Z reports legitimacy instability or death spiral" >> "$lh_risk_lines"
    lh_fail=$((lh_fail + 1))
  else
    echo "info|phase-z|Z_PASS|Phase Z did not report legitimacy failure markers" >> "$lh_risk_lines"
  fi
  rm -f "$z_log"
  echo ""

  echo "── Phase J: Baseline stable (100 epochs) ──────────────────────────────────"
  clojure -M:run:with-sew -- -m -p data/params/phase-j-baseline-stable.edn || lh_fail=$((lh_fail + 1))
  echo ""

  if [ "$lh_fail" -eq 0 ]; then
    echo "Long-horizon suite: all extended-horizon scenarios PASSED"
  else
    echo "Long-horizon suite: $lh_fail scenario(s) FAILED"
  fi

  echo "internal_failures=$lh_fail" > "$lh_meta_file"

  return $lh_fail
}
