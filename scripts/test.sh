#!/usr/bin/env bash
# Canonical test runner for Protocol Robustness Framework.
#
# Usage:
#   ./scripts/test.sh            # run all suites (unit + invariants + fixtures + triage)
#   ./scripts/test.sh unit       # Clojure unit tests only
#   ./scripts/test.sh generators # Generator + equilibrium regression tests (pinned seeds)
#   ./scripts/test.sh invariants # S01–S100 deterministic invariant scenarios only
#   ./scripts/test.sh yield-provider-scenarios # Standalone yield-v1 file-backed scenarios
#   ./scripts/test.sh sew-yield-scenarios      # Sew escrow + yield integration file-backed scenarios
#   ./scripts/test.sh yield-scenarios          # Compatibility alias: runs both yield path suites (deprecated)
#   ./scripts/test.sh lint:cleanup          # Clean up lint warnings (interactive)
#   ./scripts/test.sh contracts  # Cross-layer contract checks (proto/service/wire compatibility)
#   ./scripts/test.sh suites     # fixture suite runner (all-invariants + equilibrium-validation + spe-validation + spe-regression)
#   ./scripts/test.sh reference-validation  # public reference evidence harness (suites/reference-validation-v1)
#   ./scripts/test.sh triage     # Failure triage grouped by purpose/threat-tag
#   ./scripts/test.sh equivalence-new # New equivalence comparison stack (auth/race/escalation/accounting)
#   ./scripts/test.sh monte-carlo # Representative Monte Carlo phase sweep (5 domains)
#   ./scripts/test.sh long-horizon # Extended horizon scenarios (100/200/500/1000 epochs)
#
# Target-level parallelism (opt-in): set PARALLEL_TARGETS=1 to run the
# independent targets in `all`/`ci` concurrently, each in its own isolated
# artifact subdir. PARALLEL_TARGET_JOBS (default 4) caps concurrent targets.
#   PARALLEL_TARGETS=1 PARALLEL_TARGET_JOBS=8 ./scripts/test.sh ci
#
# The parallel namespace runner (scripts/parallel-test-runner.clj) keeps
# audit-flagged namespaces in a sequential safety lane by default. Override
# via PARALLEL_TEST_EXCLUDE_NS (e.g. "" to disable, or a custom ns list).
# Set PARALLEL_TEST_LEAK_CHECK=1 to also verify root registries and the shared
# artifact dir are left untouched by the run (mirrors run-sew-tests).
#
# Evidence tiers (see core.phases/phase-evidence-tiers):
#   :protocol-kernel-evidence — calls resolve-dispute or replay-with-protocol
#   :analytic                 — algebraic/closed-form, no protocol calls
#   :exploratory              — narrative, qualitative, or pre-prototype
#
# Phases C, E, M (analytic) are NOT CI-gated — run via --phase-c-dr etc.
# Phases Q, R, U, V, W, X (exploratory) are NOT CI-gated — run via --phase-q etc.
# All other phases are documented in core.phases/phase-evidence-tiers.
#
# Exit code: 0 = all passed, 1 = any failure.

cd "$(dirname "$0")/.."
source "$(dirname "$0")/monte_carlo.sh"

MODE="${1:-all}"
FAILURES=0
TARGETS_COMPLETED=0
TARGETS_PASSED=0
FAST_MODE=false

# When set to 1, the `all`/`ci` modes run independent targets concurrently,
# each in its own isolated artifact subdirectory (see run_targets_parallel).
# Off by default to preserve exact sequential behaviour.
PARALLEL_TARGETS="${PARALLEL_TARGETS:-0}"
# Cap on how many targets run at once in parallel-target mode.
PARALLEL_TARGET_JOBS="${PARALLEL_TARGET_JOBS:-4}"

# Check for fast mode
if [ "$MODE" = "fast" ] || [ "$MODE" = "ci" ]; then
  FAST_MODE=true
fi
if [ -n "${PRF_ARTIFACT_DIR:-}" ]; then
  ARTIFACT_DIR="$PRF_ARTIFACT_DIR"
else
  ARTIFACT_DIR="$(python3 -c "from scripts.evidence.evidence_config import EvidenceConfig; print(EvidenceConfig().artifact_dir)" 2>/dev/null)" || ARTIFACT_DIR="results/test-artifacts"
fi
ARTIFACT_FILE="$ARTIFACT_DIR/test-summary.json"
RUN_MANIFEST_FILE="$ARTIFACT_DIR/test-run.json"
ARTIFACT_REGISTRY_FILE="$ARTIFACT_DIR/test-artifacts.json"
CLAIMABLE_CLASSIFICATION_FILE="$ARTIFACT_DIR/claimable-classification.json"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RUN_STARTED_AT="$(date +%s)"
MAX_UNHIT_TRANSITIONS="${MAX_UNHIT_TRANSITIONS:-4}"
MAX_UNSAFE_REGION_DELTA_PCT="${MAX_UNSAFE_REGION_DELTA_PCT:-10}"
STRICT_CLAIM_REGISTRY="${STRICT_CLAIM_REGISTRY:-0}"

mkdir -p "$ARTIFACT_DIR"

require_clojure() {
  if ! command -v clojure >/dev/null 2>&1; then
    echo "ERROR: Clojure CLI not found on PATH."
    echo "Install Clojure CLI, then retry."
    echo "Hint: this is installed automatically in CI via setup-clojure."
    return 127
  fi
  return 0
}

TARGET_LOG=""

start_target() {
  TARGET_LOG="$ARTIFACT_DIR/.target-${1}-${RUN_ID}.log"
  : > "$TARGET_LOG"
}

record_target() {
  target="$1"
  code="$2"
  dur_ms="$3"
  status="pass"
  if [ "$code" -ne 0 ]; then
    status="fail"
  fi
  printf '%s,%s,%s,%s,%s\n' "$target" "$status" "$code" "$dur_ms" "$TARGET_LOG" >> "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
}

