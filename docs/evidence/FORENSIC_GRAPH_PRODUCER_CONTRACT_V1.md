# Forensic Graph Producer Contract V1

**Status:** proposed implementation contract for PRO-38 / PRO-39. This document does not add a persisted canonical schema, change a hash domain, or qualify any evidence. PRO-40 and PRO-41 must adopt the shared verification-result shape before emitting it as a persisted artifact.

## Purpose and authority

A domain adapter gives existing source artifacts a domain-specific interpretation. A generic graph producer composes those references into PRF's existing canonical evidence-node DAG and typed forensic view. Independent validators establish source authenticity, semantic correctness, and deployment context. A graph's shape and a producer's status labels establish none of those facts by themselves.

The producer is a deterministic projection over retained inputs. It may report claims, unknowns, gaps, and conflicts. It must not admit evidence, accept research, create reward eligibility, authorize an obligation, or execute payment.

## Existing PRF surfaces

| Surface | Existing owner and contract | Boundary for this producer contract |
|---|---|---|
| Canonical evidence node and DAG | `resolver-sim.evidence.node`: `build-execution-node`, canonical node hash, persistence and node/DAG validation | Remains the sole canonical evidence DAG and hash authority. Producers use it; they do not create a parallel node identity. |
| Typed forensic composition | `resolver-sim.evidence.forensic-graph`: `build-forensic-evidence-node`, `validate-forensic-evidence-graph` | Remains a validation view over canonical nodes, roots, declared forensic roles, profile requirements, gaps, and conflicts. It is not source authentication. |
| Scenario execution DAG | `resolver-sim.forensic.execution-dag` and `execution-dag.v1` | Run-plan metadata only; never substitute it for the researcher-facing evidence DAG. |
| Derived-artifact adapter | `resolver-sim.evidence.forensic-adapter`: `ForensicEvidenceAdapter`, `write-forensic-adapter-output!` | Useful for diagnostic output and artifact registration. Its current output hash and `DIAGNOSTIC` registration do not make it a canonical graph or an admitted verification. |
| Domain execution evidence | `resolver-sim.research.native-accounting-work/evidence-nodes`, `resolver-sim.research.runner-forensics`, and `resolver-sim.benchmark.packs.partial-fill.pro-rata-execution-evidence` | Examples of domain-specific evidence construction. Preserve each domain's source and semantic checks outside the generic producer. |
| Partial-fill verification | PRO-40 / PRO-41 | Owns truthful profile validation and allocation/application rederivation. The graph transports its source-bound result and links; it does not recompute or promote partial-fill predicates. |
| Dispatcher and case state | PRO-34 / PRO-35 | May select and schedule work from verified gaps. Its mutable case/task state is outside the canonical evidence DAG and cannot make evidence valid. |
| Independent graph admission | PRO-36 | Reopens artifacts and runs the applicable validators before admitting graph changes. The producer cannot self-admit. |

## Producer and adapter boundary

The integration remains deliberately small. A protocol-specific adapter supplies source identities and relationships; the generic producer supplies the deterministic composition and common report envelope. The existing `forensic-graph` namespace is the generic composition target. PRO-39 should add only orchestration that actual second-domain reuse requires; if direct use of the existing builder suffices, no new generic runtime module is required.

### Adapter input

An adapter receives a retained bundle root and a closed, versioned request containing:

- `domain/id` and `domain/version`;
- the subject kind and immutable subject root(s);
- references to source artifact bodies, each with artifact kind, canonical identity, and bundle-relative location or resolver key;
- the execution/deployment context needed by that domain, including chain/environment and operation identity where applicable;
- an explicit evidence requirement profile, identifying required and optional subjects/roles;
- verifier profile identifiers to invoke or report as unavailable.

The adapter must resolve and validate artifact bodies. A caller-supplied `verified`, `valid`, `complete`, or equivalent flag is a claim to test, never an input authority. A missing body or unsupported source version remains unresolved; it is not replaced by an expected value or fixture value.

### Adapter output

The adapter returns a deterministic projection containing:

- references to source artifacts and their recomputed identities;
- typed evidence-node specifications using existing PRF node construction;
- explicit causal and custody roots and parent-node references;
- producer assertions, clearly kept distinct from validator findings;
- the requirement profile used to derive expected evidence coverage;
- typed gaps and conflicts with references to the affected subjects and artifacts;
- zero or more verifier reports, each linked to its exact source roots and verifier identity.

The generic producer canonicalizes only collection ordering needed for deterministic output, builds ordinary PRF evidence nodes, and invokes the existing forensic graph validator. It does not alter source artifacts, invent parent links, infer economic meaning from field names, or change canonical semantic roots.

### Determinism and identity

Given the same source artifact bodies, source identities, domain adapter version, requirement profile, and producer version, reconstruction must produce byte-identical canonical evidence nodes and the same forensic view. Paths, timestamps, task IDs, process state, display labels, and other volatile metadata do not become canonical node identity inputs unless an existing canonical schema explicitly includes them.

