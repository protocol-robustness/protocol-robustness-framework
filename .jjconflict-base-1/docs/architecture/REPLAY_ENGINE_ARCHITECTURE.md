# Replay Engine & Dual-Engine Architecture

## 1. Dual-Engine Design

The framework operates two complementary simulation engines that use common
economic primitives where explicitly shared, while applying different execution
and analysis models:

| Aspect | Deterministic Replay | Statistical (Monte Carlo) |
|--------|---------------------|---------------------------|
| **Purpose** | Deterministic executable invariant checking | Economic incentive analysis |
| **Input** | Scenario event sequences | Parameter maps + strategies |
| **RNG** | Replay core consumes an already-defined event sequence; scenario generation may occur upstream | Seeded PRNG (reproducible) |
| **Checks** | Protocol-supplied per-step and post-replay invariants via `SimulationAdapter` | Aggregate economic, equilibrium, participation, and stability checks |
| **Evidence** | Per-step trace and content-addressed evidence artifacts/DAG; attestations and signatures where configured | Reproducible run metadata, aggregate distributions, epoch trajectories, and economic diagnostics |
| **Results** | Per-step trace | Distributions and confidence summaries over N trials, plus population and strategy trajectories |
| **Code** | `contract_model/replay/` | `sim/`, `stochastic/` |
| **Use case** | Bug finding, regression, audit, invariant validation, and execution substrate for counterfactual and mechanism-property analysis | Parameter sensitivity, population equilibrium, strategy adaptation |

The two engines are not redundant — they address different threat models.
Deterministic replay demonstrates for the executed trace that a *specific event sequence* does not violate
protocol invariants. Monte Carlo estimates or tests under the modelled distribution that under a *distribution of behaviours*, economic incentives point in the right direction.

*Deterministic replay detects violations on explicit, reproducible event traces. Statistical simulation estimates economic and behavioural properties under declared probabilistic assumptions. Neither result should be generalised beyond its scenario or model coverage without an explicit argument.*

---

## 2. Engine A: Deterministic Replay

### 2.1 Public API

The replay engine lives in `contract_model/replay/` and exposes a two-tier
API:

**Tier 1 — Pure computation (no I/O, no evidence chain):**

```clojure
(replay-events protocol scenario opts?)
```

Runs the simulation loop and returns the full result map (trace, metrics,
outcome, expectations, theory diagnostics) without evidence chain, risk
monitoring, or filesystem I/O. Evidence chain functions called during
execution use whatever dynamic context is active (callers like
`replay-with-protocol` supply the context externally).

| Parameter | Type | Description |
|-----------|------|-------------|
| `protocol` | `SimulationAdapter` instance | Protocol adapter |
| `scenario` | map | Scenario with events, agents, params |
| `opts` | map (optional) | Flags, profiles, run-id — passed to `resolve-replay-flags` |

**Tier 2 — Full orchestration with evidence, risk, and I/O:**

```clojure
(replay-with-protocol protocol scenario replay-opts?)
```

Layers evidence chain finalization, risk monitoring, theory diagnostics file
I/O, and scenario snapshot registration on top of `replay-events`.

| Parameter | Type | Description |
|-----------|------|-------------|
| `protocol` | `SimulationAdapter` instance | Protocol adapter |
| `scenario` | map | Scenario with events, agents, params |
| `replay-opts` | map (optional) | Flags, signing keys, TSA, profiles |

**Return map** (same shape for both tiers; Tier 2 adds `:risk-events`):

```clojure
{:outcome :pass | :fail | :invalid
 :scenario-id  "s01-baseline-happy-path"
 :events-processed 14
 :halt-reason nil | :invariant-violated | ...
 :trace [{:seq, :time, :agent, :action, :params,
           :result, :error, :invariants-ok?, :violations, :world, ...}]
 :metrics {:events-accepted 14, :events-rejected 0, :invariant-violations 0, ...}
 :states {0 -> world-snapshot, 1 -> world-snapshot, ...}
 :world-checkpoints {seq -> pre-decision-world}
 :last-valid-world <world>
 :expected-error-analysis {:expected [] :unexpected [] :missing []}
 :risk-events [...]}  ;; Tier 2 only
```

Additional entry points:

| Function | Purpose |
|----------|---------|
| `simple-replay` | Lightweight wrapper calling `replay-events` with relaxed defaults |
| `resume-from-snapshot` | Continue from an existing world for SPE forks |
| `replay-idempotent-same-trace?` | Run twice and verify byte-for-byte equality |
| `validate-scenario` | Validate scenario structure before replay |

### 2.2 Simulation Loop

The replay engine is factored into two layers:

**Pure computation** (`replay-events`):

