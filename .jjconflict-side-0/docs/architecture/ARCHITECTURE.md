# Protocol Robustness Framework — Architecture

## What this is

A framework for adversarial testing and robustness analysis of decentralised
protocols — escrow, dispute resolution, coordination, multi-agent Ethereum
protocols. The Sew Protocol is the main subject of the current simulation work.

The framework operates at two levels:

1. **Deterministic replay** (`contract_model/`, `protocols/`) — step-by-step
   execution of protocol scenarios against adversarial strategies; invariants
   are checked at every transition; traces are recorded.

2. **Statistical simulation** (`sim/`, `stochastic/`) — probabilistic Monte
   Carlo phases that test incentive properties across parameter spaces.

---

## Two-engine design

See `docs/architecture/REPLAY_ENGINE_ARCHITECTURE.md` for the full architecture of
the deterministic replay and statistical simulation engines, their adapter interface
design, namespace boundaries, and dispatch flow.

## Temporal semantics contract (event-time vs valid-time vs record-time)

To avoid overloading one timestamp with multiple meanings, the framework uses
three distinct temporal notions:

- **event-time**
  - Scenario/replay logical time from events (`:time`) and world snapshots
    (`:block-time`).
  - This is protocol-simulation time used for transition semantics,
    invariant checks, deadlines, and ordering.

- **valid-time**
  - XTDB bitemporal truth time (`_valid_from`) used by
    `FOR VALID_TIME AS OF ...` queries.
  - In this repo, valid-time is derived from simulated block-time and represents
    when a persisted fact is considered true in simulated-chain time.

- **record/system-time**
  - Host ingestion/write clock time (when the row/doc was written in real life).
  - Useful for ops/debugging, but **not** used for protocol semantics.

### Policy

1. Protocol semantics and invariants must use **event-time** only.
2. Historical snapshot queries must use explicit **valid-time** (`*-at` APIs).
3. Reporting should expose whether a result used latest/mixed reads vs explicit
   valid-time snapshots.
4. Write paths that emit ordered temporal records should reject decreasing
   valid-time within a run.

```
┌─────────────────────────────────────────────────────────────────┐
│  Replay engine  (contract_model/replay.clj)                     │
│  Protocol-agnostic harness. Drives event sequences, collects    │
│  metrics, calls invariant checks. Knows nothing about escrow,   │
│  disputes, or bonds.                                            │
│                          │                                      │
│              SimulationAdapter interface                        │
│                          │                                      │
│        ┌─────────────────┴──────────────────┐                  │
│        │ SewProtocol                         │ DummyProtocol   │
│        │ (protocols/sew.clj)                 │ (test double)   │
│        │ Wires Sew domain logic into the     │                  │
│        │ adapter interfaces.                 │                  │
│        │                                     │                  │
│        │  protocols/sew/*                    │                  │
│        │  state_machine, lifecycle,          │                  │
│        │  resolution, accounting,            │                  │
│        │  invariants, authority, ...         │                  │
│        └─────────────────────────────────────┘                  │
└─────────────────────────────────────────────────────────────────┘
                          │
              Statistical simulation layer
              (sim/*, stochastic/*, adversaries/*, ...)
```

---

## Adapter interfaces

The replay engine interacts with all protocols through three Clojure
`defprotocol` interfaces defined in `protocols/protocol.clj`. The engine
checks `satisfies?` at runtime for the optional ones.

### 1. `SimulationAdapter` — mandatory

The minimal contract required for deterministic replay, invariant checking,
and trace generation.

| Method | Purpose |
|--------|---------|
| `protocol-id` | Stable string identifier (`"sew-v1"`, `"dummy"`) |
| `init-world` | Construct initial world-state from a scenario map |
| `build-execution-context` | Build the context passed opaquely to `dispatch-action` |
| `dispatch-action` | Apply one event to the world; returns `{:ok bool :world world' :error kw}` |
| `check-invariants-single` | Single-world invariant checks |
| `check-invariants-transition` | Cross-transition invariant checks |
| `world-snapshot` | Lean serializable world snapshot for trace output |
| `available-actions` | Valid action maps available to an actor in the current world |
| `resolve-id-alias` | Resolve string ID aliases in event params to entity IDs |
| `created-id` | Return the entity ID created by a `create-*` action, or nil |
| `open-entities` | Seq of entity IDs still unresolved at end of scenario |
| `project-state` | Execute a protocol-specific read-only state projection query |

### 2. `EconomicModel` — optional

Required for adversarial metrics and payoff analysis. The engine degrades
gracefully if this is absent.

