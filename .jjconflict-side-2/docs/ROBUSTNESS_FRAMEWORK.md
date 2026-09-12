# Protocol Robustness Framework

**Adversarial Validation, Deterministic Replay, and Simulation Architecture**

---

## Why this is included in the Kleros packet

This document is included as optional background for the Kleros team because the
same simulation workbench used to validate Sew's resolver and escalation economics
may be applicable to Kleros-style dispute systems more broadly.

For Kleros review, the most relevant sections are:

- **§1** — Deterministic Replay Engine (how protocol behavior is replayed and checked)
- **§3** — Scenario Suite, specifically S18–S23 (Kleros integration paths)
- **§4** — Monte Carlo and Statistical Simulations (escalation trap, ring attacker)
- **§8** — Evidence Strength and Known Gaps

The framework design — a protocol-adapter boundary with a shared replay/simulation
kernel — was chosen partly to allow the same infrastructure to be applied to other
dispute protocols. This document is not required reading for the Kleros integration
review; it is offered for researchers and teams interested in the validation methodology.

---

## Current coverage at a glance

| Area | Count |
|---|---|
| Canonical invariant IDs | **37** |
| Named deterministic scenarios | **48** |
| Named fixture suites | **20+** |
| Statistical simulation modules | **8** |
| EDN parameter configurations | **40+** |
| Adversary models | Ring attacker, colluder, profit-maximizer, forking strategist, flash-loan, reentrancy |

Detailed tables for each area follow in §2–§5 below.

---

## Overview

The Sew protocol robustness workbench is a protocol-specific adversarial testing
framework built around a protocol-adapter boundary intended to support
protocol-specific models behind a shared replay/simulation kernel. Its purpose is
to generate falsifiable evidence about protocol correctness under adversarial,
edge-case, and stress conditions — not to produce qualitative claims.

The framework has three interlocking layers:

| Layer | What it does | Where it lives |
|---|---|---|
| **Deterministic replay** | Replays event sequences against the protocol kernel; checks every invariant after every transition | `contract_model/replay.clj` |
| **Adversarial simulation** | Runs Monte Carlo trials with adversarial and honest agents across a production threat envelope | `sim/adversarial.clj`, `adversaries/` |
| **Statistical simulations** | Hypothesis-driven sweeps over protocol parameter space; each module produces a pass/fail verdict | `sim/waterfall.clj`, `sim/multi_epoch.clj`, `sim/governance_impact.clj`, etc. |

All three layers are pure-functional. No I/O touches the core computation; the
shell layer (`io/`, `db/`) handles persistence. This means the kernel is
testable without a running database or filesystem.

---

## 1. Deterministic Replay Engine

### What it is

The replay engine (`resolver-sim.contract-model.replay`) is an open-world
scenario harness. See `docs/architecture/REPLAY_ENGINE_ARCHITECTURE.md` for the
full architecture. It accepts a scenario map — a sequence of timed events with
agents and protocol parameters — and drives a Sew world-state forward through
each event, checking invariants between every step.

It is protocol-agnostic: all protocol-specific logic (actions, invariant
predicates, state machine) is injected through the `SimulationAdapter`
interface (`protocols/protocol.clj`).

### How a scenario is structured

```clojure
{:scenario-id     "s02-dr3-dispute-release"
 :schema-version  "1.0"
 :initial-block-time 1000
 :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                   {:id "seller"   :address "0xseller"   :strategy "honest"}
                   {:id "resolver" :address "0xresolver" :strategy "honest"}]
 :protocol-params {:resolver-fee-bps 150
                   :appeal-window-duration 0
                   :max-dispute-duration 2592000}
 :events
 [{:seq 0 :time 1000 :agent "buyer"    :action "create_escrow"
   :params {:token "USDC" :to "0xseller" :amount 5000}}
  {:seq 1 :time 1100 :agent "buyer"    :action "dispute"
   :params {:workflow-id 0}}
  {:seq 2 :time 1200 :agent "resolver" :action "resolve"
   :params {:workflow-id 0 :outcome :release}}]}
```

Key properties:

