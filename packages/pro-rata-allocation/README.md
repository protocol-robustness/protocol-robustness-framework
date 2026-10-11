# Pro-rata allocation package boundary

Status: independently loadable **Clojure and Rust source package**, consumed
locally by PRF. A reproducible `0.1.0-rc.1` source bundle is available under
`releases/`; it includes the canonical Clojure and Rust source dependencies
and its conformance fixtures. This is a qualified local candidate, not a
remote registry release.
Its Rust realized-statement identity layer is also extracted and
conformance-tested; the broader Rust primitive request/result profile remains
incomplete. The registry remains proposed and non-runtime. The wider pro-rata
artifact family still needs extraction.

Definitions and authority: [proposed registry](../../docs/architecture/canonical-artifacts/canonical-artifacts.v1.edn),
[package-local V1 allocation profile](ALLOCATION_V1.md),
[frozen portable contract](../../portable-pro-rata-contract/SPEC.md), and
[NOW semantic freeze](../../docs/architecture/PROTECTED_CAPITAL_PRO_RATA_V2_SEMANTICS.md).

## Current and target trees

Current ownership and location:

```text
PRF/
├── packages/canonical/clj/          typed canonical codec + generic domain hashing
├── packages/pro-rata-allocation/
│   ├── ALLOCATION_V1.md             package-owned section 4 profile
│   └── clj/
│       ├── deps.edn                 Clojure + explicit canonical local dependency
│       ├── src/prf/pro_rata/
│       │   ├── api.clj              public one-argument and adapter entry points
│       │   ├── request.clj, rows.clj validation and canonical row ordering
│       │   ├── kernel.clj           normalization, quotas, largest remainder
│       │   ├── single_pass.clj      allocation with explicit runtime callbacks
│       │   ├── redistribution.clj   active-set cap redistribution
│       │   ├── identity.clj, runtime.clj package roots and serial adapter
│       │   ├── witness.clj, result.clj, policy.clj, outcome.clj
│       │   └── realized_statement.clj frozen statement projection from supplied roots
│       └── test/
│           ├── fixtures/             pinned allocation vector and byte subset
│           └── prf/pro_rata/         independent allocation oracle + runner
│   └── rust/                         allocation types/projections, rate math, realized fill and statement identities
├── src/resolver_sim/pro_rata/       PRF runtime/hash adapters; further domain code
├── src/resolver_sim/allocation/     context/lifecycle adapters and evidence binding
├── pro_rata_contract_src/           independent frozen reference; shared effects
├── portable-pro-rata-contract/     allocation + effect/transition specs and vectors
├── test/resolver_sim/pro_rata/      parity, progress, arithmetic, host integration
└── coprocessor/core/               realized allocation + lifecycle + source binding
```

Target semantic package boundaries and repository homes, after qualification
and incremental extraction:

```text
Separate repositories:
prf-canonical/                         canonical artifact machinery
prf-pro-rata-allocation/               allocation semantics and artifact definitions
├── clj/                               Clojure implementation
├── rust/                              Rust implementation
└── conformance/                       specs, independent references and frozen vectors
prf-capital-lifecycle/                 Protected Capital lifecycle
prf-shortfall/                         Shortfall scarcity application
prf-researcher-network/                researcher-network application (C1)
prf-examples/                          examples and application-specific demo assembly
prf-evm/                               Solidity implementations and EVM-local projections
protocol-deployments/                 authoritative deployment ledger and release semantics

Semantic package boundaries initially retained in PRF:
protected-operations/                  reusable AO assurance and execution semantics;
                                       future standalone repo is optional
accounting-reductions/                 independently owned accounting semantics;
                                       package boundary may remain in-tree
PRF integration repository:
research analysis and verification; authoritative accounting and incentive/reward
semantics until C1/C2 jointly justify packaging; integration tests and adapters
```

### Dependency direction

```text
prf-canonical ──► prf-pro-rata-allocation ─────►┐
       ├────────► protected-operations          │
       ├────────► prf-capital-lifecycle ◄────────┘ allocation-neutral result adapter
       ├────────► prf-shortfall ─ ─ optional use of pro-rata
       ├────────► accounting-reductions
       └────────► prf-researcher-network (C1)

protected-operations ──► prf-capital-lifecycle (generic assurance interface)
prf-examples ──────────► assembles domain packages and consumes deployment workflows
prf-evm ───────────────► implements EVM profiles; consumes authoritative records from
                         protocol-deployments, not a competing deployment registry
```

## Semantic ownership matrix

