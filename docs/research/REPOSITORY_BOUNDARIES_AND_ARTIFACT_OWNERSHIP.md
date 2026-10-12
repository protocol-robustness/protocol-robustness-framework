# Research network and accounting repository boundaries

## Purpose and non-goals

This is the pre-extraction ownership map for separating the researcher
participation network and, later if justified, research accounting from PRF.
It records current artifact authority and dependency direction; it does not
move code, change schemas, create a new request artifact, or activate the
Challenge 001 pilot.

The governing rule is:

```text
repository ownership may move
canonical artifact meaning and root may not move implicitly
```

A consumer repository may validate and reference PRF artifacts, but must not
copy their builders or redefine their acceptance, accounting, evidence, or
reward semantics.

## Current ownership map

| Concern / artifacts | Current authority | Root and validation owner | Intended boundary |
| --- | --- | --- | --- |
| Canonical hashing and root references | PRF shared foundation | `resolver-sim.hash.canonical`, `resolver-sim.hash.reference`; domain-separated roots and canonical projection | Shared PRF protocol dependency; never forked into network/accounting repositories |
| Generic forensic evidence nodes/graphs | PRF shared foundation | `resolver-sim.evidence.forensic-graph`; typed subjects, causal links, graph validation | Shared PRF protocol dependency |
| Research computation and analytical results | PRF research-analysis | `resolver-sim.benchmark.research-definition`, `research-analysis`; frozen definition/axes, output verification, claim derivation | PRF authority; network can point to roots and invoke verification |
| Claim acceptance and lifecycle | PRF claim authority | `research-claim-acceptance.v1`, `research-claim-lifecycle-transition.v1` | Remains PRF-owned; network review actions supply authenticated decisions but do not replace these artifacts |
| Generic reward semantics: policy, eligibility, proposal and obligation | PRF incentive/reward contracts | `resolver-sim.benchmark.researcher-reward`; V1 builders and validators | Shared PRF capability; network derives views and references. Eligibility is not a funding authority's commitment decision |
| Challenge, network configuration, signed actions, task claims, review replay and researcher summaries | In-repository researcher-network module (`resolver-sim.network.researcher-challenges`) | `researcher-challenges.v0` network root; schema-specific roots for challenge/config/action/task-claim/control artifacts; replay and signature checks in the same namespace | First candidate for a separate researcher-network repository, depending on PRF contracts |
| Challenge-specific Shortfall method and candidate policy | Challenge 001 adapter (`resolver-sim.research.shortfall-pilot`, publication/CLI/docs/examples) | Method/scope/policy roots and source-content manifest; uses canonical allocator and PRF analysis verifier | Network/pilot application layer; not generic PRF accounting or allocation semantics |
| Deterministic accounting reductions: reduction contract, request, partition plan/work units, partials and reconciliation | In-repository semantic module (`resolver-sim.accounting.reductions`) | `accounting-*` versioned roots; exact source coverage, deterministic sum-by-category and reconciliation validation | Semantically independent `accounting-reductions` capability, not owned by research-analysis; separate repository remains a later packaging decision |
| Economic relationships and positions: claim-to, possession position, accounting position | Currently implemented in PRF research (`resolver-sim.research.accounting-semantics`) | `claim-to/root`, `possession-position/root`, `accounting-position/root` | Shared economic semantics with domain extensions; separate from reduction mechanics and researcher identity/rewards |
| Researcher incentive accounting: accepted contributions, experience snapshot, quoted amount, obligation reference, payout status | Network history derives accepted/eligible facts; generic obligation contract remains PRF-owned; actual commitment authority belongs to the designated funding authority | Challenge history/task-claim roots plus PRF acceptance, eligibility and obligation roots; payout status is externally recorded | Network summary/projection only; do not create a competing score, eligibility ledger, obligation calculator, or implied funding authorization |
| Native accounting execution, dependencies, implementation assurance, run evidence | PRF distributed research execution | `native-accounting-work`, dependency/execution assurance and forensic graph validators | Remains an execution adapter over accounting semantics; should not migrate into the researcher-network repository |
| Runner transport, durable journal, delivery and work dispatch | PRF research runner transport | Runtime journal/recovery validators and canonical work/assignment/result bindings | Separate operational adapter; transport is not accounting semantics or researcher-network policy |

