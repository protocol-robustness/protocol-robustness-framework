# General Glossary

Common terminology for concepts, use cases, evaluation, evidence, research, and protocol mappings.

## Concepts and Use-Case Vocabulary

| Term | Definition | Boundary |
|------|------------|----------|
| **Concept** | A named, versionable explanation-layer model that connects stakeholder language to protocol mechanics, assumptions, outcomes, failure modes, metrics, and evidence. | A concept describes and maps a domain; it does not alter protocol behavior or assert deployment support. |
| **Use-case concept** | A stakeholder-facing concept that models a concrete interaction, such as an ecommerce purchase or event deposit. | Its terminology is defined by its own EDN record, rather than by a fixed global vocabulary. |
| **Concept mapping** | An explicit association from a concept term to one or more protocol keywords, optionally with a confidence level and explanatory note. | A mapping can be approximate; it is not an assertion that the terms are identical. |
| **Role term** | A use-case-specific name for a participating party, such as *buyer*, *merchant*, *depositor*, *beneficiary*, or *condition authority*. | The same protocol actor may have different stakeholder names in different use cases. |
| **Entity term** | A use-case-specific name for something the participants act on, such as an *order*, *payment*, *deposit*, *condition*, or *appeal bond*. | It can map to a shared protocol primitive while retaining its contextual meaning. |
| **Action term** | A use-case-specific description of an interaction, such as *place order*, *confirm delivery*, *claim expiry*, or *appeal resolution*. | It can map to one or several protocol actions. |
| **Outcome term** | A use-case-specific, stakeholder-readable terminal or material result, such as *merchant receives payment*, *buyer refunded*, *beneficiary receives funds*, or *deposit stuck*. | Outcome terms are defined in each concept’s `:concept/outcomes`; global outcome labels are examples and do not exhaust the vocabulary. |
| **Failure-mode term** | A named harmful or undesirable situation in a use case, including its mechanism and stakeholder impact. | A failure mode is not necessarily an invariant violation; an authorized path can produce a stakeholder-unfair outcome. |
| **Metric term** | A concept-level measurement used to describe or assess a use case, for example time to finality or dispute count. | A metric declaration does not guarantee that every runner or scenario emits it. |

### How concepts define vocabulary

Concept records dynamically define their vocabulary through `:concept/roles`,
`:concept/entities`, `:concept/actions`, `:concept/outcomes`,
`:concept/failure-modes`, and `:concept/metrics`. For example,
`:ecommerce/purchase` defines the outcome `:merchant-paid` as “Merchant
receives payment,” whereas `:event/deposit` defines `:deposit-released` as
“Beneficiary receives funds because the condition was met.” Both can map to
`:protocol.outcome/released` without becoming the same stakeholder term.

## Evaluation, Evidence, and Coverage Vocabulary

These terms describe different layers of the evaluation system. A `-backed`
label identifies the source of support for a statement; it does **not** by
itself establish a formal proof, complete threat-model coverage, or production
correctness.

### Core artifacts

| Term | Definition | Repository usage / boundary |
|------|------------|-----------------------------|
| **Scenario** | A versioned, executable or declarative test case: initial conditions, actions or stimuli, expected outcomes, and optionally claims, invariants, and evidence requirements. It is the smallest unit normally executed and reported. | A scenario may be simulator-backed, but a scenario definition alone is not evidence of a successful run. |
| **Suite** | A named, curated set of scenarios with shared scope, protocol, metadata, and execution/CI policy. | Suites may be filesystem manifests (for example `SUITE.yaml`) or named registry entries. A suite is not a benchmark: it does not by itself specify scoring or claims. |
| **Benchmark** | A versioned evaluation contract that applies a runner policy, evidence policy, scoring rule, and explicit claims to one scenario suite. Its result measures the declared claims over that scoped workload. | The contract is specified in `benchmarks/BENCHMARK_PACK_SPEC_V1.md`. Multiple benchmarks can be lenses over one suite. |
| **Benchmark pack** | A registered, domain- and protocol-oriented collection of related benchmark definitions, with shared registry metadata. |  A pack organizes benchmarks; it is not itself one aggregate benchmark result unless it defines an explicit aggregate. |
| **Evidence bundle** | The portable, content-addressed collection of run evidence and results produced for an execution. | This is the established artifact term. It can contain traces, invariant results, environment metadata, hashes, and claim results. |
| **Evidence pack** | A curated package of evidence, reproducibility instructions, assumptions, and reviewer-oriented derived artifacts for investigating a stated research question or claim. | `docs/evidence/RESEARCHER_EVIDENCE_PACK.md` is the current researcher-facing example. It is not a canonical runtime artifact with a schema, identity, or validation contract; use **evidence bundle** for the current run output. |
| **Forensic run** | An execution of the forensic pipeline that produces a forensic run bundle with recorded inputs, provenance, hashes, optional signatures, verification results, and copied evidence-DAG nodes. | It supports reproducibility and tamper detection, but is not automatically an isolated execution or formal proof. The bundle workflow is `docs/forensic/BUNDLE_WORKFLOW.md`. |
| **Forensic run bundle** | The directory produced by a forensic run, containing preflight and source snapshots, run request, registry/evidence-policy snapshots, result summary, bundle root, and optional claims, attestations, and anchors. | The bundle root commits to the evidence-DAG root. A bundle must pass the forensic verifier before it is described as verified. |
| **Research task** | A content-addressed request for a research runner to perform a defined task, currently including a task type, benchmark, suite, claim IDs, and acceptance criteria. | The `research-task.v0` record is a work request, not the execution result or an attestation. Community workflow states derive from subsequent mailbox messages. |
| **Research task graph** | The derived relationship view connecting a research task with its task announcement, runner results, reproduction results, challenges, agreements, and disagreements. | It tracks community workflow/status and must not be confused with the canonical evidence DAG for execution provenance. |