```
replay-events
  1. Merge replay flags from opts + scenario :options + profile defaults
  2. Build metric vocabulary (base + protocol-specific via EconomicModel)
  3. Validate scenario structure (agents, events, params)
  4. Build execution context (proto/build-execution-context)
  5. Initialize world (proto/init-world)
  6. Delegate to execution/run-simulation-loop
  7. Apply world checkpoint policy (trim per flag)
  8. Evaluate expectations (expected-error analysis, theory diagnostics)
```

**I/O orchestration** (`replay-with-protocol` wraps `replay-events`):

```
replay-with-protocol
  1. Setup fresh evidence chain context (with-fresh-registry,
     with-fresh-chain-cursor, with-fresh-risk-context)
  2. Call replay-events (pure computation above)
  3. Write theory diagnostics to disk
  4. Finalize evidence chain (sign + timestamp)
  5. Register scenario snapshot
  6. Attach risk events
```

The simulation loop (`execution/run-simulation-loop`) processes events one by
one (or in deterministic-batch groups):

```
for each event in events:
  resolve aliases (proto/resolve-id-alias)
  dispatch action (proto/dispatch-action)
  if dispatch fails → reject, continue
  if dispatch succeeds:
    if invariants enabled:
      check single-world invariants (proto/check-invariants-single)
      check transition invariants (proto/check-invariants-transition)
      if any invariant violated:
        revert world to pre-action state
        emit invariant failure evidence
        halt with outcome :fail
    emit transition evidence (before/after worlds, action, result)
    emit invariant attestation evidence (per-step invariant results)
    if AnalysisModule available:
      emit projection evidence (compute-projection hash)
    update metrics
```

### 2.3 Invariant Checking

Two classes of invariants are checked after every action:

**Single-world invariants** — properties that must hold on the world-state
snapshot in isolation. Examples: solvency, fees-non-negative, held-non-negative,
terminal-states-unchanged. These are checked by `check-invariants-single`.

**Transition invariants** — properties that must hold across two consecutive
world states. Examples: time-non-decreasing, no-action-after-finality,
escrow-state-transition-valid. These are checked by
`check-invariants-transition`.

**Violation flow:**

```
dispatch succeeds
  → check-invariants-single(world-after) → :violations?
    → check-invariants-transition(world-before, world-after) → :violations?
      → if violated: revert world, halt, emit invariant-failure evidence
      → if clean: emit transition-evidence + invariant-attestation-evidence
```

Both invariant checks are protocol-provided via the `SimulationAdapter`
interface. The replay engine does not know what specific invariants exist.

### 2.4 Evidence Capture During Replay

Every replay step can produce layered evidence records, controlled by the
`:evidence-mode` replay flag (see §2.5):

| Mode | Transition | Invariant failure | Invariant attestation | Projection | Checkpoint |
|------|-----------|-------------------|----------------------|------------|-----------|
| `:all` | Yes | Yes | Yes | Yes | Yes |
| `:essential` | Yes | Yes | — | — | — |
| `:none` | — | — | — | — | — |

The five evidence layers:

| Layer | Evidence | When | Fields |
|-------|----------|------|--------|
| 2 | Transition evidence | After every dispatch | before/after worlds, action, seq, result |
| 2 | Invariant attestation | After every step | per-invariant pass/fail, links to transition hash |
| 6 | Invariant failure | On violation | failing invariant IDs, step index |
| 8 | Projection evidence | When AnalysisModule available | projection hash link |
| 9 | Checkpoint evidence | At decision nodes (raise_dispute, etc.) | world hash links |

At scenario end, the chain is finalized via `chain/finalize-and-attest!`, which
writes the evidence registry, optionally signs with Ed25519 and timestamps via
RFC 3161. These artifacts feed into the evidence DAG (see
`docs/architecture/EVIDENCE_DAG_ARCHITECTURE.md`).

### 2.5 Replay Profiles

Three replay profiles control which features are enabled:

| Profile | Checks | Expectations | Theory | Temporal | Evidence | Projections | Checkpoints |
|---------|-----------|-------------|--------|----------|---------|-------------|-------------|
| `:golden-full` | Yes | Yes | Yes | Yes | `:all` | Yes | Yes |
| `:fast-regression` | Yes | Yes | Deferred | Yes | `:all` | Limited | No |
| `:minimal` | Yes | No | No | No | `:none` | No | No |

Flags are resolved from explicit opts, scenario `:options`, and profile defaults
via `replay-flags/resolve-replay-flags`.

| Flag | Values | Default | Description |
|------|--------|---------|-------------|
| `:evidence-mode` | `:all`, `:essential`, `:none` | `:all` (full profile), `:none` (minimal) | Controls evidence emission during replay. `:all` emits all five evidence types; `:essential` emits only transition + invariant failure; `:none` skips all emission entirely. |