| Boundary | Owns | Does not own |
|---|---|---|
| `prf-canonical` | Canonicalization, hashing, typed root references, artifact-definition registration, generic schema and conformance machinery | Domain artifact meanings, proof acceptance policy, capital authorization, research rewards, execution admission |
| PRF research analysis/verification | Authoritative research models, analysis, result interpretation and verification until separately packaged | Researcher-network workflows or a duplicate canonical foundation |
| Researcher network (C1) | Researcher challenges, network application, and interaction/delivery for incentive programs | Authoritative research conclusions/accounting or parallel artifact hashing/registration |
| Accounting reductions | Accounting-reduction semantics as an independently owned package boundary; may initially remain in-tree | Canonical codec/hash or unrelated protocol-specific state machines |
| `protected-operations` | Generic AO assurance, operation binding, state-projection interface, freshness, replay/nonces, route authorization, proof/configuration binding, safe consumption | Domain transitions, entitlement economics, capital conservation rules |
| `prf-capital-lifecycle` | Authoritative entitlement admission/binding, liabilities, activation, withdrawal realization, closure, lifecycle conservation | Determining why/what a beneficiary is owed; generic AO assurance implementation |
| `prf-pro-rata-allocation` | Allocation arithmetic/policy, results, residuals, allocation-owned artifact definitions and vectors | Protected-capital custody/withdrawal or Shortfall source eligibility |
| `prf-shortfall` | Eligible demand, source observation, reserve/source binding, scarcity-specific unmet-demand meaning | Allocation arithmetic or generic protected-operation assurance |
| `prf-examples` | Demo assembly, examples, application-specific deployment workflows | Normative semantics or authoritative deployment ledger |
| `prf-evm` | Solidity implementation, EVM-local commitments/projections, contract tests, EVM-specific conformance | Competing canonical SHA-256 identities or deployment registry authority |
| `protocol-deployments` | Authoritative deployment ledger, admission/observation registry, manifests, release semantics | Domain package semantics or EVM-local implementation details |

Researcher incentive/reward calculations remain authoritative PRF semantics
until C1 and C2 agree on packaging; the network application may move separately.

`protected-operations` is the reusable AO boundary described by
[`ASSURED_OPERATIONS_V1`](../../docs/architecture/ASSURED_OPERATIONS_V1.md).
It owns generic assurance and operation binding, state-projection interfaces,
freshness, nonce/replay protection, route authorization, proof/configuration
binding, and safe consumption semantics. It does not own application state
transitions or economic invariants. Capital lifecycle consumes AO admission,
freshness, and replay interfaces through an adapter; it supplies the operation
proposition, lifecycle preconditions, postconditions, and conservation rules.
This avoids a second lifecycle-specific assurance implementation. The package
can be versioned in-tree first; no AO rewrite or repository split is required.
EVM dispatch and EVM-specific conformance remain in `prf-evm`.

Entitlement economics belong to the mechanism/application that establishes who
is owed what and why. Capital lifecycle owns admission and binding of an
authoritative entitlement set, liabilities, activation, withdrawal realization,
closure, and lifecycle conservation. It accepts allocation-neutral inputs from
different mechanisms and does not define a new universal entitlement format.
Shortfall owns eligible demand, source/reserve binding, and scarcity-specific
semantics. Protected operations gates execution; it does not determine
entitlement economics.

`prf-canonical` owns canonicalization, hashing, typed root references, generic
artifact-definition registration, schema support, and cross-language
conformance machinery. Domain packages define semantic artifact types and
substantive admission policy. Generic verification interfaces may be shared;
proof acceptance, capital authorization, research rewards, and execution
admission remain with the domain that has authority to make those decisions.
Keep the artifact-definition registry, verifier registry, deployment ledger,
and evidence/instance store separate. Package manifests and dependency metadata
belong to build/release tooling, not a canonical semantic registry.

### C1 coordination and overlap

The C1 direction is accommodated without moving or redefining its semantics:

- Research analysis and verification remain in PRF until separately justified.
- `prf-researcher-network` owns the network application and researcher challenge
  coordination. It consumes PRF's authoritative research results and reward
  rules; it does not fork them.
- Researcher incentive/reward calculations remain authoritative PRF semantics
  until C1/C2 agree on an extraction boundary. Network interaction and delivery
  may move independently.
- `accounting-reductions` has a first-class semantic package boundary and may
  remain in-tree; authoritative accounting behavior remains in PRF until
  packaging is justified.
- All these domains consume the same canonical codec/hash and artifact-definition
  registration machinery. Semantic schema and domain-tag ownership follows the
  domain that defines the artifact; there must be no parallel C1 registry,
  encoder, hash scheme, or shared dependency manifest.

