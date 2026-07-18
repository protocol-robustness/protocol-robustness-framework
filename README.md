# Protocol Robustness Framework

A framework for adversarial multi-actor scenario testing, specializing in robustness analysis for escrow, dispute-resolution, and state-machine protocols.

**New to the codebase?** The project root shows only the protocol-agnostic
framework by default (`:paths ["." "src"]`).  Protocol implementations live
under `protocols_src/` and are added via the `:with-sew` alias:

    clojure -M:with-sew            # full-stack REPL
    clojure -M:test:with-sew       # run all tests (framework + Sew)

The `workspaces/with-sew/` directory provides the same full-stack view
without the alias.  See `workspaces/MAP.md` for details.

## Build & Run

Build the two supported standalone distributions. No Clojure CLI is needed at
runtime—only a JVM.

```bash
bb build:prf              # → target/prf.jar (framework-only unified CLI)
bb build:sew              # → target/prf-runner-sew-0.1.0-uber.jar (full Sew distribution)
bb build                  # both sequentially
```

### Run a scenario

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-scenario classpath:scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
  --run-root /tmp/prf-scenario
```

Development equivalent:

```bash
bb run:scenario scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
  --run-root /tmp/prf-scenario
```

### Run a benchmark

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-benchmark sew/sew-force-authorisation-custody-v1 \
  --run-root /tmp/prf-benchmark
```

Development equivalent:

```bash
bb benchmark:run :benchmark/prf-protocol-robustness-v0 --run-root /tmp/prf-benchmark
```

### Verify output

A canonical single-scenario run writes `completion.json` only after its required
execution DAG, finalizations, registry validation, canonical-integrity assurance,
and immutable `manifest/run-package-index.json` pass the pre-completion package
gate. Completion binds the exact persisted package-index bytes; it does not mean
the scenario semantically passed, and unsigned content integrity is not release
or signer assurance. Benchmark and suite package profiles remain unsupported by
the single-scenario package validator.

Validate a completed run:

```bash
java -jar target/prf.jar verify-scenario --run-root /tmp/prf-scenario
java -jar target/prf.jar verify-benchmark --run-root /tmp/prf-benchmark
```

Available commands:

```bash
java -jar target/prf.jar help
```

### Supported distributions

| JAR | Contents | Entry point | Use case |
|-----|----------|-------------|---------|
| `prf.jar` | Framework and unified CLI; no Sew implementation or corpus | `resolver-sim.cli.main` | Framework-compatible external inputs |
| `prf-runner-sew` | Framework, Sew implementation, supported corpus, benchmarks and suites | `resolver-sim.cli.main` | Canonical Sew scenario and benchmark execution |

### GPG signing

```bash
# Sign the uberjar for distribution
bb sign:sew
bb sign:prf
```

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

## Repository orientation

For new contributors, the most important distinction is:

* The **framework** is the reusable protocol robustness infrastructure.
* **Sew** is the primary validation target and worked example.

See `docs/overview/REUSABLE_COMPONENTS.md` for the framework harness and adapter overview, `docs/architecture/ARCHITECTURE.md` for layering rules, and `docs/SYSTEM_OVERVIEW.md` for the narrative system overview.

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
* Cryptographic evidence chain and DAG with attestation signing
* IPFS artifact bundling
* Forensic execution pipeline (mailbox, quorum, consensus)

## Current status

* **Demonstrated benchmark catalogue**: Sew-backed replay, dispute, and slashing workloads with declared runnable claims. Yield-shortfall is experimental pending closed-form artifact coverage.
* **Experimental research profiles**: broad protocol-robustness and PRF shortfall profiles retain deferred semantic claims and are not readiness evidence.
* **Framework capability**: an operational in-process deterministic runner and evidence/artifact infrastructure.
* **Research tooling**: adversarial, equilibrium, and integration tools exist, but their presence does not imply they are covered by an active public benchmark.
  Run the fixture-based game-theory research validator with
  `bb benchmark:game-theory --suite :suites/spe-validation --out /tmp/prf-game-theory`.

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

## Documentation

* `docs/README.md` — documentation index
* `docs/SYSTEM_OVERVIEW.md` — narrative overview: engines, findings, roadmap, and technical architecture
* `docs/architecture/ARCHITECTURE.md` — layering rules, namespace map, generalisation matrix
* `docs/STABILITY.md` — stability surface tracking and manifest management
* `docs/ROBUSTNESS_FRAMEWORK.md` — adversarial validation and simulation architecture
* `docs/scenarios.md` — scenario index and protocol properties
* `docs/testing/` — validation coverage and status
* `docs/overview/CAPABILITY_STATUS.md` — current capability, coverage, parity, and limitation matrix
* `docs/quickstart/QUICKSTART.md` — setup and first run
* `docs/reference/usage.md` — CLI, Babashka task, and test-runner reference
* `docs/evidence/RESEARCHER_EVIDENCE_PACK.md` — ≤15-minute reproducibility pack for external reviewers
* `schemas/README.md` — machine-readable schema catalog

## License

Apache 2