- **Workflow IDs**: sequential integers assigned by creation order (first create → `0`,
  second → `1`, etc.). Use integers directly in `:params/:workflow-id`.
- **Deterministic time**: block time is integer-typed; `time-non-decreasing` is
  an enforced invariant.
- **Schema versioning**: schema versions `"1.0"` and `"1.1"` are supported;
  unknown versions are rejected at validation time.
- **Expected-fail scenarios**: scenarios with `:expected-fail? true` are expected
  to trigger an invariant violation. The runner treats them as passing only when
  the outcome is `:fail`. This documents known-fixed regression cases.

### What is checked after every step

The replay engine calls two classes of invariant predicates after every
successful transition:

1. **Single-world invariants** — properties that must hold on the world-state
   snapshot in isolation (e.g. solvency, fee non-negativity, status consistency).
2. **Transition invariants** — properties that must hold across two consecutive
   world states (e.g. terminal states are absorbing, time is non-decreasing,
   no action after finality).

A violation in either class immediately halts the scenario with outcome `:fail`
and records the violating invariant ID, the step index, and the world-state
diff. Expected-fail scenarios invert this: a halt is the expected outcome.

### Trace minimizer

When a scenario fails, the trace minimizer (`sim/minimizer.clj`) reduces the
event sequence to its **1-minimal subset** that still triggers the same
invariant violation. This is the smallest set of events from which no event can
be removed without suppressing the violation. Minimized traces are the
canonical artifact for bug reports and regression fixtures.

```clojure
;; Produce the 1-minimal failing trace for a target invariant
(minimizer/minimize scenario :solvency)
```

The minimizer is protocol-agnostic; it accepts any `DisputeProtocol`
implementation via the registry.

---

## 2. Invariant Catalogue

**53 canonical invariant IDs** (40 world-level, 13 transition-level) are enforced
across the Sew v1 model, defined in `protocols/sew/invariants.clj` under
`canonical-ids`. Registry tests in `test/resolver_sim/protocols/sew/invariant_registry_test.clj`
ensure every ID is executed by `check-all` or `check-transition`.

Reference-validation evidence IDs map to subsets of these; see
`docs/protocol/evidence-invariant-mapping.md`.

### Accounting invariants

| ID | Meaning |
|---|---|
| `:solvency` | `total-held[t] = sum(live afa[t])` and `total-held[t] ≤ token-balance[t]` |
| `:fees-non-negative` | Accumulated fee pool is never negative |
| `:fees-monotone` | Fee pool never decreases |
| `:held-non-negative` | Per-escrow held amount is never negative |
| `:conservation-of-funds` | Funds in + funds created = funds out + funds held |
| `:fee-cap` | Collected fees do not exceed the fee-cap configured in params |
| `:finalization-accounting-correct` | Claimable credits exactly match what was finalized |
| `:token-tax-reconciliation` | Tax-adjusted token flows reconcile across all escrows |
| `:single-resolution-payout-consistent` | A single-path resolution pays out exactly one side |

### State machine invariants

| ID | Meaning |
|---|---|
| `:terminal-states-unchanged` | `RELEASED`, `REFUNDED`, `RESOLVED` are absorbing; no transition out |
| `:escrow-state-transition-valid` | Every `:escrow-state` change is an edge in `allowed-transitions` (no backward/circular steps) |
| `:all-status-combinations-valid` | `SenderStatus` × `RecipientStatus` combination is valid; terminal escrows require both statuses `:none` |
| `:persisted-escrow-state-valid` | Stored escrows never use `:none` or unknown `:escrow-state` |
| `:escrow-state-in-graph` | Every `:escrow-state` is a node in `state-machine/allowed-transitions` |
| `:escrow-dispute-metadata-consistent` | `:pending` has no dispute timestamp/level; `:disputed` has timestamp |
| `:pending-settlement-consistent` | If `pendingSettlement.exists`, escrow is in `DISPUTED` state |
| `:module-snapshot-immutable` | `:module-snapshots` for in-flight escrows do not change after create |
| `:dispute-resolution-path` | Dispute resolves only through authorized, sequenced resolution path |
| `:escalation-level-monotonic` | Escalation level is non-decreasing within a dispute |
| `:no-withdrawal-during-dispute` | `withdrawEscrow` cannot be called while escrow is in `DISPUTED` state |
| `:no-auto-fraud-execute` | Automated execution cannot fire on an escrow flagged for fraud |
| `:no-stale-automatable-escrows` | No escrow remains in an automatable state past its scheduled execution time |

