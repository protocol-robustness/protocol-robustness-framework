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
| `coprocessor/` | Compatibility entry point for the extracted Rust/SP1 proving repository. |
| `test/` and `protocols_src/test/` | Framework and protocol regression tests. |
| `scenarios/`, `data/`, `resources/`, and `schemas/` | Scenario inputs, fixtures, canonical resources, and schema definitions. |
| `examples/`, `notebooks/`, and `docs/` | Demonstrations, research notebooks, architecture, and operator guidance. |
| `scripts/`, `bin/`, and `bb.edn` | Build, validation, CLI, and maintenance entry points. |

## Why four pro-rata directories remain at the root

The four root paths are `pro_rata_contract_src/`,
`pro_rata_contract_test/`, `portable-pro-rata-contract/`, and
`pro-rata-evm-v2-conformance/`. They describe different contracts and evidence
roles; merging them by name would blur their ownership. The linkage-only
`packages/pro-rata-allocation/` directory is separate and is not one of these
four root trees.

| Path | Current role | Safe next step |
| --- | --- | --- |
| `packages/pro-rata-allocation/` | Linkage to the extracted `prf-pro-rata-allocation` package; implementation and allocation fixtures are consumed from its pinned revision. | Keep the pointer and pin. |
| `pro_rata_contract_src/` and `pro_rata_contract_test/` | Independent Clojure reference implementation and its conformance suite. This remains a second implementation used as an oracle. | Retain until the extracted packages are independently checked against the same frozen allocation and transition vectors. Then relocate as one reviewed reference-fixture unit. |
| `portable-pro-rata-contract/` | Historical mixed allocation/effect-transition contract bundle with a manifest-bound release root. | Preserve its path and bytes while consumers still verify that historical identity. The effect-transition candidate is not yet an admitted PRF dependency. |
| `pro-rata-evm-v2-conformance/` | EVM V2 conformance inputs and rejection vectors for the PRF/EVM assurance boundary. | Keep with that boundary; it is not part of the portable allocator package. |

The remaining extraction work is specific: reconcile the effect-transition
pin recorded in `EXTRACTED_REPOSITORIES.md` with the actual root `deps.edn`
classpath (which does not currently load that package); decide how the
independent reference suite will be distributed; and only then move the
historical mixed bundle behind an explicit compatibility path. Until the pin
and classpath agree, do not describe the effect-transition candidate as an
active PRF dependency. Do not rename canonical identities, namespaces,
schemas, or roots as part of filesystem cleanup.

## Test keys and extensions

`test-keys/` is active test and local-demo material. The researcher signing key
is read by protocol and benchmark tests and by the ratification demo generator;
release-signing key paths are used by the assurance-lab scripts. It should not
be deleted as unused. A later path-only move into a clearly named test-fixture
location is possible after the scripts and test working-directory assumptions
are updated together.

The two directories under `extensions/` are already separately packaged. More
code should move there only when a capability has a stable public boundary,
clear source ownership, and a consumer that can use the package without
reaching back into PRF internals. Splitting merely to reduce root directory
count would add package and compatibility work without improving the boundary.
