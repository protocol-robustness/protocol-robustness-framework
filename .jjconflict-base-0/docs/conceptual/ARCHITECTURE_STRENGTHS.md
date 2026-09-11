# Protocol Robustness Framework — Architectural Strengths

A conceptual document highlighting the design decisions that make this
framework fit for adversarial protocol analysis at production rigour.

## 1. Functional core, imperative shell

The framework applies the functional-core/imperative-shell pattern
strictly. Every protocol rule, invariant check, yield computation, and
game-theoretic evaluation lives in pure functions over immutable
world-state maps. File I/O and database writes are confined to the `db/`
and `io/` namespaces. This is not a style choice — it is a correctness
enabler.

**Consequences:**

- The **entire contract model** is testable without a running XTDB
  instance, filesystem, or network.  The 200-unit-test suite runs in
  under 30 seconds with 1030 assertions and no test databases.
- Replays are **deterministic**. The same scenario run twice produces
  identical trace hashes. This property is tested explicitly in every
  yield-policy scenario.
- World snapshots are plain Clojure maps — they can be diffed,
  serialised, and inspected directly, without deserialisation layers.

## 2. Protocol as first-class concept

The `SimulationAdapter` protocol in `protocols/protocol.clj` defines
the contract between the generic replay engine and any protocol
implementation:

```clojure
(defprotocol SimulationAdapter
  (protocol-id [_])
  (init-world [_ scenario])
  (build-execution-context [_ agents protocol-params])
  (dispatch-action [_ context world event])
  (check-invariants-single [_ world])
  (check-invariants-transition [_ world-before world-after])
  (world-snapshot [_ world])
  (open-entities [_ world])
  (available-actions [_ world actor]))
```

**Consequences:**

- **`SewProtocol`** — the main production implementation — wires 30+
  invariants, a 7-state escrow FSM, lifecycle operations, dispute
  resolution (including Kleros-style escalation), fee arithmetic, bond
  accounting, slash mechanics, and a yield subsystem.
- **`DummyProtocol`** — a single-`deftype` test double that
  always-passes every invariant and accepts every action. Useful for
  isolating the replay engine during kernel development.
- **`YieldProtocol`** — a separate protocol ID for yield-only
  scenarios, using the same replay engine.

No protocol adapter touches the filesystem or database. The simulation
engine (`replay.clj`) knows nothing about escrow states or dispute
resolution — it dispatches through the protocol interface.

## 3. Invariants as machine-checkable post-conditions

The Sew protocol defines **33 single-world invariants** and **14
cross-world transition invariants** — every single one is a predicate
over world-state maps returning `{:holds? bool :violations [...]}`.

| Category | Examples |
|----------|----------|
| Single-world | `solvency` (total-held = Σ (AFA + bonds + yield + stakes)), `claimable-classification`, `fee-cap`, `conservation-of-funds` |
| Cross-world | `terminal-states-unchanged` (terminal escrows stay terminal), `finalization-accounting-correct` (AFA moves from held to claimable on release), `held-delta-accounted`, `terminal-escrow-accounting-unchanged` |

These are checked **on every step** — both `check-all` (single-world,
after every transition) and `check-transition` (cross-world, after
every successful transition). If any invariant fails, the replay halts
immediately and returns the `last-valid-world` for diagnosis.

**Why this matters in adversarial testing:** An attacker's action may
be accepted by the protocol's own guard checks but still violate a
global invariant. The double-layer checking (protocol guards +
framework invariants) catches these cases.

## 4. Declarative state machine

The escrow state machine is defined as a **declarative graph** in
`types.clj`:

```clojure
(def allowed-transitions
  {:none      #{:pending}
   :pending   #{:disputed :released :refunded}
   :disputed  #{:released :refunded :resolved}
   :resolved  #{}
   :released  #{}
   :refunded  #{}})
```

From this single graph, the framework derives:
- **`terminal-states`** — any state whose outgoing edge set is `#{}`
  (automatically derived, no manual synchronisation)
- **`live-states`** — the complement of terminal states, used by
  solvency, yield exposure, and liveness checks
- **`valid-transition?`** — O(1) edge check
- **`transition-graph-acyclic`** — verified at compile time, enforced
  in state-machine tests

Adding a new protocol state requires changing exactly **one** data
structure. All downstream derivations update automatically.

The state machine has an additional **declarative transition
registry** that maps logical actions to the graph:

```clojure
(def registry
  {:to-disputed
   {:from         #{:pending}
    :to           :disputed
    :guards       [:participant?]
    :effects      [:set-raise-dispute-status :record-dispute-timestamp]
    :state-error  :transfer-not-pending}
   :to-released
   {:from         #{:pending :disputed}
    :to           :released
    :state-error  :invalid-state-for-release}
   ...})
```

