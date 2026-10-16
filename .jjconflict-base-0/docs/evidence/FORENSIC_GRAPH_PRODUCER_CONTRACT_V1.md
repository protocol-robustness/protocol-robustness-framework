# Forensic Graph Producer Contract V1

**Status:** implementation boundary and compatibility contract. This document
does not add a persisted canonical schema, hash domain, evidence-admission rule,
research-acceptance rule, or reward authority.

## Ownership and authority

A domain adapter interprets retained artifacts. A graph producer composes
references to those artifacts into the existing PRF evidence-node DAG and
forensic view. Independent validators establish source authenticity, semantic
correctness, and deployment context. A graph's shape, node hash, profile result,
or producer-supplied `verified` label cannot establish those claims by itself.

| Responsibility | Existing owner | Contract |
|---|---|---|
| Canonical evidence node identity and DAG | `resolver-sim.evidence.node` | Sole node hashing, persistence, and parent-DAG validation authority. |
| Typed forensic node composition and view validation | `resolver-sim.evidence.forensic-graph` | Adds typed subject, role, observation, causal/custody references, actor, cutpoint, and gap extensions to ordinary evidence nodes; validates extensions, DAG relations, profiles, and conflicts. |
| Domain extraction and semantic checks | Domain adapters and their validators | Recompute domain facts from source artifacts; do not change generic node hashing or infer semantics from field names. |
| Investigation planning | `resolver-sim.evidence.investigation-dispatcher` | Produces a bounded planning-only plan; it authorizes no execution or evidence admission. |
| Evidence admission and researcher/reward authority | Existing PRF validation and research lifecycle | Remains outside graph construction and dispatch. |

Relevant implementation paths:

- `src/resolver_sim/evidence/node.clj`
- `src/resolver_sim/evidence/forensic_graph.clj`
- `src/resolver_sim/evidence/forensic_adapter.clj`
- `src/resolver_sim/evidence/deployment_bound.clj`
- `src/resolver_sim/evidence/investigation_dispatcher.clj`
- `src/resolver_sim/benchmark/packs/partial_fill/pro_rata_execution_evidence.clj`
- `src/resolver_sim/benchmark/packs/partial_fill/evidence.clj`

## Producer and adapter boundary

An adapter starts from retained artifact bodies and produces typed subjects,
source roots, proposed relationships, and domain-specific validation results.
The generic producer may order collections deterministically and build ordinary
PRF evidence nodes through `build-forensic-evidence-node`. It must not alter
source artifacts, invent parent links, resolve conflicting observations by
arrival order, or create a parallel canonical graph identity.

The adapter must load and validate referenced bodies. Caller-provided
`verified`, `valid`, `complete`, or equivalent fields are claims to check, not
inputs to trust. Missing bodies and unsupported versions remain explicit gaps
or blocked results.

For deterministic reconstruction, identical artifact bodies, identities,
adapter/profile versions, and producer version must yield identical evidence
node hashes and forensic validation results. Paths, timestamps, task IDs,
process state, and display labels are not identity inputs unless an existing
canonical schema explicitly includes them.

## Relationship semantics and graph validation

`build-forensic-evidence-node` constructs an ordinary evidence node with a
typed extension. `validate-forensic-evidence-graph` composes existing
`validate-node-dag` checks with extension shape, known causal/custody roots,
required-role coverage, and conflicting-observation checks. It returns
integrity, policy-coverage, gaps, conflicts, and errors separately.

The role vocabulary (`declared`, `observed`, `derived`, `validated`,
`verified`, `proved`, `attested`) is descriptive. In particular, profile role
coverage checks that a node with a requested role exists; it does not rerun the
validator that would justify that role. A valid DAG, `:policy-satisfied?`, a
node hash, or an extension containing `:forensic/role :verified` must never be
treated as cryptographic, semantic, deployment-bound, or source authenticity
verification. Consumers must require and independently validate the relevant
source-specific result.

Canonical `:parent-hashes` represent evidence-node dependencies. Typed
`:forensic/causal-parent-roots` and `:forensic/custody-roots` reference source
artifacts or facts; their presence does not authenticate those references.
Edges or claims without independently supported relationships remain declared
or unresolved and must not be promoted by graph validation.

## Verification results and gaps

Keep verification output distinct from canonical node identity unless an
explicitly versioned schema says otherwise. Reports should identify the exact
subject and source roots, validator/profile identity, requested and achieved
strength, required/performed/skipped checks, findings, and limitations.
`verified` means the requested strength was satisfied: every required check
for that request ran and passed. If only a lower level ran, retain the achieved
level but return a non-success status such as `blocked` or `not-run`; do not
emit `verified` merely because some checks passed.

Keep these conditions distinguishable:

- missing source body;
- missing execution or state observation;
- present claim with no applicable independent validator;
- conflicting observations or findings;
- missing or invalid policy-required attestation;
- optional research or reward material not requested by the active profile.

Optional omissions are not evidence gaps unless the selected requirement
profile requires them. A task marked complete cannot close a gap; only
independently validated evidence can change the reconstructed view.

## Pro-Rata adapter and portability

The controlled Pro-Rata adapter binds source allocation, proof/program,
deployment/configuration, protected operation, funding, and per-claim execution
observations. Its validators own allocation/application rederivation, proof
verification, transfer coverage, balances, and storage checks. The generic
graph layer transports their artifacts and results without recomputing or
promoting Pro-Rata predicates. A calculated entitlement, event, submitted
transaction, or declared profile value alone is not an observed payout.

The existing deployment-bound reconstruction is explicitly local-only and
reports runtime-code and cryptographic proof limitations where they have not
been independently checked. It does not qualify public-chain execution.

A second domain such as Sew escrow/dispute is the portability test. It should
use its own source artifacts and validators while using the same canonical node
and typed forensic graph boundary. No generic graph input, output, or validator
should assume allocation, beneficiary, claim-root, or Pro-Rata amount semantics.

## Independent reconstruction

Reconstruction starts from retained artifact bodies and declared adapter and
profile versions. It reruns applicable source validators, rebuilds ordinary
evidence nodes, validates the DAG and typed view, and compares node hashes,
findings, gaps, and conflicts with retained outputs. Coordinator state, task
completion flags, copied verification booleans, and a producer's success
summary are not reconstruction inputs.

The current `investigation-dispatcher` is planning-only. Any later execution
or case-journal implementation must keep mutable task/hypothesis state outside
the canonical evidence DAG; task execution can produce candidate artifacts but
cannot admit them or change a verification result.

## Acceptance boundary for reuse

Reuse this contract only where the domain adapter can resolve and validate its
source bodies and the consumer independently checks the required verification
level. Adding another domain must demonstrate that the generic graph path
preserves its source semantics, reports missing/malformed/conflicting evidence,
and introduces no domain-specific branches into canonical hashing. If these
conditions are not met, keep the domain adapter separate rather than claiming
generic verification.
