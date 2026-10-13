# Codebase Index and Stability Assessment

*As of 6 June 2026, branch `clean-refactor`*

---

## What this repository is

A **protocol-agnostic adversarial testing framework** for decentralised dispute
resolution, escrow, and coordination — with the **Sew Protocol** as the primary
subject. Two engines coexist:

| Engine | Location | Purpose |
|--------|----------|---------|
| **Deterministic replay** | `contract_model/`, `protocols/sew/` | Step-by-step scenario replay with invariant checks |
| **Monte Carlo / stochastic** | `sim/`, `stochastic/`, `research/` | Parameter sweeps over resolver incentives and attack economics |

---

## Scale

| Metric | Count |
|--------|------:|
| Source namespaces (`.clj`) | **279** |
| Test namespaces | **~106** |
| Deterministic invariant scenarios | **116** (S01–S107 + boundary cases) |
| JSON scenario fixtures | **~167** |
| Monte Carlo param files (`data/params/`) | **71** |
| Architecture / testing docs | **~110** markdown files |

---

## Source index by module

### Functional core (pure — no I/O)

```
contract_model/     9 files   Protocol-agnostic replay kernel
  replay.clj              Open-world scenario replay engine
  replay/yield.clj        Yield-aware replay extensions
  replay/temporal.clj     Temporal boundary handling
  replay/validation.clj   Trace validation
  idempotency.clj         Idempotent action semantics

protocols/           61 files  Adapter interfaces + Sew implementation
  protocol.clj            SimulationAdapter / EconomicModel interfaces
  sew.clj                   SewProtocol adapter (wires sew/*)
  sew/lifecycle.clj         Escrow create / release / cancel / dispute
  sew/state_machine.clj     7-state escrow FSM
  sew/resolution.clj        Dispute execute / escalate / settle
  sew/accounting.clj        Fee, bond, slashing arithmetic
  sew/authority.clj         Resolver authority checks
  sew/snapshot.clj          Per-escrow frozen terms (make-escrow-snapshot)
  sew/invariants/           10+ invariant domains (solvency, yield, fees, …)
  sew/invariant_scenarios/  S01–S107 scenario definitions (6 files)
  sew/research_models/      8 adversarial research models
  yield.clj                 Yield protocol adapter

scenario/            23 files  CDRS v1.1 theory + expectation evaluators
  theory.clj                Game-theoretic claim falsification
  expectations.clj          Execution-level outcome validation
  suites.clj                Named scenario collections (:sew-yield-scenarios, etc.)
  equilibrium.clj           Stochastic equilibrium analysis
  projection.clj            Funds-ledger projection

sim/                 37 files  Phase harness + Monte Carlo infrastructure
  engine.clj                make-result, run-parameter-sweep, print-phase-header
  batch.clj, sweep.clj      Parameter-space sweep runners
  fixtures.clj              Fixture suite runner (run-suite, minimise-suite)
  minimizer.clj             Trace minimisation
  trajectory.clj            Equity / spread / displacement helpers
  multi_epoch.clj           Multi-epoch reputation (Phase J)
  waterfall.clj             Shortfall repair waterfall
  adversarial/              Ring model, Phase Z, reorg checks
  phase_*.clj               Named phase runners (C, E, F, M)

stochastic/          11 files  Pure statistical/economic models
  economics.clj, dispute.clj, decision_quality.clj, rng.clj, …

yield/               22 files  Yield subsystem (accrual, liquidity, modules)
  modules/                  Archetype factories (liquid_lending, fixed, adversarial, none)
  accounting.clj, invariants.clj, schedule.clj, presets.clj

research/            23 files  Monte Carlo phase implementations (O–AI)
  sew/adversarial/          14 adversarial hypothesis phases
  sew/governance/           4 governance phases
  sew/economic/             6 economic pressure phases

financial/            3 files  NEW (untracked WIP)
  solvency.clj              Cryptographic solvency classification
  finality.clj              Finality tiers
  loss.clj                  Loss accounting

governance/           1 file    Governance rule models
adversaries/          2 files   Adversary strategy protocol + ring attacker
oracle/               1 file    Detection models
economics/            1 file    Canonical payoff calculations
evidence/             1 file    Semantic facts vocabulary
generators/          11 files   Scenario / action / yield generators
time/                 3 files   Deadline model + temporal invariants
definitions/          1 file    Definition registry
```

### Imperative shell (I/O only)

```
io/                  16 files  File I/O
  params.clj                EDN param loading + validation
  results.clj               Result serialisation
  scenario_runner.clj       CLI scenario dispatch
  trace_store.clj           Trace persistence
  claimable_classification_emitter.clj  Artifact emission

db/                   6 files  XTDB persistence
  store.clj                 sew_trial_outcomes + escrow events
  telemetry.clj             Runner output → DB writes
  temporal.clj              Temporal query helpers
```

### Entry points and services

```
core.clj              CLI dispatch (--invariants, --serve, phase flags)
core/cli.clj          Option parsing
core/phases.clj       Phase registry + per-mode runners
server/grpc.clj       gRPC server + session management
benchmark/            8 files — signed benchmark sharing
notebooks/           27 files — Clerk interactive notebooks (SPEDS, yield, manifest)
scripts/              3 files — evidence signing, manifest tools
```

---

## Data and configuration

| Path | Contents |
|------|----------|
| `data/params/` | 71 EDN Monte Carlo parameter definitions (phases O–AI, controls, baselines) |
| `data/fixtures/golden/` | Golden report fixtures for yield scenarios (S68–S115) |
| `data/claims/` | Claim registry (`sew-claims.edn`) |
| `data/yield/` | Yield invariant catalog |
| `scenarios/` | ~120 JSON deterministic scenario files |
| `verification-suite/scenarios/` | Public reference-validation scenarios |
| `suites/reference-validation-v1/` | Public evidence harness manifest |
| `schemas/` | JSON schemas (claimable-classification-v2, etc.) |
| `results/` | Run outputs (gitignored) |