### 2.6 Deterministic Batch Mode

When multiple events share the same timestamp (`:deterministic-batch` mode),
the engine groups them and applies a deterministic-first-wins ordering:

1. All events with the same timestamp are collected
2. Protocol's `BatchConflictModel/event-conflict-domains` classifies serialization boundaries
3. Events with non-conflicting domains execute in parallel; conflicting domains
   resolve via deterministic ordering
4. Post-batch invariants check the net world state after all batch events
5. Unknown actions receive the conservative `[:global :unknown]` domain

### 2.7 Idempotency

Actions subject to `apply-once` deduplication are declared by the protocol
adapter (`REPLAY_SENSITIVE_ACTIONS.md`). The current catalogue includes:

| Action | Dedupe key fields |
|--------|-------------------|
| `execute-resolution` | `[action agent workflow-id nil nil event-id]` |
| `propose-fraud-slash` | `[action agent workflow-id slash-id nil event-id]` |
| `challenge-proposed-slash` | `[action agent workflow-id slash-id nil event-id]` |
| `resolve-challenged-slash` | `[action agent workflow-id slash-id nil event-id]` |
| `create_escrow` | `[action agent nil nil nil event-id]` |
| `dispute` | `[action agent workflow-id nil nil event-id]` |
| `cancel` | `[action agent workflow-id nil nil event-id]` |
| `finalize` | `[action agent workflow-id nil nil event-id]` |

Deduplication uses `(action agent workflow-id slash-id hop-scope event-id)` as
the composite key, preventing cross-action collisions.

---

## 3. Engine B: Statistical Simulation (Monte Carlo)

### 3.1 Single Trial Model

Each Monte Carlo trial simulates one dispute resolution with RNG-driven outcomes:

```clojure
(resolve-dispute rng params)
;; => {:honest-ev 142.0 :malicious-ev 201.0
;;     :outcome :release :slash-amount 0, ...}
```

A trial involves:
1. Draw random detection roll (oracle detection probability)
2. Determine verdict correctness (resolver skill + noise)
3. Compute fee income and bond loss
4. Compute payoff for honest and malicious strategies
5. Return expected value comparison

Key difference from deterministic replay: no step-by-step state machine, no
invariant checking, no evidence chain. The MC model uses a simplified
closed-form payoff calculation.

### 3.2 Batch Runner

The batch runner (`sim/batch.clj`) runs N trials and aggregates results:

```clojure
(run-batch rng params n-trials)
;; => {:n 10000 :honest-mean 142 :malicious-mean 201
;;     :dominance-ratio 0.71 :pass? false, ...}
```

With early stopping: when 80% confidence threshold is met, the batch stops
early without running all N trials.

### 3.3 Parameter Sweeps

The sweep runner (`sim/sweep.clj`) varies one or more parameters across a grid:

```clojure
(run-parameter-sweep rng base-params sweep-spec)
;; => [{:fee-bps 100 :dominance-ratio 1.2 :pass? true}
;;     {:fee-bps 200 :dominance-ratio 0.9 :pass? false}
;;     ...]
```

The primary question is dominance: `EV(honest) / EV(malicious) > 1.0`.
A sweep pass threshold is normally ≥ 80% of trials satisfying dominance.

### 3.4 Phase System

Statistical simulations are organized into phases (`core/phases.clj`):

| Phase category | Examples | Evidence class |
|----------------|----------|---------------|
| Adversarial | `--adversarial` (hill-climb) | `:protocol-kernel-evidence` |
| Waterfall | `--waterfall` (pool solvency) | `:protocol-kernel-evidence` |
| Multi-epoch | `--multi-epoch` (reputation) | `:protocol-kernel-evidence` |
| Governance | `--governance` (delay/impact) | `:protocol-kernel-evidence` |
| Analytic | `--phase-f` (closed-form math) | `:analytic` |
| Exploratory | `--phase-ae` (attack research) | `:exploratory` |

Each phase is registered in `phase-runners` map mapping CLI flags to runner
functions. Evidence classification (`:protocol-kernel-evidence` vs `:analytic`
vs `:exploratory`) determines which phases are included in evidence packs.

### 3.5 Key Monte Carlo Modules

