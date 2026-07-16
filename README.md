# Protocol Robustness Framework

A framework for adversarial multi-actor scenario testing, specializing in robustness analysis for escrow, dispute-resolution, and state-machine protocols.

**New to the codebase?** The project root shows only the protocol-agnostic
framework by default (`:paths ["." "src"]`).  Protocol implementations live
under `protocols_src/` and are added via the `:with-sew` alias:

    clojure -M:with-sew            # full-stack REPL
    clojure -M:test:with-sew       # run all tests (framework + Sew)

The `workspaces/with-sew/` directory provides the same full-stack view
without the alias.  See `workspaces/MAP.md` for details.

## Public Benchmark Showcase

PRF is a working robustness framework with a deliberately limited public
benchmark catalogue. The current catalogue demonstrates named workloads and
claims; it does not claim comprehensive protocol assurance.

| Today | Scope |
|---|---|
| Sew-backed deterministic replay | The included Sew dispute workload produces matching canonical results and evidence roots across repeated PRF-runner executions. |
| Sew yield shortfall checks | Experimental: the named yield workload now requires closed-form allocation artifacts before preservation, cap, or allocation correctness claims can be demonstrated. |
| Sew dispute/slashing checks | Active Sew benchmarks run their declared invariant-backed safety and liveness claims. |

Experimental profiles retain visible deferred claims for protocol robustness and
shortfall research. Defined concepts and scenario mappings are explanatory;
they do not count as evaluated coverage without a runnable claim evaluator.