### Time invariants

| ID | Meaning |
|---|---|
| `:time-non-decreasing` | Block time strictly does not go backwards between events |
| `:time-no-action-after-finality` | No event can be processed against a finalized escrow |
| `:time-lock-integrity` | Time-lock protected operations cannot execute before unlock timestamp |
| `:dispute-timestamp-consistent` | Dispute start timestamp is set exactly once, at dispute initiation |

### Bond and slashing invariants

| ID | Meaning |
|---|---|
| `:bond-liquidity` | Bond posted is held in contract and accessible for slashing |
| `:bond-slash-bounded` | Slash amount ≤ posted bond for the workflow |
| `:appeal-bond-conserved` | Appeal bond is conserved across the appeal lifecycle |
| `:appeal-bond-custody-consistent` | Appeal bond custody state matches the current appeal phase |
| `:slash-status-consistent` | Slash status flags are consistent with resolver freeze state |
| `:slash-distribution-consistent` | Slash proceeds distributed to insurance and treasury sum correctly |
| `:slash-epoch-cap-respected` | Slashes per epoch do not exceed the configured epoch cap |
| `:reversal-slash-disabled` | Reversal slash path is disabled (config-gated) |
| `:fraud-slash-executions-accounted` | Every fraud-detected slash is recorded in the slash ledger |

### Resolver invariants

| ID | Meaning |
|---|---|
| `:resolver-capacity` | Resolver's active dispute count does not exceed capacity |
| `:resolver-not-frozen-on-assign` | A frozen resolver cannot be assigned new disputes |
| `:resolver-bond-mix-valid` | Resolver bond mix (own + delegated) satisfies minimum coverage rules |
| `:senior-coverage-not-exceeded` | Senior resolver coverage exposure does not exceed declared limit |
| `:dispute-level-bounded` | Escalation level cannot exceed the module's maximum round |

### Yield invariants

| ID | Meaning |
|---|---|
| `:yield-position-consistency` | Yield module position is consistent with escrow held state |
| `:yield-exposure` | Aggregate yield exposure does not exceed the yield exposure cap |

---

## 3. Scenario Suite

### Suite composition

The canonical suite (`protocols/sew/invariant_scenarios.clj`) contains **48
named scenarios** spanning four types:

| Type | Count | Purpose |
|---|---|---|
| `:baseline` | 8 | Happy-path flows; confirm basic protocol operations work correctly |
| `:edge-case` | 17 | Boundary conditions; confirm guards and error paths fire correctly |
| `:stress` | 7 | Multi-escrow or high-load conditions; confirm accounting holds under pressure |
| `:adversarial` | 16 | Active adversary agents; confirm incentive alignment holds under attack |

### Baseline scenarios

| Scenario | Coverage |
|---|---|
| `s01-baseline-happy-path` | Create → release; no dispute |
| `s02-dr3-dispute-release` | Dispute raised; resolver releases to buyer |
| `s03-dr3-dispute-refund` | Dispute raised; resolver refunds to seller |
| `s05-pending-settlement-execute` | Split settlement proposed and executed |
| `s06-mutual-cancel` | Mutual cancel without dispute |
| `s13-pending-settlement-refund` | Pending settlement refunded |
| `s16-ieo-create-release` | Instant-escrow-only flow (no resolver) |
| `s18-dr3-kleros-l0-resolves` | Kleros round-2 resolves without further escalation *(note: "L0" in the scenario ID refers to the Kleros-internal dispute index starting at 0, not the Sew escalation round; Sew treats this as round 2)* |

### Edge-case scenarios

> Kleros rows between `GENERATED-EDGE-CASE-SUMMARIES` markers are synced from
> `invariant_scenarios/doc_summaries.clj` via `bb docs:scenarios`.

