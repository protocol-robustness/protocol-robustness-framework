# Pro-rata allocation package boundary

Status: independently loadable **Clojure source package**, consumed locally by
PRF. It has no published version, package-owned conformance runner, Rust crate,
or independently qualified release yet. The canonical registry remains proposed
and non-runtime. This review covers the extracted primitive; the wider pro-rata
artifact family still needs extraction.

Definitions and authority: [proposed registry](../../docs/architecture/canonical-artifacts/canonical-artifacts.v1.edn),
[portable contract spec](../../portable-pro-rata-contract/SPEC.md), and
[NOW semantic freeze](../../docs/architecture/PROTECTED_CAPITAL_PRO_RATA_V2_SEMANTICS.md).

## Current and target trees

Current ownership and location:

```text
PRF/
├── packages/canonical/clj/          typed canonical codec
├── packages/pro-rata-allocation/clj/
│   ├── deps.edn                    Clojure + explicit canonical local dependency
│   └── src/prf/pro_rata/
│       ├── api.clj                  normalized request → allocation/witness/result
│       ├── request.clj, rows.clj    validation and canonical row ordering
│       ├── kernel.clj              normalization, quotas, largest remainder
│       ├── single_pass.clj         allocation with explicit runtime callbacks
│       ├── redistribution.clj      active-set cap redistribution
│       └── witness.clj, result.clj, policy.clj
├── src/resolver_sim/pro_rata/       PRF runtime/hash adapters; further domain code
├── src/resolver_sim/allocation/     realized statement and lifecycle coupling
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

All nine `prf.pro-rata.*` namespaces require only other namespaces in this
package or `prf.canonical.codec`; the codec has no PRF host dependencies. The
package `deps.edn` exposes only `src` and declares its sibling canonical package
through `:local/root "../../canonical/clj"`. PRF currently adds both source roots
to its main classpath. This proves a source dependency boundary; PRF has not yet
switched to a pinned package dependency or release artifact.

`prf.pro-rata.api/allocate` takes a public request and three adapters:

| Adapter | Required contract | Current PRF assembly |
|---|---|---|
| `:execute-allocation` | `(execution-request redistribution-policy)`; dispatches the supported policy to the package algorithm and returns its result | `src/resolver_sim/pro_rata/allocation.clj`, private `execute-allocation`; scoped PRF wrappers delegate to the package |
| `:hash-request` | Commits the normalized canonical request with the frozen intent projection | PRF `hash-with-intent` using `:projection-artifact` |
| `:hash-result` | Commits the projected result passed by the API | Portable `allocation-result-root`; its redundant projection preserves the same root |

Request and result both use `PROJECTION_ARTIFACT_V1`, but their preimages differ:

- Request: `{:intent :projection-artifact :artifact canonical-request}` under the
  existing PRF intent projection.
- Result: `prf.pro-rata.result/root-input`, without `:allocation/hash` or the
  diagnostic `:allocation/fractional-remainder-total`; there is no request-style
  intent envelope around the result.
- Policy: `prf.pro-rata.policy/identity-basis` under
  `PRO_RATA_ALLOCATION_POLICY_V1` through the PRF policy hash adapter.

The frozen scheme is SHA-256 of domain UTF-8 bytes followed by typed canonical
bytes. JSON serialization and EVM keccak commitments are different contracts.
The package API accepts adapters explicitly; it does not certify their outputs.
The independent reference and pinned bytes/roots are the conformance authority.
Do not replace the PRF intent projection with a raw request hash during packaging.

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
and hooks remain excluded from canonical request identity. A production runtime
adapter must preserve these contracts rather than copy the illustrative serial
adapter below.

### Standalone arithmetic example

Run from `packages/pro-rata-allocation/clj`:

```bash
clojure -M -e '
(require (quote [prf.pro-rata.redistribution :as allocation]))
(let [runtime {:effective-claimant-parallelism (fn [& _] 1)
               :ordered-detached-mapv (fn [_ _ f values] (mapv f values))
               :report-progress! (fn [& _])}
      result (allocation/allocate
              {:amount 100
               :items [{:id :a :weight 1 :cap 10}
                       {:id :b :weight 1 :cap 40}
                       {:id :c :weight 1}]}
              runtime)]
  (prn (mapv :allocated (:allocations result))))'