## Three meanings currently grouped under “accounting”

### Deterministic accounting reductions

This is a bounded, reusable computation/reconciliation capability: canonical
input source set, declared reduction contract, deterministic partitioning,
partial admission, exact coverage, and serial-equivalent result. Its semantic
ownership should be independent of PRF research-analysis even while the code
remains physically in this repository. The current V1 is **not** a general
financial accounting engine: it supports only integer sum-by-category, with
the declared algebraic properties and source model shown below.

### Economic positions and movements

Claims, claim-to targets, possession, accounting positions, entitlements,
liabilities, and capital movements have economic meanings independent of a
researcher's reward program. Their shared semantics should not be dictated by
the challenge/network application. Preserve current V1 contracts; design a
versioned successor only for a demonstrated source/use case. In particular,
a pool funding debit is a movement, not an artificial claimant or aggregate
claim.

### Researcher incentive accounting

This is an application projection over authoritative accepted-contribution
history and PRF reward artifacts. Experience is derived, not edited. Objective
eligibility verification does not itself authorize a funding commitment. The
network/reviewer/funding authority must retain its own explicitly authorized
decision stage; generic PRF obligation construction must not be misread as
permission to spend funds.

## Accounting-reduction artifact chain

The current accounting contract lives in
`src/resolver_sim/accounting/reductions.clj` and is exposed as
`resolver-sim.accounting.reductions`:

```text
accounting-reduction-contract.v1
        ↓
accounting-request.v1
        ↓
accounting-partition-plan.v1
  └─ accounting-work-unit.v1
        ↓
accounting-partial-result.v1
  └─ canonical-accounting-partial.v1 (semantic contribution identity)
        ↓
accounting-reconciliation.v1
        ↓
canonical-accounting-result.v1
```

The request root binds the accounting domain, cutpoint, accounting definition
and policy roots, reduction-contract root, and canonical source rows. It is the
parent semantic basis for partitioning and reconciliation, not a generic job
envelope. Runner, assignment, implementation, environment and delivery facts
belong to their existing execution/forensic artifacts rather than to the
semantic accounting request.

Current constraint: every V1 source row requires a rooted `claim-to` relation.
That is valid only when the source has a genuine canonical claim relationship.
A pool-side funding debit is not a claimant and must not receive a fabricated
claim-to relation. Supporting such non-claim source movements requires a
deliberate accounting contract successor or another explicit existing semantic
source type; it is not solved by extracting the current schema to another repo.

### Research and challenge artifacts

There is currently **no generic `research-request.v1`**. Existing concepts are
more specific:

```text
research-source / frozen research definition / result matrix
research command, assignment, output, verification and analytical claim
research execution job / rooted work unit / runner assignment
researcher challenge / signed challenge action / task claim
```

Do not introduce `research-request.v1` merely to create a repository boundary.
It would risk conflating an experiment's semantic input, an execution job, and
a public solicitation for contributions. If a future network needs a dispatch
record, define a narrowly named challenge-work request owned by that network;
it should reference the exact challenge root and authoritative PRF subject or
request root, not duplicate their fields or computation semantics.

The existing challenge task-claim artifact is an experience/reward quotation
bound to the signed claim action and frozen history prefix. It is not the
canonical request to execute a computation. Challenge configuration defines
the subject/revision, contribution type, criteria, capacity and reward-policy
root; submission references the existing PRF verification inputs and evidence.

## Proposed semantic/package topology

Package ownership and repository ownership are separate choices. The first
goal is narrow in-repository semantic boundaries, not one broad PRF API that
couples claims, rewards, research, and accounting.

```text
PRF shared foundation ──→ PRF research analysis
          │                         │
          ├──→ accounting-reductions (independent semantics)
          ├──→ shared economic semantics / capital-domain extensions
          └──→ generic reward contracts

researcher-network application ──→ PRF research / acceptance / reward APIs
                                └→ accounting-reductions when needed

execution adapters ──→ rooted research/accounting work contracts
```