| Scenario | What it validates |
|---|---|
| `s12a/s12b-snapshot-isolation` | Fee-param change after escrow creation does not apply retroactively (see `doc_summaries.clj` for s12a/s12b detail) |
<!-- GENERATED-EDGE-CASE-SUMMARIES-START -->
| `s04-dispute-timeout-autocancel` | Dispute expires without resolution; auto-cancel |
| `s07-unauthorized-resolver-rejected` | Non-authorized resolver call is rejected |
| `s08-state-machine-attack-gauntlet` | Invalid state transitions attempted; all correctly rejected |
| `s10-double-finalize-rejected` | Attempt to finalize an already-finalized escrow is rejected |
| `s11-zero-fee-edge-case` | Escrow with fee_bps=0; correct handling |
| `s14-dr3-module-authorized` | DR3 module resolves with correct authority |
| `s15-dr3-module-unauthorized-rejected` | DR3 module with wrong authority is rejected |
| `s17-ieo-dispute-no-resolver-timeout` | IEO dispute with no resolver; timeout resolution |
| `s19-dr3-kleros-escalation-rejected-l0-resolves` | Preemptive escalation rejected (no pending settlement); L0 resolves; L1 blocked on terminal escrow |
| `s20-dr3-kleros-max-escalation-guard` | Repeated preemptive escalations rejected; wrong-tier resolver rejected; dispute may stay open |
| `s21-dr3-kleros-pending-cleared-on-escalation` | L0 resolves to pending; buyer escalates (clears pending); L1 resolves; keeper executes settlement |
| `s22-status-leak-agree-cancel-over-dispute` | Regression: agree-to-cancel status cleared when dispute is raised |
| `s23-preemptive-escalation-blocked` | Seller preemptive escalation rejected; L0 resolves; post-terminal escalation rejected |
<!-- GENERATED-EDGE-CASE-SUMMARIES-END -->
| `s46-reorg-idempotence` | Re-submitted event is idempotent (reorg safety) |
| `s66-cooldown-boundary-reorg` | Cooldown window boundary is correctly enforced across reorg |

### Stress scenarios

| Scenario | What it validates |
|---|---|
| `s09-multi-escrow-solvency` | Solvency invariant holds across concurrent escrows |
| `s24-resolver-stake-depletion-cascade` | Stake depletion cascades across 3 slashing events |
| `s38-dr3-bond-mix-valid` | Mixed bond (own + senior delegated) validates correctly |
| `s39-dr3-senior-coverage-delegation` | Senior coverage limit respected under delegation |
| `s40-dr3-freeze-post-slash` | Resolver correctly frozen after slash; no new assignments |
| `s41-dr3-reversal-slash-disabled` | Reversal slash does not fire when config gate is closed |
| `s68-yield-pool-insolvency` | Yield pool insolvency is detected and isolated; escrow accounting holds |

### Adversarial scenarios

Adversarial scenarios involve agents with explicit adversary types and declared
attack traits. The registry currently covers three adversary classes:

#### `:profit-maximizer`

Attempts to extract value from escrow or resolver infrastructure through
misuse of protocol mechanics.

| Scenario | Traits | What is being probed |
|---|---|---|
| `s25-profit-maximizer-slash-lifecycle` | `:multi-step :capital-efficient` | Profit via deliberate slash cascade |
| `s34-profit-maximizer-unchallenged-slash` | `:capital-efficient` | Slash without appeal response |
| `s35-profit-maximizer-governance-wins-appeal` | `:multi-step :high-capital` | Appeal won via governance lever |
| `s36-profit-maximizer-pre-window-execute-rejected` | `:multi-step :adaptive` | Pre-window execution rejected |
| `s37-profit-maximizer-two-resolver-split-outcomes` | `:stealthy :capital-efficient` | Conflicting outcomes across two resolvers |
| `s45-flash-loan-stake-inflation` | `:flash-loan :stake-inflation` | Stake inflation via flash loan |
| `s67-reentrancy-callback` | `:reentrancy :callback` | Reentrancy via callback |

#### `:forking-strategist`

Attempts to exploit escalation mechanics by forcing the dispute into
unfavorable rounds for the opposing party.