| Method | Purpose |
|--------|---------|
| `adversarial-event?` | Classify an event as adversarial (affects attack-success/failure counters) |
| `classify-event` | Tag an event with metric labels |
| `metric-vocabulary` | Protocol-specific metric keyword set |
| `accum-protocol-metrics` | Update the metrics accumulator after each event |
| `summarise-batch` | Compute summary statistics over a batch of trial outcomes |
| `advisory` | Protocol-specific analysis (action suggestions, attack-surface signals) |

### 3. `AnalysisModule` — optional

Required for differential testing, formal projections, and mechanism
validation. Used in equilibrium-concept validation suites.

| Method | Purpose |
|--------|---------|
| `compute-projection` | `[projection hash]` for differential testing |
| `classify-transition` | Trace metadata for a completed transition |
| `trace-projection` | Terminal trace projection for a replay result |
| `io-projection` | Protocol I/O projection (e.g. `:funds-ledger-view` read-only accounting view) |
| `mechanism-property-validators` | Protocol-specific mechanism validators |
| `equilibrium-concept-validators` | Protocol-specific equilibrium validators |
| `reference-model` | Idealised reference model result for a scenario |

---

## How Sew plugs in

`protocols/sew.clj` defines `SewProtocol`, a Clojure `deftype` that
implements all three adapter interfaces. It is the glue layer between the
framework and the Sew domain logic.

**`SewProtocol` does not contain domain logic.** It delegates:

- `dispatch-action` → `apply-action` multimethod (in `protocols/sew.clj`),
  which dispatches by action string to `protocols/sew/lifecycle.clj`,
  `protocols/sew/resolution.clj`, `protocols/sew/accounting.clj`, etc.
- `check-invariants-*` → `protocols/sew/invariants.clj`
- `compute-projection` → `protocols/sew/diff.clj`
- `advisory` → `protocols/sew/advisory.clj`
- `summarise-batch` → `protocols/sew/db.clj`

The Sew domain logic lives in `protocols/sew/*` (pure functions, no I/O).
`SewProtocol` assembles those functions behind the adapter interface.

### Protocol registry

`protocols/registry.clj` maps protocol-id strings to concrete adapter
instances. The replay engine looks up adapters by id at runtime.

```clojure
{"sew-v1"   resolver-sim.protocols.sew/protocol
 "yield-v1" resolver-sim.protocols.yield/protocol
 "dummy"    resolver-sim.protocols.dummy/protocol}
```

`default-protocol-id` is `"sew-v1"`. `sew-v1` uses the in-process invariant
scenario registry; `yield-v1` currently uses file-backed yield scenarios.
Additional protocols are registered here as they are added.

---

## Namespace map