This registry drives the **transition coverage** system — every guard,
effect, and error path is tracked, and unhit paths become a backlog for
scenario authoring.

## 5. Fork-replay for counterfactual evaluation

The `resume-from-snapshot` primitive in `replay.clj` allows the
framework to **fork** a simulation from any `:world-checkpoints` entry
(full world-state map captured before each event during replay):

```clojure
(resume-from-snapshot protocol agents params scenario-id world
                      events trace metrics options)
```

This is the foundation of **subgame counterfactual evaluation**:
- For each actor's decision node, enumerate all available actions via
  `proto/available-actions`
- Fork the simulation for each alternative, with the deviation action
  as seq 0 followed by the original trace's continuation events
- Compare terminal utilities to detect **profitable deviations**
- Tag continuation events as `:fork/continuation` and stale rejections
  as `:fork/stale-continuation` for evidence classification

The result is a structured SPE (Subgame Perfect Equilibrium) table
with:
- **Checkability classification** — whether the node has enough
  information for equilibrium evaluation
- **Structured counterexamples** — profitable deviations with
  epsilon-relative regret thresholds
- **Backward-induction mode** — bottom-up evaluation for subgame
  consistency (Phase K)
- **Memoisation** — by deterministic node key to avoid redundant fork
  replays

## 6. Immutable world-state model

The entire world state is a single immutable Clojure map:

```clojure
{:escrow-transfers    {workflow-id {:escrow-state ...
                                    :amount-after-fee ...
                                    :from ..., :to ..., :token ...}}
 :total-held          {token-addr balance}
 :total-fees          {token-addr balance}
 :pending-settlements {workflow-id {:resolution-hash ...}}
 :dispute-levels      {workflow-id level}
 :resolver-stakes     {addr amount}
 :block-time          timestamp
 :yield/positions     {owner-id {:principal ... :shares ... :entry-index ...}}
 :yield/indices       {module-id {token cumulative-index}}
 :yield/risk          {module-id {token {:loss-mode ... :liquidity-mode ...}}}
 :yield/modules       {module-id {:module/id ... :module/type ...}}
 ...}
```

**Design properties:**

- **No mutability, no atoms, no STM.** Every transition function
  returns a new world map. This enables the fork-replay system (snapshot
  the world, replay from it) without any cloning or deep-copy overhead.
- **Diffable traces.** The `diff/projection` function produces
  comparable world snapshots at every trace step, enabling hash-based
  determinism checks across replays.
- **Zero-cost inspection.** Debugging is `(get-in world [:escrow-transfers
  3 :escrow-state])` — no deserialisation, no DB query.

## 7. Multi-layered finality enforcement

Finality (the guarantee that terminal escrows never regress) is enforced
by **three independent layers**:

| Layer | Mechanism | When |
|-------|-----------|------|
| Transition graph | `set-escrow-state` throws `ex-info` on illegal edges | Action dispatch |
| Domain guards | Every lifecycle/resolution function checks for expected live state | Before mutation |
| Invariants | `terminal-states-unchanged`, `terminal-escrow-accounting-unchanged`, `escrow-state-transition-valid`, `no-action-after-finality` | After every step |

The invariant check `terminal-escrow-accounting-unchanged` goes beyond
state labels: it verifies that terminal escrows retain their
`:amount-after-fee`, `:from`, `:to`, and `:token` fields unchanged. A
mutation that modifies terminal accounting without changing the state
keyword would be caught here even if it dodged the first two layers.

In the fork-replay context, stale continuation events that target
already-finalized escrows are dispatched and rejected by the guard
checks rather than crashing. They produce `:rejected` trace entries
tagged `:fork/stale-continuation`, giving evidence consumers full
visibility into expected counterfactual fallout.

## 8. Yield accounting as a modular substrate

The yield system is designed as a **protocol-agnostic accounting
substrate** that can be plugged into any dispute protocol:

- **`update-position-yield`** — standardised position update function
  that computes `current-value = shares × current-index`, derives
  unrealized PnL, and applies configurable loss modes (`:none` for
  optimistic clamping, `:mark-to-market` for signed PnL).
- **Three yield modules** — `liquid-lending` (Aave-like compounding
  indices), `fixed` (simple-interest linear accrual), and `adversarial`
  (drain/bloat actions for stress testing).
- **All modules** now call `update-position-yield` with index tracking,
  guaranteeing consistent position shapes (`:current-index`,
  `:current-value`, `:unrealized-yield`) across archetypes.
- **Yield invariants** — `yield-position-consistency`,
  `yield-exposure`, `sew-live-position?` — verify that yield positions
  remain internally consistent and that the protocol holds sufficient
  reserves for all live yield positions.