Changing source content or an identity-bearing relationship must change the relevant existing node identity or cause validation to reject. A display-only projection may change without changing the evidence DAG. No new graph root/hash scheme is introduced by this contract.

## Relationship semantics

Canonical `:parent-hashes` encode evidence-node dependency/causal ancestry. `:forensic/causal-parent-roots` and `:forensic/custody-roots` refer to source artifacts or facts; they do not independently assert that those references were authenticated. The producer supplies only edges justified by source artifacts and labels their basis in the domain output.

The existing `:forensic/role` vocabulary (`:declared`, `:observed`, `:derived`, `:validated`, `:verified`, `:proved`, `:attested`) is descriptive data in a node extension. `validate-forensic-evidence-graph` currently checks that a role is in the vocabulary; it does not establish that a source was observed, a derivation was independently rerun, a proof verified, or an attestation was authenticated. A node's role, a correct node hash, graph validity, or an artifact field named `verified` must never be used as a substitute for the corresponding validator result.

Until a distinct, canonical relationship-status schema is approved, do not invent one. Keep a relationship's source references and validation result explicit in existing node extensions / linked report artifacts. Report unsupported or merely declared relationships as gaps. PRO-39 must preserve compatibility with existing node hashes and qualified assertions.

## Shared verification-result handoff (PRO-40 / PRO-41 proposal)

The following is a **proposed noncanonical report interface** for those issues to adopt or revise together. It is not an evidence-node hash schema and is not independently authoritative merely because it is serialized or hashed.

```clojure
{:verification-result/schema-version "prf.verification-result.v1"
 :verification-result/status :verified ; :invalid | :not-run | :not-applicable | :blocked
 :verification-result/requested-level :semantic
 :verification-result/achieved-level :integrity
 :verification-result/subject {:subject/kind :... :subject/root "sha256:..."}
 :verification-result/policy-root "sha256:..."
 :verification-result/mechanism-root "sha256:..."
 :verification-result/source-roots ["sha256:..."]
 :verification-result/checks
 [{:check/id :... :check/required? true
   :check/status :passed ; :failed | :not-run | :not-applicable | :blocked
   :check/reason nil
   :check/source-roots ["sha256:..."]}]
 :verification-result/producer-claims [{:claim/id :... :claim/value ... :claim/source-root "sha256:..."}]
 :verification-result/findings [{:finding/id :... :finding/value ... :finding/source-roots ["sha256:..."]}]
 :verification-result/verifier {:verifier/id :... :verifier/revision "..."}
 :verification-result/limitations []}
```

Required behavior:

1. Status is exactly one of `verified | invalid | not-run | not-applicable | blocked`.
2. Verification level is explicit: `structural | integrity | binding | semantic | deployment-bound`. Requested and achieved levels are both recorded; an achieved level cannot be inferred from a profile flag, self-hash, producer assertion, or a lower-level result.
3. Every required check is listed. `verified` requires every check required for the achieved profile to have run and passed against the recorded immutable inputs. Failed predicates produce `invalid`; unavailable required inputs or authority produce `blocked`; no validator execution produces `not-run`; `not-applicable` requires an explicit scoped reason and may not stand in for a missing required check.
4. Source, subject, policy, and mechanism roots are references to exact retained inputs. Verifier identity includes an immutable code revision or artifact identity sufficient for independent rerun.
5. Producer claims and independently computed findings are separate fields. A verifier must load and recompute the source facts needed for its result; it must not trust the producer's claim value or `:forensic/role`.
6. Limitations and skipped checks are explicit. A report that achieves only integrity cannot satisfy a request for semantic or deployment-bound verification.
7. This report does not change existing evidence-node identity. If later persisted or committed canonically, that requires an explicit schema owner, canonical encoding, compatibility decision, and changelog entry.

The report is linked as an input/causal root from the relevant node. The graph may display the report status but cannot upgrade it. Independent admission (PRO-36) reruns the verifier in its own process and checks the report's source binding before treating a reported result as an admitted finding.

## Common result and gap vocabulary

The report status describes a validator invocation. It is separate from a graph gap, which describes evidence or coverage missing from the case.

| Gap class | Existing forensic status | Example reason | Closure condition |
|---|---|---|---|
| Missing source artifact/body | `:not-available` | `:source-artifact-missing` | Required source body is obtained and its identity independently validates. |
| Missing execution/state observation | `:not-observed` | `:required-observation-missing` | The observation is captured for the bound subject and context, then source-validated. |
| Claim present but not independently checked | `:not-verifiable` | `:claim-not-independently-verified` | Applicable independent validator runs at the requested level. |
| Conflicting observations/findings | `:conflicting` | `:observation-conflict` | Conflict is resolved by new/authoritative evidence or remains explicitly unresolved; do not choose by arrival order. |
| Policy-required attestation absent/invalid | `:not-observed` or `:not-verifiable` | `:required-attestation-missing` / `:required-attestation-invalid` | The profile's required attestation is authenticated and bound to the exact subject. |
| Optional research/reward artifact absent | `:not-applicable` only when profile explicitly makes it optional | `:optional-research-artifact-not-requested` | No gap closure is required for a case that did not request that artifact. |

