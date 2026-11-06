# Repository layout and ownership

This guide explains the main source areas and why similarly named pro-rata
directories remain separate. It is a navigation aid; ownership and immutable
dependency pins are recorded in
[`EXTRACTED_REPOSITORIES.md`](EXTRACTED_REPOSITORIES.md) and
[`PRO_RATA_REPOSITORY_OWNERSHIP.md`](PRO_RATA_REPOSITORY_OWNERSHIP.md).

## Main areas

| Path | Purpose |
| --- | --- |
| `src/` | PRF framework, execution, evidence, research, and application integration. |
| `protocols_src/` and `protocols/` | Protocol-specific models, tests, and protocol definitions. |
| `extensions/` | Separately packaged capabilities. Current packages include `force-authorisation` and `held-custody`. |
| `packages/` | Package metadata, local package boundaries, and linkage to extracted dependencies. |
| `data/prf/external-repositories.json` and `scripts/setup-prf-coprocessor.sh` | Pin and explicitly prepare the extracted Rust/SP1 proving repository. |
| `test/` and `protocols_src/test/` | Framework and protocol regression tests. |
| `scenarios/`, `data/`, `resources/`, and `schemas/` | Scenario inputs, fixtures, canonical resources, and schema definitions. |
| `examples/`, `notebooks/`, and `docs/` | Demonstrations, research notebooks, architecture, and operator guidance. |
| `scripts/`, `bin/`, and `bb.edn` | Build, validation, CLI, and maintenance entry points. |

## Pro-rata package directories

Four related trees now share one parent and consistent `kebab-case` names:

| Path | Current role |
| --- | --- |
| `pro-rata/contract-source/` and `pro-rata/contract-tests/` | Independent Clojure reference implementation and its conformance suite. |
| `pro-rata/portable-contract/` | Historical mixed allocation/effect-transition contract bundle with a manifest-bound release root. |
| `pro-rata/evm-v2-conformance/` | EVM V2 conformance inputs and rejection vectors for the PRF/EVM assurance boundary. |

These paths are grouped for navigation; the contents still serve distinct
contracts and evidence roles. Keep the portable bundle's canonical bytes and
release root stable, and keep the linkage-only `packages/pro-rata-allocation/`
directory separate from these four trees.

## Test keys and extensions

`test/fixtures/signing-keys/` contains active test and local-demo signing
fixtures. The researcher key pair is read by protocol and benchmark tests and
by the ratification demo generator. The release-signing pair and policy files
are retained unchanged; no in-repository code refers to those specific files.
They should be removed only after their external/manual use has been ruled out.

The two directories under `extensions/` are already separately packaged. More
code should move there only when a capability has a stable public boundary,
clear source ownership, and a consumer that can use the package without
reaching back into PRF internals. Splitting merely to reduce root directory
count would add package and compatibility work without improving the boundary.

### Extension package boundary today

`extensions/force-authorisation/` and `extensions/held-custody/` are
package-shaped source and test trees, but they are **not independently
released packages**. Their `deps.edn` files use `:local/root "../.."` for
`org.protocol-robustness/prf-core`, so their current consumer is this
monorepo's PRF source tree. A passing test from one of these directories
does not by itself establish a published artifact or independent
reproducibility. The separate clean-room qualification below proves the
manifest-declared source closure can be staged and tested without the monorepo
root on its classpath.

Their supported core source boundary is now
`resolver-sim.extensions.api.v1`; its exported functions and compatibility
rules are recorded in [`EXTENSION_CORE_API_V1.md`](../architecture/EXTENSION_CORE_API_V1.md).
The recommended first extraction slice and its release gates are recorded in
[`PRF_CORE_EXTRACTION_READINESS.md`](PRF_CORE_EXTRACTION_READINESS.md).

The intended dependency direction is:

```text
extension package → explicitly supported PRF core contracts
protocol adapter  → extension/core contracts and protocol implementation
PRF core          ↛ extension package or protocol implementation
```

Extension source may use its own namespaces and the explicitly allowed core
contract namespaces. It must not import `protocols_src/` or a protocol adapter
unless the package is specifically the adapter. Avoid treating broad internal
namespaces such as `resolver-sim.assurance.*` as a stable package API without
an explicit API/compatibility policy. Runtime test paths should be opt-in,
separate from a package's production source paths.

The current production source imports PRF through this supported namespace:

| Package | Direct PRF namespace imports |
|---|---|
| `force-authorisation` | `resolver-sim.extensions.api.v1` |
| `held-custody` | `resolver-sim.extensions.api.v1` |

The versioned facade currently delegates to PRF assurance, configuration,
artifact, hash, and sensitivity implementations. Those are implementation
dependencies behind the facade, not additional package-facing API imports.
The complete source closure and a clean-room qualification are recorded in
[`packages/prf-extension-api-v1/DEPENDENCIES.edn`](../../packages/prf-extension-api-v1/DEPENDENCIES.edn).
The facade and its compatibility rules are the API contract; the packages
still do not consume a separately published core artifact. Replacing
`:local/root` requires publishing and pinning the core artifact, then repeating
the package tests against that coordinate.

Qualification should run each package from its own `deps.edn`, with only its
runtime source path by default and tests selected through a test alias. Record
the exact local PRF revision used. This is a useful monorepo boundary check;
an independent-consumer claim additionally requires replacing the local root
with a released/pinned PRF artifact and building without repository-only paths.