The concrete coordination surface is canonical schemas and domain tags,
artifact-definition registry format, package/release metadata, and dependency
version metadata. No conflicting owner is established by the agreed C1
direction. Resolve any C1 proposal to duplicate those shared mechanisms before
editing them; keep domain-specific artifact definitions with their semantic
owners. The portable contract's generic effect/transition material also needs
an explicit owner in the C1 crosswalk.

### Deployment authority

`prf-examples` owns demo assembly and application-specific deployment workflows.
`protocol-deployments` remains authoritative for the deployment ledger,
admission/observation registry, manifests, and release semantics. `prf-evm` may
own Solidity test/development scripts and EVM projections, but those do not
create a competing authoritative registry. Examples and EVM tooling consume the
deployment authority's interfaces and records.

### Migration invariants

- Complete and qualify in-tree package boundaries before external repository
  moves; preserve compatibility re-exports while consumers migrate.
- Preserve every existing root, schema, domain separator, canonical projection,
  proof public-value layout, and verifier identity during extraction.
- Keep independent Clojure/Rust fixtures and verify exact semantic equivalence.
- Keep runtime evidence separate from canonical economic identities.
- Do not change Solidity economic behavior as part of repository extraction.
- Do not duplicate authoritative artifact builders to remove a dependency.
- Replace developer-local paths with pinned dependency versions only after
  external package releases are qualified.

Frozen identities must remain distinct throughout extraction.

## Dependency closure and public adapters

All eleven `prf.pro-rata.*` namespaces require only package namespaces or the
canonical package. `prf.canonical.codec` and `prf.canonical.hash` have no PRF
host dependencies. The
package `deps.edn` exposes only `src` and declares its sibling canonical package
through `:local/root "../../canonical/clj"`. PRF declares this package through a
local dependency, which brings the canonical package transitively. This proves
an explicit build dependency boundary; it is not a pinned version or release
artifact.

The Rust crate at `packages/pro-rata-allocation/rust` uses
`packages/canonical/rust` for the generic codec/hash and declares its required
numeric and digest libraries directly. The package now owns exact rate math,
legacy allocation context/result projections and shared allocation types,
allocation root projections, deterministic outcome selection, the ordered
allocation assertion contract, realized-fill calculation, and realized-statement
identities/projections. `allocation-kernel` retains JSON parsing, raw-input
construction, proof/admission orchestration, and lifecycle orchestration. It keeps
the previous module paths as compatibility re-exports; roots, public values, and
the SP1 input contract remain unchanged.

`prf.pro-rata.api/allocate` has a default one-argument entry point and an
adapter-taking arity:

| Adapter | Required contract | Implementation |
|---|---|---|
| `:execute-allocation` | `(execution-request redistribution-policy)`; dispatches to the selected semantic algorithm | Package serial runtime by default; PRF supplies its budgeted executor, progress, and realization scopes |
| `:hash-request` | Commit normalized canonical request with the frozen projection-artifact intent | Package `identity/request-root`; PRF calls the same function |
| `:hash-result` | Commit the result projection passed by the API | Package `identity/allocation-result-root`; PRF calls the same function |

The default runtime is serial, preserves input and failure order, and delivers
function progress callbacks while swallowing callback exceptions. A lone
`:progress-atom` requires the explicit adapter arity because its reducer belongs
to the host. The PRF runtime keeps dynamic parallelism, executor budgets,
quiescence, atom progress, and realization completion.

Request and result both use `PROJECTION_ARTIFACT_V1`, but their preimages differ:

- Request: `{:intent :projection-artifact :artifact canonical-request}`.
- Result: `prf.pro-rata.result/root-input`, without `:allocation/hash` or the
  diagnostic `:allocation/fractional-remainder-total`; there is no request-style
  intent envelope around the result.
- Policy: `prf.pro-rata.policy/identity-basis` wrapped in its existing
  `:pro-rata-allocation-policy` identity and hashed under
  `PRO_RATA_ALLOCATION_POLICY_V1`.

The frozen scheme is SHA-256 of domain UTF-8 bytes followed by typed canonical
bytes. JSON serialization and EVM keccak commitments are different contracts.
The default and PRF adapters share these package projections. The policy
outcome builder and validator also live in this package and retain the frozen
`PRO_RATA_POLICY_OUTCOME_V1` domain and preimage. PRF keeps its compatibility
functions and the stronger host-side primitive witness check before building
the outcome. Context-bound evaluation packages and their provenance envelopes
remain in PRF because they combine execution, claims, and application inputs.
The independent reference and pinned bytes/roots remain the conformance
authority.