```
src/resolver_sim/

  contract_model/         ← Replay engine (pure, protocol-agnostic)
    replay.clj              Scenario execution harness; calls SimulationAdapter
                            methods; owns metrics accumulation and trace output.
                            Does not know about escrow, disputes, or bonds.

  protocols/              ← Adapter interfaces + implementations (all pure)
    protocol.clj            Defines SimulationAdapter, EconomicModel, AnalysisModule
    registry.clj            Maps protocol-id → lazily resolved adapter instance
    sew.clj                 SewProtocol adapter (wires sew/* into the interfaces)
    yield.clj               YieldProviderProtocol adapter for file-backed yield scenarios
    dummy.clj               DummyProtocol test double (always-pass, no domain logic)

    common/
      action_context.clj    Shared actor-resolution helpers

    sew/                  ← Sew domain logic (pure functions, no I/O)
      state_machine.clj     Escrow FSM: allowed-transitions graph, apply-transition!
      lifecycle.clj         Escrow lifecycle actions (create, release, cancel, dispute)
      resolution.clj        Dispute resolution (execute-resolution, escalate, settle)
      accounting.clj        Fee/bond/slashing arithmetic; fund conservation checks
      authority.clj         Resolver authority checks; Kleros module integration
      invariants.clj        Protocol post-conditions (30+ invariants)
      invariants/
        accounting.clj      Accounting sub-invariants (FoT, projection hash)
      types.clj             World-state shape, constructors, accessors, constants
      diff.clj              World-state hashing and EVM differential testing helpers
      trace_metadata.clj    Transition/effect/resolution type vocabulary
      registry.clj          Resolver stake/bond registry
      invariant_runner.clj  In-process deterministic scenario runner
      invariant_scenarios.clj Scenario definitions for the deterministic suite
      runner.clj            Top-level live-simulation trial runner
      action_context.clj    Sew-specific actor-resolution helpers
      advisory.clj          Action suggestions and attack-surface signals
      projection.clj        Formal projection helpers
      equilibrium.clj       Equilibrium concept validators
      compat.clj            Action-name normalisation (legacy aliases)
      db.clj                Batch summarisation helpers (pure)
      io/
        trace_export.clj    Trace export helpers

    sew/research_models/  ← Sew-specific research models (pure)
      bribery_markets.clj, contingent_bribery.clj, delegation.clj
      escalation_economics.clj, evidence_spoofing.clj
      information_cascade.clj, panel_decision.clj, resolver_ring.clj

    sew/yield/            ← Yield module integration (pure)
      invariants.clj, policy.clj

  stochastic/             ← Statistical/economic models (pure)
    economics.clj, dispute.clj, decision_quality.clj, rng.clj, types.clj
    bribery_markets.clj, contingent_bribery.clj, correlated_failures.clj
    delegation.clj, difficulty.clj, escalation_economics.clj
    evidence_costs.clj, evidence_spoofing.clj, information_cascade.clj
    liveness_failures.clj, panel_decision.clj, resolver_ring.clj

  sim/                    ← Simulation phases (pure Monte Carlo sweeps)
    phase_o.clj … phase_ai.clj   One file per hypothesis phase
    engine.clj                   Phase harness (make-result, run-parameter-sweep)
    batch.clj, sweep.clj         Batch/sweep runners
    fixtures.clj                 Fixture suite runner (run-suite, list-suites)
    minimizer.clj                Trace minimisation (failing trace → 1-minimal subset)
    trajectory.clj               Equity/spread/displacement trajectory helpers
    multi_epoch.clj              Multi-epoch reputation simulation
    adversarial.clj, waterfall.clj, governance_impact.clj

  governance/             ← Governance rule models (pure)
  adversaries/            ← Adversary strategy models (pure)
    strategy.clj            Adversary defprotocol
    ring_attacker.clj       RingAttack adversary
  oracle/                 ← Detection models (pure)
  economics/              ← Canonical payoff calculations (pure)
  canonical/              ← Canonical action vocabulary (pure)

  db/                     ← Imperative shell: XTDB persistence
    store.clj               sim_trial_results + sim_entity_events table ops
    telemetry.clj           Adapter: sew/runner output → XTDB writes

  io/                     ← Imperative shell: file I/O
    params.clj              Load and validate EDN params
    results.clj             Write CSV / EDN / metadata
    trace_store.clj         Trace persistence
    trace_export.clj        Trace export helpers

  server/                 ← gRPC server + session management
  notebooks/              ← Notebook data helpers (read-only)
  core.clj                ← CLI entry point (imperative shell; dispatch only)
```

---

## Functional core / imperative shell boundary

```
FUNCTIONAL CORE (no I/O, testable without a live DB or filesystem)
  contract_model/*   protocols/*   stochastic/*   sim/*
  governance/*       adversaries/* oracle/*
  economics/*        canonical/*

IMPERATIVE SHELL (effectful)
  db/*       — XTDB reads/writes
  io/*       — filesystem reads/writes
  server/*   — gRPC session state
  core.clj   — CLI entry point
```

**Rule**: namespaces in the functional core must never import `db/*` or
`io/*`. Shell code flows inward; core code never reaches out.

---

## Layering rules

| Namespace | May import | Must NOT import |
|-----------|-----------|----------------|
| `protocols/protocol.clj` | nothing | everything else |
| `contract_model/*` | `protocols/protocol` | anything else |
| `protocols/sew/*` | `protocols/protocol`, `contract_model/*` | `sim/*`, `db/*`, `io/*` |
| `protocols/dummy` | `protocols/protocol` | everything else |
| `stochastic/*` | nothing outside `stochastic/` | everything else |
| `sim/*` | `contract_model/*`, `protocols/*`, `stochastic/*`, `governance/*`, `adversaries/*`, `oracle/*` | `db/*`, `io/*` |
| `governance/*`, `adversaries/*`, `oracle/*` | `stochastic/*` only | `db/*`, `io/*` |
| `db/*` | `contract_model/*`, `protocols/sew/*`, `evaluation.xtdb` | `sim/*` |
| `io/*` | `stochastic/*`, `sim/*` | `db/*` |
| `core.clj` | everything | — |

**Key invariant**: the functional core is fully testable without a running
XTDB instance or filesystem. `db/` and `io/` are the only namespaces with
side effects.

---

## Design principles

- **Functional core, imperative shell** — all protocol logic and simulation
  logic is pure. Only `db/` and `io/` have side effects.
- **Explicit RNG** — randomness is a parameter, never implicit. Same seed +
  params → identical output, byte-for-byte.
