<!-- public-claims: registry -->
# Protocol Robustness Framework (PRF)

<p align="center">
  <a href="https://github.com/protocol-robustness/protocol-robustness-framework/actions/workflows/uberjar-build.yml"><img alt="PRF Core" src="https://img.shields.io/github/checks-status/protocol-robustness/protocol-robustness-framework/main?label=PRF%20Core"></a>
  <a href="https://github.com/protocol-robustness/protocol-robustness-framework/actions/workflows/conformance.yml"><img alt="Conformance" src="https://img.shields.io/github/checks-status/protocol-robustness/protocol-robustness-framework/main?label=Conformance"></a>
  <a href="https://github.com/protocol-robustness/protocol-robustness-framework/actions/workflows/reference-validation-v1.yml"><img alt="Reference" src="https://img.shields.io/github/checks-status/protocol-robustness/protocol-robustness-framework/main?label=Reference"></a>
</p>

An open, protocol-agnostic framework for modelling, testing, and independently verifying protocol behaviour under adversarial, strategic, and sequence-dependent conditions.

PRF is designed to answer a question that ordinary unit tests do not:

> Does the protocol still behave correctly when multiple participants act strategically, adversarially, or unexpectedly over time?

The framework models how actors interact across sequences of valid protocol actions, then evaluates whether critical correctness, safety, liveness, accounting, and integrity properties continue to hold.

PRF combines deterministic scenario execution, invariant evaluation, reproducible benchmarks, structured evidence generation, and independent output verification. Sew and Yield are reference protocol integrations; neither defines the scope of the framework.

## Continuous integration

Workflows are named `<CATEGORY> · <SCOPE> · <PURPOSE>` so the Actions list is readable at a glance:

| Category | Meaning |
| --- | --- |
| `BUILD` | Can we produce the artifact? |
| `TEST` | Does an implementation unit behave as intended? |
| `INTEGRATION` | Do real system boundaries (PostgreSQL, filesystem, network) work together? |
| `CONFORMANCE` | Do independently implemented representations (Rust ↔ Clojure, forge) agree? |
| `VALIDATION` | Does PRF produce the specified result for known/pinned cases? |
| `DEPLOY` | Publish an already-authorized artifact. |

**What red means:** a GitHub workflow failure indicates that the workflow, framework, implementation, integration, or a release gate did not behave as required. It is **not** caused merely by PRF producing a negative verdict.

**What green means:** the workflow behaved according to its contract. In practice, PRF findings against deliberately invalid or benchmark material — for example, `VALIDATION · SEW · Protocol gates` reporting `continue-on-error` findings — are expected outcomes. Those do **not** fail the workflow; a red status there means PRF itself failed to run correctly and needs engineering attention, not that a detected finding exists.

> `sew-validation-gates` is RED only when PRF is unhealthy (structural/infrastructure/core failure). SEW findings detected during a healthy run are reported as nullable, non-gating steps and findings — not as CI failures.



> **External reviewers:** Start with the [Researcher Evidence Pack](docs/evidence/RESEARCHER_EVIDENCE_PACK.md) for the review scope, reproduction commands, evidence paths, supported claims, and documented limitations.



**New to the codebase?** The project root shows only the protocol-agnostic
framework by default (`:paths ["." "src"]`).  Protocol implementations live
under `protocols_src/` and are added via the `:with-sew` alias:

    clojure -M:with-sew            # full-stack REPL
    clojure -M:test:with-sew       # run all tests (framework + Sew)



> **Framework boundary:** PRF is the reusable protocol-robustness infrastructure. Sew is the primary mature reference implementation and validation target.



## Current evidence and status

PRF is operational research and engineering software with deterministic execution, structured evidence generation, and independent verification workflows. Its public evidence is deliberately scoped: the repository demonstrates specific workloads and claims rather than asserting comprehensive protocol assurance.

### Demonstrated capabilities

The current supported workflows include:

- deterministic multi-actor scenario execution and replay;
- invariant and protocol-property evaluation;
- canonical evidence, artifact, and package generation;
- independent verification of persisted scenario and benchmark outputs;
- integrity checks across execution plans, registries, manifests, evidence chains, and package indexes;
- Sew-backed reference workloads covering replay, disputes, slashing, and force-authorisation custody;
- trace-comparison tooling for declared model, replay, and EVM-equivalence scopes.

The public benchmark catalogue identifies the workloads, evaluators, evidence requirements, and claims that are currently runnable. Publishable headline claims are generated from the versioned headline-claim registry rather than maintained as unbound prose.

### Experimental and incomplete areas

Yield-shortfall and broader protocol-robustness research profiles remain experimental. Their concepts, scenarios, and proposed properties may be defined, but they do not count as evaluated coverage unless they have:

1. a runnable claim evaluator;
2. a qualifying evidence package;
3. an independently verifiable execution path; and
4. an explicit publication rule.

The recorded yield-shortfall suite currently reports 15 of 15 passing scenarios under its earlier invariant-only mapping. Independent CLI verification of that recorded run remains pending an unrelated execution-registry repair, so it should not yet be treated as review-ready evidence.

### Interpreting results

A passing scenario or benchmark supports only the claims declared by its evaluator and evidenced by that realised execution. It does not, by itself, establish global protocol safety, complete economic security, equilibrium correctness, or coverage of every possible strategic deviation.

For the current review scope, reproduction commands, evidence paths, supported claims, and known limitations, start with the Researcher Evidence Pack and the Capability Status.



> **Framework boundary:** PRF is the reusable protocol-robustness infrastructure. Sew is the primary mature reference implementation and validation target.



## Public benchmarks

PRF maintains a deliberately limited public benchmark catalogue. Each published benchmark identifies its workload, runnable claims, evaluators, evidence requirements, and known limitations.

The catalogue demonstrates specific protocol behaviours and framework capabilities; it does not claim comprehensive protocol assurance.

Public headline copy is generated from the versioned headline-claim registry rather than maintained as unbound prose. A scenario, concept, or profile does not count as evaluated public coverage unless it has a runnable claim evaluator, qualifying evidence package, and explicit publication rule.

Start with the Benchmark Showcase for:

- the current capability matrix;
- supported benchmark lifecycles;
- runnable and deferred claims;
- evidence-inspection paths;
- benchmark-specific limitations.

Yield-shortfall and broader protocol-robustness profiles remain experimental unless identified there as independently verifiable public evidence.



## Quick start

PRF can be built as either:

- `prf.jar` — the protocol-agnostic framework and unified verification CLI;
- `prf-runner-sew` — PRF bundled with the Sew reference implementation, scenarios, benchmarks, and suites.