| Scenario | Traits | What is being probed |
|---|---|---|
| `s26-forking-strategist-l1-reversal` | `:multi-step :adaptive` | L1 escalation reversal |
| `s27-forking-strategist-l2-fork` | `:multi-step :adaptive` | L2 fork strategy |
| `s28-forking-strategist-late-escalation-rejected` | `:multi-step` | Late escalation outside window rejected |
| `s29-forking-strategist-seller-escalates` | `:multi-step :reactive` | Seller-initiated escalation path |
| `s30-forking-strategist-double-loss` | `:multi-step :adaptive` | Strategy that incurs two losses |
| `s31-forking-strategist-all-levels-confirm` | `:multi-step :high-capital` | All levels confirm original outcome |
| `s32-forking-strategist-premature-settlement-rejected` | `:multi-step` | Settlement during open appeal rejected |
| `s33-forking-strategist-two-escrow-fork-isolation` | `:multi-step :state-isolation` | Escalation of wf0 must not block, overwrite, or contaminate wf1 settlement (shared seller + L0) |

#### `:colluder`

Models coordinated multi-agent attacks involving bribery or collusion.

| Scenario | Traits | What is being probed |
|---|---|---|
| `s42-resolver-buyer-bribery-loop` | `:multi-agent :bribery` | Resolver-buyer bribe loop |

---

## 4. Monte Carlo and Statistical Simulations

### Architecture

The stochastic layer runs independent trials across a parameter space. Each
trial instantiates an honest agent and a malicious agent with the same protocol
parameters, simulates the dispute process stochastically, and records expected
value (EV) for each strategy.

The primary question: **is honest behavior the dominant strategy under the
production threat envelope?**

The dominance condition is:

```
EV(honest) / EV(malicious) > 1.0
```

A ratio above 1.0 means honest resolving is more profitable than malicious
resolving. Pass threshold for most hypotheses is ≥ 80% of trials across a
sweep satisfying this condition.

### Production Threat Envelope (PTE)

The adversarial search uses a defined parameter envelope
(`sim/adversarial.clj`):

```clojure
(def pte-v1
  {:resolver-fee-bps           [100 300]   ; 1–3% resolver fee
   :resolver-bond-bps          [500 1500]  ; 5–15% bond requirement
   :slash-multiplier           [1.0 3.0]   ; 1–3× slash on bond
   :slashing-detection-probability [0.03 0.30]}) ; 3–30% fraud detection rate
```

The hill-climbing adversarial search finds the parameter combination within
the PTE that maximises malicious EV, and reports whether honest behavior is
still dominant at that worst-case point.

### Statistical simulation modules

| Module | File | What it tests |
|---|---|---|
| **Adversarial search** | `sim/adversarial.clj` | Honest/malicious EV dominance across the full PTE; hill-climb + grid search |
| **Waterfall cascade** | `sim/waterfall.clj` | Simultaneous multi-resolver slashes; insurance pool survival; six stress scenarios |
| **Multi-epoch reputation** | `sim/multi_epoch.clj` | Reputation decay over 10+ epochs; Sybil re-entry; governance failure scenarios |
| **Governance delay / impact** | `sim/governance_delay.clj`, `sim/governance_impact.clj` | Resolver freezing eliminates response-time sensitivity; 3-day to 14-day window sweep |
| **Appeal outcomes** | `sim/appeal_outcomes.clj` | Bond waterfall at 50% appeal success rate; surplus maintained above 3,300% |
| **Capacity exhaustion** | `sim/capacity_exhaustion.clj` | Six concurrent-dispute exhaustion scenarios; per-resolver `maxConcurrentDisputes` enforcement |
| **Defection dynamics** | `sim/defection.clj` | Rational defection model; strategy-switching after epoch payoff observation |
| **Stochastic equilibrium** | `sim/stochastic_equilibrium.clj` | Equilibrium stability under stochastic parameter draws |

### Phase parameter configurations

All statistical modules accept EDN parameter files from `data/params/`. The
main configurations are:

| Config | DR tier | Notable feature |
|---|---|---|
| `baseline.edn` | DR3 full system | 10% bond, 2.5% fee, progressive slash, Kleros L2 |
| `dr1-fee-only.edn` | DR1 | No bonds; honest/malicious ratio ≈ 1.0 (baseline comparison) |
| `dr2-reputation.edn` | DR2 | 5% bond, 1.5× slash; ratio ≈ 5.0 |
| `phase-g-slashing-delays.edn` | DR3 | Slash delay sensitivity sweep |
| `phase-h-2d-realistic.edn` | DR3 | 2D bond-mechanics sweep |
| `phase-i-all-mechanisms.edn` | DR3 | All detection mechanisms: fraud + timeout + reversal |
| `phase-j-*.edn` | DR3 | Multi-epoch reputation: stable, Sybil, governance failure |
| `phase-l-*.edn` | DR3 | Waterfall: baseline, cascade, simultaneous slashes, senior degraded |
| `phase-m-*.edn` | DR3 | Governance response time: instant, 3-day, 7-day, 14-day |
| `phase-n-*.edn` | DR3 | Appeal outcomes at varying success rates |
| `phase-o-*.edn` | DR3 | Market exit cascade; graceful pool degradation |
| `phase-ah-*.edn` | DR3 | Ring attacker trajectory sweep |
| `phase-ai-*.edn` | DR3 | Dual-layer ring + escalation trap + mitigation variants |

### Stochastic model layer

The statistical modules draw from pure model functions in `stochastic/`:

| Module | What it models |
|---|---|
| `stochastic/economics.clj` | Fee, bond, slashing, and honest/malicious EV calculations |
| `stochastic/dispute.clj` | Dispute resolution and outcome distributions |
| `stochastic/decision_quality.clj` | Accuracy loss from time pressure; evidence review bonuses |
| `stochastic/evidence_costs.clj` | Effort budget and evidence-verification cost models |
| `stochastic/liveness_failures.clj` | Juror opportunity cost; boredom threshold; adverse selection |
| `stochastic/correlated_failures.clj` | Shared-bias and herding effects in resolver panels |

### Ring attacker model

The `RingAttacker` adversary (`adversaries/ring_attacker.clj`) models a
coordinated ring of *N* resolvers that rotate disputes among members to
suppress per-member fraud detection probability:

```
effective-detection(member) = base-detection / ring-size
detection-avoidance-rate    = 1 − (1 / ring-size)
```

The ring earns the same gross profit as an individual attacker, but each
member stays below the detection threshold longer. This model is the
foundation of the Phase AH/AI multi-epoch ring evasion tests.

### Research simulation coverage

Beyond the canonical phase configurations, `data/params/` contains
purpose-built configurations for specific attack hypotheses:

**Adversarial economics:**

| Config | What it probes |
|---|---|
| `phase-y-evidence-fog.edn` | Does evidence cost asymmetry create exploitable fog-of-war? |
| `phase-z-legitimacy.edn` | Can a resolver maintain legitimacy score while selectively defecting? |
| `phase-ae-fair-slashing.edn` | Does slashing correctly distinguish malicious vs negligent failure? |
| `phase-ai-escalation-trap.edn` | Can an adversary force unnecessary escalation to drain appeal bonds? |
| `phase-ai-ring-model-stochastic.edn` | Ring evasion under stochastic detection regime |
| `phase-ai-ring-detection-mitigation.edn` | Mitigation: dual-layer detection closes ring advantage |

**Governance:**

| Config | What it probes |
|---|---|
| `phase-aa-governance.edn` | Can a governance adversary capture protocol parameters? |
| `phase-ab-effort-rewards.edn` | Do effort-proportional rewards maintain resolver quality? |
| `phase-ac-threshold-sweep.edn` / `phase-ac-trust-floor.edn` | Trust-score floor below which resolver is barred |
| `phase-ad-governance-floor.edn` | Minimum governance participation floor for security |
| `phase-t-governance-capture.edn` | Governance capture drift over multi-epoch runs |

**Economic:**

| Config | What it probes |
|---|---|
| `phase-f-baseline.edn` / `phase-f1-delegation-ring.edn` | Delegation ring; resolver bond mixing |
| `phase-e1-kleros.edn` | Kleros-round economics under adversarial escalation |
| `cartel.edn` / `whale-attack.edn` | Cartel and whale concentration attacks |
| `two-layer.edn` | Two-layer detection (resolver + oracle) interaction |