The frozen `realized-allocation-statement.v1` projection and its six-root
assembly also live here. The package accepts pre-derived allocation-context
and round-lifecycle roots; PRF retains those two source-schema adapters,
persisted reconstruction/admission, and scenario-evidence binding. Package
conformance pins the existing all-active statement root; PRF integration tests
continue to cover the full context-to-statement path.

`single-pass/allocate` and `redistribution/allocate` take an execution request
and runtime callbacks:

| Callback | Contract |
|---|---|
| `:effective-claimant-parallelism` | `(requested item-count observer)`; progress observers require serial execution |
| `:ordered-detached-mapv` | `(parallelism timeout f values)`; preserves input order and deterministic failure order |
| `:report-progress!` | `(observer event)`; isolates observer exceptions from allocation semantics |
| `:claimant-hook` | Optional redistribution instrumentation, called for each item inside the mapper |
| `:claimant-determination-hook` | Optional redistribution instrumentation, called on the coordinator with round metadata |

PRF owns executor budgets, dynamic binding conveyance, timeout/quiescence,
realization completion, and progress delivery. The extracted algorithms own cap
commitment, round progression, arithmetic, and output ordering. Runtime options
and hooks remain excluded from canonical request identity. The PRF adapter supplies production executor and progress behavior.

### Standalone public API

Run from `packages/pro-rata-allocation/clj`:

```clojure
(require '[prf.pro-rata.api :as allocation])

(allocation/allocate
 {:schema-version "pro-rata-allocation-request.v1"
  :mechanism/version 1
  :allocation/id :example/one
  :available 100
  :rows [{:row/id :row/a :obligation/id :ob/a :requested 100 :weight 1 :cap 10}
         {:row/id :row/b :obligation/id :ob/b :requested 100 :weight 1 :cap 40}
         {:row/id :row/c :obligation/id :ob/c :requested 100 :weight 1 :cap nil}]
  :rounding-policy :largest-remainder
  :tie-break-policy :canonical-row-id
  :redistribution-policy :redistribute-cap-excess})

(allocation/verify-result request result)
```

The call returns the normalized request, allocation witness, result identity and
canonical SHA-256 root. The deterministic serial runtime supports an
`:on-progress` function. Pass host adapters for progress atoms or parallel
execution. `verify-result` derives the allocation again, rejects any changed
semantic result field or root, and ignores the noncanonical fractional
remainder diagnostic. Source binding, beneficiary consequences, and custody
stay outside the allocation API.

## Conformance ownership and release gates

The package runner uses a pinned copy of the allocation-specific vectors and
result bytes. Each file digest and the fixture manifest digest are checked; the
fixture manifest records the existing portable release root
`b863e04a61d0dded845dc3351626ebf57c4d5abea7e18ea19a89cacf4b1ad12a`, which is
unchanged. Run from `packages/pro-rata-allocation/clj` with
`clojure -M:conformance`; an alternate fixture root may be passed for comparison.
The aggregate portable contract keeps its generic transition fixtures at their
existing location and ownership.

Run the standalone Rust identity tests from the repository root with
`cargo test --manifest-path packages/pro-rata-allocation/rust/Cargo.toml`.
The `allocation-kernel` integration suite additionally checks the raw-input
builder against the same frozen roots.

The frozen Clojure reference remains in
`pro_rata_contract_src/resolver_sim/pro_rata_contract/contract.clj`, where
allocation and canonical effect transitions share a namespace. The package test
tree now contains an allocation-only copy of that oracle and its independent
canonical encoder. Its conformance runner compares complete allocation results
against the package API while the effects/transition oracle remains in the PRF
contract source. This is test material only; the package runtime has no
dependency on the PRF reference source.

Rebuild the checked-in candidate archive from the PRF root with
`python3 scripts/package_pro_rata_allocation.py`. The builder emits a stable
gzip tarball and a SHA-256 sidecar in `packages/pro-rata-allocation/releases/`.
After extraction, run `clojure -M:conformance` from
`packages/pro-rata-allocation/clj`; the bundle contains the sibling canonical
source needed by its local dependency declaration.

