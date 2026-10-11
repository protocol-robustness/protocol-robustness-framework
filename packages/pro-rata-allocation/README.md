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
│   └── rust/                         realized-fill model + statement identity crate
├── src/resolver_sim/pro_rata/       PRF runtime/hash adapters; further domain code
├── src/resolver_sim/allocation/     context/lifecycle adapters and evidence binding
├── pro_rata_contract_src/           independent frozen reference; shared effects
├── portable-pro-rata-contract/     allocation + effect/transition specs and vectors
├── test/resolver_sim/pro_rata/      parity, progress, arithmetic, host integration
└── coprocessor/core/               realized allocation + lifecycle + source binding
```

Target, after separate qualification and incremental extraction:

```text
prf-canonical/                      codecs, hashing, typed roots, artifact definitions,
                                    generic schema/conformance support
prf-pro-rata-allocation/            allocation arithmetic, policies, realized results,
                                    allocation artifact definitions and vectors
prf-capital-lifecycle/              entitlement admission/binding, liabilities,
                                    activation, withdrawal, closure, conservation
prf-shortfall/                      scarcity, eligible demand, source/reserve binding,
                                    Shortfall-specific unmet-demand semantics
prf-protected-operations/           possible future repo; reusable AO assurance boundary
                                    (package boundary first, still in-tree)
prf-researcher-network/             network application and interaction surfaces
PRF in-tree research/accounting/    analysis, verification, incentive semantics and
                                    accounting-reductions until packaging is justified
prf-examples/                       examples, demo assembly, application workflows
prf-evm/                            Solidity implementation, EVM projections and tests
protocol-deployments/               authoritative deployment ledger, admission /
                                    observation registry, manifests and release semantics
```

These are semantic ownership destinations, not instructions to move entire
folders or create every repository immediately. Clojure and Rust implementations
and their independent conformance fixtures travel with the semantic package.
`protected-operations` is an in-tree semantic boundary only for now; this plan
does not rewrite AO or create its repository. `prf-examples` consumes deployment
authority from `protocol-deployments`; neither examples nor `prf-evm` creates a
competing authoritative deployment registry. The portable contract also contains
generic effects and transition semantics, which need an explicit ownership
decision before extraction. Frozen identities remain distinct throughout.

### Dependency DAG

```text
                         ┌─> pro-rata-allocation ─┐
canonical ───────────────┼─> capital-lifecycle <──┼─ application/allocation basis
                         ├─> Shortfall ───────────┘
                         ├─> protected-operations ─> lifecycle / allocation / Shortfall
                         └─> research + accounting semantic packages (as adopted)