---

## 5. Fixture Suite and Golden Reports

The fixture suite (`sim/fixtures.clj`) loads scenarios from `data/fixtures/`
as EDN and runs them through the replay engine. The suite runner supports
named suites defined in `data/fixtures/suites/manifest.edn`.

### Available suites

| Suite key | File | Coverage |
|---|---|---|
| `:suites/all-invariants` | `all-invariants.edn` | All 48 named invariant scenarios |
| `:suites/baseline-safety` | `baseline-safety.edn` | Core baseline safety properties |
| `:suites/dr3-critical` | `dr3-critical.edn` | DR3-specific critical paths |
| `:suites/equilibrium-validation` | `equilibrium-validation.edn` | Equilibrium property scenarios |
| `:suites/spe-validation` | `spe-validation.edn` | Subgame-perfect equilibrium validation |
| `:suites/spe-regression` | `spe-regression.edn` | SPE regression cases |
| `:suites/governance-decay` | `governance-decay.edn` | Governance decay scenarios |
| `:suites/token-pathologies` | `token-pathologies.edn` | Fee-on-transfer and rebase token edge cases |
| `:suites/withdrawal-adversarial` | `withdrawal-adversarial.edn` | Adversarial withdrawal paths |
| `:suites/timelock-regression` | `timelock-regression.edn` | Timelock integrity regression |
| `:suites/escalation-collision` | `escalation-collision.edn` | Concurrent escalation race conditions |
| `:suites/same-block-ordering` | `same-block-ordering.edn` | Same-block event ordering determinism |
| Equivalence suites (×8) | `equivalence-*.edn` | Semantic equivalence: accounting, auth paths, ordering, race pairs, money-path integrity, economic stress, escalation boundaries, governance snapshot |

Golden reports (`generate-golden-report`) record the expected pass/fail
outcome for each fixture, allowing regression detection: if a scenario
transitions from pass to fail (or vice versa), the suite fails.

Trace minimization is integrated into the suite: failing scenarios are
automatically minimized before recording.

---

## 6. Layering and Purity Constraints

The framework's correctness depends on strict layer isolation. Violations are
treated as bugs.

```
protocols/sew/*        ← Domain logic (pure)
  invariants.clj         37 canonical invariant predicates
  invariant_scenarios.clj  48 named scenarios
  invariant_runner.clj     In-process suite runner (run-all / run-and-report)

contract_model/replay.clj  ← Protocol-agnostic kernel (pure)
  - imports: protocols/protocol only
  - no db/*, no io/*

sim/*                  ← Statistical and adversarial simulations (pure)
  - imports: contract_model/*, protocols/*, stochastic/*, adversaries/*
  - no db/*, no io/*

adversaries/*          ← Adversary strategies (pure)
  - imports: stochastic/* only

stochastic/*           ← Pure statistical model functions
  - no imports outside stochastic/

db/*                   ← XTDB persistence (shell; side-effectful)
io/*                   ← File I/O (shell; side-effectful)
core.clj               ← CLI dispatch (wires everything)
```

The key invariant: **the functional core is fully testable without a running
XTDB instance or filesystem.** This means every scenario, every Monte Carlo
trial, and every adversarial simulation can be reproduced in a clean JVM
process with a fixed seed and no external dependencies.

---

## 7. Running the Framework

### Full canonical test suite

```bash
./scripts/test.sh all
```

This runs: unit tests → generator regressions → cross-layer contract checks →
deterministic invariant suite → fixture suites.

### Deterministic scenario run & report

See [scenario-run-report.md](./scenario-run-report.md) for the unified summary
contract, `:pass?` semantics, and reporting boundaries.

```bash
clojure -M:run -- --invariants
clojure -M:run -- --invariants --suite yield-provider-scenarios  # canonical provider gate (scenarios/Y01..Y05)
clojure -M:run -- --invariants --suite sew-yield-scenarios
# legacy alias: --suite yield-scenarios → sew-yield-scenarios
clojure -M:run -- --invariants --fixture-suite suites/all-invariants
```