```

Expected: `[10N 40N 50N]`. This example demonstrates package-only arithmetic;
it supplies no progress observer, parallel execution, timeout enforcement,
canonical public artifact hash, source assurance, or custody lifecycle.

## Conformance ownership and release gates

Keep one authoritative set of frozen vectors at the existing paths until the
package can run them independently. Move each set with the semantic contract it
specifies; host behavior needs separate integration coverage.

| Current material | Proposed destination and constraint |
|---|---|
| `portable-pro-rata-contract/vectors/` allocation vectors and matching allocation-result canonical bytes | `pro-rata-allocation`; preserve bytes/roots, negative cases, row order and rounding/redistribution policies |
| Portable allocation reference in `pro_rata_contract_src/.../contract.clj` | Independent allocation reference owned by pro-rata; separate its generic effects/transition material deliberately |
| `test/resolver_sim/pro_rata/portable_contract_vectors_test.clj` | Package conformance runner for primitive results; retain PRF wrapper parity assertions as integration coverage |
| Allocation/pro-rata characterization tests | Semantic arithmetic cases travel with pro-rata; executor, progress and realization cases stay with host adapters |
| `resources/test-vectors/pro-rata/policy-outcome-conformance-v1.edn` | Pro-rata policy/outcome contract; implementation is still host-located beyond the extracted policy basis |
| `coprocessor/core/tests/realized_statement_conformance.rs`, statement fixtures and corresponding Clojure tests | Pro-rata realized-statement profile; preserve explicit context/lifecycle adapters |
| Source-bound entitlement vectors and Rust/SP1 materializer | Capital-lifecycle owns entitlement projection conformance; preserve mixed source-bound cases as explicit Shortfall/allocation/lifecycle integration coverage. Fixture location does not confer semantic ownership on SP1/deployment |
| Solidity execution/admission/custody tests | Companion integration coverage for the relevant projection and lifecycle profile; not primitive allocation SHA-256 equivalence by itself |

The registry correctly marks the primitive request/result/policy Rust
implementations missing. Rust realized-fill/statement code implements a different
profile; it does not establish support for the complete public primitive
request/result/witness format. Solidity support is profile-specific too. Do not
promote the registry's partial conformance status from a Clojure package smoke.

Minimum gates before an independently consumable release:

1. Assemble the public API with exact frozen request/result/policy hash adapters
   and a documented supported runtime. Run with only declared package dependencies.
2. Give that package its own runner for positive/negative allocation vectors,
   canonical bytes, roots and policy cases. Keep the reference independent.
3. Make PRF consume an explicit local package dependency, then a pinned version;
   prove wrapper/integration parity before removing classpath source entries.
4. Package and qualify each additional artifact profile separately. Establish
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
| Public adapter assembly + package-owned primitive conformance runner | 1–2 |
| Explicit PRF package dependency + consumer parity | 1–2 |
| Separate/pin allocation reference, specs and vectors from shared effects | 1–2 |
| Policy/outcome and evaluation artifact extraction | 2–3 |
| Realized allocation statement Clojure/Rust extraction with context/lifecycle adapters | 3–5 |
| Capital-lifecycle extraction with allocation-neutral inputs | 4–7 |

The first three slices prepare the existing Clojure primitive for independent
consumption. Rust implementation of the missing primitive profile and completion
of unimplemented production lifecycle semantics are additional work. Each slice
can leave PRF usable through compatibility adapters; physical repository splits
and release operations follow qualification rather than preceding it.