| Module | What it models |
|--------|---------------|
| `stochastic/dispute.clj` | Resolution outcomes, appeal dynamics |
| `stochastic/detection.clj` | Oracle/detection probability, slash detection |
| `stochastic/economics.clj` | Fee/bond/EV calculations |
| `stochastic/decision_quality.clj` | Verdict accuracy vs time pressure |
| `stochastic/evidence_costs.clj` | Effort budget, verification costs |
| `stochastic/liveness_failures.clj` | Juror boredom, adverse selection |
| `stochastic/correlated_failures.clj` | Shared bias, herding |
| `sim/waterfall.clj` | Insurance pool solvency |
| `sim/multi_epoch.clj` | Reputation decay, Sybil, governance |
| `sim/governance_delay.clj` | Response time sensitivity |
| `sim/governance_impact.clj` | Governance capture modelling |
| `sim/adversarial.clj` | Hill-climb adversarial search |
| `sim/stochastic_equilibrium.clj` | Equilibrium stability |
| `sim/defection.clj` | Rational defection dynamics |

---

## 4. How the Two Engines Relate

### 4.1 Shared Arithmetic

The engines share fee, bond, slashing, and appeal-bond formulas via
`stochastic/economics.clj`. Cross-engine calibration tests verify that both
engines produce identical arithmetic results for the same inputs (the
current catalogue is reported by the benchmark artifacts).

### 4.2 Complementary Threat Models

| Question | Engine |
|----------|--------|
| Can this specific attack succeed? | Deterministic replay |
| Is fraud economically rational on average? | Monte Carlo |
| Does the state machine follow the spec? | Deterministic replay |
| Does governance capture become viable at scale? | Monte Carlo |
| Are funds conserved across all transitions? | Deterministic replay |
| Do honest resolvers out-earn malicious ones? | Monte Carlo |

### 4.3 Calibration Link

The Monte Carlo model accepts a `fraud-success-rate` parameter (default 0.0).
Rather than being empirically calibrated from deterministic results (which
would treat scenario-suite coverage as a probability estimate), this rate
should be treated as an explicit sensitivity parameter in Monte Carlo sweeps.

Deterministic scenarios can eliminate or demonstrate specific mechanistic
attack paths. They cannot determine the frequency of unknown attacks. The
bridge between the two engines is therefore expressed as a coverage-qualified
model input, not a measured frequency:

```clojure
{:fraud-model
 {:covered-attack-classes #{:dispute-resolution :fee-manipulation ...}
  :successful-covered-scenarios 0
  :total-covered-scenarios <suite-count>
  :residual-risk-assumption 0.01
  :residual-risk-source :analyst-assumption
  :scenario-suite-hash "..."
  :model-version "..."}}
```

The Monte Carlo engine can then sweep residual fraud risk rather than treating
deterministic success counts as a probability bound. The state machine remains
the load-bearing mechanism for economic security, but the coverage claim is
qualified by the scenario-suite metadata above.

### 4.4 Game-Theory Applications by Engine

Game-theory concepts are applied across both engines, with different scope
depending on the analysis mode (single trace vs. distributional population).

All SPE-related implementations below are **trace-conditioned epsilon-SPE
analysis** — they fork from a single observed trace at strategic checkpoint
nodes and evaluate regret for encoded alternative actions, using the original
trace as the continuation where possible. They do not enumerate a complete
game tree or represent information sets. See §8.9.