### Current direct and transitive namespace dependency audit

The focused source-level dependency guard is
`test/resolver_sim/research/repository_boundary_dependency_test.clj`. It
parses candidate `ns` forms and also walks the transitive internal Clojure
source closure for the accounting, researcher-network, pilot-method, and pilot
adapter roots. This is still not a whole build-system/hermetic dependency
checker: it does not resolve external Maven artifacts, Java class references,
runtime `requiring-resolve`, data files, or build plugins. Those remain part of
the separately computed package/source closure.

The audited direction is:

```text
accounting-reductions ──→ canonical hash / root references
legacy accelerated-accounting ──→ accounting-reductions (compatibility only)
accounting-semantics ────→ root references

researcher-network ──────→ research-analysis
                        ├→ claim acceptance / lifecycle
                        ├→ generic reward contracts
                        ├→ signing / incentive model
                        └→ forensic graph / canonical roots

shortfall-pilot method ─→ PRF allocator / analysis contracts
network pilot adapter ──→ shortfall-pilot method + researcher-network

native-accounting-work ──→ accounting reductions + execution/dependency
                           assurance + generic forensic graph
runner-transport ────────→ rooted work adapters, including native accounting
```

The accounting reduction and position namespaces do not import the
researcher-network application, research-analysis, or runner transport.
Research-analysis does not import the network application or runner
transport. The challenge application consumes PRF research, acceptance,
lifecycle, reward, signing and evidence APIs; no reverse import from those
PRF authorities was found in the audited set. Runner transport imports work
adapters, not researcher challenge/reward workflow.

The reduction contract now has an explicit package-level namespace and all
production work/execution adapters and accounting tests depend on it directly.
The former
`resolver-sim.research.accelerated-accounting` namespace is a compatibility
shim only; new consumers should not depend on it. This is a code/package
boundary, not a new artifact schema or repository extraction. Its current V1
operation remains integer sum-by-qualified-category with the existing roots.

The researcher challenge application now has the corresponding
`resolver-sim.network.researcher-challenges` package namespace. CLI, pilot,
publication and focused test consumers use that namespace; the prior
`resolver-sim.research.researcher-challenges` remains a compatibility shim.
The dependency guard ensures the network module depends on PRF contracts and
not on the pilot adapter or runner transport. This is still one repository:
the application requires several PRF modules directly, so a separate release
must first define and pin those dependency contracts rather than relying on a
workspace source path.

The Shortfall coupling is now localized in
`resolver-sim.network.shortfall-pilot-adapter`. The method namespace
`resolver-sim.research.shortfall-pilot` owns arithmetic, method validation,
analysis-context construction and rooted pilot-profile semantics; it no longer
imports researcher-network. The adapter owns configuration replay, challenge
history/control replay, and composes the method gate with network submissions.
Publication and CLI orchestration use that adapter. This changes module
placement only: the existing pilot, controlled-pilot and network artifact
schemas/root calculations are unchanged. If the network is extracted, this
adapter moves with it; PRF analysis and allocator semantics do not gain a
reverse dependency.

### Provisional package contracts and version rules

These are in-repository extraction contracts, not published coordinates or
stable external APIs. The existing canonical and pro-rata packages are still
qualified source candidates (`0.1.0-rc.1`), not registry releases. The two
candidate capabilities should use provisional `0.1.x` package lines only once
they are independently packaged; no version is currently released:

| Candidate contract | Semantic public surface | Required dependency direction | Release gate |
| --- | --- | --- | --- |
| `accounting-reductions` (provisional `0.1.x`) | `resolver-sim.accounting.reductions`: reduction contract, request, deterministic partition plan/work units, partial validation/admission, completeness and reconciliation | canonical hash/root-reference contract only; no research-analysis, network, runner or execution dependency | integer sum-by-qualified-category V1 vectors and exact-root conformance; package must declare its full runtime/test/resource closure |
| `researcher-network` (provisional `0.1.x`) | `resolver-sim.network.researcher-challenges`: challenge/configuration/action/task-claim/control roots, signature checks, history replay, checkpoint and summary APIs | PRF canonical, signing, analysis/verification, claim lifecycle/acceptance, reward and generic evidence contracts; no pilot or runner dependency | versioned, pinned PRF contract coordinates and replay/signature conformance; shortfall application adapter moves with network |