Development and builds require a JVM, the Clojure CLI, and [Babashka](https://babashka.org/). The built JARs require only a JVM at runtime.

### 1. Clone the repository

```bash
git clone https://github.com/protocol-robustness/protocol-robustness-framework.git
cd protocol-robustness-framework
```

### 2. Build the standalone distributions

```bash
bb build
```

This produces:

```text
target/prf.jar
target/prf-runner-sew-0.1.0-uber.jar
```

Build either distribution separately when required:

```bash
bb build:prf
bb build:Sew
```

### 3. Run a reference scenario

The Sew distribution includes the supported reference corpus:

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-scenario classpath:scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
  --run-root /tmp/prf-scenario
```

The equivalent development command is:

```bash
bb run:scenario \
  scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
  --run-root /tmp/prf-scenario
```

### 4. Verify the persisted output

Verification is performed independently from scenario execution:

```bash
java -jar target/prf.jar \
  verify-scenario \
  --run-root /tmp/prf-scenario
```

A completed run binds the persisted package and confirms that the required execution, finalisation, registry, integrity, and package checks completed. Completion does not by itself mean that every semantic property passed or that the package carries signer or release assurance.

### 5. Run and verify a benchmark

Run the supported force-authorisation custody benchmark:

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-benchmark force-authorisation-custody-v1 \
  --run-root /tmp/prf-benchmark
```

Verify the resulting benchmark package:

```bash
java -jar target/prf.jar \
  verify-benchmark \
  --run-root /tmp/prf-benchmark
```

The development equivalent is:

```bash
bb benchmark:run \
  :benchmark/prf-protocol-robustness-v0 \
  --run-root /tmp/prf-benchmark
```

### 6. Explore the CLI and documentation

List the available standalone commands:

```bash
java -jar target/prf.jar help
```

For a guided introduction, continue with:

- `docs/quickstart/README.md` — development setup and first run;
- `docs/reference/usage.md` — CLI and Babashka command reference;
- `docs/evidence/RESEARCHER_EVIDENCE_PACK.md` — reviewer-oriented reproduction and evidence guide;
- `docs/overview/CAPABILITY_STATUS.md` — supported capabilities, experimental areas, and limitations.

> **External reviewers:** Start with the Researcher Evidence Pack, which identifies the review scope, reproduction commands, evidence paths, supported claims, and documented limitations.



## Framework versus protocol implementation

This repository separates reusable protocol-robustness infrastructure from protocol-specific models and semantics.

The **framework** provides the machinery for defining, executing, evaluating, recording, and independently verifying protocol tests. A **protocol implementation** supplies the state model, actions, transitions, evidence mappings, invariants, and domain-specific validation rules needed to apply that machinery to a particular protocol.

### Repository boundary

| Area                      | Responsibility                                          |
| ------------------------- | ------------------------------------------------------- |
| `src/`                    | Protocol-agnostic PRF framework code                    |
| `protocols_src/`          | Protocol-specific implementations and adapters          |
| `test/`                   | Framework tests and protocol integration tests          |
| `scenarios/`              | Scenario definitions used by supported protocol corpora |
| `suites/`                 | Runnable validation suites and expected outcomes        |
| `schemas/`                | Machine-readable framework and evidence contracts       |
| `resources/test-vectors/` | Canonical conformance and verification vectors          |

The default project classpath includes only the repository root and `src/`:

```clojure
:paths ["." "src"]
```

Protocol implementations are opt-in. The `:with-sew` alias adds the Sew implementation and its supported resources:

```bash
clojure -M:with-sew
clojure -M:test:with-sew
```

This boundary is intended to make framework-level dependencies explicit: namespaces under `src/` should not depend on Sew or another protocol implementation.

### Distribution boundary

The same separation is preserved in the standalone builds:

| Distribution     | Contents                                                     | Intended use                                       |
| ---------------- | ------------------------------------------------------------ | -------------------------------------------------- |
| `prf.jar`        | Framework core and unified verification CLI; no Sew implementation or corpus | External inputs and protocol-agnostic verification |
| `prf-runner-sew` | Framework plus the Sew implementation, scenarios, benchmarks, and suites | Canonical Sew execution and reference validation   |

The framework-only distribution can inspect and verify compatible persisted artifacts without embedding the protocol implementation that produced them. The Sew runner contains the additional domain semantics required to execute canonical Sew scenarios and benchmarks.

### Current reference implementations

Sew is the primary mature reference implementation and the source of most currently demonstrated public workloads. Yield exercises a different protocol domain and remains partly experimental.

These integrations provide evidence that the framework can support distinct state machines and protocol semantics. They are validation targets and worked examples; they do not define the scope of PRF.

A new protocol integration should normally provide:

- a protocol state and transition model;
- an adapter between protocol actions and the PRF execution interface;
- protocol-specific invariants and claim evaluators;
- evidence and correlation mappings;
- scenarios, fixtures, and conformance tests;
- explicit capability, coverage, and limitation documentation.

See `docs/overview/REUSABLE_COMPONENTS.md` for the framework harness and adapter model, and `docs/architecture/ARCHITECTURE.md` for dependency and layering rules.



## Build, execute, and verify

PRF separates protocol-specific execution from protocol-agnostic verification.

- `prf.jar` contains the framework and unified CLI without the Sew implementation or scenario corpus.
- `prf-runner-sew` contains the framework together with the Sew reference implementation, supported scenarios, benchmarks, and suites.

Development builds require a JVM, the Clojure CLI, and Babashka. Once built, the standalone JARs require only a compatible JVM.

### Build the distributions

Build the framework-only distribution:

```bash
bb build:prf
```

Output:

```text
target/prf.jar
```

Build the Sew reference distribution:

```bash
bb build:Sew
```

Output:

```text
target/prf-runner-sew-0.1.0-uber.jar
```

Build both distributions sequentially:

```bash
bb build
```

> The Sew JAR filename currently contains the project version. Replace `0.1.0` in the commands below if the build produces a different version.

### Execute a scenario

Canonical Sew scenarios are executed using the Sew distribution because they depend on Sew-specific state transitions, invariants, evidence mappings, and reference data.

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-scenario \
  classpath:scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
  --run-root /tmp/prf-scenario
```

The equivalent development command is:

```bash
bb run:scenario \
  scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
  --run-root /tmp/prf-scenario
```

Use a new or empty run root for each canonical execution unless the command explicitly supports resuming or overwriting an existing run:

```text
/tmp/prf-scenario
```

Replace the scenario path with another supported scenario when required:

```text
classpath:<SCENARIO_RESOURCE_PATH>
```

or, for development:

```text
<LOCAL_SCENARIO_PATH>
```

### Verify a scenario package

Verification is a separate operation from execution. The framework-only CLI reads the persisted run package and checks its declared structure, commitments, and assurance outputs.

```bash
java -jar target/prf.jar \
  verify-scenario \
  --run-root /tmp/prf-scenario
```

A canonical completed scenario run writes `completion.json` only after the required execution DAG, finalisations, registry validation, canonical-integrity checks, and package-index checks have passed the pre-completion gate.

The completion record binds the exact persisted bytes of:

```text
manifest/run-package-index.json
```

Completion establishes that the required package-generation and integrity process completed. It does not, by itself, establish that:

- every semantic claim passed;
- the protocol is globally safe;
- the scenario covers every strategic deviation;
- the package has been signed or approved for release;
- benchmark or suite package profiles satisfy the single-scenario package contract.

The verifier’s structured result should be used to determine the status of the declared checks and claims.

### Execute a benchmark

Run the supported Sew force-authorisation custody benchmark:

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-benchmark \
  force-authorisation-custody-v1 \
  --run-root /tmp/prf-benchmark
```

The development equivalent is currently:

```bash
bb benchmark:run \
  :benchmark/prf-protocol-robustness-v0 \
  --run-root /tmp/prf-benchmark
```

> **Confirm before publication:** the standalone benchmark name and the development registry identifier appear to refer to different benchmark identifiers. Replace one of them if they are not intentionally separate entry points for the same review workflow.

For another benchmark, replace the identifier with:

```text
<BENCHMARK_ID>
```

### Verify a benchmark package

Verify the persisted benchmark output independently:

```bash
java -jar target/prf.jar \
  verify-benchmark \
  --run-root /tmp/prf-benchmark
```

The benchmark verifier should report whether the package is structurally complete, whether its committed artifacts reconcile, and whether the evidence required by its declared package profile is present and valid.

> **Confirm before publication:** document the exact success status, exit code, and principal output file produced by `verify-benchmark`.

### Inspect the available commands

List the commands exposed by the framework CLI:

```bash
java -jar target/prf.jar help
```

List command-specific help where supported:

```bash
java -jar target/prf.jar <COMMAND> --help
```

Examples:

```bash
java -jar target/prf.jar verify-scenario --help
java -jar target/prf.jar verify-benchmark --help
```

### Sign distributions

Sign the generated distributions for release or reviewer delivery:

```bash
bb sign:prf
bb sign:Sew
```

> Signing commands require the configured GPG identity and local signing environment. Document the expected key, generated signature filenames, and verification command in the release guide.

Suggested verification placeholder:

```bash
gpg --verify <SIGNATURE_FILE> <JAR_FILE>
```

### Recommended reviewer workflow

For a clean independent reproduction:

```bash
git clone https://github.com/protocol-robustness/protocol-robustness-framework.git
cd protocol-robustness-framework

bb build

java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-scenario \
  classpath:scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
  --run-root /tmp/prf-scenario

java -jar target/prf.jar \
  verify-scenario \
  --run-root /tmp/prf-scenario
```

For the exact review commit, benchmark, expected outputs, evidence-inspection path, and known limitations, follow the Researcher Evidence Pack.



## Features and use cases

PRF provides reusable infrastructure for testing how protocols behave across adversarial, strategic, and sequence-dependent interactions. Its focus is not only whether individual functions behave correctly, but whether protocol-level guarantees continue to hold across complete execution traces.

### Features

#### Adversarial multi-actor scenarios

Define scenarios in which multiple actors interact with a protocol over time, including honest, strategic, adversarial, delayed, and unexpected behaviour.

Scenarios can exercise:

- competing participant actions;
- timing and ordering effects;
- partial execution and recovery paths;
- escalation and dispute lifecycles;
- authorised and unauthorised transitions;
- failures that emerge only from combinations of otherwise valid actions.

#### Deterministic execution and replay

Execute scenarios from explicit fixtures and reproduce the resulting traces under the same committed inputs and execution environment.

Deterministic replay supports:

- regression investigation;
- behavioural comparison between revisions;
- independent reproduction;
- trace-level debugging;
- evidence inspection after execution.

#### Invariant and claim evaluation

Evaluate protocol properties against realised executions.

Checks may cover:

- state-machine validity;
- accounting conservation;
- balance and quota constraints;
- custody and authorisation rules;
- lifecycle ordering;
- evidence-to-state reconciliation;
- safety, liveness, integrity, and timing properties.

A passing check applies only to the evaluated property and realised scope. It does not imply that all protocol properties or all possible traces have been covered.

#### Benchmark and suite execution

Group scenarios into named workloads with declared evaluators, expected outputs, evidence requirements, and publication rules.

Benchmark definitions can bind:

- system models and parameters;
- generated and fixed cases;
- claims and falsifiers;
- evaluation policies;
- expected evidence;
- execution and provenance commitments.

This allows results to be compared within an explicit scope rather than as unqualified pass or fail statements.

#### Structured evidence and artifact generation

Persist execution outputs as structured, content-addressed artifacts rather than relying only on console output.

Depending on the workflow, generated evidence may include:

- execution plans and manifests;
- state and transition traces;
- invariant and claim results;
- evidence chains and registries;
- procedure-execution witnesses;
- canonical-integrity reports;
- package indexes and completion records;
- signatures and release metadata.

#### Risk projection and VaR

Generate a canonical, evidence-backed view of escrow exposure across a scenario
corpus, with an explicit — and honestly separated — VaR pipeline:

- `risk-projection.v1` — time-indexed `escrow/total-held` exposure series with
  per-row evidence provenance (evidence hash + exact field path), scenario-local
  deltas, measured/not-measured coverage, and corpus-safe metrics. Chain
  verification is `:verified`; content-hash recomputation and world-transition
  recomputation are reported `:not-measured` with recorded reasons.
- `scenario-distribution.v1` — the explicit probability/weighting boundary
  (empirical, uniform weights over measured scenarios).
- `var-projection.v1` — `:var/p95`, `:var/p99`, exact expected shortfall, and
  tail attribution, with a mandatory statement that these are corpus-relative
  quantiles, not a probabilistic forecast.

Every artifact commits a canonical root that re-verifies; rendering is outside
the commitment. Generate the full set with `bb risk:projection`. Spec:
`docs/specs/RISK_PROJECTION_SPEC_V1.md`.

#### Independent output verification

Verify persisted scenario and benchmark packages separately from the process that executed them.

Verification can check:

- artifact hashes and content roots;
- manifest and registry commitments;
- execution-plan bindings;
- evidence-chain integrity;
- cross-artifact references;
- witness and correlation consistency;
- package completeness;
- declared assurance requirements.

The framework-only CLI is intended to support verification without embedding the protocol implementation used during execution, where the package profile permits this separation.

#### Behavioural drift detection

Use golden fixtures, expected results, canonical test vectors, and trace comparison to detect changes in protocol behaviour.

This is useful when a code change remains locally valid but alters:

- transition ordering;
- terminal state;
- accounting outcomes;
- generated evidence;
- committed artifact structure;
- previously established protocol properties.

#### Model, replay, and implementation comparison

Compare declared projections across different execution or modelling environments.

Current tooling includes trace-comparison workflows for:

- repeated framework executions;
- model and implementation projections;
- Clojure and EVM trace equivalence;
- terminal-state and per-step commitments.

Equivalence claims remain limited to the fields, traces, invariants, and execution scope declared by the relevant profile.

#### Protocol adapter architecture

Integrate protocol-specific semantics without placing them in the reusable framework core.

A protocol adapter can provide:

- state and transition definitions;
- supported actions;
- scenario execution hooks;
- invariant and claim evaluators;
- evidence correlation rules;
- artifact projections;
- protocol-specific validation.

Sew is the primary mature reference implementation. Yield provides an additional, partly experimental integration with different state and accounting semantics.

#### Research and review workflows

PRF includes infrastructure for reproducible research outputs and independent review, including benchmark definitions, researcher run reports, outcome manifests, review rounds, signed positions, and scoped comparison of results.

These components support evidence-backed conclusions while keeping the relationship between execution results, researcher interpretation, and publication claims explicit.

### Use cases

#### Protocol engineering

Test whether protocol guarantees remain valid across complex action sequences before changes are released.

Typical questions include:

- Can valid actions combine to create an invalid terminal state?
- Does a retry, partial failure, or delayed action produce duplicate effects?
- Are accounting and custody records reconciled across every completion path?
- Does a protocol revision change established behaviour?

#### Smart-contract and protocol auditing

Complement function-level tests and manual review with reproducible protocol-level scenarios.

PRF can help auditors:

- encode suspected failure sequences;
- preserve exploit or edge-case traces as regression cases;
- evaluate invariants across multi-step interactions;
- provide independently inspectable evidence for findings;
- distinguish demonstrated failures from broader untested concerns.

PRF does not replace source review, formal verification, economic analysis, or production-environment assessment.

#### Mechanism and economic analysis

Evaluate realised allocations, incentives, accounting effects, and strategic interactions under explicit assumptions.

Potential applications include:

- pro-rata allocation mechanisms;
- slashing and reward distribution;
- liquidity shortfalls;
- participant prioritisation;
- dispute incentives;
- force-authorised execution;
- partial fulfilment and deferred obligations.

Stronger economic or equilibrium conclusions require appropriate models, deviation analysis, falsifiers, and evidence beyond scenario execution alone.

#### Escrow, custody, and settlement systems

Exercise lifecycle guarantees for systems that temporarily hold or conditionally release assets or obligations.

Relevant checks may include:

- authorisation binding;
- release and refund exclusivity;
- settlement deadlines;
- held-balance conservation;
- write-back and finalisation behaviour;
- post-finality immutability;
- failure after accounting or custody mutation.

#### Dispute-resolution protocols

Model complete dispute lifecycles involving claimants, respondents, resolvers, escalation paths, evidence deadlines, and settlement outcomes.

PRF is particularly useful where safety depends on the ordering and interaction of several valid actions rather than on a single invalid call.

#### State-machine protocols

Test protocols whose correctness depends on allowed transitions, terminal states, timing rules, and cross-state invariants.

Examples may include:

- workflow and approval systems;
- governance execution;
- settlement and clearing protocols;
- bridge or message-processing lifecycles;
- distributed coordination mechanisms;
- off-chain services with committed state transitions.

#### Independent research and replication

Package benchmark definitions, realised inputs, outputs, evidence, and provenance so that another researcher can reproduce or challenge a result.

This supports:

- exact replication;
- independent sampling;
- model corroboration;
- falsification attempts;
- scoped comparison of conclusions;
- review of whether public claims exceed the committed evidence.

#### Continuous integration and release assurance

Run selected scenarios, suites, and package-verification gates in CI to detect behavioural or evidence regressions.

CI results should be interpreted as evidence for the configured checks and corpus, not as unrestricted proof of protocol robustness.



## Claims and limitations

PRF is designed to produce scoped, reproducible evidence about protocol behaviour. Its outputs should be interpreted according to the exact scenario, benchmark, evaluator, evidence contract, and execution environment that produced them.

The presence of a feature, model, scenario, invariant, or research component in the repository does not by itself establish evaluated coverage or support a public claim.

### Evidence-backed claims

A PRF claim should identify:

- the protocol or model under evaluation;
- the committed inputs and parameter scope;
- the scenarios or generated cases executed;
- the property or invariant evaluated;
- the evaluator used to determine the result;
- the evidence required to support the result;
- the execution and verification status;
- the limitations and publication rule attached to the claim.

Public claims should be generated from versioned claim definitions or registries and should not exceed the scope of the committed evidence.

### What a passing scenario establishes

A passing scenario establishes that, for the realised execution:

- the scenario completed under the declared execution rules;
- the recorded trace satisfied the checks evaluated for that scenario;
- the observed behaviour was consistent with the declared property;
- the persisted outputs can be inspected and, where supported, deterministically replayed;
- the generated artifacts satisfy the applicable structural and integrity checks.

A passing scenario does not establish that the same property holds for every possible trace, parameter value, actor strategy, implementation, or production environment.

### What a passing benchmark establishes

A passing benchmark establishes only the claims declared by its benchmark definition and supported by its qualifying cases and evidence package.

Depending on the benchmark, this may show that:

- all required cases executed;
- the declared evaluators passed;
- fixed regression cases remained stable;
- generated cases remained inside the committed parameter domain;
- required evidence artifacts were produced;
- the benchmark package reconciles with its manifests, registries, and content commitments.

A benchmark result should not be generalised beyond its model, parameters, sampling policy, generated cases, evaluators, and evidence contract.

Comparisons between benchmark results are meaningful only when their scope relationship is explicit—for example, exact replication, independent sampling, or related-model corroboration.

### Completion is not semantic success

For supported single-scenario packages, `completion.json` indicates that the required package-generation process reached the completion gate and that the completion record binds the persisted package index.

Completion may require successful:

- execution-DAG processing;
- required finalisations;
- registry validation;
- canonical-integrity checks;
- package-index construction;
- pre-completion package validation.

Completion does not necessarily mean that:

- every semantic invariant passed;
- the scenario produced the intended protocol outcome;
- every declared research claim is supported;
- the package is signed;
- the package is approved for publication or release.

Semantic results must be read from the applicable invariant, claim, benchmark, and assurance outputs.

### Integrity is not authenticity

Content hashes, Merkle-style commitments, evidence chains, and package indexes can establish that persisted artifacts are internally consistent and have not changed relative to their recorded commitments.

Unsigned integrity does not establish:

- who produced the artifacts;
- who reviewed or approved them;
- whether the execution environment was trusted;
- whether a release is official;
- whether the underlying model accurately represents a production protocol.

Authenticity or release assurance requires an applicable signature, trusted public-key binding, reviewer identity, release policy, or other external trust mechanism.

### Replay is not production equivalence

Deterministic replay shows that an execution can be reproduced under the same declared inputs, implementation, and environment.

Replay does not by itself prove equivalence with:

- deployed smart-contract bytecode;
- another implementation language;
- live network ordering or latency;
- external services, oracles, bridges, or governance systems;
- production configuration and permissions;
- unmodelled environmental behaviour.

Where model, replay, Clojure, or EVM traces are compared, the equivalence claim is limited to the declared traces, state projections, invariant equations, and replication scope.

### Scenario coverage is not exhaustive verification

Scenario-based testing is particularly useful for complex, multi-step failures that ordinary unit tests may miss. It remains a sampled or deliberately constructed exploration of behaviour.

Unless explicitly demonstrated by another method, PRF does not claim:

- exhaustive state-space exploration;
- proof over all possible actor strategies;
- proof over all timing and ordering combinations;
- complete coverage of all information sets;
- absence of unknown protocol failures;
- formal verification of the full implementation.

Broader assurance may require property-based testing, model checking, formal proof, economic analysis, production-equivalence testing, external audit, or additional independent replication.

### Invariants are only as strong as their definitions

An invariant result establishes that the implemented check passed over the evidence and state it examined.

It does not establish that:

- the invariant captures every relevant safety property;
- the state projection includes every material field;
- the model omits no critical actor or transition;
- the evaluator implementation is correct;
- the assumptions behind the invariant hold in production.

Invariant definitions, projections, evaluators, and assumptions must therefore remain reviewable and versioned.

### Economic and strategic limitations

A scenario may demonstrate a realised accounting, allocation, incentive, or strategic outcome without establishing a general economic theorem.

Scenario execution alone does not prove:

- equilibrium existence or uniqueness;
- incentive compatibility;
- resistance to every profitable deviation;
- economic security under every market condition;
- robustness to unbounded capital or collusion;
- correctness of assumptions about actor knowledge or preferences.

Economic conclusions require explicit models, premises, parameter domains, deviation sets, falsifiers, and appropriate analytical or empirical support.

### Reference implementations do not define framework scope

Sew is the primary mature reference implementation and currently provides most demonstrated public workloads. Yield provides a second protocol domain but remains partly experimental.

Success on a reference implementation demonstrates capability within the evaluated integration and corpus. It does not establish that PRF supports every protocol architecture without additional adapter, evaluator, evidence, and conformance work.

### Experimental components

A component should be treated as experimental when it lacks one or more of:

- a stable schema or interface;
- a runnable evaluator;
- qualifying fixtures or cases;
- an independently verifiable evidence package;
- current conformance tests;
- a declared publication rule;
- reviewer-facing documentation.

Experimental scenarios, models, mappings, notebooks, or research profiles may support development and exploration, but they should not be cited as review-ready assurance evidence.

### Known current limitations

The current public evidence should be read with the following limitations:

- the public benchmark catalogue is deliberately limited;
- demonstrated coverage is concentrated in the Sew reference integration;
- Yield-shortfall and broad protocol-robustness profiles remain experimental;
- some package and verification paths support single-scenario outputs more fully than suite-level or benchmark-level profiles;
- production-equivalence claims apply only where an explicit equivalence profile and qualifying trace set exist;
- external infrastructure, production deployments, and operational security controls are outside the scope of ordinary scenario results.

Additional workflow-specific limitations are recorded in the relevant benchmark definitions, evidence profiles, capability matrix, and reviewer documentation.

### Responsible interpretation

The strongest supported statement is usually of the form:

> Under the declared model, inputs, execution scope, and evaluator, the realised cases satisfied the specified properties, and the resulting evidence package passed the applicable independent verification checks.

Claims should become broader only when broader evidence is available.

For the current supported capabilities, exclusions, experimental areas, and review scope, see:

- `docs/overview/CAPABILITY_STATUS.md`

- `docs/evidence/RESEARCHER_EVIDENCE_PACK.md`

- the applicable benchmark definition and evidence contract

- the versioned public headline-claim registry


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
- `integration/` — Integration-specific projections (e.g. Cartesi).
- `fixtures/` — Reusable test and demo inputs.
- `notebooks/` — User-facing Clerk notebooks.
- `examples/` — Stable educational examples and expected artifacts.
- `scripts/` — Build, validation, and tooling scripts.
- `schemas/` — Machine-readable schema definitions.
- `config/` — Configuration files.


## Documentation
* `docs/README.md` — documentation index
* `docs/SYSTEM_OVERVIEW.md` — narrative overview: engines, findings, roadmap, and technical architecture
* `docs/architecture/ARCHITECTURE.md` — layering rules, namespace map, generalisation matrix
* `docs/STABILITY.md` — stability surface tracking and manifest management
* `docs/ROBUSTNESS_FRAMEWORK.md` — adversarial validation and simulation architecture
* `docs/scenarios.md` — scenario index and protocol properties
* `docs/testing/` — validation coverage and status
* `docs/overview/CAPABILITY_STATUS.md` — current capability, coverage, parity, and limitation matrix
* `docs/quickstart/README.md` — setup and first run
* `docs/reference/usage.md` — CLI, Babashka task, and test-runner reference
* `docs/evidence/RESEARCHER_EVIDENCE_PACK.md` — ≤15-minute reproducibility pack for external reviewers
* `schemas/README.md` — machine-readable schema catalog


## License

Apache 2
