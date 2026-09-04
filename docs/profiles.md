# Constrained composition profiles

Profiles are authoritative declarations of a supported implementation composition.
They are not additive `deps.edn` aliases: a profile resolves to one generated,
constrained source view.

## Commands

```sh
bb profile:describe pro-rata
bb profile:view pro-rata-bounty
bb profile:check pro-rata
  bb profile:check pro-rata-claimant-options
  bb profile:diff pro-rata pro-rata-claimant-options
```

`profile:view` writes `.profile-view/<name>/`, which is generated and ignored.
It contains:

- file-level Clojure symlinks for the transitive `resolver-sim.*` dependency
  closure of component entry namespaces;
- explicitly declared non-Clojure material, such as configuration, test vectors,
  fixtures, and (where selected) Rust crates;
- a generated `deps.edn` whose `:paths` contain only the view's `src`,
  `protocols_src`, and `resources` roots;
- `PROFILE.edn`, recording the resolved profile, components, source closure, and (when selected) runtime profile;
- `runtime-options.edn`, when a runtime profile is selected, recording the resolved runtime options and runtime-profile root.

The individual-file links preserve namespace paths without linking an ancestor
like `src/resolver_sim`, which would expose unselected features. Edit a linked
file normally: it updates the authoritative checkout.

## Source profiles versus runnable artifacts

The strict `framework` source profile is the architectural ownership boundary;
it is not the dependency closure of a runnable CLI distribution. The package
variants are intentionally separate:

- `prf.jar` is the non-runnable framework library built from the strict
  `framework` view. It has no `Main-Class` contract.
- `prf-runnable.jar` is the runnable PRF CLI distribution. It preserves the
  existing CLI, resource, and example/application inclusion closure and carries
  the `Main-Class` contract.
- The Sew variant remains a Sew-enabled runnable distribution with its existing
  resolved inputs and artifact semantics.

Runnable artifacts may therefore be intentional supersets of a source ownership
profile. Source ownership remains authoritative; executable packaging must not
broaden the strict framework boundary merely to preserve a historical JAR name.

Framework-library loading and runnable-CLI execution are separate acceptance
claims: `prf.jar` must be consumable as a library, while `prf-runnable.jar` is
the artifact tested with `java -jar`. 

## Current profiles

| Profile | Status | Composition |
| --- | --- | --- |
| `framework` | supported | portable canonical/framework base only |
| `runner` | supported | framework + runnable application/runner assembly |
| `sew-core` | supported | runner + Sew protocol |
| `pro-rata` | supported | Sew + ideal pro-rata |
| `pro-rata-bounty` | supported | pro-rata + semantic bounty |
| `pro-rata-claimant-options` | supported | pro-rata + runtime-only claimant execution controls |
| `pro-rata-full` | supported | pro-rata + bounty + claimant runtime controls + pro-rata verifier |

`runner-v1` owns runnable/application assembly such as scenario execution, replay,
server/session integration, and CLI runners. It is intentionally not part of the
minimal `framework` profile. The existing source locations are unchanged; the
component/profile boundary is expressed through the generated source view.

`claimant-options` is an implemented runtime abstraction in
`resolver-sim.execution.context`, not a source/package component. Its profile
spells the existing runtime contract directly:

```clojure
{:execution/claimant-parallelism 4
 :execution/claimant-parallel-threshold 1
 :execution/quiescence-timeout-seconds 30}
```

These options affect claimant-local execution and are intentionally separate
from semantic claimant inputs. The claimant-options profile therefore has the
same component and Clojure source closure as `pro-rata`; it changes runtime
metadata, not source visibility or semantic implementation composition.

`pro-rata-full` demonstrates the three profile planes:

```text
semantic:   ideal-pro-rata + bounty
runtime:    claimant-options
assurance:  pro-rata-verifier
```

## Adding a component

Add a component to `profiles/components.edn` with its plane, direct component
requirements, entry namespaces, and any non-Clojure material paths. Then add a
small profile descriptor under `profiles/`. The generator follows Clojure
requires from those entry namespaces, but it does not infer resources, fixtures,
Rust crates, scripts, or other material: those must remain explicit.

The profile report describes repository-owned composition. In particular:

- `owned` means namespaces explicitly owned by selected components;
- `transitive` means namespaces/files reached through the selected entry
  namespaces and declared material;
- `user-added` is not inferred from a namespace or symbol and is not currently
  a profile-report category. Caller-supplied extensions or examples must be
  declared by a future, explicit extension/material mechanism rather than being
  mistaken for framework ownership;
- protocol inclusion is represented by selected protocol components (for example
  `:prf/sew-core-v1`), not by the Solidity shadow-coverage option
  `:include-protocols?`.

`include-protocols?` belongs to Solidity shadow coverage and is outside profile
closure/view/report semantics. A profile report must therefore not be read as a
report of Solidity shadow coverage.

For example, `add-held` is a Sew held-custody primitive supplied by the
repository's protocol layer. Its implementation is outside strict framework
ownership; it is not classified as user-added merely because a caller may use
it. Symbol-level ownership is not currently reported; the owning namespace and
component are the authoritative classification.

Runtime-only features should not be added as source components. Add their
resolved settings under `:profile/runtime` instead. The settings must use the
existing `resolver-sim.execution.context` vocabulary and must be concrete values,
not unresolved `:default` markers.

For an editor/LSP profile workspace, open `.profile-view/<profile>/` and exclude
the checkout's broad `src/` and `protocols_src/` roots from indexing. This avoids
duplicate real/symlink source indexing.
