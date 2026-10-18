# Pro-rata repository ownership map

Status: first-pass inventory. This records current ownership and proposed
follow-up work; it does not authorize moving files or changing canonical
identities.

## Ownership by area

| Current path | Contents and role | Owner / action |
| --- | --- | --- |
| `packages/pro-rata-allocation/` | Package source, Clojure and Rust implementations, API contract, tests, and local RC bundle kept in the PRF tree for integration | `prf-pro-rata-allocation`. PRF should consume the pinned external revision after integration qualification; the in-tree directory is a migration copy until then. |
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
  ├── prf-pro-rata-allocation (currently local path in deps.edn)
  │     └── prf-canonical (currently local dependency in the package tree)
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

## Recommended sequence

1. **Close the package ownership migration.** Change PRF from the local package
   path to the pinned Git revision in the integration workspace, run package
   conformance and PRF adapter tests, and remove the tracked package copy only
   after the clean-checkout build succeeds. Keep the independent reference.
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
