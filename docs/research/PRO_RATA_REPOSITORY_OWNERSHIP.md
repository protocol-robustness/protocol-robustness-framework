# Pro-rata repository ownership map

Status: first-pass inventory. This records current ownership and proposed
follow-up work; it does not authorize moving files or changing canonical
identities.

## Ownership by area

| Current path | Contents and role | Owner / action |
| --- | --- | --- |
| `packages/pro-rata-allocation/` | Linkage README only | `prf-pro-rata-allocation`; implementation, contract, tests, and package metadata live in the pinned external repo. |
| `packages/canonical/` | Canonical codecs and generic hashing used by the allocator and PRF | `prf-canonical`; dependency is pinned separately. Do not copy implementations into either consumer. |
| `portable-pro-rata-contract/` | Normative allocation/effect/transition specs, manifest, vectors, canonical bytes, and extraction plan | Mixed ownership requiring a follow-up decision. Allocation vectors/spec portions belong with allocation; generic effect/transition material needs its own owner. Keep the frozen material authoritative until consumers and roots are migrated. |
| `pro_rata_contract_src/` | Independent Clojure reference implementation for allocation plus effect/state/transition projections | Independent conformance/reference code. It is not the extracted allocator implementation. Keep it independent while it is the oracle; later package it with the relevant frozen vectors or replace it only after equivalent independent conformance exists. |
| `pro_rata_contract_test/` | Tests and runner for the independent contract implementation | PRF conformance integration for now; likely co-locate with the reference contract material when that boundary is resolved. |
| `src/resolver_sim/pro_rata/` | PRF host integration, execution/progress, protocol application, evidence, policy, EVM, assurance, evaluation, and publication code | PRF. This directory is intentionally broader than the portable allocation package and should not move wholesale. Separate sub-boundaries only where a consumer exists and ownership is explicit. |
| `test/resolver_sim/pro_rata/` | PRF integration, protocol, EVM, assurance, evidence, and package parity tests | PRF. Portable allocator tests belong in its repo; tests that exercise PRF adapters and downstream contracts remain here. |
| `resources/test-vectors/pro-rata/`, `test/fixtures/pro-rata/`, `data/fixtures/pro-rata/` | PRF policy/application/evidence fixtures and vectors | PRF unless a vector is normative for the portable allocation API. Normative allocation vectors should have one owner and be consumed by pinned revision. |
| `profiles/pro-rata*.edn`, `profiles/components.edn`, `scripts/profile_view.clj` | PRF source composition profiles and generator | PRF. These profiles select framework, Sew, bounty, runtime, and verifier components. They are not allocation-library profiles. |
| `pro-rata-evm-v2-conformance/`, `examples/assured-operations/pro-rata-conformance/` | EVM V2 conformance inputs and assurance integration | PRF / EVM and assurance boundaries. Do not move into the portable allocation package based on shared terminology. |
| `examples/use-cases/pro-rata-allocation/`, `notebooks/`, `scenarios/` | PRF demos and applied scenarios | PRF examples/scenarios. They should consume the extracted package through pinned dependencies where they use portable allocation. |

## Current dependency picture

```text
PRF integration (`src/resolver_sim/pro_rata`, allocation/evidence adapters)
  ├── prf-pro-rata-allocation (Git revision pinned in integration deps.edn)
  │     └── prf-canonical (Git revision pinned in integration deps.edn)
  ├── independent reference (`pro_rata_contract_src`)
  │     └── independent canonical codec in that reference tree
  ├── PRF protocol/runtime/assurance code
  └── coprocessor and EVM conformance boundaries

PRF profile views
  └── PRF component manifest and local namespace closure
```

The extracted package and the independent contract reference are separate
implementations with different purposes. The old `portable-pro-rata-contract`
name currently groups allocation and effect/transition contracts, so a global
rename or wholesale move would obscure ownership rather than clarify it.

## Qualification and migration status

The integration workspace at PRF revision `9f03721b` declares these immutable
dependencies in `deps.edn`:

| Repository | Revision | Verification in a clean PRF checkout |
| --- | --- | --- |
| `protocol-robustness/prf-pro-rata-allocation` | `48e0b362748cd39d09994a8aa1496c668199aa74` | Clojure PRF compatibility: 36 tests / 382 assertions passed; allocator standalone conformance: 8 tests / 79 assertions passed; Rust: 52 tests passed |
| `protocol-robustness/prf-canonical` | `5e2b520feca812b049ba397b7b3681963c66a48c` | Loaded as the allocator's canonical dependency |

The clean PRF checkout loaded `prf.pro-rata.api` from the pinned allocator
revision and `prf.canonical.hash` from the pinned canonical revision. Each
namespace resource appeared once on the classpath. The package Git checkout was
clean at the pinned revision. This establishes source dependency compatibility
for those checks; it does not promote release qualification or SP1/Solidity
proof claims.

In the integration checkout, the allocation-kernel Cargo suite passed 83 tests
and the SP1 verifier guest passed `cargo check`. The SP1 host check stopped
while compiling the nested executor because the filesystem ran out of space;
this is an environment-capacity blocker and is not a host qualification result.

The live PRF copy under `packages/pro-rata-allocation/clj` and `rust` has been
removed. The remaining directory is a pointer to the external repository and
the immutable revision in root `deps.edn`. The dependency-graph test now scans
the actual pinned classpath source and confirms that allocator and canonical
namespaces each resolve once. The partial-fill source evidence builder now
archives clean source checkouts of both pinned dependencies, records their
URLs/revisions, and excludes Git metadata and build output. Historical source
inventories and retained evidence remain unchanged.