pro-rata-allocation ── allocation result/binding adapter ──> capital-lifecycle
Shortfall ──────────── source/reserve application binding ──> lifecycle / AO interfaces
all semantic packages ──> PRF integration and examples
protocol-deployments ── deployment authority consumed by examples and EVM workflows
prf-evm ── EVM implementations/projections/conformance for relevant semantic packages
```

Arrows mean dependency on a lower-level contract, not ownership transfer.
Capital lifecycle accepts an admitted entitlement set and manages its economic
lifecycle; the allocation or application domain defines the economic basis for
who receives what. AO supplies reusable assurance gates and safe-consumption
semantics; lifecycle and other domains supply the state transitions and
economic invariants behind those gates. The DAG must not create a dependency
from generic canonical infrastructure back into domain semantics.

### Semantic ownership matrix

| Boundary | Owns | Explicitly does not own |
|---|---|---|
| `canonical` | Canonicalization/codecs, hashes and domains as mechanisms, typed root references, artifact-definition registration, generic schema/conformance support | Domain artifact meaning, verifier/deployment/evidence registries, substantive proof admission or economic authorization |
| Research | PRF research analysis and verification semantics remain authoritative in PRF pending packaging | Researcher-network delivery or a duplicate canonical infrastructure |
| Accounting | `accounting-reductions` semantic boundary; may remain an in-tree package while ownership and release are established | A second canonical artifact registry or copied accounting rules in the network application |
| Protected operations / AO | Reusable assurance, operation/proof/configuration binding, state projection, freshness, nonce/replay control, route authorization and safe consumption | Entitlement economics, allocation arithmetic, reserve/scarcity policy, or domain state transitions |
| Capital lifecycle | Admission/binding of an authoritative entitlement set, liabilities, activation, cancellation consequences, withdrawal realization, closure/settlement and conservation invariants | Deciding the allocation/application basis for entitlement amounts; Shortfall source/reserve semantics; AO assurance implementation |
| Pro-rata allocation | Deterministic allocation, policy, rounding/redistribution, residuals, realized allocation and allocation result/statement artifacts | Beneficiary/custody/withdrawal execution or lifecycle entitlement admission |
| Shortfall | Scarcity application, eligible demand, source observation, available capital, reserve/source and allocation bindings, unmet-demand semantics | Allocation arithmetic or generic lifecycle semantics |
| Researcher network | Researcher-facing application, challenges and interaction/delivery surfaces | Authoritative PRF research/accounting or researcher reward calculation rules pending C1 agreement |
| `prf-examples` | Demonstrations, assembled examples and application-specific deployment workflows | Normative canonical semantics or authoritative deployment ledger |
| `prf-evm` | Solidity implementations, EVM-local projections/commitments, local scripts and cross-language conformance | Competing deployment authority or ownership inferred from implementation location |
| `protocol-deployments` | Deployment ledger, admission/observation registry, manifests and release semantics | Canonical semantic artifact definitions or domain economics |

The artifact-definition registry remains distinct from the verifier registry,
deployment registry, and evidence/instance store. Generic verification
interfaces may be in `canonical`; the owning semantic domain decides whether a
proof is admissible and what state change follows.

### Coordination with C1

The currently supplied C1 direction leaves research analysis/verification and
authoritative research/accounting semantics in PRF while separating
researcher-network functionality. It also identifies researcher incentives
and `accounting-reductions` as semantic concerns whose packaging is not yet
justified. C2 therefore reserves those in-tree boundaries and depends on the
single canonical foundation without migrating, copying, or redefining them.
No concrete conflicting owner is established by the current plans. Coordinate
before changing shared canonical schema/domain-tag conventions, the artifact
definition manifest/validator, package manifests or dependency-version
metadata. Research/accounting artifact schemas and their domain tags stay with
their semantic owners even when the common codec and hash implementation is
shared. Any C1 proposal to duplicate those shared mechanisms or move an
authoritative rule into the researcher-network application is an ownership
conflict to resolve jointly, not a parallel implementation task.

## Dependency closure and public adapters

All eleven `prf.pro-rata.*` namespaces require only package namespaces or the
canonical package. `prf.canonical.codec` and `prf.canonical.hash` have no PRF
host dependencies. The
package `deps.edn` exposes only `src` and declares its sibling canonical package
through `:local/root "../../canonical/clj"`. PRF declares this package through a
local dependency, which brings the canonical package transitively. This proves
an explicit build dependency boundary; it is not a pinned version or release
artifact.

The Rust crate at `packages/pro-rata-allocation/rust` depends only on
`packages/canonical/rust`. `allocation-kernel` retains its raw-input
context/lifecycle builder and delegates statement domains and root assembly
to that crate. The realized-fill model, deterministic fill calculation, and
canonical result projection also live in the package; `allocation-kernel`
re-exports them under its existing module path, preserving callers and the SP1
input contract.

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
source needed by its local dependency declaration. The extraction qualification
also runs `cargo test --offline --manifest-path
packages/pro-rata-allocation/rust/Cargo.toml` from the extracted bundle root;
both commands pass without referring to the PRF workspace. Verify the archive
with `sha256sum -c` against its checked-in sidecar before consuming it.

| Current material | Proposed destination and constraint |
|---|---|
| `portable-pro-rata-contract/vectors/` allocation vectors and matching allocation-result canonical bytes | `pro-rata-allocation`; preserve bytes/roots, negative cases, row order and rounding/redistribution policies |
| Allocation functions in `pro_rata_contract_src/resolver_sim/pro_rata_contract/contract.clj` | Frozen aggregate reference remains in place; allocation-only oracle copy and independent codec now live under package test sources, separate from generic effects/transition functions |
| `packages/pro-rata-allocation/clj/test/prf/pro_rata/` and the package `test/fixtures/` bundle | Package-owned allocation roots, canonical bytes, independent-reference equivalence, pinned fixture integrity, and request-rejection cases; PRF wrapper parity remains integration coverage |
| Allocation/pro-rata characterization tests | Semantic arithmetic cases travel with pro-rata; executor, progress and realization cases stay with host adapters |
| `packages/pro-rata-allocation/clj/src/prf/pro_rata/outcome.clj` and the pinned `vectors/policy-outcome-conformance-v1.edn` fixture | Pro-rata policy-outcome builder/validator and frozen outcome roots; the PRF fixture/test remain compatibility integration coverage |
| `src/resolver_sim/pro_rata/evaluation.clj` and its commitment-stack tests | Keep in PRF. Evaluation resolves passive intent/projection/claim registries, executes and replays allocation, and assembles validation receipts. Its ex-post result envelope additionally binds world state, actions, Shortfall outcome, claim/invariant links, and evidence references. No independently portable sub-contract was identified in this pass |
| `packages/pro-rata-allocation/clj/src/prf/pro_rata/realized_statement.clj`, `packages/pro-rata-allocation/rust/src/realized_statement.rs`, and corresponding conformance tests | Pro-rata realized-statement projection; package owns six-root assembly while PRF/Rust host adapters retain context/lifecycle derivation and application evidence binding |
| Source-bound entitlement vectors and Rust/SP1 materializer | The relevant application/allocation domain owns the economic basis and derivation; capital-lifecycle owns admission/binding and lifecycle conformance. Preserve mixed source-bound cases as explicit Shortfall/allocation/lifecycle integration coverage. Fixture location does not confer semantic ownership on SP1/deployment |
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

Known repository review limits from the preceding extraction remain separate:
the notebook failure at `appealed_escrow_walkthrough.clj:270` and all-notebook
timeout; three benchmark lifecycle integration errors reproduced with the
parent implementation. The `bb validate` subprocess-status wrapper also
incorrectly prints success after nonzero exits. These are not package
conformance evidence.

## Migration invariants and next steps

The existing extraction direction remains in force: finish in-tree package
boundaries before external repository moves; retain compatibility re-exports
where callers need them; pin dependency versions after extraction; and keep
independent Clojure/Rust implementations and fixtures that test exact semantic
equivalence. Moving code must not change frozen roots, schemas, domain
separators, canonical projections, proof public-value layouts, verifier
identities, or Solidity economic behavior. Runtime evidence stays separate
from canonical economic identities. Keep one authoritative builder per
artifact and add adapters at package boundaries instead of copying builders.

Already correct and to leave intact: the minimal proposed non-runtime
`canonical-artifacts.v1` registry; `canonical` owning canonicalization
mechanics rather than domain artifact meaning; pro-rata owning realized
allocation statements; Shortfall owning scarcity/source/reserve application
semantics; independent Clojure/Rust conformance; PRF as the integration
environment; and gradual extraction behind compatibility adapters. The
completed Rust context/root projection preparation is preserved. This
refinement does not reopen its contract or reset extraction progress.

Next implementation sequence:

1. Continue the already-started Rust pro-rata extraction one existing semantic
   slice at a time, retaining `allocation-kernel` compatibility facades and
   proving frozen roots with the current Clojure/Rust fixtures.
2. At the lifecycle boundary, define the adapter for an externally derived,
   authoritative entitlement set and its source binding. Do not create a new
   universal entitlement format or move derivation into lifecycle.
3. Treat AO as an existing in-tree boundary/interface in the dependency map.
   Extract or rewrite no AO implementation until a concrete package consumer
   requires that work.
4. Package/release repositories only after their in-tree dependency closure,
   conformance, and pinned dependency versions are qualified. Keep
   `protocol-deployments` authoritative throughout.

## Remaining effort in modern LLM coding passes

A pass means one coherent edit/review/validation cycle. These are planning
estimates, assuming unchanged frozen identities and an available runnable
baseline, not release guarantees.

| Next slice | Estimated passes |
|---|---:|
| Qualify and publish a versioned allocation package artifact | 2–3 |
| Package the four policy-outcome vectors and pin their roots | Complete |
| Evaluation artifact boundary reassessment | Complete: keep current evaluation package/envelope in PRF |
| Realized allocation statement Clojure/Rust extraction with context/lifecycle adapters | 3–5 |
| Capital-lifecycle extraction with allocation-neutral inputs | 4–7 |

The public adapter, explicit PRF local dependency, pinned allocation and
policy-outcome fixtures, and positive plus request-negative runners are now in
place. Rust
implementation of the missing primitive profile and completion of unimplemented
production lifecycle semantics are additional work. Each slice can leave PRF
usable through compatibility adapters; physical repository splits and release
operations follow qualification rather than preceding it.