### Chains, DAGs, and graphs

| Structure | Definition | Canonicality / boundary |
|-----------|------------|-------------------------|
| **Evidence chain** | The ordered, self-hashed linkage of protocol-level evidence records captured during replay. A cursor sequences records and a registry aggregates them. | Run-scoped integrity structure; it is distinct from the DAG’s explicit dependency topology. |
| **Evidence registry** | A derived, indexed registry of evidence artifacts and their hashes, built from the artifacts rather than treated as their source of truth. | Supports lookup, validation, and reconciliation; it is not itself a graph. |
| **Evidence DAG** | The canonical researcher-facing directed acyclic graph of immutable, content-addressed execution/evidence nodes, linked by explicit parent hashes. | Canonical execution-provenance topology. A forensic bundle root commits to its root node. |
| **Forensic execution DAG** | An older, separate planning/output artifact for scenario-run metadata. | Not the canonical researcher-facing evidence DAG; do not use it interchangeably with the evidence DAG. |
| **Claim dependency graph** | The acyclic dependency relationships among evidence-node claim definitions and the claim results needed to evaluate them. | Used by the evidence-node claim path. It is distinct from the benchmark claim catalogue, whose evaluators consume replay results and invariant outputs. |
| **Research task graph** | The task-to-announcement/result/reproduction/challenge/agreement relationship view for community participation. | Workflow and consensus-oriented; not an evidence-provenance DAG. |
| **State-transition graph** | The protocol state machine’s allowed states and transitions, used to validate lifecycle movement during replay. | A domain-model graph, not an evidence artifact. For example, escrow-state invariants check membership and valid edges in this graph. |
| **Mechanism persistence index / scenario matrix** | Derived researcher views that organize evidence-DAG material by mechanism, scenario, claim, invariant, or status. | Useful indexes/views, not canonical graphs and not replacements for evidence-DAG nodes and hashes. |

### Backing labels

| Label | Meaning | Minimum support implied | Does not imply |
|-------|---------|-------------------------|----------------|
| **Simulator-backed** (`simulator_backed` in machine-readable metadata) | The stated result or scenario has been executed through the project simulator/state machine. | A replayed execution with simulator-produced output; where applicable, its configured invariants/expectations are checked. | Coverage of all inputs, real-chain execution, formal verification, or a universal protocol claim. |
| **Evidence-backed** | A result, decision, or case is traceable to retained, verifiable evidence artifacts. | Identifiable artifacts and enough provenance to inspect or validate the asserted relationship. | That the evidence is simulator-produced, that all claims evaluate, or that the evidence is forensic-grade unless those properties are stated. |
| **Benchmark-backed** | A property is supported by named benchmark claim(s), metrics, and scoped benchmark execution. | A reference to the benchmark and the applicable claim(s); for a strong assertion, the claims should have runnable evaluators and recorded outcomes. | General coverage beyond the benchmark’s suite, protocol, configuration, and claim maturity. |
| **Scenario-backed** | A property or concept is associated with one or more executable scenarios. | Traceable scenario references. | A successful execution, evidence capture, scoring, or broad coverage. |
| **Invariant-backed** | A claim is evaluated indirectly by one or more named invariants over scenario results. | The relevant invariants run and pass under the evaluator’s stated conditions. | A direct semantic proof of the claim; invariants are explicitly a proxy at claim maturity Level 2. |
| **Derivation-backed** | A statement is supported by an analytical derivation or manual review rather than a replayed simulator execution. | A reproducible derivation or review record. | Simulator execution or operational evidence. |

### Recommended usage

- Use hyphenated prose labels (for example, **simulator-backed**) and the
  schema’s existing spelling for data fields (for example, `simulator_backed`).
- Attach backing labels to the **result, claim, or coverage statement** they
  qualify—not indiscriminately to a suite or benchmark name.