### Fixture suite runner (REPL)

```clojure
(require '[resolver-sim.sim.fixtures :as f])
;; Legacy auto-print + summary map:
(f/run-suite :suites/all-invariants)
;; Data only:
(f/run-suite :suites/all-invariants nil nil {:silent? true})
```

### Monte Carlo simulation (example)

```bash
clojure -M:run -- -p data/params/phase-h-2d-realistic.edn -s
```

### Adversarial hill-climb

```clojure
(require '[resolver-sim.sim.adversarial :as adv]
         '[resolver-sim.stochastic.rng  :as rng])
(adv/hill-climb (rng/make-rng 42) adv/pte-v1 200)
```

### Trace minimization

```clojure
(require '[resolver-sim.sim.minimizer :as m])
(m/minimize failing-scenario :solvency)
```

### List available suites

```clojure
(require '[resolver-sim.sim.fixtures :as f])
(f/list-suites)
```

---

## 8. Evidence Strength and Known Gaps

### What the framework currently covers

- ✅ **37 invariants** enforced after every step of every deterministic replay
- ✅ **48 named scenarios** across baseline, edge-case, stress, and adversarial types
- ✅ **20+ named fixture suites** including equivalence, regression, and adversarial suites
- ✅ **8 statistical simulation modules** with large parameter sweeps across the production threat envelope
- ✅ **40+ EDN parameter configurations** covering DR1/DR2/DR3 and all research hypotheses
- ✅ Ring attacker, bribery, collusion, flash-loan, reentrancy, and escalation
  trap adversary models
- ✅ Multi-epoch reputation decay, governance capture, waterfall cascade
- ✅ Correlated failure models: shared-bias, herding, evidence-fog

### Known gaps (not yet covered)

| Gap | Risk level | Notes |
|---|---|---|
| Formal verification (Halmos / Echidna) | Medium | Foundry invariant suites are present (state/resolver/DR/yield/slashing), but simulator-to-contract parity is not yet tracked by a formal mapping matrix; Halmos harness/profile exists but is not yet a stable CI gate |
| Cross-module yield failure interaction | Medium | Yield dual-failure edge case modelled analytically; no deterministic scenario beyond s68 |
| Kleros round-2 final-appeal path | Low–Medium | S18–S23 cover L0/L1 Kleros paths; `MAX_ROUND` final path not yet a named deterministic scenario |
| Governance collusion across multiple epochs | Medium | `phase-t-governance-capture.edn` exists; no deterministic invariant scenario |
| Token rebase / fee-on-transfer tokens | Medium | `:token-tax-reconciliation` invariant defined; `token-pathologies` suite exists but stochastic coverage limited |
| Concurrent dispute flood (on-chain gas) | Out of scope | `capacity_exhaustion.clj` covers accounting; gas model is not simulated |

---

## Related Documents

- [`docs/testing/TEST_SUITE.md`](testing/TEST_SUITE.md) — canonical test matrix and run commands
- [`docs/testing/RUNNING_TESTS.md`](testing/RUNNING_TESTS.md) — test entrypoints and current baseline
- [`docs/evidence/RESEARCHER_EVIDENCE_PACK.md`](evidence/RESEARCHER_EVIDENCE_PACK.md) — condensed evidence for external reviewers
- [`docs/SYSTEM_OVERVIEW.md`](SYSTEM_OVERVIEW.md) — simulation architecture overview
- [`docs/architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md) — namespace and layer map

---

## Evidence

| | |
|---|---|
| **Contracts** | `sew-protocol` @ `8785826` |
| **Framework** | `protocol-robustness-framework` @ `9fbb4ba` (branch: `further-refactoring`) |
| **Generated/reviewed** | 2026-05-21 |
| **Verification status** | Invariant IDs and counts verified against `protocols/sew/invariants.clj` `canonical-ids`. Scenario IDs verified against `protocols/sew/invariant_scenarios.clj`. Suite list verified against `data/fixtures/suites/manifest.edn`. Statistical module list verified against `src/resolver_sim/sim/`. Phase configs verified against `data/params/`. |