- **Pluggable adapters** — the replay engine knows only `SimulationAdapter`.
  Adding a new protocol means implementing the adapter interfaces and
  registering an id in `protocols/registry.clj`.
- **Reproducibility** — every run captures git SHA, seed, JVM version, and
  params in `metadata.edn`.

---

## Testing

**Canonical test runner**: `./scripts/test.sh [mode]`

| Mode | What runs |
|------|-----------|
| `all` (default) | unit tests + deterministic invariant suite + fixture suites |
| `unit` | Clojure unit tests only |
| `invariants` | `clojure -M:run -- --invariants` (S01–S41, ~1 s, no server required) |
| `suites` | Fixture suites (all-invariants + equilibrium-validation) |

Unit test namespaces:
- `test/resolver_sim/protocols/sew/*_test.clj` — state machine, invariants, lifecycle
- `test/resolver_sim/contract_model/replay_bridge_test.clj` — kernel bridge
- `test/resolver_sim/protocols/protocol_adapter_test.clj` — SewProtocol + DummyProtocol interface parity

Integration tests in `test/resolver_sim/db/` require a live XTDB instance on localhost:5432.

---

## Cross-project coupling (eval-engine)

The Protocol Robustness Framework depends on `eval-engine` as a local dep:
```clojure
og/eval-engine {:local/root "../og/eval-engine"}
```

Only `resolver-sim.db.*` may import `evaluation.xtdb`. The rest of the
codebase is decoupled from eval-engine.

---

## XTDB persistence layer

Two tables, auto-created on first write:

| Table | Purpose |
|-------|---------|
| `sim_trial_results` | One row per simulation trial — adapter id, outcome, invariant results, params/metrics blobs |
| `sim_entity_events` | One row per entity state transition — protocol-agnostic event stream with valid-time semantics |

`_valid_from` = simulated block timestamp. Queries with
`FOR VALID_TIME AS OF` reproduce world state at any point in the simulated
chain timeline.

Pass `nil` as datasource to skip all writes — enables offline runs and unit
tests without a live XTDB instance.


---

## Appendix A: Namespace → Layer Map

> Merged from `docs/architecture/layers.md`.

### Layer definitions

| Layer | Meaning |
|---|---|
| Framework Substrate | Reusable mechanics/infrastructure that remains protocol-agnostic (or adapter-pluggable). |
| Adapter Contract | Interface boundary and adapter wiring points. |
| Sew Protocol Model | Sew protocol model (primary validation target) and tightly coupled Sew-specific modules. |
| Research Track | Exploratory/phase-study modules and evidence-generation code not treated as stable framework API. |

> Rule of thumb: classification follows dominant intent, not every incidental use.

| Namespace / Path | Layer | Notes |
|---|---|---|
| `resolver-sim.contract-model.*` | Framework Substrate | Deterministic replay kernel, protocol-agnostic orchestration. |
| `resolver-sim.protocols.protocol` | Adapter Contract | Core protocol interface contracts. |
| `resolver-sim.protocols.registry` | Adapter Contract | Adapter registry/wiring boundary. |
| `resolver-sim.protocols.common.*` | Framework Substrate | Reusable adapter flow-control wrappers. |
| `resolver-sim.protocols.dummy` | Adapter Contract | Minimal reference for adapter conformance. |
| `resolver-sim.protocols.sew` | Sew Protocol Model | Sew adapter — wires Sew domain logic into framework interfaces. |
| `resolver-sim.protocols.sew.*` | Sew Protocol Model | Sew domain semantics and invariants. |
| `resolver-sim.scenario.*` | Framework Substrate | Scenario analysis/projection/evaluation substrate. |
| `resolver-sim.time.model` | Framework Substrate | Generic temporal model primitives. |
| `resolver-sim.time.deadlines` | Framework Substrate | Time/deadline mechanics usable across adapters. |
| `resolver-sim.time.invariants` | Framework Substrate | Temporal invariant scaffolding/helpers. |
| `resolver-sim.db.*` | Framework Substrate | Persistence shell and run/event storage infrastructure. |
| `resolver-sim.io.scenarios` | Framework Substrate | Scenario loading/validation substrate. |
| `resolver-sim.io.trace-score` | Framework Substrate | Generic scoring façade (delegates to protocol-specific providers). |
| `resolver-sim.server.*` | Framework Substrate | Session/server infrastructure with protocol pluggability. |
| `resolver-sim.generators.actions` | Framework Substrate | Generic generation orchestration façade. |
| `resolver-sim.generators.adversarial` | Framework Substrate | Generic adversarial generation façade. |
| `resolver-sim.generators.sew.*` | Sew Protocol Model | Sew-specific action/adversarial templates. |
| `resolver-sim.economics.payoffs` | Framework Substrate | Generic basis-point/capacity accounting helpers; no pro-rata allocator. |
| `resolver-sim.pro-rata.{allocation,engine,redistribution,evaluation}` | Framework Substrate | Pro-rata semantic closure: canonical allocation, redistribution, and result/root projection; no protocol coupling. |
| `resolver-sim.yield.accounting` | Sew Protocol Model | Sew-integrated accounting mechanics. |
| `resolver-sim.yield.registry` | Sew Protocol Model | Sew-integrated module registry/policy assumptions. |
| `resolver-sim.yield.modules.*` | Sew Protocol Model | Current yield modules integrated to Sew semantics. |
| `resolver-sim.sim.minimizer` | Framework Substrate | Protocol-agnostic minimization harness. |
| `resolver-sim.sim.phase-z-scenarios` | Research Track | Phase/scenario study module. |
| `resolver-sim.sim.adversarial.*` | Research Track | Exploratory adversarial studies. |
| `resolver-sim.sim.phase_*` | Research Track | Hypothesis/phase experiments unless explicitly promoted. |
| `resolver-sim.stochastic.*` | Research Track | Statistical/economic exploration models. |
| `resolver-sim.adversaries.*` | Research Track | Strategy-model exploration layer. |
| `resolver-sim.oracle.*` | Research Track | Detection-model research layer. |