## Recommended sequence

1. **Close the package ownership migration.** The integration workspace pins
   the Git revisions above, PRF compatibility checks pass, source archives
   include the exact dependencies, and the live implementation mirror is
   removed. Keep the independent reference.
2. **Split contract ownership on paper before moving files.** Identify which
   vectors/spec sections define allocation V1 and which define generic effects,
   state, and transitions. Preserve exact bytes and roots and assign one
   authoritative home to each.
3. **Migrate only portable allocation contract material.** Make PRF and the
   allocation repository consume a single immutable vector/spec revision;
   leave generic effect/transition semantics with their chosen owner.
4. **Align PRF integration tests and profiles.** PRF-specific integration,
   runtime controls, SEW/bounty composition, assurance, EVM, and scenario tests
   stay in PRF. Update profile generation to resolve pinned dependencies
   explicitly rather than exposing stale in-tree package paths.
5. **Do a path presentation cleanup.** Rename/group directories only after
   ownership and consumers have settled. Update build aliases, scripts,
   notebooks, source manifests, evidence inventories, docs, and CI references;
   prove canonical roots and frozen bytes unchanged.

## Contract bundle ownership split

`portable-pro-rata-contract/` is a mixed normative bundle. Its current manifest
hashes every listed file and binds them under release root
`b863e04a61d0dded845dc3351626ebf57c4d5abea7e18ea19a89cacf4b1ad12a`. Treat that
bundle and root as historical immutable evidence. A physical split must create
new package identities and revision pins; it must not rewrite the existing
manifest or claim the existing release root for a subset.

| Contract content | Current files | Proposed owner | Split treatment |
| --- | --- | --- | --- |
| Allocation request/result schemas, rounding, caps, redistribution, identity, and invariants | `SPEC.md` §4; allocation schema/policies/domains in `contract.edn`; six allocation vectors under `vectors/`; five `*.allocation-result.hex` files under `canonical-bytes/` | `prf-pro-rata-allocation` contract surface | Publish the exact bytes under a new immutable allocation-contract identity, or keep the existing bundle as the normative source until that package is pinned. Preserve every allocation root and byte vector. |
| Canonical quantity state, effects, transition derivation, and rejection rules | `SPEC.md` §§5–7 and transition portions of §9; effect/state/transition schema and semantics in `contract.edn`; `canonical-transition-001.edn`, `negative-transition-001.edn`; `state-before.hex`, `effects.hex`, `state-after.hex`, `transition.hex` | A separately versioned effect-transition contract package (candidate name: `prf-effect-transition-contract`) | Move only after consumers can resolve and verify the new immutable identity. These are generic transition semantics, not allocation implementation. |
| Allocation-to-effect relationship and mixed release metadata | `SPEC.md` §8; `manifest.edn`; shared release/domain declarations in `contract.edn`; `EXTRACTION_PLAN.md` | PRF integration contract during migration | Keep the bridge explicit in PRF until both contract packages are pinned. The old manifest remains a compatibility/provenance record; a new composition manifest may reference both package revisions but must receive a distinct root. |

The first group contains six EDN vectors: allocation rounding, cap clamping,
floor residual, negative allocation, cap-excess redistribution, and zero
weight. The transition group contains the two transition vectors. Allocation
result byte fixtures are separate from the four state/effect/transition byte
fixtures. The current `contract.edn` and release manifest mix declarations for
both groups, so they cannot be assigned wholesale to either package.

The independent `pro_rata_contract_src/` implementation remains PRF-owned as a
conformance oracle for now. Before replacing or relocating it, consumers need
to validate both new package pins against the same frozen allocation and
transition bytes in clean checkouts. No contract file, namespace, schema name,
domain tag, or canonical root changes in this ownership pass.

## Naming and compatibility

The mixed naming (`pro_rata`, `pro-rata`, and `portable-pro-rata-contract`)
spans Clojure namespace conventions, repository paths, and contract identity.
Only filesystem paths should be considered for cleanup. Clojure namespaces,
schema names, domain tags, artifact identities, and canonical roots are stable
contracts and must not be renamed as part of presentation work.

Path renames are source-compatible only after all path consumers are updated.
Known consumers include `deps.edn` path aliases, package assembly scripts,
profile source roots, test fixture lookups, conformance runners, evidence/source
inventories, notebook links, and CI workflows. Historical evidence may continue
to record old paths; preserve its source revision and content hashes rather than
rewriting the historical record.

## Gates before declaring extraction complete

- PRF and standalone consumers resolve the same immutable allocator and
  canonical revisions without local classpath overrides.
- No duplicate `prf.pro-rata.*` or `prf.canonical.*` implementation is visible
  on a consumer classpath.
- Independent Clojure and Rust allocation conformance passes against frozen
  bytes and roots.
- PRF adapter, policy/evidence, profile, and downstream integration checks pass
  from a clean checkout.
- Effect compilation V2/V3, EVM statements, SP1 guest/host code, source-bound
  assurance, protocol lifecycle, benchmarks, and scenarios remain with their
  established owners unless separately audited and approved.
- RC limitations remain explicit; source qualification does not imply release,
  SP1, or Solidity proof qualification.
