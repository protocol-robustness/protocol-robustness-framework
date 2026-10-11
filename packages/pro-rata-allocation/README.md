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
│   └── rust/                         standalone statement identity crate
├── src/resolver_sim/pro_rata/       PRF runtime/hash adapters; further domain code
├── src/resolver_sim/allocation/     context/lifecycle adapters and evidence binding
├── pro_rata_contract_src/           independent frozen reference; shared effects
├── portable-pro-rata-contract/     allocation + effect/transition specs and vectors
├── test/resolver_sim/pro_rata/      parity, progress, arithmetic, host integration
└── coprocessor/core/               realized allocation + lifecycle + source binding
```

Target, after separate qualification and incremental extraction:

```text
canonical/                          codec, generic hashing/registration machinery
pro-rata-allocation/                owns allocation artifact definitions
├── clj/                            primitive API + semantic algorithms
├── rust/                           corresponding supported artifact profiles
├── conformance/                    allocation specs, reference and frozen vectors
└── allocation definitions/         policy/outcome/realized-statement profiles
capital-lifecycle/                  entitlement, consequence, withdrawal, closure
Shortfall/                          authoritative demand/source/reserve bindings
PRF or example/deployment/          explicit pinned dependencies and host adapters
```

These are ownership destinations, not instructions to move entire existing
folders. In particular, the portable contract also contains generic effects and
transition semantics; that material requires its own ownership decision. Frozen
identities must remain distinct throughout extraction.

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
to that crate, preserving its public Rust API and SP1 input contract.

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
| Source-bound entitlement vectors and Rust/SP1 materializer | Capital-lifecycle owns entitlement projection conformance; preserve mixed source-bound cases as explicit Shortfall/allocation/lifecycle integration coverage. Fixture location does not confer semantic ownership on SP1/deployment |
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
24 unrelated formatting failures; notebook failure at
`appealed_escrow_walkthrough.clj:270` and all-notebook timeout; three benchmark
lifecycle integration errors reproduced with the parent implementation. The
`bb validate` subprocess-status wrapper also incorrectly prints success after
nonzero exits. These are not package conformance evidence.

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