`accounting-reductions` currently imports only
`resolver-sim.hash.canonical` and `resolver-sim.hash.reference` internally;
these are workspace compatibility entry points over the canonical foundation.
The eventual package dependency must point to the shared canonical contract,
not copy those implementations. The network package is not yet independently
buildable because its PRF dependencies are source namespaces in this checkout,
not published/pinned package coordinates. Do not solve that by vendoring PRF
builders or by publishing a broad all-domain facade.

For contract review, the current narrow callable surface is:

```text
accounting-reductions:
  build-reduction-contract / valid-reduction-contract?
  build-request / valid-request? / serial-values
  build-partition-plan / valid-partition-plan?
  build-partial-result / valid-partial-result? / ingest-partial
  accounting-completeness / reconcile

researcher-network:
  configuration / prepare-configuration / build-challenge
  action / sign-action / replay / append-action
  build-control-policy / control-action / replay-controlled
  checkpoints / summaries / review dossier
```

These names document the extraction seam, not an external stability promise;
conformance vectors and pinned dependency coordinates are prerequisites to
publishing them.

Versioning rules for extraction: preserve all existing artifact V1 identities
and golden vectors byte-for-byte; a bug fix that preserves V1 meaning may be a
package patch release; additive callable APIs that do not alter roots may be a
minor package release; any changed canonical field set, root encoding or
artifact meaning requires a successor artifact schema (normally V2) and an
explicit migration/conformance decision. Package semver never silently revises
an artifact's canonical semantics.

### 1. Narrow PRF shared foundation

Keep the existing PRF repository as the source of truth for its existing
artifacts while defining small importable boundaries. Do not make a single
shared package expose every domain. Generic foundations include:

- canonical root/hash behavior and root-reference validation;
- generic evidence graph and provenance validation;
- only the evidence/hash contracts and utility operations that a consumer
  actually needs.

The network must depend inward on these contracts. PRF core must not depend on
the network application, pilot files, operator key provisioning, CLI, or
publication workflow.

### 2. Researcher-network repository

Candidate contents: challenge/network artifact builders and replay; signed
action/control admission and durable history; challenge-specific policy
adapters; researcher summaries; local signing instructions; evidence bundle
exchange/publication tooling; pilot docs and non-secret examples. It consumes
PRF verification, acceptance, eligibility, and obligation APIs and records
their roots. It does not copy them.

Keep the runner transport and execution backends out of this repository. A
researcher network can solicit evidence without owning the mechanism that
executes every kind of research work.

### 3. Accounting-reductions capability

Give reductions independent semantic ownership now, without requiring a
separate Git repository. This package/module owns the accounting request, closed
reduction semantics, deterministic partition plan, partial admission,
completeness and reconciliation. It depends on PRF's canonical hashing and
minimal typed evidence contracts; it does not depend on PRF research-analysis,
the researcher network, or runner transport. Native/distributed execution
remains an adapter above it. Physical repository extraction is a later release,
maintenance, adoption, or operational decision—not a prerequisite for semantic
independence.

“Researcher accounting” should initially remain a network projection over
authoritative PRF history: accepted unique contribution count, frozen quote
history, existing reward-obligation roots, and externally recorded payout
status. Do not create an editable reputation score or a second reward ledger
that can issue obligations independently. A separate researcher-accounting
repository is not justified yet.

## Extraction sequence and gates

1. **Review this ownership map.** Resolve any disputed owner and identify the
   exact public APIs before moving files.
2. **Extend conformance vectors.** Store canonical
   fixture inputs and expected roots for a research definition/output/claim,
   challenge/configuration/action/task claim, accounting request/partition plan,
   partial/reconciliation/result, and reward eligibility/obligation. Include
   accepted-history replay and signature verification vectors. Vectors are
   tests, not new semantics.