- **Loss mode / failure mode system** — `:loss-mode` controls PnL
  clamping; `:failure-modes` triggers protocol-level responses
  (`:negative-yield → mark-to-market`, `:provider-paused → withdraw
  fails`).

## 9. Deterministic scenario-based testing at three scales

| Scale | Description | Count |
|-------|-------------|-------|
| **Unit tests** (`test/`) | Pure-function tests for every state transition, lifecycle operation, invariant, and yield module function | 200 tests, 1030 assertions |
| **Deterministic scenario suite** (`invariant_scenarios.clj`) | S01–S41, full end-to-end escrow traces with invariants checked on every step | 41 scenarios, ~1 second |
| **Monte Carlo phases** (`sim/phase_*.clj`) | Parameter-space sweeps with adversarial strategies, hypothesis falsification, statistical analysis | 35 phases (O–AI) |

The invariant scenario suite is **self-checking**: every scenario
includes expected-failure specifications for each event, and the runner
checks invariants (both single-world and cross-world) after every step.
A passing scenario is one where:
1. Every event produces the expected result keyword (`:ok` or
   `:rejected`)
2. No invariant fails during execution
3. The trace projection hash is deterministic across replays

## 10. Strict layering encodes the dependency budget

The layering rules are not guidelines — they are enforced by the import
graph and would fail a compile check if violated:

```
protocols/protocol.clj     →  imports nothing
    ↑
contract_model/*, protocols/sew/*, protocols/dummy
    ↑
sim/*, scenario/*
    ↑
db/*, io/*
    ↑
core.clj                   →  imports everything
```

Every namespace has a defined **may-import** and **must-NOT-import**
boundary. `stochastic/` can import nothing outside `stochastic/`.
`contract_model/` can import only `protocols/protocol`. `db/` and `io/`
are the only namespaces with side effects.

This discipline means:
- The functional core compiles and runs without any I/O dependencies
- New protocol implementations can be added by writing one
  `SimulationAdapter` implementation — no changes to the replay engine
- The stochastic models are independently testable and reusable across
  protocols

## 11. Hardcoding eliminated by single-source-of-truth derivation

A recent systematic audit identified and eliminated all hardcoded
terminal-state and live-state sets. The framework now uses:

- **`t/terminal-states`** — derived from `allowed-transitions` graph
  (nodes with `#{}` outgoing edges), not manually maintained
- **`t/live-states`** — `#{:pending :disputed}`, exported from
  `types.clj` and referenced by solvency, yield invariants, and trace
  scoring
- **`t/zero-address`** — the canonical null-address sentinel
- **`acct/liquidity-modes`** — the set of modes that block new deposits
- **`sender-statuses`** unified with `recipient-statuses` — one is now
  an alias of the other
- **Status-set derivation** — `valid-status-combination?` derives its
  per-state valid-status sets from `t/sender-statuses` via `disj`
  rather than inlining `#{:none :agree-to-cancel}` and `#{:none
  :raise-dispute}`

A `lint:terminal-states` check (integrated into `bb check`) prevents
re-introduction of any inline `#{:released :refunded :resolved}` literal
in source files. Zero such violations remain.

## 12. The construction is disciplined, not accidental

Several framework properties emerge from consistent application of
design rules rather than one-off fixes:

- **Every action returns `{:ok bool :world world' :error keyword}`.**
  This uniform result type is consumed by the replay engine and the
  invariant checker without protocol-specific knowledge.
- **Invariants return `{:holds? bool :violations [...]}`.**  Every
  checkable predicate follows the same contract, making the registry
  uniform and the test harness generic.
- **Errors are keyword-coded, not string-based.**  The stale
  continuation classification, expected-failure matching, and trace
  inspection all operate on error keywords that are consistent across
  guard functions and domain layers.
- **The world snapshot uses `select-keys` with a controlled comparable
  set.**  Only `:escrow-transfers`, `:total-held`, `:total-fees`,
  `:pending-settlements`, `:dispute-levels`, and `:block-time` are
  included in deterministic comparisons — yield positions and indices
  are excluded, preventing false mismatches from floating-point
  differences.
- **`process-step` is protocol-agnostic.**  The replay loop calls
  `proto/dispatch-action` on every event and `proto/check-invariants-*`
  after every action, but knows nothing about escrows, disputes, or
  yield.  Adding a new protocol means implementing one set of protocol
  methods.

This is a framework that treats adversarial protocol analysis as a
scientific discipline — falsifiable hypotheses, deterministic
replication, machine-checked invariants, and structured evidence — not
as an ad-hoc collection of integration tests.