`forensic-graph` currently supports the status set above and a keyword gap reason, but its `profile-gaps` implementation only checks whether the requested roles exist. Therefore, a required evidence policy must not be considered satisfied merely because a node with a matching role exists when it carries an unresolved required gap or lacks the linked validator report. PRO-39/PRO-36 must enforce this in the adapter/admission layer or make a narrowly reviewed validator change; the generic producer itself cannot assert closure.

## Domain contracts

### Pro-Rata controlled-operation adapter

The Pro-Rata adapter binds one operation to its source allocation, proof/program, deployment/configuration, ratification/funding, and per-claim execution observations. It names those subjects and links their canonical artifact roots; it does not rederive partial-fill semantics in generic graph code. Its source-specific checkers own transfer coverage, per-transaction balances, storage snapshots, proof verification, and allocation/application semantic rederivation. A calculated entitlement, emitted event, submitted transaction, or declared profile value alone is not an observed payout.

The first case is the retained local Alice/Bob/Carol controlled example. It remains `localOnly`; the adapter reports precisely which deployment, verifier, proof, transaction, balance, storage, and independent-observation checks were run or absent. It makes no public-chain qualification claim.

### Second independent domain acceptance

A Sew escrow/dispute fixture is the portability test after the Pro-Rata path is stable. It must use Sew-specific source artifacts and validators while satisfying the same generic producer contract. Acceptance requires:

- no generic producer input, output, branch, or node kind assumes allocations, beneficiaries, claim roots, or pro-rata amounts;
- source evidence resolves to real Sew artifact identities and retains its own causal/custody semantics;
- declaration, observation, derivation, validation, conflict, and gap states remain distinct;
- the same generic graph validator accepts a sound case and reports tampering, missing parents, conflicts, and required gaps without relying on a Pro-Rata adapter;
- no Sew-specific interpretation is moved into canonical node hashing or generic graph validation.

## Validation and failure rules

The producer returns an invalid/blocked graph result, never a success-shaped partial graph, when required identities cannot resolve, a source artifact fails integrity, an unsupported adapter/profile version appears, a relationship is ambiguous, or a required dependency is missing. Conflicting inputs are retained and surfaced as conflicts; none is silently selected. Optional omissions remain explicit and do not become required failures unless the active requirement profile says otherwise.

Independent reconstruction starts from the retained bundle and its declared adapter/profile identity, resolves every referenced body, reruns each applicable verifier, rebuilds the canonical nodes, then runs the existing DAG and forensic-view validators. The checker compares reconstructed node hashes, graph roots where the owning bundle format already defines one, verification reports, and gap/conflict sets with the retained outputs. A producer completion flag or coordinator database is not an input to acceptance.

## Ownership and migration sequence

1. **PRO-38 (this contract):** agree the generic/adapter boundary and the proposed shared verification-result handoff with PRO-40/41. No canonical schema or code behavior changes.
2. **PRO-40:** repair partial-fill profile validators and emit the agreed truthful verification result; keep profile integrity distinct from independent semantic verification.
3. **PRO-41:** independently recompute supported partial-fill allocation/application facts from pinned source artifacts and emit the same report shape with its own verifier identity.
4. **PRO-39:** introduce only the reusable graph-construction seam demonstrated by Pro-Rata plus the Sew portability fixture. Transport the reports without upgrading their status; preserve existing evidence hashes or document any explicitly approved new profile.
5. **PRO-36:** independently resolve and rerun the applicable checks before graph admission; emit gap closure/delta as a verification output, not a producer claim.
6. **PRO-34/35:** dispatch bounded tasks and persist mutable case history outside the canonical DAG; completion of a task changes no evidence status until PRO-36 admission.

If existing `forensic-graph` plus thin domain adapters already meet the composition contract, PRO-39 should record that and stop at direct reuse rather than extracting a generic graph framework.

## Source map

- `src/resolver_sim/evidence/node.clj` — canonical node construction, hash, persistence, and DAG validation.
- `src/resolver_sim/evidence/forensic_graph.clj` — typed subject/role/gap bindings, conflicts, requirement gaps, and composed validation.
- `src/resolver_sim/evidence/forensic_adapter.clj` — current adapter protocol and diagnostic output registration.
- `src/resolver_sim/forensic/execution_dag.clj` — distinct scenario run-planning DAG.
- `src/resolver_sim/research/native_accounting_work.clj` — validated native accounting lineage and node construction.
- `src/resolver_sim/research/runner_forensics.clj` — source-specific causal reconstruction over the generic graph.
- `src/resolver_sim/benchmark/packs/partial_fill/pro_rata_execution_evidence.clj` — Pro-Rata execution evidence adapter surface.
- `test/resolver_sim/evidence/forensic_graph_test.clj` — subject binding, role gaps, conflicts, missing causes, declaration-only nodes, and cycle rejection.
- `test/resolver_sim/forensic/execution_dag_test.clj` — run-plan DAG integrity tests; not evidence-source authentication tests.