parse_progress() {
  local target="$1"
  local ran=0
  local passed=0
  local failed=0
  local current_ns=""
  
  # Determine total tests dynamically if known
  local total=""
  case "$target" in
    unit) total=307 ;;
    suites) total=94 ;;
    dr3-coverage) total=15 ;;
    reference-validation) total=8 ;;
  esac

  while IFS= read -r line; do
    if [[ "$line" =~ Testing[[:space:]]+([a-zA-Z0-9.-]+) ]]; then
      current_ns="${BASH_REMATCH[1]}"
      current_ns="${current_ns#resolver-sim.}"
      if [ -n "$total" ]; then
        printf "\r\033[K  [$(date +%H:%M:%S)] %s: testing %s (ran %d of %d | %d passed)" "$target" "$current_ns" "$ran" "$total" "$passed"
      else
        printf "\r\033[K  [$(date +%H:%M:%S)] %s: testing %s (ran %d | %d passed)" "$target" "$current_ns" "$ran" "$passed"
      fi
      if [ $failed -gt 0 ]; then
        printf " (%d failed)" "$failed"
      fi
    elif [[ "$line" =~ "scenario/end" || "$line" =~ "scenario/halt" ]]; then
      ran=$((ran + 1))
      if [[ "$line" =~ ":outcome :pass" ]]; then
        passed=$((passed + 1))
      else
        failed=$((failed + 1))
      fi
      local progress_str=""
      if [ -n "$total" ]; then
        progress_str=$(printf "ran %d of %d | %d passed" "$ran" "$total" "$passed")
      else
        progress_str=$(printf "ran %d | %d passed" "$ran" "$passed")
      fi
      if [ $failed -gt 0 ]; then
        progress_str="$progress_str ($failed failed)"
      fi
      if [ -n "$current_ns" ]; then
        printf "\r\033[K  [$(date +%H:%M:%S)] %s: testing %s (%s)" "$target" "$current_ns" "$progress_str"
      else
        printf "\r\033[K  [$(date +%H:%M:%S)] %s: %s" "$target" "$progress_str"
      fi
    elif [[ "$line" =~ "FAIL in (" || "$line" =~ "ERROR in (" ]]; then
      failed=$((failed + 1))
      ran=$((ran + 1))
      if [ -n "$total" ]; then
        printf "\r\033[K  [$(date +%H:%M:%S)] %s: testing %s (ran %d of %d | %d passed (%d failed))" "$target" "$current_ns" "$ran" "$total" "$passed" "$failed"
      else
        printf "\r\033[K  [$(date +%H:%M:%S)] %s: testing %s (ran %d | %d passed (%d failed))" "$target" "$current_ns" "$ran" "$passed" "$failed"
      fi
    elif [[ "$line" =~ ^Ran[[:space:]]+([0-9]+)[[:space:]]+tests[[:space:]]+containing[[:space:]]+([0-9]+)[[:space:]]+assertions ]]; then
      local tests_run="${BASH_REMATCH[1]}"
      printf "\r\033[K  [$(date +%H:%M:%S)] %s: ran %s tests" "$target" "$tests_run"
    elif [[ "$line" =~ ^"── Phase" ]]; then
      local clean_line
      clean_line=$(echo "$line" | tr -d '─' | xargs)
      printf "\r\033[K  [$(date +%H:%M:%S)] %s: %s" "$target" "$clean_line"
    elif [[ "$line" =~ ^Suite:[[:space:]]+([a-zA-Z0-9:/-]+) ]]; then
      local suite_name="${BASH_REMATCH[1]}"
      printf "\r\033[K  [$(date +%H:%M:%S)] %s: running %s" "$target" "$suite_name"
    elif [[ "$line" =~ "checks passed" || "$line" =~ "integrity checks succeeded" || "$line" =~ "alignment checks passed" ]]; then
      ran=$((ran + 1))
      passed=$((passed + 1))
      printf "\r\033[K  [$(date +%H:%M:%S)] %s: ran %d | %d passed" "$target" "$ran" "$passed"
    fi
  done
  printf "\r\033[K"
}

run_target() {
  target="$1"
  func="$2"
  start_target "$target"
  t0="$(date +%s)"
  echo "  [$(date +%H:%M:%S)] Running $target..."
  
  # Run the target function in the background, redirecting output to log
  "$func" >"$TARGET_LOG" 2>&1 &
  local pid=$!
  
  # Tail the log file in real-time and parse the progress
  tail -n +1 -f "$TARGET_LOG" --pid=$pid 2>/dev/null | parse_progress "$target"
  
  # Wait for the background process to complete and get its exit code
  wait $pid
  code=$?
  
  t1="$(date +%s)"
  dur_ms=$(( (t1 - t0) * 1000 ))
  record_target "$target" "$code" "$dur_ms"

  # Update counters
  TARGETS_COMPLETED=$((TARGETS_COMPLETED + 1))
  if [ $code -eq 0 ]; then
    TARGETS_PASSED=$((TARGETS_PASSED + 1))
  fi

  # Show progress summary
  if [ $code -eq 0 ]; then
    echo "  [$(date +%H:%M:%S)] ✓ $target completed in ${dur_ms}ms ($TARGETS_PASSED/$TARGETS_COMPLETED passed)"
  else
    echo "  [$(date +%H:%M:%S)] ✗ $target failed after ${dur_ms}ms (exit code: $code) ($TARGETS_PASSED/$TARGETS_COMPLETED passed)"
  fi

  cat "$TARGET_LOG"
  return "$code"
}

# Run a batch of independent targets concurrently (opt-in target-level
# parallelism). Each target runs in its own isolated artifact subdirectory so
# evidence/suite/coverage outputs never collide across concurrent processes.
#
# Arguments: "target:func" pairs — order as passed (run longest first).
# Returns the number of failing targets as the exit code; FAILURES and the
# pass/completed counters are updated by the caller via $?.
run_targets_parallel() {
  local pairs=("$@")
  local -a pids=()
  local resfile="${ARTIFACT_DIR}/.parallel-results-${RUN_ID}.txt"
  : > "$resfile"
  echo "  [$(date +%H:%M:%S)] Running ${#pairs[@]} targets in parallel (jobs=${PARALLEL_TARGET_JOBS})..."
  local i=0
  for pair in "${pairs[@]}"; do
    if [ "$i" -ge "$PARALLEL_TARGET_JOBS" ]; then
      # wait for the earliest pending job to free a slot
      wait "${pids[0]}" || true
      pids=("${pids[@]:1}")
    fi
    local target="${pair%%:*}"
    local func="${pair#*:}"
    local tdir="${ARTIFACT_DIR}/targets/${target}"
    mkdir -p "$tdir"
    (
      ARTIFACT_DIR="$tdir"
      PRF_ARTIFACT_DIR="$tdir"
      run_target "$target" "$func" >"${ARTIFACT_DIR}/.parallel-${target}.log" 2>&1
      echo "$?" >> "$resfile"
    ) &
    pids+=($!)
    i=$((i + 1))
  done
  for p in "${pids[@]}"; do wait "$p" || true; done

  local nfail=0
  while IFS= read -r code; do
    [ -z "$code" ] && continue
    TARGETS_COMPLETED=$((TARGETS_COMPLETED + 1))
    if [ "$code" -eq 0 ]; then
      TARGETS_PASSED=$((TARGETS_PASSED + 1))
    else
      nfail=$((nfail + 1))
    fi
  done < "$resfile"

  echo "  [$(date +%H:%M:%S)] Parallel batch complete ($TARGETS_PASSED/$TARGETS_COMPLETED passed, $nfail failed)"
  for pair in "${pairs[@]}"; do
    local target="${pair%%:*}"
    local log="${ARTIFACT_DIR}/.parallel-${target}.log"
    if [ -f "$log" ]; then
      echo "  ── $target  (isolated artifacts under ${ARTIFACT_DIR}/targets/$target)"
      grep -E "✓ |✗ |completed|failed" "$log" | tail -2 || true
    fi
  done
  return "$nfail"
}