**Promotion guidance:** Move a namespace from Research Track to Framework Substrate only when its semantics are adapter-agnostic, it has stable interfaces and tests, and its guarantees are documented here.

---

## Appendix B: Namespace Semantics (Conceptual vs Implementation)

> Merged from `docs/architecture/namespaces.md`.

A recurring source of confusion:

- **Conceptual/public framing**: **Protocol Robustness Framework**
- **Implementation namespace root**: `resolver_sim` (Clojure ns: `resolver-sim.*`)

These are intentionally different concerns. The public conceptual system boundary is the **Protocol Robustness Framework**. The implementation is rooted at `src/resolver_sim/*` as a **stable implementation namespace**, not the public conceptual boundary.

**One-line policy:**
> **Protocol Robustness Framework** (conceptual boundary), implemented under stable namespace root **`resolver_sim`**.

---

## Appendix C: Generalisation Matrix

> Merged from `docs/overview/GENERALISATION_MATRIX.md`.

| Area | Generalisation Level | Reusability | Sew-Specificness | Notes |
|---|---|---|---|---|
| Replay kernel (`contract_model/replay.clj`) | **High** | **High** | Low | Protocol-agnostic orchestration boundary is stable. |
| Protocol interface (`protocols/protocol.clj`) | **High** | **High** | Low | Tiered adapter contracts (Simulation/Economic/Analysis). |
| Shared action context (`protocols/common/action_context.clj`) | **High** | **High** | Low | Reusable flow-control wrappers; no domain error semantics. |
| Scenario analysis utilities (`scenario/*`) | **Medium-High** | **High** | Low-Medium | Generic over trace shape; domain meaning may be protocol-specific. |
| Fixtures + scenario DSL (`data/fixtures/*`) | **Medium** | **Medium-High** | Medium | Reusable structure, but many fixtures encode Sew domain behaviors. |
| Generic adapter façades (`generators/actions.clj`, `io/trace_score.clj`) | **Medium** | **Medium-High** | Medium | Stable façade, currently defaulting to Sew providers. |
| Use-of-funds output contract (`:funds-ledger-view`) | **High (contract), Medium (computation)** | **High** | Medium | Output contract is protocol-adaptable; current computation is Sew-specific. |
| Sew projection implementation (`protocols/sew/projection.clj`) | Low-Medium | Medium | **High** | Contains Sew semantics and funds/drift summaries. |
| Sew protocol implementation (`protocols/sew/*`) | Low | Low-Medium | **Very High** | Core domain state machine, accounting, resolution, invariants. |
| Yield modules (`yield/modules/*`) | Low-Medium | Medium | **High** | Conceptually portable, but integrated to Sew world semantics. |
| Server session layer (`server/session.clj`) | **Medium** | **Medium-High** | Medium | Generic session pattern with optional protocol projections. |
| Experimental simulation (`sim/adversarial/*`, exploratory `sim/*`) | Low | Low-Medium | High | Research-track; not treated as core reusable capability. |

**Quick readout:**
- Most generalised/reusable today: replay kernel + protocol contracts + shared action context.
- Best "reusable with adapter hooks" layer: scenario analysis + funds-ledger output contract.
- Most Sew-specific: `protocols/sew` domain modules and their direct accounting semantics.