- State scope alongside a label: protocol/version, suite/scenario IDs,
  configuration, and claim maturity where relevant.
- Do not overload **protocol-backed**, **fixture-backed**, or
  **filesystem-backed** as assurance labels. Existing uses describe domain
  semantics, test fixtures, or storage implementation, respectively.
- Name the topology when discussing provenance: use **evidence chain** for
  ordered capture, **evidence DAG** for dependency/provenance, and **research
  task graph** for community workflow relationships.

## Verifiable Assurance Concepts

A meta-concept that describes *how* stakeholders verify protocol outcomes
rather than *what* happened. Maps to the evidence-chain, canonical hashing,
attestation, and replay infrastructure.

| Concept Term | Protocol Mapping | Definition |
|-------------|-----------------|------------|
| Observer | `:protocol.role/verifier` | Party examining the evidence trail. |
| Auditor | `:protocol.role/verifier` | Third party performing independent verification. |
| Evidence Bundle | `:protocol.entity/evidence-bundle` | Portable collection of all evidence from a run. |
| Evidence Chain | `:protocol.entity/evidence-chain` | Linked evidence nodes with hash integrity. |
| Canonical Hash | `:protocol.entity/hash` | Deterministic SHA-256 over canonically serialized EDN. |
| Attestation | `:protocol.entity/attestation` | Cryptographically signed statement linking identity to a hash. |
| Trace Log | `:protocol.entity/trace` | Ordered sequence of protocol events. |
| Invariant Result | `:protocol.entity/invariant-result` | Pass/fail for a specific invariant check. |

## Role Terms

| Concept Term | Protocol Mapping | Definition |
|-------------|-----------------|------------|
| Buyer | `:protocol.actor/sender` | Party paying for goods or services. |
| Merchant | `:protocol.actor/recipient` | Party expected to fulfill the order or provide the service. |
| Depositor | `:protocol.actor/sender` | Party committing funds to a deposit or account. |
| Beneficiary | `:protocol.actor/recipient` | Party entitled to claim released funds. |
| Resolver | `:protocol.actor/resolver` | Party or module that decides disputes. |
| Governance | `:protocol.actor/governance` | Protocol governance that adjusts rules or intervenes. |
| Condition Authority | `:protocol.actor/resolver` / `:protocol.actor/oracle` | Party that attests whether conditions are satisfied. |
| Spending Authority | `:protocol.actor/resolver` | Party that authorises individual spends. |
| Account Holder | `:protocol.actor/sender` / `:protocol.actor/recipient` | User who owns a spending account. |

## Entity Terms

| Concept Term | Protocol Mapping | Definition |
|-------------|-----------------|------------|
| Order | `:protocol.entity/escrow` | Commercial transaction being protected. |
| Payment | `:protocol.entity/funds` | Funds held pending release, refund, or dispute. |
| Deposit | `:protocol.entity/escrow` / `:protocol.entity/funds` | Funds committed with a release condition. |
| Dispute | `:protocol.entity/dispute` | A challenge or disagreement about entitlement. |
| Evidence | `:protocol.entity/evidence` | Facts submitted to support a claim. |
| Condition | `:protocol.entity/condition` | Predicate that must be satisfied for release. |
| Account | `:protocol.entity/escrow-aggregate` | Balance abstraction over multiple escrows. |
| Hold | `:protocol.entity/pending-release` | Pending charge against a balance. |
| Withdrawal | `:protocol.entity/funds` | Funds moved out of an account. |

## Outcome Terms

| Concept Term | Protocol Mapping | Definition |
|-------------|-----------------|------------|
| Merchant Paid | `:protocol.outcome/released` | Merchant receives payment. |
| Buyer Refunded | `:protocol.outcome/refunded` | Buyer receives funds back. |
| Funds Locked | `:protocol.outcome/stuck` | Neither party can access funds. |
| Manipulated Resolution | `:protocol.outcome/adversarial-resolution` | Unfair outcome via authorized path. |
| Slash Applied | `:protocol.outcome/slashed` | Party loses bond due to rule violation. |

## Failure Mode Terms

| Failure Mode | Description |
|-------------|-------------|
| Merchant-controlled resolver | Resolver aligned with merchant rules unfairly. |
| Buyer griefing | Repeated low-merit disputes to delay payout. |
| Fund lock | No viable resolution path before timeout. |
| False attestation | Condition attested incorrectly. |
| Over-draw | Withdrawal exceeding available balance. |
| Hold never released | Indefinite hold reducing available balance. |
| Unauthorized freeze | Account frozen without proper authority. |

## Metric Terms

| Metric | Source | Definition |
|--------|--------|------------|
| orders-placed | Trace | Number of escrows created. |
| orders-disputed | Trace | Number of flows entering dispute. |
| funds-locked | Protocol metrics | Funds unavailable at terminal state. |
| holds-active | Trace | Number of currently reserved holds. |
| balance | Trace | Current available account balance. |
