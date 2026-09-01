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

## Current profiles

| Profile | Status | Composition |
| --- | --- | --- |
| `framework` | supported | framework entry points and configuration |
| `sew-core` | supported | framework + Sew |
| `pro-rata` | supported | Sew + ideal pro-rata |
| `pro-rata-bounty` | supported | pro-rata + semantic bounty |
| `pro-rata-claimant-options` | supported | pro-rata + runtime-only claimant execution controls |
| `pro-rata-full` | supported | pro-rata + bounty + claimant runtime controls + pro-rata verifier |

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

Runtime-only features should not be added as source components. Add their
resolved settings under `:profile/runtime` instead. The settings must use the
existing `resolver-sim.execution.context` vocabulary and must be concrete values,
not unresolved `:default` markers.

For an editor/LSP profile workspace, open `.profile-view/<profile>/` and exclude
the checkout's broad `src/` and `protocols_src/` roots from indexing. This avoids
duplicate real/symlink source indexing.