run_unit() {
  require_clojure || return $?
  echo "Running unit tests (all — framework + Sew, parallel namespaces)..."
  clojure -M:test:with-sew -m scripts.parallel-test-runner \
    resolver-sim.core-tests \
    resolver-sim.protocol-alignment-test \
    resolver-sim.protocols.sew.replay-test \
    resolver-sim.protocols.sew.forking-strategist-expectations-test \
    resolver-sim.scenario.expectations-test \
    resolver-sim.scenario.equilibrium-test \
    resolver-sim.sim.multi-epoch-test \
    resolver-sim.sim.defection-test \
    resolver-sim.sim.strategy-adaptation-test \
    resolver-sim.protocols.sew.slashing-test \
    resolver-sim.protocols.sew.phase-k-test \
    resolver-sim.protocols.sew.phase-m-test \
    resolver-sim.sim.waterfall-test \
    resolver-sim.io.scenario-fixture-parity-test \
    resolver-sim.contract-model.replay-batch-test \
    resolver-sim.contract-model.replay-batch-sew-test \
    resolver-sim.contract-model.replay-batch-appeal-test \
    resolver-sim.contract-model.replay-batch-slash-domain-test \
    resolver-sim.protocols.sew.dispute-resolution-coverage-test \
    resolver-sim.protocols.sew.resolution-test \
    resolver-sim.protocols.sew.trace-export-idempotency-test \
    resolver-sim.financial.pro-rata-characterization-test \
    resolver-sim.scenario.suites-test \
    resolver-sim.validation.scenario-registry-test \
    resolver-sim.run.overview-test \
    resolver-sim.benchmark.game-theory-validation-test \
    resolver-sim.benchmark.packs.partial-fill.evidence-test \
    resolver-sim.assurance.custody-summary-test \
    resolver-sim.evidence.node-test \
    resolver-sim.evidence.attestation-dag-test \
    resolver-sim.economics.payoffs-test \
    resolver-sim.economics.with-bounty.stage-a-test \
    resolver-sim.economics.with-bounty.application-plan-test \
    resolver-sim.economics.with-bounty.verification-test \
    resolver-sim.economics.with-bounty.replay-test \
    resolver-sim.protocols.sew.with-bounty-test \
    resolver-sim.hash.canonical-test \
    resolver-sim.hash.concat-properties-test \
    resolver-sim.hash.attestor-hash-test \
    resolver-sim.ordering.priority-test \
    resolver-sim.ordering.priority-composition-test \
    resolver-sim.evidence.chain-test \
    resolver-sim.evidence.commitment-root-test \
    resolver-sim.evidence.finalization-test \
    resolver-sim.evidence.qol-test
  return $?
}