| Concept | Deterministic Engine | Stochastic Engine | Shared? |
|---------|-------------------|-------------------|---------|
| **Trace-Conditioned Regret** | `scenario/subgame_counterfactual.clj` — forks from observed trace at strategic checkpoints, computes local regret per decision node, classifies deviations | N/A (requires explicit trace) | No |
| **Bounded Local Deviation Regret** | Absolute and relative epsilon thresholds via `regret-exceeds-epsilon?` for encoded alternatives | N/A | No |
| **Continuation-Utility Comparison** | `compute-backward-alt-utility` — compares terminal and continuation payoffs for encoded deviations | N/A | No |
| **Reputation-Aware Utility** | `compute-reputation-utility` — wealth + reputation penalties | N/A | No |
| **Strategy Profile Matrix** | `run-profile-matrix` — trace-conditioned epsilon-SPE diagnostic across multiple reputation profiles | N/A | No |
| **Empirical Strategy Dominance** | No encoded adversarial deviation improves utility on the evaluated traces | Honest strategy has higher expected utility across sampled populations and parameter regimes | Concepts differ |
| **Empirical Incentive Alignment** | No adversarial profit exceeds honest baseline on evaluated traces | Multi-epoch: honest EV > malice EV across sampled populations | Concepts differ |
| **Individual Rationality** | No honest participant has negative net payoff (outside option defaults to zero) | Honest cumulative profit > 0 (outside option defaults to zero) | No |
| **Bounded Nash Diagnostic** | No profitable unilateral deviation among the encoded alternatives at the evaluated state or strategy profile | N/A (not established from distributions alone) | No |
| **Bayesian Nash Equilibrium** | Not currently established | Not currently established; stochastic population results may inform future Bayesian best-response analysis | No |
| **Sybil Resistance** | Not directly checked — `attack-successes = 0` is not a sufficient Sybil test; requires comparing utility across identity splits | Ring/collusion models may contribute evidence, but Sybil and collusion resistance remain separate properties | No |
| **Collusion Resistance** | No evaluated coalition deviation increases total coalition utility relative to the non-collusive baseline (within configured coalition and side-payment model) | Ring-model sweeps over coalition size and side-payment assumptions | No |
| **Shortfall Conservation** | Allocated + residual amounts reconcile with available value | Closed-form conservation across parameter sweeps | Same — both check accounting conservation |
| **Pro-rata Allocation Correctness** | Trace output matches the specified weight/cap allocation rule | Closed-form proportionality, cap, redistribution, and monotonicity checks | Same metric definition, independently evaluated |
| **Cancellation-Specific Deviation Analysis** | Sew adapter: regret filtered to cancel-specific nodes; establishes cancellation dominance only over encoded alternatives | N/A | No |
| **Fraud Detection** | Closed-form `fraud-survival-probability`, `breakeven-detection` | Same formulas in `resolve-dispute` | **Yes** — shared implementation |
| **Strategy Dominance** | Referenced in adversarial hill-climb search | `strategy-dominance-score` = EV(honest)/EV(malicious) | Same metric definition, independently evaluated |
| **Adversarial Hill-Climb** | Cross-run search layer — uses deterministic replay batches as the evaluation function | N/A | No |
| **Adversary Policies** | Explicit scripted attackers on concrete traces (Static, Bribery, Evidence, Adaptive) | Population-level malicious, collusive, lazy, and adaptive agent policies | No |
| **Strategy Adaptation** | N/A | Agents switch strategies based on payoff | No |
| **Liveness Incentives** | N/A | Incentive strength classification | No |
| **Evidence Costs** | N/A | Load-dependent rational threshold | No |
| **Result Strength** | `:pass`, `:epsilon-pass`, `:profitable-deviation`, `:inconclusive`, `:not-checkable` | N/A | No |
| **Coverage** | `:off-path-coverage` with evaluated/total node counts and fraction | N/A | No |
| **Continuation Mode** | `:forward` (trace-follow) or `:backward-induction` | N/A | No |

The deterministic engine provides the execution and evidence substrate for
explicit-trace counterfactual analysis, bounded deviation-regret checks,
mechanism-property evaluation, and adversarial strategy testing. The
stochastic engine complements this with population-level expected-utility,
adaptation, collusion, participation, and stability analysis. Results should
distinguish formal guarantees from scenario-conditioned checks and empirical
proxies.