3. **Draw and test the dependency DAG.** The initial selected-namespace graph
   is now recorded above and guarded by
   `repository-boundary-dependency-test`; the intended direction is
   `researcher-network → narrow PRF research/acceptance/reward APIs`;
   `accounting-reductions → canonical/evidence foundations`; and
   `execution adapters → rooted work and domain contracts`. There must be no
   dependency from PRF back to the network, and no cycle between reductions,
   positions/movements, transport, and network packages.
4. **Establish a package boundary in-tree before a second repository.** Export
   only stable builders/validators and keep implementation namespaces private
   where practical. Run all golden vectors and existing focused suites against
   the package boundary.
5. **Separate researcher network when the pilot requires it.** Its in-tree
package boundary is now explicit. When an external pilot needs an independently
released repository, move only network application code,
   its CLI/replay tests, docs, and non-secret examples. Depend on a pinned PRF
   package/revision; do not copy PRF source or use a developer-local path in
   the external pilot.
6. **Verify cross-repository conformance.** Rebuild and validate challenge,
   submission, acceptance, obligation and summary artifacts from exchanged
   files. Require unchanged PRF roots and exact replay before any pilot use.
7. **Consider physical accounting extraction separately.** Preserve
   `accounting-request.v1` roots and semantics. Extract when independent release,
   adoption, or maintenance justifies repository overhead. Treat non-claim
   accounting source support as its own versioned design decision, not a
   migration-side rewrite.

## Current conformance-vector status

Existing tests exercise builder validation, exact coverage, root substitution,
idempotence, conflicts, and challenge-history replay. The initial
`repository-boundary-golden-test` now pins deterministic roots for the
accounting reduction contract, request, plan, work units, forensic partials,
semantic partials, reconciliation and canonical result, plus challenge network,
reward policy, challenge, and unsigned action roots. These fixtures prove root
stability for this selected subset only; they are not yet a complete
cross-package conformance suite. Before extraction, also pin stable
input/output vectors for at least:

- accounting reduction contract, request, plan, work units, semantic partials,
  forensic partial results, reconciliation and canonical result;
- challenge configuration root and task-claim quote;
- accepted-history replay, experience snapshot, acceptance, eligibility,
  reward obligation, and externally recorded payout projection;
- research definition/output/verification/claim and their established roots.

Vectors should use deterministic, non-secret fixtures. Signature vectors must
pin public test keys and signatures only; private test material stays out of
published artifacts. A cross-package test must compare exact canonical roots
and reject mutated bodies, not only compare decoded values.

## Root and compatibility safeguards

- Namespace/file relocation alone must not change a schema version, domain
  string, canonical projection, ordering rule, or root.
- If a builder must change to preserve compatibility, prove it against the
  captured vectors before packaging.
- The dependency/artifact root remains the semantic/research binding. A source
  bundle or deployment-content hash is qualification provenance, not an
  automatic replacement for that implementation identity.
- Tests must show semantic artifact roots remain independent of runner, host,
  assignment, completion order and transport. Those remain forensic/runtime
  identities.
- Keep approval/publication state separate from candidate configuration;
  creating a second repository does not authorize publication or create
  reward obligations.

## Known limitations to carry across the boundary

- The current researcher challenge pilot is an offline signed-file workflow,
  not a permissionless public service or identity system.
- Distinct pseudonymous references do not prove distinct people,
  organizations, operators or machines.
- Challenge-specific verification uses PRF's existing analytical verifier;
  reviewer acceptance and incentive eligibility remain separate decisions.
- Accounting V1 supports its declared integer sum-by-category reduction only;
  it does not parallelize pro-rata allocation.
- Accounting source `claim-to` requirements do not represent pool-side funding
  movements without a genuine claim; do not fabricate one during extraction.
- Existing source/dependency identity is not necessarily a hermetic transitive
  source closure. A repository/package split does not upgrade that assurance.
- Transport and runner recovery are separate from challenge history recovery;
  one repository must not imply qualification of the other.
