<!-- public-claims: registry -->
# Protocol Robustness Framework (PRF)

<p align="center">
  <a href="https://github.com/protocol-robustness/protocol-robustness-framework/actions/workflows/uberjar-build.yml"><img alt="PRF Core" src="https://img.shields.io/github/checks-status/protocol-robustness/protocol-robustness-framework/main?label=PRF%20Core"></a>
  <a href="https://github.com/protocol-robustness/protocol-robustness-framework/actions/workflows/conformance.yml"><img alt="Conformance" src="https://img.shields.io/github/checks-status/protocol-robustness/protocol-robustness-framework/main?label=Conformance"></a>
  <a href="https://github.com/protocol-robustness/protocol-robustness-framework/actions/workflows/reference-validation-v1.yml"><img alt="Reference" src="https://img.shields.io/github/checks-status/protocol-robustness/protocol-robustness-framework/main?label=Reference"></a>
</p>

An open, protocol-agnostic framework for **high-assurance protocol execution, adversarial evaluation, and independent verification**.

PRF is designed for systems where correctness depends not only on individual calls, but on the interaction of **state, authority, ordering, concurrency, economic rules, and evidence over time**.

PRF provides reusable assurance mechanisms, not a complete protocol policy or
assurance case. Protocol authors must supply the applicable state, successor,
admission, economic, game-theoretic, authority, and operational definitions. See
[`docs/architecture/ADOPTER_RESPONSIBILITIES.md`](docs/architecture/ADOPTER_RESPONSIBILITIES.md).

It is designed to answer questions such as:

> Does the protocol still behave correctly when multiple participants act strategically, adversarially, concurrently, or unexpectedly — and can an independent verifier reconstruct why the resulting state was allowed?

PRF combines deterministic execution, invariant and claim evaluation, canonical content-addressed artifacts, authority-bound state transitions, reproducible benchmarks, structured research evidence, and independent verification.

For protocol finality, forensic workflows and protected pro-rata checks, see
[the command guide](docs/cli/protocol-commands.md). Run `bin/prf help` to list
the registered commands.

Extracted repository ownership and immutable source pins are recorded in
[`docs/research/EXTRACTED_REPOSITORIES.md`](docs/research/EXTRACTED_REPOSITORIES.md).
`make setup-coprocessor` explicitly prepares the pinned coprocessor checkout in
the managed user cache; notebook and ordinary Clojure evaluation never clone it.

Its strongest recent work focuses on **high-assurance execution boundaries**: exact-state authority, fenced finalization, deterministic reconstruction, cross-implementation conformance, portable evidence, and proof-backed protocol decisions.

Sew is the primary mature protocol integration. **Provably Fair Pro-Rata** is the flagship application for exercising the framework's allocation, portability, proof, authority, and final-state assurance architecture.

---

## Provably Fair Pro-Rata

PRF includes an end-to-end reference application for **provably fair pro-rata allocation**.

The goal is stronger than implementing a pro-rata formula correctly. The application is designed so that an allocation can be:

* derived from explicit, committed inputs and allocation semantics;
* executed deterministically using integer-domain rules;
* reconstructed independently from persisted artifacts;
* checked against allocation, rounding, redistribution, and lifecycle invariants;
* bound to the exact application and protocol context that produced it;
* represented by canonical rooted artifacts suitable for forensic replay;
* compared across independent implementations;
* carried through proof and EVM verification boundaries; and
* admitted to authoritative state only through the applicable authority and finalization rules.

A simplified assurance path is:

```text
committed requests + allocation policy + authority
        │
        ▼
canonical allocation semantics
        │
        ▼
deterministic allocation kernel
        │
        ├── canonical allocation artifacts
        ├── state/effect reconstruction
        ├── invariant and conformance evaluation
        └── realised-allocation statement
                │
                ├── independent reconstruction
                ├── Rust ↔ Clojure conformance
                ├── proof / verifier boundary
                └── EVM admission
                        │
                        ▼
                authoritative final state
```

The application is intentionally separated from the generic PRF framework. Pro-rata-specific economic semantics belong to the application; canonical artifacts, execution, evidence, reconstruction, conformance, authority, and verification machinery are reusable framework concerns.

This separation is also intended to make results portable: a verifier should be able to determine **what application ran, under which semantics and authority, over which inputs, producing which committed result**, without relying on informal knowledge of the originating process.

### What “provably fair” means here

The term does **not** mean that every possible fairness criterion or economic property has been mathematically proven.

It refers to a narrower and auditable claim: the realised allocation can be bound to explicit allocation semantics and committed inputs, independently reconstructed, checked against the applicable invariants, and — where the proof profile applies — verified through the corresponding proof and admission boundary.

Broader claims such as incentive compatibility, equilibrium properties, collusion resistance, or universal fairness require additional models and evidence.

---

## High-assurance architecture

PRF increasingly treats assurance as a chain of independently checkable boundaries rather than as a single test result.

### Canonical identity and reconstruction

Important protocol objects are represented as canonical, content-addressed artifacts with explicit roots.

Where a workflow supports independent reconstruction, a verifier can recompute the semantic result from committed inputs rather than merely trusting a producer-supplied output hash.

This pattern is used across execution packages, allocation artifacts, application bindings, research outputs, authority material, and other high-assurance paths.