The "Shared?" column indicates whether both engines call the same underlying
pure function or validated equivalent implementation ("shared implementation"),
use the same metric definition evaluated independently ("same metric
definition"), or apply the same conceptual idea through different means
("concepts differ").

---

## 5. Adapter Interface Design

The replay engine interacts with all protocols through a three-tier interface
system defined in `protocols/protocol.clj`. Interfaces are detected at runtime
via `satisfies?` — protocols opt in to features they support.

### 5.1 SimulationAdapter (Mandatory)

12 methods required for deterministic replay. The replay engine knows nothing
about protocol semantics — all domain logic is injected through this interface.

| Method | Purpose |
|--------|---------|
| `protocol-id` | Stable string identifier |
| `init-world` | Construct initial world from scenario map |
| `build-execution-context` | Build opaque context for dispatch |
| `dispatch-action` | Apply one event to world |
| `check-invariants-single` | Single-world invariant checks |
| `check-invariants-transition` | Cross-transition invariant checks |
| `world-snapshot` | Serializable world projection for trace |
| `available-actions` | Valid actions for an actor |
| `resolve-id-alias` | Resolve entity ID aliases in events |
| `created-id` | Extract entity ID from create action |
| `open-entities` | Unresolved entities at scenario end |
| `project-state` | Query world via protocol-specific projection |

### 5.2 EconomicModel (Optional)

6 methods for adversarial metrics and payoff analysis. The engine degrades
gracefully when absent.

| Method | Purpose |
|--------|---------|
| `adversarial-event?` | Classify event as adversarial |
| `classify-event` | Tag event with metric labels |
| `metric-vocabulary` | Protocol-specific metric keywords |
| `accum-protocol-metrics` | Update metrics after each event |
| `summarise-batch` | Summary stats over trial outcomes |
| `advisory` | Protocol-specific analysis |

### 5.3 AnalysisModule (Optional)

7 methods for differential testing, formal projections, and mechanism
validation.

| Method | Purpose |
|--------|---------|
| `compute-projection` | Protocol projection hash |
| `classify-transition` | Trace metadata for transition |
| `trace-projection` | Terminal trace projection |
| `io-projection` | Protocol I/O view (e.g. `:funds-ledger-view`) |
| `mechanism-property-validators` | Mechanism-specific validators |
| `equilibrium-concept-validators` | Equilibrium validators |
| `reference-model` | Idealized reference result |

### 5.4 BatchConflictModel (Optional)

1 method for deterministic batch ordering.

| Method | Purpose |
|--------|---------|
| `event-conflict-domains` | Classify serialization boundaries |

### 5.5 Protocol Registry

Protocols register in `protocols/registry.clj`:

```clojure
{"sew-v1"   resolver-sim.protocols.sew/protocol
 "yield-v1" resolver-sim.protocols.yield/protocol
 "dummy"    resolver-sim.protocols.dummy/protocol}
```

The registry uses `requiring-resolve` for lazy loading.

---

## 6. Namespace Architecture

### 6.1 Layering

```
core.clj                     ← CLI entry point (impure shell)
  ├── io/scenario_runner.clj ← Scenario loading, dispatch, reporting (impure)
  ├── core/phases.clj        ← Phase registration, I/O orchestration (impure)
  │
  ├── contract_model/replay/ ← Deterministic replay engine
  │   ├── replay.clj         ← Public API: replay-events (pure), replay-with-protocol (I/O layer)
  │   ├── execution.clj      ← Simulation loop, step processing (pure-ish)
  │   ├── flags.clj          ← Replay profile resolution (pure)
  │   ├── checkpoints.clj    ← World checkpoint management (pure)
  │   ├── metrics.clj        ← Metric accumulation (pure)
  │   ├── analysis.clj       ← Expected error analysis (pure)
  │   ├── validation.clj     ← Scenario validation (pure)
  │   ├── temporal.clj       ← Temporal rule evaluation (pure)
  │   └── yield.clj          ← Yield-v1 replay (contains some I/O)
  │
  ├── sim/                   ← Statistical/Monte Carlo engine
  │   ├── batch.clj          ← Batch runner, aggregation (pure)
  │   ├── engine.clj         ← Result schema, sweep runner (pure)
  │   ├── sweep.clj          ← Parameter sweeps (pure)
  │   ├── fixtures.clj       ← Fixture suite runner (pure)
  │   ├── minimizer.clj      ← Trace minimization (pure)
  │   ├── adversarial.clj    ← Hill-climb search (pure)
  │   ├── waterfall.clj      ← Pool solvency (pure)
  │   ├── multi_epoch.clj    ← Reputation simulation (pure)
  │   └── ...                ← Phase-specific modules
  │
  ├── stochastic/            ← Statistical model functions (pure)
  │   ├── dispute.clj        ← Single trial resolution
  │   ├── detection.clj      ← Oracle/detection models
  │   ├── economics.clj      ← Fee/bond calculations
  │   └── ...
  │
  ├── protocols/             ← Adapter interfaces + implementations
  │   ├── protocol.clj       ← defprotocol definitions (pure)
  │   ├── registry.clj       ← Protocol lookup (side-effectful)
  │   ├── sew.clj            ← SewProtocol adapter
  │   ├── dummy.clj          ← DummyProtocol test double
  │   └── sew/*              ← Sew domain logic (pure)
  │
  ├── evidence/              ← Evidence chain + DAG (impure)
  ├── db/                    ← XTDB persistence (impure shell)
  ├── io/                    ← File I/O (impure shell)
  └── server/                ← gRPC server (impure shell)
```

### 6.2 Purity Constraints

The key invariant: the functional core (replay engine + MC engine + protocol
domain logic) is fully testable without a running database or filesystem.

| Layer | Purity | Testable without I/O? |
|-------|--------|-----------------------|
| `protocols/sew/*` | Pure | Yes |
| `contract_model/replay/execution.clj` | Pure-ish | Yes (evidence is best-effort) |
| `contract_model/replay/flags.clj` | Pure | Yes |
| `contract_model/replay/checkpoints.clj` | Pure | Yes |
| `contract_model/replay/metrics.clj` | Pure | Yes |
| `contract_model/replay/analysis.clj` | Pure | Yes |
| `contract_model/replay/validation.clj` | Pure | Yes |
| `contract_model/replay/temporal.clj` | Pure | Yes |
| `sim/*` | Pure | Yes |
| `stochastic/*` | Pure | Yes |
| `contract_model/replay/replay.clj` | Contains I/O | Partially |
| `io/scenario_runner.clj` | Impure | No |
| `evidence/chain.clj` | Impure | No |
| `db/*` | Impure | No |

---

## 7. CLI Dispatch

The CLI entry point (`resolver-sim.core/-main`) dispatches between engines:

```
-main
  ├── --invariants      → scenario-runner/run-and-report (deterministic replay)
  ├── --serve           → gRPC server
  ├── --diff-traces     → diff-runner
  └── <phase flag> or default
       ├── --adversarial   → hill-climb search (MC)
       ├── --sweep         → parameter sweep (MC)
       ├── --multi-epoch   → reputation simulation (MC)
       ├── --waterfall     → pool solvency (MC)
       ├── --governance    → governance impact (MC)
       ├── --phase-*       → named phase runner (MC or analytic)
       └── (default)       → run-simulation batch (MC)
```

The invariant branch routes through `scenario-runner/run-and-report` which:

1. Loads scenarios from CLI args or suite manifests
2. Resolves the protocol adapter
3. Creates a protocol-specific replay function via `replay-fn-for-protocol`
   (sew-v1 → `replay-with-protocol`, yield-v1 → `replay-yield-scenario`)
4. Runs each scenario through the replay function
5. Aggregates results, writes artifacts, validates the evidence chain

Phase branches route through `core/phases.clj` phase-runners map, which
associates CLI keywords with runner functions. Evidence class annotation
determines inclusion in evidence packs.

---

## 8. Key Design Decisions

### 8.1 Protocol-Agnostic Kernel

The replay engine knows nothing about protocol semantics. All domain logic is
injected through `SimulationAdapter`. This means any protocol implementing the
interface gets deterministic replay, invariant checking, and evidence chain
integration without engine changes.

### 8.2 Runtime Interface Detection

`satisfies?` over compile-time protocols. Features are detected at runtime,
allowing protocols to gradually adopt optional interfaces without breaking
changes to the engine.

### 8.3 Evidence chain and Evidence DAG integration

Every replay step emits hashed evidence records linked by content hashes.
At scenario end, the chain is finalized with optional cryptographic signatures
and RFC 3161 timestamps. This makes replay output independently verifiable.

### 8.4 Separation of I/O from Computation

The replay engine separates pure computation from I/O. `replay-events` is a
pure function that runs the simulation loop and returns trace + metrics without
side effects. `replay-with-protocol` layers evidence chain finalization, theory
diagnostics file I/O, and risk monitoring on top of `replay-events`. Library
consumers (notebooks, `simple-replay`) can use `replay-events` directly and
skip the I/O layer.

### 8.5 Deterministic Batch Ordering

Same-timestamp events use conflict-domain classification for deterministic
ordering. Unknown actions get the conservative `[:global :unknown]` domain.

### 8.6 Two-Tier Metrics

Base metrics (event counts, acceptance, invariant violations) are engine-owned.
Protocol-specific metrics via `EconomicModel/accum-protocol-metrics` extend the
set. The `:metrics-profile` flag selects the accumulation path:

| Profile | Protocol vocab in `zero-metrics` | `accum-protocol-metrics` called | Yield/provider merges |
|---------|--------------------------------|--------------------------------|----------------------|
| `:sew-integrated` | Yes | Yes | Yield |
| `:yield-provider` | Yes | Yes | Yield + provider |
| `:base` | No | No | None |

The `:base` profile produces only the 8 universal counters
(`:attack-attempts`, `:attack-successes`, `:rejected-attacks`, `:reverts`,
`:invariant-violations`, `:batch-buckets`, `:batch-events`, `:batch-conflicts`)
and is suitable for library-style usage where only structural event counts
matter. Pass `{:metrics-profile :base}` in replay opts to activate it.

### 8.7 Three Replay Profiles

Profiles (`:golden-full`, `:fast-regression`, `:minimal`) allow trading
thoroughness for speed depending on context (CI, development, library use).

### 8.8 MC Engine Uses Simplified Model

The Monte Carlo engine does not run the protocol state machine. It uses
closed-form stochastic models of dispute resolution. This is a deliberate
simplification: the MC engine measures *directional incentive alignment*, not
*mechanistic protocol adherence*. The deterministic engine covers the latter.

### 8.9 Trace-Conditioned SPE

The SPE implementation is trace-conditioned epsilon-SPE analysis, not full
game-theoretic SPE. It evaluates deviations from a single observed trace at
strategic checkpoint nodes identified by the protocol adapter. It does not
enumerate the full game tree or represent information sets.

The `:equilibrium-claim-tier` metadata on each equilibrium result documents
whether deviations were actually evaluated (`:trace-conditioned`) or only
single-trace heuristics were applied (`:proxy`). The `:off-path-coverage`
map reports how many decision nodes were examined and how many could be fully
evaluated.

Full mechanism-wide SPE is not a tractable target for the unrestricted
protocol model. The protocol has an effectively unbounded state and action
space: arbitrary numbers of escrows and resolvers, large parameterised token
amounts, repeated interactions, appeal sequences, reputation-dependent
histories, and cross-workflow economic dependencies. Complete extensive-form
analysis would also require explicit observation models, information sets,
continuation strategies, beliefs, and utility functions for every participant
role.

The practical development frontier:

1. **Strategic action generation** — connect adapter-defined actions to the
   fork machinery while canonicalising and bounding parameterised alternatives.
2. **Policy- or simulation-based continuation values** — replace mechanically
   following the original trace with declared actor policies or seeded
   stochastic rollouts.
3. **Selective multi-ply search** — evaluate deviations and responses at
   important decision nodes under explicit depth, branch, pruning, and horizon
   limits.
4. **Finite mechanism-specific game abstractions** — define bounded game
   profiles for high-value mechanisms such as cancellation, appeals, resolver
   selection, and shortfall allocation.

Results must expose their analytical scope: decision nodes examined, candidate
actions, continuation model, search horizon, omitted alternatives, utility
assumptions, and statistical uncertainty. The term "SPE" should be reserved
for finite, explicitly defined game abstractions in which all relevant
subgames and deviations have been evaluated. Current unrestricted-protocol
results should be described as trace-conditioned, policy-conditioned, bounded,
or statistical sequential-rationality diagnostics.

### 8.10 Replay Core vs. Analysis Layers

The replay engine (`contract_model/replay/`) is a deterministic event processor:
it takes a protocol adapter and event sequence, applies actions one by one,
checks invariants, accumulates metrics, and optionally emits evidence. It
knows nothing about game theory, equilibrium concepts, or adversarial search.

Higher-level analysis modules use the replay engine as an evaluator:

| Module | Uses replay as | Located in |
|--------|---------------|------------|
| Trace-conditioned epsilon-SPE (`subgame-counterfactual`) | Calls `resume-from-snapshot` to fork at decision nodes, runs continuation traces | `scenario/subgame_counterfactual.clj` |
| Mechanism property checks | Wraps replay result in equilibrium vocabulary | `scenario/equilibrium.clj`, `protocols_src/.../sew/equilibrium.clj` |
| Adversarial hill-climb | Evaluates each parameter set via batch replay | `sim/adversarial.clj` |

These layers are not part of the replay kernel. The architecture doc groups
them under "deterministic replay" because they share the replay engine as
their evaluation substrate, but their analytical claims (SPE, dominance,
adversarial optimality) are produced by the analysis layer, not by the replay
core itself.

---

## 9. References

- `src/resolver_sim/contract_model/replay.clj` — Public API: `replay-events` (pure), `replay-with-protocol` (I/O layer)
- `src/resolver_sim/contract_model/replay/execution.clj` — Simulation loop
- `src/resolver_sim/contract_model/replay/flags.clj` — Replay profile resolution
- `src/resolver_sim/contract_model/replay/checkpoints.clj` — World checkpoints
- `src/resolver_sim/protocols/protocol.clj` — Adapter interface definitions
- `src/resolver_sim/protocols/registry.clj` — Protocol adapter registry
- `src/resolver_sim/core/phases.clj` — Phase registration and dispatch
- `src/resolver_sim/core.clj` — CLI entry point
- `src/resolver_sim/io/scenario_runner.clj` — Scenario orchestration
- `src/resolver_sim/sim/batch.clj` — MC batch runner
- `src/resolver_sim/sim/engine.clj` — MC result schema
- `src/resolver_sim/stochastic/dispute.clj` — Single MC trial
- `src/resolver_sim/stochastic/economics.clj` — Shared formula layer
- `docs/architecture/ARCHITECTURE.md` — Overall namespace and layer map
- `docs/architecture/EVIDENCE_DAG_ARCHITECTURE.md` — Evidence DAG architecture
- `docs/architecture/EVIDENCE_CHAIN_ARCHITECTURE.md` — Evidence chain architecture
- `docs/architecture/framework-boundaries.md` — Framework vs adapter vs protocol boundaries
- `docs/architecture/ADAPTER_AUTHORING_GUIDE.md` — Protocol adapter authoring guide
- `docs/replay/REPLAY_SENSITIVE_ACTIONS.md` — Idempotency specification
- `docs/replay/DEVIATION_EVENT_POLICY.md` — SPE counterfactual event policy
- `docs/ROBUSTNESS_FRAMEWORK.md` — Invariant catalogue and scenario suite
- `docs/SYSTEM_OVERVIEW.md` — Plain-language engine overview