run_framework() {
  require_clojure || return $?
  echo "Running framework unit tests (no Sew protocol)..."
  clojure -M:test -e "
(require 'resolver-sim.evidence.chain
         '[clojure.test :as t]
         'resolver-sim.core-tests
         'resolver-sim.protocol-alignment-test
         'resolver-sim.time.context-test
         'resolver-sim.time.model-test
         'resolver-sim.sim.defection-test
         'resolver-sim.sim.strategy-adaptation-test
         'resolver-sim.sim.waterfall-test
         'resolver-sim.workflow-group-test
         'resolver-sim.hash.algorithm-test
         'resolver-sim.deferral-test
         'resolver-sim.claim-outcome-test
         'resolver-sim.grounded-amount-test)
(binding [resolver-sim.evidence.chain/*allow-dirty* true]
  (let [results (t/run-tests
                'resolver-sim.core-tests
                'resolver-sim.protocol-alignment-test
                'resolver-sim.time.context-test
                'resolver-sim.time.model-test
                'resolver-sim.sim.defection-test
                'resolver-sim.sim.strategy-adaptation-test
                'resolver-sim.sim.waterfall-test
                'resolver-sim.workflow-group-test
                'resolver-sim.hash.algorithm-test
                'resolver-sim.deferral-test
                'resolver-sim.claim-outcome-test
                'resolver-sim.grounded-amount-test)]
              (when (pos? (+ (:error results) (:fail results)))
                (System/exit 1))))"
  return $?
}

run_sew() {
  require_clojure || return $?
  echo "Running Sew protocol unit tests..."
  clojure -M:test:with-sew -e "
(require 'resolver-sim.evidence.chain
         '[clojure.test :as t]
         'resolver-sim.core-tests
         'resolver-sim.protocols.sew.replay-test
         'resolver-sim.protocols.sew.forking-strategist-expectations-test
         'resolver-sim.scenario.expectations-test
         'resolver-sim.scenario.equilibrium-test
         'resolver-sim.protocols.sew.slashing-test
         'resolver-sim.protocols.sew.phase-k-test
         'resolver-sim.protocols.sew.phase-m-test
         'resolver-sim.contract-model.replay-batch-sew-test
         'resolver-sim.contract-model.replay-batch-appeal-test
         'resolver-sim.contract-model.replay-batch-slash-domain-test
         'resolver-sim.protocols.sew.lifecycle-test
         'resolver-sim.protocols.sew.resolution-test
         'resolver-sim.protocols.sew.state-machine-test
         'resolver-sim.protocols.sew.governance-test
         'resolver-sim.protocols.sew.integration-test
         'resolver-sim.protocols.sew.accounting-test
         'resolver-sim.protocols.sew.governance-gates-test
                'resolver-sim.protocols.sew.governance-identity-test
         'resolver-sim.protocols.sew.governance-identity-test
         'resolver-sim.protocols.sew.yield-reorg-race-test
         'resolver-sim.protocols.sew.yield-solvency-test
         'resolver-sim.protocols.sew.yield.failure-test
         'resolver-sim.protocols.sew.yield.policy-test
         'resolver-sim.protocols.sew.yield.finalize-parity-test
         'resolver-sim.protocols.sew.resolver-yield-accrual-test
         'resolver-sim.protocols.sew.invariant-registry-test
         'resolver-sim.protocols.sew.invariant-runner-test
         'resolver-sim.protocols.sew.dispute-capacity-test
         'resolver-sim.protocols.sew.funds-ledger-projection-test
         'resolver-sim.protocols.sew.snapshot-test
         'resolver-sim.protocols.sew.snapshot-boundary-test
         'resolver-sim.protocols.sew.claimable-classification-test
         'resolver-sim.protocols.sew.replay-bridge-test
         'resolver-sim.protocols.sew.replay-dedupe-policy-test
         'resolver-sim.protocols.sew.replay-event-id-scenario-test
         'resolver-sim.protocols.sew.replay-idempotency-test
         'resolver-sim.protocols.sew.require-event-id-test
         'resolver-sim.protocols.sew.temporal-boundary-test
         'resolver-sim.protocols.sew.temporal-generator-test
         'resolver-sim.protocols.sew.trace-export-idempotency-test
         'resolver-sim.protocols.sew.authority-test
         'resolver-sim.protocols.sew.idempotence-checklist-test
         'resolver-sim.protocols.sew.invariants.solvency-test
         'resolver-sim.protocols.sew.invariants.temporal-test
         'resolver-sim.scenario.subgame-counterfactual-test
         'resolver-sim.scenario.yield-expectations-test
         'resolver-sim.scenario.yield-scenario-lint-test
         'resolver-sim.scenario.report-test
         'resolver-sim.scenario.runner-test
         'resolver-sim.scenario.theory-test
         'resolver-sim.scenario.golden-test
         'resolver-sim.scenario.phase-3-spe-test
         'resolver-sim.scenario.spe-fork-event-id-test
         'resolver-sim.properties.invariants-test
         'resolver-sim.definitions.registry-test
         'resolver-sim.evidence.registry-test
         'resolver-sim.evidence.qol-test
         'resolver-sim.protocols.sew.evidence.slashing-test)
(binding [resolver-sim.evidence.chain/*allow-dirty* true]
  (let [results (t/run-tests
                'resolver-sim.core-tests
                'resolver-sim.protocols.sew.replay-test
                'resolver-sim.protocols.sew.forking-strategist-expectations-test
                'resolver-sim.scenario.expectations-test
                'resolver-sim.scenario.equilibrium-test
                'resolver-sim.protocols.sew.slashing-test
                'resolver-sim.protocols.sew.evidence.slashing-test
                'resolver-sim.protocols.sew.phase-k-test
                'resolver-sim.protocols.sew.phase-m-test
                'resolver-sim.contract-model.replay-batch-sew-test
                'resolver-sim.contract-model.replay-batch-appeal-test
                'resolver-sim.contract-model.replay-batch-slash-domain-test
                'resolver-sim.protocols.sew.lifecycle-test
                'resolver-sim.protocols.sew.resolution-test
                'resolver-sim.protocols.sew.state-machine-test
                'resolver-sim.protocols.sew.governance-test
                'resolver-sim.protocols.sew.integration-test
                'resolver-sim.protocols.sew.accounting-test
                'resolver-sim.protocols.sew.governance-gates-test
                'resolver-sim.protocols.sew.yield-reorg-race-test
                'resolver-sim.protocols.sew.yield-solvency-test
                'resolver-sim.protocols.sew.yield.failure-test
                'resolver-sim.protocols.sew.yield.policy-test
                'resolver-sim.protocols.sew.yield.finalize-parity-test
                'resolver-sim.protocols.sew.resolver-yield-accrual-test
                'resolver-sim.protocols.sew.invariant-registry-test
                'resolver-sim.protocols.sew.invariant-runner-test
                'resolver-sim.protocols.sew.dispute-capacity-test
                'resolver-sim.protocols.sew.funds-ledger-projection-test
                'resolver-sim.protocols.sew.snapshot-test
                'resolver-sim.protocols.sew.snapshot-boundary-test
                'resolver-sim.protocols.sew.claimable-classification-test
                'resolver-sim.protocols.sew.replay-bridge-test
                'resolver-sim.protocols.sew.replay-dedupe-policy-test
                'resolver-sim.protocols.sew.replay-event-id-scenario-test
                'resolver-sim.protocols.sew.replay-idempotency-test
                'resolver-sim.protocols.sew.require-event-id-test
                'resolver-sim.protocols.sew.temporal-boundary-test
                'resolver-sim.protocols.sew.temporal-generator-test
                'resolver-sim.protocols.sew.trace-export-idempotency-test
                'resolver-sim.protocols.sew.authority-test
                'resolver-sim.protocols.sew.idempotence-checklist-test
                'resolver-sim.protocols.sew.invariants.solvency-test
                'resolver-sim.protocols.sew.invariants.temporal-test
                'resolver-sim.scenario.subgame-counterfactual-test
                'resolver-sim.scenario.yield-expectations-test
                'resolver-sim.scenario.yield-scenario-lint-test
                'resolver-sim.scenario.report-test
                'resolver-sim.scenario.runner-test
                'resolver-sim.scenario.theory-test
                'resolver-sim.scenario.golden-test
                'resolver-sim.scenario.phase-3-spe-test
                'resolver-sim.scenario.spe-fork-event-id-test
                'resolver-sim.properties.invariants-test
                'resolver-sim.definitions.registry-test
                'resolver-sim.evidence.qol-test
                'resolver-sim.evidence.registry-test)]
              (when (pos? (+ (:error results) (:fail results)))
                (System/exit 1))))"
  return $?
}

run_yield() {
  require_clojure || return $?
  echo "Running yield protocol unit tests..."
  clojure -M:test:with-sew -e "
(require 'resolver-sim.evidence.chain
         '[clojure.test :as t]
         'resolver-sim.protocols.sew.yield-reorg-race-test
         'resolver-sim.protocols.sew.yield-solvency-test
         'resolver-sim.protocols.sew.yield.failure-test
         'resolver-sim.protocols.sew.yield.policy-test
         'resolver-sim.protocols.sew.yield.finalize-parity-test
         'resolver-sim.protocols.sew.resolver-yield-accrual-test
         'resolver-sim.scenario.yield-expectations-test
         'resolver-sim.scenario.yield-scenario-lint-test
         'resolver-sim.yield.liquid-lending-v2-test
         'resolver-sim.yield.pro-rata-accounting-test
         'resolver-sim.yield.pro-rata-propagation-properties-test
         'resolver-sim.yield.deferred-class-test
         'resolver-sim.yield.lineage-conservation-test
         'resolver-sim.yield.queue-policy-test
         'resolver-sim.yield.merge-policy-test)
(binding [resolver-sim.evidence.chain/*allow-dirty* true]
  (let [results (t/run-tests
               'resolver-sim.protocols.sew.yield-reorg-race-test
               'resolver-sim.protocols.sew.yield-solvency-test
               'resolver-sim.protocols.sew.yield.failure-test
               'resolver-sim.protocols.sew.yield.policy-test
               'resolver-sim.protocols.sew.yield.finalize-parity-test
               'resolver-sim.protocols.sew.resolver-yield-accrual-test
               'resolver-sim.scenario.yield-expectations-test
               'resolver-sim.scenario.yield-scenario-lint-test
               'resolver-sim.yield.liquid-lending-v2-test
               'resolver-sim.yield.pro-rata-accounting-test
               'resolver-sim.yield.pro-rata-propagation-properties-test
               'resolver-sim.yield.deferred-class-test
               'resolver-sim.yield.lineage-conservation-test
               'resolver-sim.yield.queue-policy-test
               'resolver-sim.yield.merge-policy-test)]
              (when (pos? (+ (:error results) (:fail results)))
                (System/exit 1))))"
  return $?
}

run_invariants() {
  require_clojure || return $?
  echo "Running deterministic invariant scenarios (S01–S100)..."
  clojure -M:with-sew -e "
(require 'resolver-sim.evidence.chain
         '[resolver-sim.io.scenario-runner :as sr])
(binding [resolver-sim.evidence.chain/*allow-dirty* true]
  (sr/run-registry-suite-and-report))"
  return $?
}

run_dispute_resolution() {
  require_clojure || return $?
  echo "Running dispute resolution coverage scenarios (S-DR-* via test namespace)..."
  clojure -M:test:with-sew -e "
(require 'resolver-sim.evidence.chain
         '[clojure.test :as t]
         'resolver-sim.protocols.sew.dispute-resolution-coverage-test)
(binding [resolver-sim.evidence.chain/*allow-dirty* true]
  (let [results (t/run-tests 'resolver-sim.protocols.sew.dispute-resolution-coverage-test)]
    (when (pos? (+ (:error results) (:fail results)))
      (System/exit 1))))"
  local dr_exit=$?
  # CI Gate: validate artifact registry
  if [[ -f "scripts/validate/ci_gate_validation.py" ]]; then
    echo "Running CI gate validation..."
    python3 scripts/validate/ci_gate_validation.py || return $?
  fi

  # CI Gate: coverage gates
  python3 scripts/validate/coverage_gates.py --artifact-dir "$ARTIFACT_DIR" --max-unhit-transitions "$MAX_UNHIT_TRANSITIONS" || return $?

  return $dr_exit
}

run_named_path_suite() {
  require_clojure || return $?
  local suite="$1"
  echo "Running registry-backed scenario path suite: $suite"
  clojure -M:with-sew -e "
(require 'resolver-sim.evidence.chain
         '[resolver-sim.io.scenario-runner :as sr])
(binding [resolver-sim.evidence.chain/*allow-dirty* true]
  (sr/run-named-suite-and-report (keyword \"$suite\") {}))"
  return $?
}

run_yield_provider_scenarios() {
  echo "WARN: test.sh yield-provider-scenarios is deprecated. Use bb test:yield-provider-scenarios instead." >&2
  run_named_path_suite yield-provider-scenarios
}

run_sew_yield_scenarios() {
  echo "WARN: test.sh sew-yield-scenarios is deprecated. Use bb test:sew-yield-scenarios instead." >&2
  run_named_path_suite sew-yield-scenarios
}

run_yield_scenarios() {
  echo "WARN: test.sh yield-scenarios is deprecated. Use bb test:yield-scenarios instead." >&2
  echo "yield-scenarios is a compatibility alias; running yield-provider-scenarios then sew-yield-scenarios."
  run_yield_provider_scenarios || return $?
  run_sew_yield_scenarios
}

run_generators() {
  require_clojure || return $?
  echo "Running generator regression tests (pinned seeds, parallel)..."
  clojure -M:test:with-sew -m scripts.parallel-test-runner \
    resolver-sim.generators.equilibrium-test \
    resolver-sim.generators.fixtures-test \
    resolver-sim.properties.invariants-test
  return $?
}

run_fast_smoke() {
  require_clojure || return $?
  echo "Running focused replay and accounting smoke tests..."
  clojure -M:test:with-sew -e "
(require '[clojure.test :as t]
         'resolver-sim.yield.accrual-test
         'resolver-sim.contract-model.replay-simple-characterization-test
         'resolver-sim.sim.stochastic-equilibrium-test)
(binding [t/*report-counters* (ref t/*initial-report-counters*)]
  (t/test-vars
   [#'resolver-sim.yield.accrual-test/test-multi-short-circuit-interaction
    #'resolver-sim.contract-model.replay-simple-characterization-test/minimal-defaults-cover-all-flag-keys
    #'resolver-sim.sim.stochastic-equilibrium-test/test-budget-balance-reports-all-components])
  (let [results @t/*report-counters*]
    (println (format \"Ran %d focused tests containing %d assertions.\" (:test results) (:pass results)))
    (println (format \"%d failures, %d errors.\" (:fail results) (:error results)))
    (when (pos? (+ (:error results) (:fail results)))
      (System/exit 1))))"
  return $?
}

run_contracts() {
  echo "Running cross-layer contract checks (proto/service/wire compatibility)..."

  # Proto service + RPC contract
  grep -q 'package sew.simulation;' proto/simulation.proto
  grep -q 'service SimulationEngine' proto/simulation.proto
  grep -q 'rpc StartSession' proto/simulation.proto
  grep -q 'rpc Step' proto/simulation.proto
  grep -q 'rpc DestroySession' proto/simulation.proto



  # Clojure server must expose same RPC names and snake_case↔kebab-case bridge
  grep -q 'SimulationEngine' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "StartSession"' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "Step"' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "DestroySession"' src/resolver_sim/server/grpc.clj
  grep -q 'snake_case' src/resolver_sim/server/grpc.clj

  # Scenario naming convention sanity checks (supports legacy + canonical ids)
  python scripts/validate/validate_scenario_naming.py

  # P1: Fixture/claim alignment checks for collusion assertions
  python scripts/validate/validate_collusion_alignment.py

  # Artifact integrity requires a completed evidence bundle; validate it via
  # scripts/validate/validate_artifact_registry.py after artifact emission.

  # Claim registry integrity checks (claim ids ↔ scenarios ↔ invariants)
  if [ "$STRICT_CLAIM_REGISTRY" = "1" ]; then
    python scripts/validate/validate_claim_registry.py --strict-theory-claims
  else
    python scripts/validate/validate_claim_registry.py
  fi

  return $?
}

run_triage() {
  require_clojure || return $?
  echo "Running failure triage (purpose/threat-tag grouping)..."
  clojure -M -m resolver-sim.scenario.triage ${1:-data/fixtures/traces}
  return $?
}

run_suites() {
   require_clojure || return $?
   local max_workers="${PARALLEL_WORKERS:-4}"
   echo "Running all canonical fixture suites (parallel=${max_workers})..."

   # Suite keyword list — must match :suites/* fixture file names
   local suites=(
     :suites/all-invariants
     :suites/baseline-safety
     :suites/equilibrium-validation
     :suites/spe-validation
     :suites/spe-regression
     :suites/equivalence-auth-paths
     :suites/equivalence-race-pairs
     :suites/equivalence-escalation-boundaries
     :suites/equivalence-accounting-min
     :suites/equivalence-money-path-integrity
     :suites/dr3-critical
     :suites/governance-decay
     :suites/same-block-ordering
     :suites/timelock-regression
     :suites/equivalence-economic-stress
     :suites/forking-strategist
   )

   local suites_ran=${#suites[@]} suites_failed=0
   clojure -M:test:with-sew -m scripts.parallel-suite-runner "${suites[@]}" || suites_failed=$?

   echo ""
   echo "=== Suite Run Complete ==="
   echo "  suites: $suites_ran  failed: $suites_failed"

   # Extract suite failures into risk digest format for test-summary.json visibility
   python3 scripts/evidence/suite_failures_to_risk.py --artifact-dir "$ARTIFACT_DIR" --run-id "$RUN_ID"
   # Touch notebooks/report.clj so Clerk's file watcher triggers re-evaluation
   touch -m "notebooks/report.clj" 2>/dev/null || true

   return $suites_failed
}

run_routed_suites() {
  require_clojure || return $?
  local routed=$(python3 scripts/validate/route_suites.py)
  if [ "$routed" = "none" ]; then
    echo "No suites impacted by changes. Skipping."
    return 0
  fi

  local suite_filter
  if [ "$routed" = "all" ]; then
    echo "Changes impact all suites. Running full suite."
    suite_filter="[:suites/all-invariants :suites/equilibrium-validation :suites/spe-validation :suites/spe-regression]"
  else
    echo "Changes impact suites: $routed"
    suite_filter="[$(echo $routed | sed 's/,/ /g')]"
  fi

  clojure -M:test:with-sew -e "
(require '[resolver-sim.sim.fixtures :as f])
(require '[resolver-sim.io.fixtures :as io-fix])
(let [suites $suite_filter
      verify-opts {:golden-verify-mode :replay-and-theory}
      results (map (fn [id] [id (io-fix/run-suite-from-key id :verify nil verify-opts)]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (f/emit-suite-result suite-id result)
    (println (str suite-id " → " (if (:ok? result) "PASS" "FAIL")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str "  FAIL: " (:trace-id r) " [" (:outcome r) "]")))))))
  (when any-fail (System/exit 1)))"
  return $?
}

run_reference_validation() {
  require_clojure || return $?
  echo "Running reference validation suite v1..."
  make clean-reference-validation-v1 >/dev/null
  make reference-validation-v1
  make verify-reference-validation-v1
  return $?
}

run_dr3_coverage() {
  require_clojure || return $?
  echo "Running DR3 critical suite + release-mapping drift checks..."

  # 1) Run the dedicated DR3-critical suite.
  local tmpfile=$(mktemp /tmp/dr3-coverage-XXXXXX.clj)
  cat > "$tmpfile" << 'CLOJURE'
(require '[resolver-sim.sim.fixtures :as f])
(require '[resolver-sim.io.fixtures :as io-fix])
(let [r (io-fix/run-suite-from-key :suites/dr3-critical nil nil {})]
  (f/emit-suite-result :suites/dr3-critical r)
  (println (str :suites/dr3-critical " → " (if (:ok? r) "PASS" "FAIL")))
  (when-not (:ok? r)
    (doseq [x (:results r)]
      (when (not= :pass (:outcome x))
        (println (str "  FAIL: " (:trace-id x) " [" (:outcome x) "]"))))
    (System/exit 1)))
CLOJURE
  clojure -M:test:with-sew -i "$tmpfile"
  local dr3_exit=$?
  rm -f "$tmpfile"
  if [ $dr3_exit -ne 0 ]; then return $dr3_exit; fi

  # 2) Verify DR3 release mapping file references valid traces and suite IDs.
  local tmpfile=$(mktemp /tmp/dr3-coverage-XXXXXX.clj)
  cat > "$tmpfile" << 'CLOJURE'
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(let [m (edn/read-string (slurp "data/fixtures/protocol/dr3-release-modules.edn"))
      traces (:dr3-critical-traces m)
      suites (:dr3-critical-suites m)
      missing-traces (->> traces
                          (map name)
                          (map #(str "data/fixtures/traces/" % ".trace.json"))
                          (remove #(-> % io/file .exists))
                          vec)
      missing-suites (->> suites
                          (map name)
                          (map #(str "data/fixtures/suites/" % ".edn"))
                          (remove #(-> % io/file .exists))
                          vec)]
  (when (seq missing-traces)
    (println "Missing DR3 mapped traces:")
    (doseq [p missing-traces] (println " -" p))
    (System/exit 1))
  (when (seq missing-suites)
    (println "Missing DR3 mapped suites:")
    (doseq [p missing-suites] (println " -" p))
    (System/exit 1))
  (println "DR3 mapping drift checks passed"))
CLOJURE
  clojure -M:test:with-sew -i "$tmpfile"
  local dr3_exit=$?
  rm -f "$tmpfile"
  if [ $dr3_exit -ne 0 ]; then return $dr3_exit; fi

  return $?
}

run_equivalence_new() {
  require_clojure || return $?
  echo "Running new equivalence comparison suites (auth/race/escalation/accounting + money-path)..."
  local tmpfile=$(mktemp /tmp/equiv-XXXXXX.clj)
  cat > "$tmpfile" << 'CLOJURE'
(require '[resolver-sim.sim.fixtures :as f])
(require '[resolver-sim.io.fixtures :as io-fix])
(let [suites [:suites/equivalence-auth-paths
          :suites/equivalence-race-pairs
          :suites/equivalence-escalation-boundaries
          :suites/equivalence-accounting-min
          :suites/equivalence-money-path-integrity]
      results (map (fn [id] [id (io-fix/run-suite-from-key id nil nil {})]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (f/emit-suite-result suite-id result)
    (println (str suite-id " → " (if (:ok? result) "PASS" "FAIL")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str "  FAIL: " (:trace-id r) " [" (:outcome r) "]")))))))
  (when any-fail (System/exit 1)))
CLOJURE
  clojure -M:test:with-sew -i "$tmpfile"
  local dr3_exit=$?
  rm -f "$tmpfile"
  if [ $dr3_exit -ne 0 ]; then return $dr3_exit; fi

  return $?
}

run_comparison_lint() {
  echo "Running comparison metadata lint..."
  python scripts/validate/validate_comparison_metadata.py
  return $?
}

run_coverage_gates() {
  require_clojure || return $?
  echo "Running transition/guard coverage report + gates..."
  mkdir -p "$ARTIFACT_DIR"
  _cov_out=$(python3 -c "from evidence_config import EvidenceConfig; print(EvidenceConfig().artifact_path('coverage'))" 2>/dev/null) || _cov_out="$ARTIFACT_DIR/coverage.json"
  clojure -M -m resolver-sim.scenario.coverage -- data/fixtures/traces "$_cov_out" || return $?
  python3 scripts/validate/coverage_gates.py --artifact-dir "$ARTIFACT_DIR" --max-unhit-transitions "$MAX_UNHIT_TRANSITIONS"
  return $?
}



emit_claimable_classification() {
  require_clojure || return 0
  echo "Emitting claimable-classification v2..."
  if [ "${CLAIMABLE_CLASSIFICATION_TAXONOMY_ONLY:-0}" = "1" ]; then
    clojure -M:with-sew -m resolver-sim.io.claimable-classification-emitter \
      "$CLAIMABLE_CLASSIFICATION_FILE" taxonomy-only
  else
    clojure -M:with-sew -m resolver-sim.io.claimable-classification-emitter \
      "$CLAIMABLE_CLASSIFICATION_FILE" aggregated "$RUN_ID"
  fi
}

run_outcome_classification_report() {
  echo ""
  echo "Outcome classification report"
  echo "============================="
  python3 scripts/evidence/outcome_classification_report.py "$ARTIFACT_FILE"
  return $?
}

print_target_summary() {
  local elapsed_seconds=$(( $(date +%s) - RUN_STARTED_AT ))
  echo "  Elapsed: ${elapsed_seconds}s"
  echo "  Target results (detailed logs are retained):"
  if [ -f "$ARTIFACT_DIR/.targets-${RUN_ID}.csv" ]; then
    while IFS=, read -r target status exit_code duration_ms log_file; do
      printf "    %-22s %-4s %7sms  log: %s\n" "$target" "$status" "$duration_ms" "$log_file"
    done < "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
  fi
  echo "  Target index: $ARTIFACT_DIR/.targets-${RUN_ID}.csv"
  echo "  Machine-readable summary (written after this footer): $ARTIFACT_FILE"
}

case "$MODE" in
  unit)
    run_unit || FAILURES=$((FAILURES + 1))
    ;;
  framework)
    run_framework || FAILURES=$((FAILURES + 1))
    ;;
  sew)
    run_sew || FAILURES=$((FAILURES + 1))
    ;;
  invariants)
    run_invariants || FAILURES=$((FAILURES + 1))
    ;;
  dispute-resolution)
    run_dispute_resolution || FAILURES=$((FAILURES + 1))
    ;;
  yield-provider-scenarios)
    run_yield_provider_scenarios || FAILURES=$((FAILURES + 1))
    ;;
  sew-yield-scenarios)
    run_sew_yield_scenarios || FAILURES=$((FAILURES + 1))
    ;;
  yield-scenarios)
    run_yield_scenarios || FAILURES=$((FAILURES + 1))
    ;;
  yield)
    run_target yield run_yield || FAILURES=$((FAILURES + 1))
    ;;
  generators)
    run_generators || FAILURES=$((FAILURES + 1))
    ;;
  contracts)
    run_target contracts run_contracts || FAILURES=$((FAILURES + 1))
    ;;
  triage)
    run_target triage run_triage || FAILURES=$((FAILURES + 1))
    ;;
  suites)
    run_target suites run_suites || FAILURES=$((FAILURES + 1))
    ;;
  routed-suites)
    run_target routed-suites run_routed_suites || FAILURES=$((FAILURES + 1))
    ;;
  reference-validation)
    run_target reference-validation run_reference_validation || FAILURES=$((FAILURES + 1))
    ;;
  dr3-coverage)
    run_target dr3-coverage run_dr3_coverage || FAILURES=$((FAILURES + 1))
    ;;
  equivalence-new)
    run_target equivalence-new run_equivalence_new || FAILURES=$((FAILURES + 1))
    ;;
  comparison-lint)
    run_target comparison-lint run_comparison_lint || FAILURES=$((FAILURES + 1))
    ;;
  coverage)
    run_target coverage run_coverage_gates || FAILURES=$((FAILURES + 1))
    ;;

  monte-carlo)
    run_target monte-carlo run_monte_carlo || FAILURES=$((FAILURES + 1))
    ;;
  long-horizon)
    run_target long-horizon run_long_horizon || FAILURES=$((FAILURES + 1))
    ;;
    all)
      : > "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
      echo "Starting full test suite..."
      echo "========================================"

      if [ "$PARALLEL_TARGETS" = "1" ]; then
        # Run independent targets concurrently (longest first). Each target gets
        # its own isolated artifact subdir; reference-validation runs last as it
        # manages its own make-driven outputs.
        run_targets_parallel \
          "monte-carlo:run_monte_carlo" \
          "suites:run_suites" \
          "unit:run_unit" \
          "generators:run_generators" \
          "invariants:run_invariants" \
          "contracts:run_contracts" \
          "coverage:run_coverage_gates" \
          "triage:run_triage"
        FAILURES=$((FAILURES + $?))
        echo ""
        run_target reference-validation run_reference_validation || FAILURES=$((FAILURES + 1))
      else
        run_target unit run_unit || FAILURES=$((FAILURES + 1))
        echo ""
        run_target generators run_generators || FAILURES=$((FAILURES + 1))
        echo ""
        run_target contracts run_contracts || FAILURES=$((FAILURES + 1))
        echo ""
        run_target invariants run_invariants || FAILURES=$((FAILURES + 1))
        echo ""
        run_target suites run_suites || FAILURES=$((FAILURES + 1))
        echo ""
        run_target reference-validation run_reference_validation || FAILURES=$((FAILURES + 1))
        echo ""
        run_target coverage run_coverage_gates || FAILURES=$((FAILURES + 1))
        echo ""
        run_target triage run_triage || FAILURES=$((FAILURES + 1))
        echo ""

        # Skip slow tests in fast mode
        if [ "$FAST_MODE" = true ]; then
          echo "  [$(date +%H:%M:%S)] ⏩ Skipping monte-carlo (slow test) in fast mode"
        else
          run_target monte-carlo run_monte_carlo || FAILURES=$((FAILURES + 1))
        fi
      fi

      echo ""
      echo "========================================"
      echo "TEST SUITE COMPLETE"
      echo "  Targets: $TARGETS_COMPLETED completed"
      echo "  Passed: $TARGETS_PASSED"
      echo "  Failed: $((TARGETS_COMPLETED - TARGETS_PASSED))"
      if [ $FAILURES -gt 0 ]; then
        echo "  Exit code: 1 (failures detected)"
      else
        echo "  Exit code: 0 (all passed)"
      fi
      if [ "$FAST_MODE" = true ]; then
        echo "  Mode: FAST (slow tests skipped)"
      fi
      echo "========================================"

      run_outcome_classification_report || true
      ;;

    fast)
      : > "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
      FAST_MODE=true
      echo "Starting FAST test suite (explicit slow targets excluded)..."
      echo "========================================"

      run_target fast-smoke run_fast_smoke || FAILURES=$((FAILURES + 1))

      echo ""
      echo "========================================"
      echo "FAST TEST SUITE COMPLETE"
      echo "  Targets: $TARGETS_COMPLETED completed"
      echo "  Passed: $TARGETS_PASSED"
      echo "  Failed: $((TARGETS_COMPLETED - TARGETS_PASSED))"
      echo "  Explicit slow targets: excluded"
      if [ $FAILURES -gt 0 ]; then
        echo "  Exit code: 1 (failures detected)"
      else
        echo "  Exit code: 0 (all passed)"
      fi
      print_target_summary
      ;;

    ci)
      : > "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
      echo "Starting CI-OPTIMIZED test suite (fast + critical slow, target: < 2 min)..."
      echo "========================================"

      if [ "$PARALLEL_TARGETS" = "1" ]; then
        run_targets_parallel \
          "monte-carlo:run_monte_carlo" \
          "suites:run_suites" \
          "unit:run_unit" \
          "generators:run_generators" \
          "invariants:run_invariants" \
          "contracts:run_contracts"
        FAILURES=$((FAILURES + $?))
        echo ""
        run_target reference-validation run_reference_validation || FAILURES=$((FAILURES + 1))
      else
        run_target unit run_unit || FAILURES=$((FAILURES + 1))
        echo ""
        run_target generators run_generators || FAILURES=$((FAILURES + 1))
        echo ""
        run_target contracts run_contracts || FAILURES=$((FAILURES + 1))
        echo ""
        run_target invariants run_invariants || FAILURES=$((FAILURES + 1))
        echo ""
        run_target suites run_suites || FAILURES=$((FAILURES + 1))
        echo ""
        run_target reference-validation run_reference_validation || FAILURES=$((FAILURES + 1))
        echo ""
        run_target monte-carlo run_monte_carlo || FAILURES=$((FAILURES + 1))
      fi

      echo ""
      echo "========================================"
      echo "CI TEST SUITE COMPLETE"
      echo "  Targets: $TARGETS_COMPLETED completed"
      echo "  Passed: $TARGETS_PASSED"
      echo "  Failed: $((TARGETS_COMPLETED - TARGETS_PASSED))"
      echo "  Duration: < 2 minutes (target)"
      if [ $FAILURES -gt 0 ]; then
        echo "  Exit code: 1 (failures detected)"
        exit 1
      else
        echo "  Exit code: 0 (all passed)"
        exit 0
      fi
      ;;

    slow)
      : > "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
      echo "Starting SLOW test suite (long-running tests)..."
      echo "========================================"

      run_target monte-carlo run_monte_carlo || FAILURES=$((FAILURES + 1))
      # Add other slow tests here as needed

      echo ""
      echo "========================================"
      echo "SLOW TEST SUITE COMPLETE"
      echo "  Targets: $TARGETS_COMPLETED completed"
      echo "  Passed: $TARGETS_PASSED"
      echo "  Failed: $((TARGETS_COMPLETED - TARGETS_PASSED))"
      echo "  Duration: > 60 seconds each (expected)"
      if [ $FAILURES -gt 0 ]; then
        echo "  Exit code: 1 (failures detected)"
        exit 1
      else
        echo "  Exit code: 0 (all passed)"
        exit 0
      fi
      ;;
  *)
    echo "Unknown test mode: $MODE"
    echo "Usage: $0 [unit|framework|sew|invariants|...|all]"
    exit 1
    ;;
esac

if [ -f scripts/evidence/generate_test_summary.py ]; then
  python3 scripts/evidence/generate_test_summary.py \
    "$ARTIFACT_DIR" \
    "$RUN_ID" \
    "$FAILURES" \
    "$MODE" \
    "$ARTIFACT_FILE" \
    "$RUN_MANIFEST_FILE" \
    "$ARTIFACT_REGISTRY_FILE" \
    "$CLAIMABLE_CLASSIFICATION_FILE"
fi

exit $FAILURES