---

## Test infrastructure

Canonical runner: `./scripts/test.sh`

| Mode | What it runs |
|------|-------------|
| `unit` | 210 tests, 1058 assertions (~18 s) |
| `invariants` | S01–S107 deterministic scenarios (~1.3 s) |
| `suites` | Fixture suite runner (all-invariants, equilibrium, SPE) |
| `yield-scenarios` | Sew+yield integration (S78–S110) |
| `reference-validation` | Public evidence harness |
| `monte-carlo` | Representative 4-domain phase sweep |
| `coverage` | Transition/guard coverage gate (≤4 unhit) |
| `adversarial-sweep` | Profitability surface sweep |
| `equivalence-new` | Auth/race/escalation/accounting comparison stack |

CI (`.github/workflows/sew-validation-gates.yml`): core validation → coverage
gates → adversarial surfaces → equivalence/comparison gates.

---

## Key architectural invariants

1. **Functional core, imperative shell** — `db/` and `io/` are the only
   side-effect namespaces.
2. **Protocol adapter boundary** — `SimulationAdapter` in `protocols/protocol.clj`
   decouples replay from Sew.
3. **Three distinct "module" factories** — yield modules, escrow snapshots,
   resolution modules (see `docs/architecture/YIELD_AND_SNAPSHOT_MODULES.md`).
4. **Falsifiable hypotheses** — every phase produces pass/fail with a numeric
   threshold (typically ≥80%).

---

## Stability statement

### Overall verdict: **moderately stable core, actively evolving periphery**

The deterministic protocol kernel is in good shape. The yield, financial, and
stochastic layers are under active development on a refactor branch.

### What is stable

| Signal | Evidence |
|--------|----------|
| Deterministic kernel | **116/116** invariant scenarios pass in **1.3 s**; 1 expected failure (S104, marked XFAIL) |
| Unit test suite | **210 tests, 1058 assertions, 0 failures, 0 errors** |
| Architectural discipline | Layering rules enforced; pure core testable without XTDB or filesystem |
| CI gates | Multi-stage pipeline: unit + invariants + coverage + adversarial + equivalence |
| Replay determinism | Explicit idempotency and dedupe-policy tests; trace-hash reproducibility |
| Recent bug fixes landed | Solvency invariant corrections, yield accrual accounting, artifact emitter hardening, snapshot presets |

### What is in flux (current branch)

| Signal | Evidence |
|--------|----------|
| Active refactor branch | `clean-refactor` with **5 modified** and **3 untracked** files |
| WIP `financial/` module | New cryptographic solvency / finality / loss layer — not yet committed |
| Recent instability in history | Commits titled "solvency bug", "scenario fails", "hardcodings", "snapshot bug" |
| Stochastic claim warnings | Unit run prints failing equilibrium claims (`participation-stable`, `budget-balance`) — informational, not test failures, but indicates economic hypotheses not yet fully satisfied |
| Documentation lag | `docs/SYSTEM_OVERVIEW.md` still references "96 scenarios" and "31 invariants"; codebase now has 116 scenarios and 30+ invariants |
| Yield subsystem growth | 14 sew-yield scenarios (S78–S110), negative-yield paths, shortfall waterfall — largest recent expansion area |

### Risk areas to watch

1. **Solvency accounting** — recent bugs and ongoing `financial/` extraction
   suggest this is the highest-churn correctness surface.
2. **Yield + escrow interaction** — partial liquidity, negative yield, reorg
   races, and shortfall repair add combinatorial complexity.
3. **Stochastic equilibrium claims** — multi-epoch population proxies do not all
   pass; statistical evidence is weaker than deterministic evidence.
4. **S104 (reversal slash appealability)** — explicitly XFAIL; known protocol
   gap, not a test harness bug.

### Practical stability tiers

```
Tier 1 — Production-grade (high confidence)
  contract_model/replay.clj
  protocols/sew/{lifecycle,state_machine,resolution,accounting,authority}
  protocols/sew/invariants/* (except solvency — recently patched)
  scenario/{theory,expectations}
  io/{params,results,scenario_runner}

Tier 2 — Stable with recent churn (medium confidence)
  yield/* (accounting, modules, invariants)
  protocols/sew/invariants/solvency.clj
  sim/{fixtures,batch,sweep,waterfall}
  claimable_classification + artifact emitter

Tier 3 — Active development (lower confidence)
  financial/* (new, untracked)
  research/sew/* (Monte Carlo phases)
  stochastic equilibrium claims
  notebooks/* (Clerk interactive analysis)
```

### Bottom line

The codebase is **fit for deterministic protocol verification** — the replay
engine, invariant suite, and Sew domain logic are well-tested and fast. It is
**not yet settled** as a complete economic-evidence platform: yield/financial
extensions, stochastic equilibrium claims, and the `clean-refactor` branch work
should be treated as pre-release until committed, reviewed, and run through the
full `./scripts/test.sh all` gate.

---

## Related documentation

| Doc | Topic |
|-----|-------|
| `CLAUDE.md` | Layering rules, architecture, testing commands |
| `docs/SYSTEM_OVERVIEW.md` | Plain-language guide for researchers and reviewers |
| `docs/architecture/ARCHITECTURE.md` | Detailed architecture |
| `docs/architecture/YIELD_AND_SNAPSHOT_MODULES.md` | Three module-factory types |
| `docs/testing/RUNNING_TESTS.md` | Test runner reference |
| `docs/conceptual/ARCHITECTURE_STRENGTHS.md` | Design rationale |