| Current material | Proposed destination and constraint |
|---|---|
| `portable-pro-rata-contract/vectors/` allocation vectors and matching allocation-result canonical bytes | `pro-rata-allocation`; preserve bytes/roots, negative cases, row order and rounding/redistribution policies |
| Allocation functions in `pro_rata_contract_src/resolver_sim/pro_rata_contract/contract.clj` | Frozen aggregate reference remains in place; allocation-only oracle copy and independent codec now live under package test sources, separate from generic effects/transition functions |
| `packages/pro-rata-allocation/clj/test/prf/pro_rata/` and the package `test/fixtures/` bundle | Package-owned allocation roots, canonical bytes, independent-reference equivalence, pinned fixture integrity, and request-rejection cases; PRF wrapper parity remains integration coverage |
| Allocation/pro-rata characterization tests | Semantic arithmetic cases travel with pro-rata; executor, progress and realization cases stay with host adapters |
| `packages/pro-rata-allocation/clj/src/prf/pro_rata/outcome.clj` and the pinned `vectors/policy-outcome-conformance-v1.edn` fixture | Pro-rata policy-outcome builder/validator and frozen outcome roots; the PRF fixture/test remain compatibility integration coverage |
| `src/resolver_sim/pro_rata/evaluation.clj` and its commitment-stack tests | Keep in PRF. Evaluation resolves passive intent/projection/claim registries, executes and replays allocation, and assembles validation receipts. Its ex-post result envelope additionally binds world state, actions, Shortfall outcome, claim/invariant links, and evidence references. No independently portable sub-contract was identified in this pass |
| `packages/pro-rata-allocation/clj/src/prf/pro_rata/realized_statement.clj`, `packages/pro-rata-allocation/rust/src/realized_statement.rs`, and corresponding conformance tests | Pro-rata realized-statement projection; package owns six-root assembly while PRF/Rust host adapters retain context/lifecycle derivation and application evidence binding |
| Source-bound entitlement vectors and Rust/SP1 materializer | The originating allocation/application domain owns entitlement economics; capital lifecycle owns authoritative set admission/binding and subsequent lifecycle semantics. Keep mixed source-bound cases as explicit Shortfall/allocation/lifecycle integration coverage; fixture location does not confer semantic ownership on SP1/deployment |
| Solidity execution/admission/custody tests | Companion integration coverage for the relevant projection and lifecycle profile; not primitive allocation SHA-256 equivalence by itself |

The negative allocation vector contains two request mutations and four result
projection mutations. The package runner exercises both request rejections and
all four result rejections through `verify-result`. The registry correctly
marks the primitive request/result/policy Rust implementations missing. Rust
realized-fill/statement code implements a different profile; it does not
establish support for the complete public primitive request/result/witness
format. Solidity support is profile-specific too. The
package runner provides positive frozen-vector, independent-reference, and
negative Clojure evidence, but Rust and Solidity profile conformance remain
incomplete, so the registry conformance status remains `:partial`.

Minimum gates before an independently consumable release:

1. Publish the checked local candidate to an agreed package repository once
   canonical source has a stable dependency coordinate; preserve
   wrapper/integration parity while replacing the local dependency.
2. Package and qualify each additional artifact profile separately. Establish
   matching supported Rust coverage and companion conformance where required.
   Keep missing implementations and profile restrictions explicit.

Previously reported repository review limits remain separate from package
conformance: notebook failure at `appealed_escrow_walkthrough.clj:270` and
all-notebook timeout; three benchmark lifecycle integration errors reproduced
with the parent implementation. The `bb validate` subprocess-status wrapper
also incorrectly prints success after nonzero exits. Recheck those at the
relevant release gate; they do not redefine package ownership.

## Remaining effort in modern LLM coding passes

A pass means one coherent edit/review/validation cycle. These are planning
estimates, assuming unchanged frozen identities and an available runnable
baseline, not release guarantees.

| Next slice | Estimated passes |
|---|---:|
| Qualify and publish a versioned allocation package artifact | 2–3 |
| Package the four policy-outcome vectors and pin their roots | Complete |
| Evaluation artifact boundary reassessment | Complete: keep current evaluation package/envelope in PRF |
| Rust legacy allocation types, roots, context/result projections and statement identity slices | Complete; preserve as-is |
| Complete primitive portable request/result/witness implementation in Rust with Clojure parity | Remaining; separate profile work, not implied by the extracted legacy kernel modules |
| Realized allocation statement Clojure/Rust extraction with context/lifecycle adapters | Mostly complete; remaining cross-language and lifecycle-adapter qualification |
| Capital-lifecycle extraction with allocation-neutral inputs | 4–7 |

The public adapter, explicit PRF local dependency, pinned allocation and
policy-outcome fixtures, and positive plus request-negative runners are now in
place. Rust
implementation of the missing primitive profile and completion of unimplemented
production lifecycle semantics are additional work. Each slice can leave PRF
usable through compatibility adapters; physical repository splits and release
operations follow qualification rather than preceding it.
