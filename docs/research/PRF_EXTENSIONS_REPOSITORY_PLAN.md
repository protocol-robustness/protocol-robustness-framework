# PRF first-party extensions repository plan

Status: proposed repository and package boundary. This plan does not move source,
create a remote repository, publish artifacts, or claim package independence.

## Decision

Use one `prf-extensions` repository as the first-party development and
composition workspace. Keep executable packages and declarative concept bundles
independently versioned and qualified. The repository is not one deployable
extension: consumers select and pin individual package roots and dependencies.
Create a separate repository only when a package has independent maintainers,
external adoption, substantial domain ownership, or a distinct release lifecycle.

Repository co-location does not relax package boundaries. Production source may
depend only on versioned public package APIs, never another package's private
source directory. Test and integration dependencies are opt-in and are not part
of runtime closures.

## Initial layout

```text
prf-extensions/
  extensions/
    force-authorisation-scope/
    force-authorisation-governance/
    held-custody/
    governed-authority/
  adapters/
    sew-force-authorisation/
  concepts/
    framework/
    prf-research/
    sew/
    stablecoins/
  integration-tests/
  tooling/
```

These are proposed package boundaries. Start with logical package manifests and
independent versioning; do not split each directory into a separate Git or Maven
project until API and release requirements justify it.

`force-authorisation-scope` owns protocol-neutral scope normalization,
commitments, expiry, target binding, and consumption semantics.
`force-authorisation-governance` consumes the governed-authority API to produce
permits; it does not implement or own the authority provider.
`sew-force-authorisation` owns Sew custody execution and effect evidence. It
depends on the force-authorisation permit contract and held-custody mutation
contract. Keep `governed-authority` and `held-custody` as provider packages.
Force-authorisation remains simulation-only until a separate on-chain path is
implemented and qualified.

## Package manifest requirements

Every package or concept bundle declares:

* Stable ID, semantic version, manifest version, owner, license, support status,
  and source root.
* Public API or catalogue schema version and compatibility promise.
* Runtime dependencies as package IDs plus accepted version ranges; no source
  path dependencies.
* Capability requirements, profiles, input/output contracts, and independent
  verification contract where executable.
* Qualification command, test suite, exact dependency lock, and retained
  qualification evidence.
* For release, immutable source root and artifact hash. A Git commit alone is
  source identity, not a built artifact or dependency closure.

Concept bundles use the same identity, dependency, version, root, and provenance
conventions but have no executable entrypoints. They declare concept ID
namespaces, required bundle versions, catalogue schema, and protocol mapping
ownership. Bundle merge is explicit, deterministic, and rejects duplicate IDs
unless an intentional shadow is declared and validated. No classpath discovery.

An evidence artifact that refers to a concept definition binds the exact bundle
and concept roots. Updating a general explanatory definition does not mutate the
meaning of a retained evidence record.

## Qualification gates

Each executable package must:

1. Load and run from a clean consumer project with no PRF checkout paths.
2. Import only public, versioned core/package namespaces.
3. Resolve all runtime dependencies from pinned immutable coordinates/commits.
4. Pass package-local positive and adversarial tests in isolation.
5. Pass composition tests with required packages missing, incompatible, or
   returning malformed/adversarial results.
6. Retain exact source, artifact, dependency, runtime and qualification roots.

Each concept bundle must pass closed schema validation, namespace and dependency
resolution, explicit ownership/mapping checks, collision checks, and deterministic
merge/root tests. It must not change execution or evidence roots unless a
consumer explicitly commits the bundle root into its own artifact.

## Migration order

1. Finish and qualify `prf-extension-api-v1`; publish or pin the public API
   artifact before removing monorepo-local core dependencies.
2. Complete the force-authorisation package contract and implementation. The
   candidate now advertises only its implemented protocol-neutral scope
   capability; governed permit issuance and Sew custody execution remain in the
   PRF runtime and are not advertised by this package. The scope entrypoint now
   invokes its verifier, and package status is incubating/source-candidate.
   Continue treating it as unreleased until an external consumer qualifies it.
3. Move the force-authorisation logical packages and tests into `prf-extensions`
   as a source-layout change. Preserve schemas, semantics, and test vectors.
4. Qualify package composition with governed-authority, held-custody, and the
   Sew adapter through public contracts.
5. Add concept-bundle schema and explicit validation/merge tooling. Migrate
   stable concepts first, then PRF research, Sew, and stablecoin domain bundles.
6. Publish packages individually only after clean consumer qualification and
   named release ownership. Do not require one coordinated all-package release.

## Current status and blocker

`packages/prf-extension-api-v1` is an in-tree, clean-room-qualified source
candidate; it is not a published core artifact. `extensions/force-authorisation`
and `extensions/held-custody` still use a local-root dependency for PRF core, so
neither is independently consumable yet. No `prf-extensions` checkout was found
in `/home/user/Code` during this planning pass. Create or seed the repository
only after the public API and source ownership are ready for an intentional
source-layout migration. The force-authorisation source-closure qualification
currently passes 105 tests / 598 assertions at
`sha256:ed63122aa5dd54274926dde94f8657fbcef04d825ffaa95aff0dd6ca70d30cf2`;
this is an in-tree source qualification, not an independently released package.