Start with the [benchmark showcase](benchmarks/README.md#public-showcase) for
the capability matrix, lifecycle definitions, and evidence-inspection path.
The recorded yield-shortfall run currently has 15/15 passing scenarios under
the previous invariant-only mapping; its
independent CLI verification is pending an unrelated execution-registry repair.

## Repository map

This repository is split between the protocol-agnostic Protocol Robustness Framework
(PRF) and protocol-specific implementations such as Sew.

- `src/` — PRF framework core code. Should not depend on Sew.
- `protocols_src/` — Protocol implementations. Sew lives here.
- `test/` — Framework and protocol tests.
- `scenarios/` — Canonical scenario definitions.
- `suites/` — Runnable validation suites with expected/actual outputs.
- `docs/` — Documentation, including specs under `docs/specs/`.
- `resources/test-vectors/` — Canonical conformance vectors.
- `results/` — Generated local run outputs (not source).
- `workspaces/` — Curated developer views and templates.
- `integration/` — Integration-specific projections (e.g. Cartesi).
- `fixtures/` — Reusable test and demo inputs.
- `notebooks/` — User-facing Clerk notebooks.
- `examples/` — Stable educational examples and expected artifacts.
- `scripts/` — Build, validation, and tooling scripts.
- `schemas/` — Machine-readable schema definitions.
- `config/` — Configuration files.

## What this is

The Protocol Robustness Framework enables adversarial simulation, invariant checking, and deterministic replay for complex protocol systems.

It is designed to answer a question that ordinary unit tests do not:

> Does the protocol still behave correctly when multiple participants act strategically, adversarially, or unexpectedly over time?

The framework models how actors interact across sequences of valid protocol actions, then verifies that the system maintains its critical correctness, safety, liveness, and accounting guarantees.

It is especially useful for systems where failures do not come from invalid code paths, but from valid actions combining in unexpected, sequence-dependent ways.

## Framework building blocks

The repository provides reusable components for protocol engineering and adversarial validation:

* **Protocol Adapter Interface**
  `src/resolver_sim/protocols/protocol.clj`

* **Deterministic Replay Harness**
  `src/resolver_sim/contract_model/replay.clj`

* **Composable Fixture System**
  `data/fixtures/`

* **Invariant-Driven Validation**
  Continuous checking of protocol guarantees during simulated execution.

* **Golden Snapshotting**
  Behavioural drift detection across protocol changes.

* **Scenario and Replay Infrastructure**
  Reproducible test cases for regression, adversarial exploration, and research-grade evidence generation.

## Sew protocol model

The repository includes a detailed Sew protocol model as the primary validation target.

Sew is both:

1. A protocol model for escrow and dispute-resolution robustness analysis.
2. The main worked example showing how to implement the framework interfaces for a new protocol.

The Sew adapter lives under:

```text
protocols_src/resolver_sim/protocols/sew/
```

When adapting the framework to another protocol, Sew should be treated as the reference implementation pattern.

## Why this exists

Traditional smart contract testing often asks:

> Does this function work?

The Protocol Robustness Framework asks:

> Does the protocol still work when participants behave strategically or adversarially over time?

For escrow, dispute-resolution, yield, settlement, and slashing systems, many failures emerge only through interaction:

* Actions are individually valid.
* State transitions are locally correct.
* But the combined sequence creates unsafe, unfair, insolvent, or stuck states.

This framework is designed to surface those failures before they reach production.

## What it verifies

The framework supports validation across several dimensions:

* **State-machine correctness**
  Transitions are checked against the formal protocol model.

* **Invariant enforcement**
  Critical properties such as bond liquidity, withdrawal safety, fee caps, time-locks, accounting boundaries, and settlement rules are continuously checked.

* **Accounting integrity**
  Funds, liabilities, fees, yield, slashing, and deferred claims are reconciled across transitions.

* **Adversarial liveness**
  Scenarios can detect conditions where assets become stuck due to rational, malicious, or strategic behaviour.

* **Deterministic replay**
  Scenarios are reproducible and replayed step-by step.

* **Model ↔ EVM equivalence**
  Execution traces can be validated against Solidity implementations where an EVM integration is available.

## Game-theoretic validation scope

The framework includes game-theoretic validation tools, but these should be interpreted carefully.

Current scope:

* **Trace-end analysis**
  Provides proxy validation on realised traces.

* **Consistency checking**
  A `:pass` confirms that an observed trace is consistent with claimed protocol properties.

* **Bounded public-state epsilon-SPE proxy**
  Supports bounded equilibrium-style checks over public state, including counterexample generation.

* **Forward and backward-induction evaluation modes**
  Used for structured strategic analysis over scenario traces.

Important limitation:

Single-trace execution is not a formal proof of safety across all possible information sets. Stronger claims such as full Subgame Perfect Equilibrium remain `:inconclusive` unless supported by sufficient deviation evidence.

## Key features

* Deterministic fixture-based scenario suites
* Protocol adapter interface for reusable validation
* Golden snapshots for behavioural drift detection
* Invariant-driven testing
* Deterministic replay
* Adversarial and multi-actor scenario modelling
* Optional evidence and artifact generation
* Validation-root builder for structured result accumulation
* Trace-comparison reports for replay and model/EVM equivalence (`bb trace:compare`)

## Advanced Capabilities & Observability

The framework has evolved to provide robust tools for audit, traceability, and deeper analysis:

*   **Cryptographic Solvency Layer**: New SHA-256 state commitments (`financial/solvency.clj`) enable live proof verification and audit-grade solvency tracking, ensuring the financial integrity of the protocol.
*   **Production Evidence Workbench**: `notebooks/workbench-v2.clj` and `notebooks/evidence_explorer.clj` provide a data-driven, visual observability surface. It bridges high-level simulation metrics with raw, signed cryptographic evidence bundles, making complex analysis accessible.
*   **IPFS Artifact Bundling**: Integrated `bb benchmark:publish-ipfs` pipeline automatically generates an `evidence-manifest.json` for workbench consumption, cryptographically binding every visual artifact to an immutable IPFS bundle. This enables shareable, verifiable evidence.
*   **V2 Semantic Identity Architecture**: A formal shift to derived, immutable identifiers (`TransferId`, `DisputeId`, `ClaimId`) eliminates identity-confusion and rebinding vulnerabilities, ensuring strong data integrity across simulations and replays.

## Current status

* **Demonstrated benchmark catalogue**: Sew-backed replay, dispute, and slashing workloads with declared runnable claims. Yield-shortfall is experimental pending closed-form artifact coverage.
* **Experimental research profiles**: broad protocol-robustness and PRF shortfall profiles retain deferred semantic claims and are not readiness evidence.
* **Framework capability**: an operational in-process deterministic runner and evidence/artifact infrastructure.
* **Research tooling**: adversarial, equilibrium, and integration tools exist, but their presence does not imply they are covered by an active public benchmark.
  Run the fixture-based game-theory research validator with
  `bb benchmark:game-theory --suite :suites/spe-validation --out /tmp/prf-game-theory`.

## Registry Architecture

The framework uses two complementary registries for content-addressed identity and semantic intent tracking.

### Runtime Hash Intents

File: `src/resolver_sim/hash/canonical.clj`

The `hash-intents` map is the executable hash intent registry. Every content-addressed artifact kind maps to exactly one runtime hash intent, which defines how its canonical hash is computed.

Each entry has the following fields:

| Field | Type | Purpose |
|---|---|---|
| `:intent/name` | keyword | Unique intent identifier (e.g. `:evidence-node`, `:action`, `:claim-result`) |
| `:intent/domain-tag` | string | Domain-separated hash prefix (e.g. `"EVIDENCE_NODE_V1"`, `"ACTION_V1"`) |
| `:intent/description` | string | Human-readable description of what the hash represents |
| `:intent/includes` | set of keywords | Top-level keys included in the hash projection |
| `:intent/excludes` | set of keywords | Top-level keys excluded from the hash projection (self-hash, runtime data) |
| `:intent/projection-fn` | fn | Deterministic function that transforms input into canonical-safe data for hashing |
| `:intent/version` | positive int | Monotonic version; incremented when the projection changes |

The `domain-hash` algorithm prepends the domain tag to canonical bytes before SHA-256, preventing cross-domain hash collisions. Excluded keys are stripped by `strip-self-hash-fields` and per-intent projection functions.

### Passive Intent Definitions

File: `src/resolver_sim/definitions/passive_registries.clj`

The `intent-definitions` vector is the DSL/project-level intent registry. These entries describe semantic intents used by hash projections, pro-rata allocation, and additive projection artifacts. They are the passive (non-executable) counterpart to the runtime hash intents.

Each entry has the following fields:

| Field | Type | Purpose |
|---|---|---|
| `:id` | keyword | Unique registry identifier (e.g. `:identity/evidence-node`) |
| `:version` | positive int | Schema version for the definition |
| `:intent/type` | keyword | Semantic category (e.g. `:identity/hash-projection`, `:pro-rata/allocation`) |
| `:intent/purpose` | keyword | Specific processing role (e.g. `:evidence-node-identity`, `:slash-obligation-allocation`) |
| `:scope` | map | Protocol, domain, and module affinity |
| `:inputs` | set of keywords | Required input fields for the projection |
| `:constraints` | set of keywords | Invariant constraints on the projection (e.g. `:canonical-safe`, `:domain-separated`, `:self-hash-excluded`, `:conservation`) |
| `:output` | map | Expected output type, unit, and any intent reference |
| `:extensions-policy` | map | Whether extensions are allowed and whether they require namespaced keys |
| `:description` | string | Human-readable description; also drives hash inclusion/exclusion documentation |

The two registries are cross-validated at startup: every passive intent definition whose `:output` references a `:hash/intent` must correspond to a known runtime hash intent in `hash-intents`.

## Stability Tracking

File: `docs/STABILITY_MANIFEST.edn`

The stability manifest records canonical hashes of source files that
define stability-controlled surfaces. Each entry maps a named surface
(scenario schema, evidence chain, attestor registry, etc.) to the
files that define it, along with a content hash and a `started-at`
timestamp.

```clojure
{:stability/id       :stability/canonical-hashing
 :stability/surface  "Canonical hashing infrastructure"
 :stability/level    :stable
 :stability/started-at #inst "2026-06-27T00:00:00Z"
 :stability/hash     "0fd471408f3425eb..."
 :stability/files    ["src/resolver_sim/hash/canonical.clj"]}
```

The hash is computed via `hash-with-intent` with intent
`:stability/snapshot` (registered in `canonical.clj`'s intent
registry), producing a deterministic fingerprint of the listed
source files. Any change to those files produces a different hash.

### Checking stability

```bash
bb stability:check
```

Compares current source file hashes against the manifest.
Output:

```
Stability Check — 2026-06-27
────────────────────────────────────────────────────────────────────────
  Surface                                        Level        Started At     Status
  ──────────────────────────────────────────────────────────────────────
  :stability/canonical-hashing                   stable       2026-06-27     ✅
  :stability/evidence-chain                      stable       2026-06-27     ✅
  :stability/scenario-schema                     stable       2026-06-27     ❌ (hash mismatch)
  ...
────────────────────────────────────────────────────────────────────────
  8 unchanged, 1 changed, 0 missing — 9 total
```

A `❌` means the source files for that surface have changed since the
recorded checkpoint. Review `docs/STABILITY.md` to determine if the change
is expected and whether the manifest should be updated.

### Adding a new stability surface

1. Add an entry to `docs/STABILITY_MANIFEST.edn` with `:stability/id`,
   `:stability/files`, and `:stability/level`.
2. Run `bb stability:check` once to populate the initial hash.
3. The surface is now tracked — any future changes to its files will
   be reported as a mismatch.

## Validation State Root

The framework includes an opt-in state monad and validation-root builder for structured result accumulation.

This is used to collect validation results, evidence, metrics, warnings, errors, and suite metadata in a composable way.

### Architecture

```text
util.state-monad
  Pure state computation: state → [value state]

        ↓

validation.state
  Semantic validation operations:
  record-error, record-warning, record-pass, record-evidence, snapshot

        ↓

validation.root
  Root builder:
  build-root, finalize-root, derive-root-status

        ↓

validation.adapters.*
  Domain adapters:
  artifact-registry → validation-root

        ↓

validation.integration.*
  CLI entry points and export integration
```

### State monad usage

Namespace:

```clojure
resolver-sim.util.state-monad
```

Example:

```clojure
(require '[resolver-sim.util.state-monad :as sm])

(sm/run-state
  (sm/bind (sm/get-state)
    (fn [s]
      (sm/bind (sm/update-state assoc :validated? true)
        (fn [_]
          (sm/return (:run-id s))))))
  {:run-id "abc"})

;; => ["abc" {:run-id "abc" :validated? true}]
```

### Semantic validation operations

Namespace:

```clojure
resolver-sim.validation.state
```

Example:

```clojure
(require '[resolver-sim.validation.state :as vs])

(vs/exec-state
  (vs/bind (vs/record-pass)
    (fn [_]
      (vs/bind
        (vs/record-error {:key :yield/mismatch
                          :severity :critical})
        (fn [_]
          (vs/snapshot)))))
  {:status-keys #{}
   :error-keys #{}
   :warning-keys #{}
   :errors []
   :warnings []
   :evidence []
   :metrics {:checks 0
             :passed 0
             :failed 0
             :warnings 0}
   :extra {}
   :suite/id nil
   :suite/type nil})
```

### Validation root

Namespace:

```clojure
resolver-sim.validation.root
```

Example:

```clojure
(require '[resolver-sim.validation.root :as root])
(require '[resolver-sim.validation.state :as vs])

(root/build-root
  (vs/bind (vs/record-pass)
    (fn [_]
      (vs/record-evidence
        {:check/id :yield/solvency
         :status :passed}))))

;; => {:status :passed,
;;     :validation/root-version "validation-root.v1",
;;     ...}
```

### Artifact registry validation

Validate the default registry:

```bash
bb validation:artifact-registry
```

Validate a specific registry:

```bash
bb validation:artifact-registry <run-root>/manifest/artifacts.json
```

Run a scenario into an exact, self-contained evidence bundle. The built JAR is the canonical execution surface; `bb run:scenario` is the development adapter:

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar run-scenario scenarios/edn/<scenario>.edn --run-root /tmp/prf-run
# Development adapter:
bb run:scenario scenarios/edn/<scenario>.edn --run-root /tmp/prf-run
```

A completed bundle is marked by `completion.json`; validate its immutable registry at `manifest/artifacts.json`.

### Validation design constraints

* No production code outside `validation.*` calls `util.state-monad` directly.
* `put-state` and `update-state` are not re-exported through `validation.state`.
* Adapter layers use `validation.state` semantic operations only.
* Validation is additive and non-blocking by default.
* Registry validation currently uses schema-version dependency matching.

## Development workflow

The project supports a multi-agent workspace model using `jj` / Jujutsu.

Typical workspace layout:

```text
agent-a
agent-b
agent-c
agent-d
integration
main
```

Where:

* `agent-*` workspaces are used for parallel exploration.
* `integration` is the merge target where agent workspaces are combined.
* `main` is the release branch pushed to remote.

### Workspace sync process

Rebase an agent workspace onto `main`:

```bash
jj rebase -d main
```

Create an integration merge with all agent workspaces:

```bash
jj new agent-a agent-b agent-c agent-d
```

Resolve conflicts, then validate:

```bash
bb validate
bb test:unit
```

Push integration and main:

```bash
jj git push -B main -B integration
```

## Quick Start

### 1. Lint and structural validation

```bash
bb validate
```

Runs clj-kondo lint over source, test, notebook, and development namespaces.

### 2. Run unit tests

```bash
bb test:unit
```

Runs unit tests across the framework, Sew protocol model, equilibrium checks, and yield modules.

### 3. Run invariant scenarios

```bash
bb test:invariants
```

Runs fast in-process invariant scenarios.

### 4. Compare replay outputs

Generate a structural and metric diff between two normalized replay JSON outputs:

```bash
bb trace:compare --baseline results/baseline.json --candidate results/candidate.json
```

### 5. Run dispute-resolution robustness validation

Search and run individual scenarios:

```bash
bb run:scenario:search <text>
```

## Build

Build standalone uberjars for portable scenario replay.  No Clojure CLI needed
at runtime — just a JVM.

```bash
# Build both variants
bb build:core             # → target/prf-runner-core-0.1.0-uber.jar  (5.6 MB)
bb build:sew              # → target/prf-runner-sew-0.1.0-uber.jar  (18 MB)
bb build                  # both sequentially

# Run from any directory (no source tree, no Clojure CLI)
java -jar target/prf-runner-core-0.1.0-uber.jar \
  -m resolver-sim.replay-core --help

java -jar target/prf-runner-sew-0.1.0-uber.jar \
  -m resolver-sim.minimal-runner --fixtures ./data/fixtures/traces \
  --scenario scenario.trace.json
```

### Variants

| JAR | Size | Entry point | Use case |
|-----|------|-------------|---------|
| `prf-runner-core` | 5.6 MB | `resolver-sim.replay-core` | Bundle verify, canonical hash, no Sew |
| `prf-runner-sew` | 18 MB | `resolver-sim.minimal-runner` | Full scenario replay with Sew protocol |

### GPG signing

```bash
# Sign the uberjar for distribution
bb sign:sew
bb sign:core
```

## Dispute-resolution validation phases

The dispute-resolution suite currently includes 22 sub-phases across four major areas.

### Phase F: Economic Parameters

Validates safe parameter zones where malicious expected value is negative.

* F1: Detection probability sensitivity, from 0.1 to 0.9
* F2: Bond size sweep, from 0.5× to 10.0× escrow value
* F3: Fee adequacy, from 50 to 1000 bps
* F4: Escrow concentration, from 100 to 1M
* F5: Multi-resolver equilibrium, from 1 to 100 resolvers
* F6: Appeal window adequacy, from 0 to 10k blocks

### Phase C: Corruption Economics

Validates whether cost of corruption exceeds profit.

* C1: Bribery cost model, from 0.1× to 2.0× escrow value
* C2: External collusion, from 2 to 50 parties
* C3: Layer escalation attack, from 1 to 5 rounds
* C4: Detection probability trade-off, using a 2D grid
* C5: Profit-maximizer lifecycle, using slash sweeps
* C6: Strategic abstention, using timeout penalty sweeps

### Phase E: Evidence Integrity

Validates robustness of the evidence layer.

* E1: Deadline enforcement
* E2: Hash mismatch detection
* E3: Conflicting evidence resolution
* E4: Evidence bloat griefing bounds, from 1KB to 1GB
* E5: Yield accrual during dispute, from 0% to 10% APY
* E6: Evidence availability guarantee

### Phase M: Fairness Analysis

Validates procedural fairness.

* M1: Access-to-justice validation, targeting 95%+ affordability
* M2: Asymmetric information cost, targeting ≤10% asymmetry
* M3: Frivolous appeal discouragement, targeting 70%+ reduction
* M4: Expert availability and cost, targeting 80%+ supply availability

## Documentation

* `docs/README.md` — documentation index
* `docs/SYSTEM_OVERVIEW.md` — narrative overview: engines, findings, roadmap, and technical architecture
* `docs/ROBUSTNESS_FRAMEWORK.md` — adversarial validation and simulation architecture
* `docs/testing/` — validation coverage and status
* `docs/scenarios.md` — scenario index and protocol properties
* `docs/overview/REUSABLE_COMPONENTS.md` — framework harness and adapter overview

## Repository orientation

For new contributors, the most important distinction is:

* The **framework** is the reusable protocol robustness infrastructure.
* **Sew** is the primary validation target and worked example.

A new protocol integration should usually start by studying:

```text
src/resolver_sim/protocols/protocol.clj
src/resolver_sim/protocols/sew/
src/resolver_sim/contract_model/replay.clj
data/fixtures/
```

Then add protocol-specific:

* adapters
* fixtures
* invariants
* scenario suites
* golden snapshots
* artifact mappings, if needed

## Intended use cases

The framework is intended for:

* protocol engineering teams
* smart contract auditors
* mechanism designers
* security researchers
* DeFi risk teams
* dispute-resolution protocol teams
* simulation-driven governance researchers

It is most useful when the question is not simply whether a function is correct, but whether the protocol remains robust under strategic, adversarial, and time-dependent behaviour.

## Status of claims

This repository supports evidence-generating validation workflows, but validation claims should remain scoped to the evidence produced.

A passing scenario means:

* the realised trace satisfied the checked invariants;
* the observed behaviour was consistent with the claimed property;
* the run can be deterministically replayed, assuming the same fixture and execution environment.

A passing scenario does not by itself prove:

* global protocol safety;
* full economic security;
* full equilibrium correctness;
* absence of all strategic deviations;
* correctness across all possible information sets.

Stronger claims require broader scenario coverage, deviation evidence, formal analysis, or production-equivalence validation.

## License

Apache 2