### Exact-state authority

Authority-sensitive transitions are designed to bind authorization to the **exact predecessor state and exact proposed successor or transition context**.

The authority model distinguishes questions such as:

* who or what is authorized;
* which exact transition is authorized;
* which configuration or application context the authorization applies to;
* whether the authorization is current;
* and whether the observed state still matches the state for which authority was issued.

This prevents an otherwise valid authorization from silently migrating to a different state, target, transition, or application.

### Fenced finalization and single-writer state

Final-state publication is treated as a separate assurance boundary.

High-assurance paths use explicit execution closure, quiescence, fences, expected-state/version checks, and single-writer finalization semantics so that concurrent or stale work cannot silently mutate authoritative state after the decision boundary.

Where persistence is authoritative, finalization is expected to bind both semantic predecessor continuity and the actual storage fence rather than treating an application-level sequence number as a substitute for storage version.

### Closed execution and deterministic reduction

Parallel and distributed execution must preserve deterministic protocol meaning.

PRF's execution architecture separates operational placement and concurrency from semantic identity, and uses explicit claims, detached results, reconciliation, deterministic reduction, closure, and completion commitments.

The intended invariant is:

> Different permitted execution schedules may change operational timing, but they must not silently change the committed protocol result.

### Independent implementation conformance

High-assurance claims are strengthened when the same committed semantics can be reconstructed by independently implemented components.

Current work includes conformance boundaries spanning Clojure, Rust, EVM/Solidity, and proof-oriented execution where the relevant profile applies.

Cross-runtime equivalence remains scoped to the exact committed inputs, projections, traces, and invariants declared by the applicable conformance profile.

### Proof-backed verification

Some workflows can move beyond ordinary replay into succinct proof verification.

The realised-allocation proof path commits the allocation context, request set, policy, realised result, failure policy, and lifecycle information into a statement that can be independently checked against the applicable proof/verifier configuration.

Proof verification is still only one link in the assurance chain: proof validity does not replace application identity, authority, state continuity, admission, or finalization checks.

### Forensic-grade portability

PRF artifacts are designed to preserve enough identity and context for later independent review.

A portable result should make it possible to establish:

```text
which application
+ which semantics
+ which inputs
+ which authority
+ which execution
+ which result
+ which evidence
+ which verification profile
```

without treating filenames, process memory, deployment location, or operator assertions as protocol truth.

Application definitions, capability bindings, sequence bindings, artifact roots, evidence manifests, and verification outputs provide the basis for this portability.

### Authentication, provenance, and review authority

Integrity and authenticity remain deliberately separate.

Content addressing can establish that an artifact matches its commitment. Authentication and provenance answer different questions: who produced, reviewed, approved, or authorized it, and under which policy.

PRF therefore models provenance and governed review authority separately from artifact integrity and semantic verification.

### Risk-bounded admission

For workflows where a correct transition may nevertheless create unacceptable exposure, PRF supports evidence-backed risk projection and authoritative risk-limit evaluation.

This allows an admission boundary to ask both:

```text
Is this transition semantically valid?
```

and:

```text
Is the resulting exposure permitted by the authoritative risk policy?
```

Risk and VaR outputs remain explicitly scoped to their valuation basis, source state, corpus, and probability assumptions.

---

## Current evidence and status

PRF is operational research and engineering software with deterministic execution, canonical artifact generation, independent reconstruction, and multiple high-assurance protocol boundaries.

Its public evidence remains deliberately scoped: the repository should claim only what a runnable evaluator, qualifying evidence package, and independent verification path actually establish.

### Demonstrated and actively stabilised capabilities

Current work spans:

* deterministic multi-actor execution and replay;
* invariant, property, and strategic-claim evaluation;
* canonical rooted artifacts and evidence packages;
* independent reconstruction and output verification;
* exact-state authority and governed transition authorization;
* fenced, single-writer finalization;
* deterministic parallel and distributed execution;
* application and capability binding for portable use cases;
* pro-rata allocation, rounding, redistribution, and realised-allocation evidence;
* independent allocation-kernel conformance;
* proof-oriented realised-allocation verification;
* Solidity/EVM verifier and admission boundaries;
* risk projection and risk-limit admission;
* authenticated provenance and governed review evidence;
* trace and semantic comparison across declared implementation boundaries.

Not every implemented mechanism should automatically be described as public review-ready assurance. The applicable capability matrix, benchmark definition, evidence package, and publication rule determine which claims are currently publishable.

### Flagship assurance application

The Provably Fair Pro-Rata application is the clearest end-to-end exercise of the high-assurance architecture because it combines:

```text
economic semantics
→ deterministic execution
→ canonical identity
→ independent reconstruction
→ cross-runtime conformance
→ proof verification
→ authoritative admission
→ final-state publication
```

It should therefore be read as both a useful allocation system and a demanding reference application for PRF's reusable assurance machinery.

---

## What PRF does not claim

High assurance is not the same as unlimited assurance.

A passing PRF workflow establishes only the claims bound to its exact model, inputs, evaluator, evidence contract, authority context, and verification profile.

Depending on the workflow, additional assurance may require formal verification, model checking, economic analysis, production-equivalence testing, external audit, operational-security review, or independent replication.

PRF's objective is to make those boundaries explicit rather than hide them behind an unqualified `PASS`.
