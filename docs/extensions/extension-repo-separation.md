# ADR-0006: Hybrid Extension Repository Architecture

- **Status:** Proposed
- **Date:** 2026-11-02
- **Decision owners:** PRF maintainers
- **Related:** ADR-0005 — Framework Extension Packages; `EXTRACTION_CANDIDATE_ASSESSMENT.md`; `packages/prf-extension-api-v1/`; `data/concepts/README.md`; `data/concept-mappings/README.md`
- **Scope:** PRF executable extensions, adapters, declarative concept bundles and third-party extension distribution

## 1. Context

PRF is evolving from a repository containing framework implementation, research mechanisms, simulation capabilities and domain-specific integrations toward a smaller core with independently consumable extensions.

Some capabilities are sufficiently distinct to warrant separate lifecycle, ownership and qualification boundaries. Examples include force-authorisation, governed-authority, held-custody and Sew-specific integration.

PRF also maintains a concept catalogue containing explanatory definitions, mappings and domain vocabulary. These are declarative assets rather than executable capabilities.

The architectural decision is whether to organize these components into:

1. A repository for each extension.
2. One shared extensions repository.
3. Extensions retained inside PRF core.
4. A hybrid arrangement combining shared development with independent packaging and selective repository extraction.

The repository boundary must not be confused with the runtime, dependency, release or trust boundary.

A standalone Git repository does not make an extension independent if it still imports private PRF implementation namespaces.

Conversely, an extension can be independently consumable, qualified and versioned while its source is maintained within a shared repository.

PRF additionally requires:

- A small, auditable core.
- Minimal tooling and operational dependencies.
- Deterministic evidence and reproducible qualification.
- Explicit authority and economic-effect boundaries.
- Clear distinctions between implemented, experimental, qualified and deployed capabilities.
- Independent research and third-party extension development.
- Compatibility with automated multi-agent development and integration workflows.
- No implicit claim that research or simulation evidence establishes production or on-chain authority.

## 2. Decision

**Adopt a hybrid repository architecture.**

PRF will maintain:

1. **PRF core repository:** framework kernel, public extension contracts, extension loading and validation, and shared primitives that genuinely belong to the framework.
2. **Shared first-party extensions repository (`prf-extensions`):** executable extensions, integration adapters, declarative concept bundles and cross-extension qualification.
3. **Independent extension repositories:** optional, for components whose ownership, distribution, security or release lifecycle justifies separation.

The default development unit is a package.

The default first-party source repository is `prf-extensions`.

The independent distribution unit is a versioned package or immutable bundle, **not the repository as a whole**.

No extension is required to move into its own repository merely because it has an independent API, test suite, semantic version or release artifact.

No production or research assurance claim follows solely from repository extraction.

### 2.1 Architectural principles

**P1 — Dependency independence before repository independence**

A package must become independent of private PRF source before repository extraction is considered complete.

**P2 — Core owns contracts; extensions own optional behavior**

PRF core owns the minimal public interfaces, loading and validation mechanisms. Optional domain behavior belongs to extensions.

Core must not depend on optional extension implementations.

**P3 — Package isolation within the shared repository**

Sharing a repository does not grant permission to depend on another package's private implementation.

**P4 — Qualification follows the artifact**

Qualification binds to a specific package identity, source revision, dependency closure, artifact digest, configuration and capability scope.

**P5 — Explicit authority**

An extension cannot acquire governance, custody, settlement or other privileged authority merely by being installed.

**P6 — Declarative concepts are not executable extensions**

Concept bundles and executable capabilities may share publication infrastructure but retain distinct contracts, validation and semantics.

**P7 — Evidence must survive repository movement**

A repository move must not silently change existing evidence identities, recorded findings or the interpretation of prior results.

**P8 — Avoid premature fragmentation**

A separate repository requires a demonstrated benefit beyond organizational tidiness.

## 3. Target repository layout

### 3.1 PRF core

```text
protocol-robustness-framework/
├── packages/
│   └── prf-extension-api-v1/
├── src/
│   └── ... framework core ...
├── data/
│   └── ... shared framework definitions ...
├── docs/
│   └── architecture/
└── ...
```

PRF core retains the extension API, loader, validation contracts and shared semantic primitives.

Initially, the extension API may remain a package within PRF. It should be independently consumable through a pinned artifact or immutable source revision.

A separately published API artifact is preferred for stable releases but does not require its own repository.

The API must remain narrower than PRF's internal implementation surface.

### 3.2 Shared extensions repository

```text
prf-extensions/
├── README.md
├── packages/
│   ├── force-authorisation/
│   │   ├── src/
│   │   ├── test/
│   │   ├── deps.edn
│   │   ├── extension.edn
│   │   └── README.md
│   │
│   ├── governed-authority/
│   └── held-custody/
│
├── adapters/
│   └── sew/
│       └── force-authorisation/
│
├── concepts/
│   ├── framework/
│   ├── prf-research/
│   ├── sew/
│   └── stablecoins/
│
├── integration-tests/
│   ├── composition/
│   ├── conformance/
│   └── negative/
│
├── tooling/
│   ├── validate-packages
│   ├── validate-concepts
│   ├── dependency-audit
│   └── qualify-package
│
├── docs/
│   ├── PACKAGE_CONTRACT.md
│   ├── RELEASE_POLICY.md
│   └── QUALIFICATION_POLICY.md
│
└── .github/
    └── workflows/
```

This is a target layout, not a claim that all listed components currently exist or are ready for extraction.

Directory placement establishes ownership and navigation, but not independent qualification.

Each package must also satisfy its API and dependency boundaries.

### 3.3 External repositories

Third-party or independently maintained extensions may reside in repositories outside `prf-extensions`.

They must use the same published extension contract and qualification requirements.

Registration in a catalogue is distinct from endorsement, security qualification or deployment authorization.

PRF must not require external contributors to transfer repository ownership to PRF merely to make an extension consumable.

## 4. Dependency rules

### 4.1 Allowed dependency direction

```text
           PRF public extension API
                       |
           +-----------+-----------+
           |           |           |
       Extension A Extension B  Extension C
           |           |
           +-----+-----+
                 |
         Explicit public APIs
                 |
          Domain adapters
                 |
          Host application
```

The diagram is conceptual. The public API is a dependency of extensions, not a runtime authority provider.

Actual dependency direction must be represented in each package manifest and resolved dependency closure.

### 4.2 Mandatory rules

**D1.** Executable extensions may depend on the published PRF extension API.

**D2.** Executable extensions must not import private PRF implementation namespaces.

**D3.** Cross-extension dependencies must use declared, versioned public interfaces.

**D4.** Cross-package private source imports are prohibited even when the packages share a repository.

**D5.** Core must not depend on optional executable extension implementations.

**D6.** Cyclic executable package dependencies are prohibited.

**D7.** A domain-specific adapter may depend on a protocol-neutral capability, but the neutral capability must not depend on the adapter.

**D8.** Required capabilities, optional capabilities and runtime authority dependencies must be distinguishable.

**D9.** All release dependencies must resolve to immutable versions, artifact digests or source revisions.

**D10.** Local path dependencies are permitted for development only. They must not be required for standalone release qualification.

**D11.** A package's public API must declare the data schemas and behavioral contracts on which consumers rely.

**D12.** Optional extensions must not become implicit dependencies of unrelated core workflows.

### 4.3 Enforcing independence

Static dependency checks and clean-checkout tests are mandatory for releasable packages.

An independent consumer test must resolve the package without relying on:

- Sibling directories in the shared repository.
- Undeclared source roots.
- A developer's machine-specific environment.
- Private PRF namespaces.
- Unpublished local implementation changes.

A package must fail qualification if its declared dependency closure differs materially from the dependencies it actually loads.

Development workspaces may use local overrides, but those overrides must not constitute release evidence.

### 4.4 Compatibility

Each executable extension declares its compatible PRF extension API versions.

Compatibility must be checked against actual API contracts, not assumed from semantic version numbers alone.

A breaking contract change requires a major package version or a separately declared compatibility boundary.

Capabilities with protocol-specific semantics must declare any additional compatibility requirements.

## 5. Package identity and manifests

Each executable extension has a manifest describing, at minimum:

- Stable package identity and namespace.
- Package version.
- Source and release identity.
- Public extension API compatibility.
- Declared executable capabilities.
- Required and optional dependencies.
- Public entrypoints and schemas.
- Capability status.
- Maintainer and support information.
- Qualification references where available.
- Known limitations and prohibited claims.

The manifest must distinguish:

1. **Declared:** capability interface exists.
2. **Implemented:** executable behavior exists.
3. **Tested:** a defined test suite has passed.
4. **Qualified:** the required independent qualification has completed.
5. **Deployed:** a specified artifact has been deployed into a specified environment.
6. **Authorized:** an explicit, separately verified authority grant exists for the relevant operation.

These states are not interchangeable. They should be represented by separate fields or evidence records rather than inferred from one broad readiness label.

A package may be implemented but unqualified, or qualified for simulation without being authorized to act on-chain.

Status claims must be bounded to the exact scope, artifact and environment.

Missing executable behavior must fail closed rather than returning success-like placeholders.

## 6. Concept bundles

### 6.1 Decision

Extract independently useful concept definitions as **versioned declarative bundles**.

Concept bundles may live in `prf-extensions/concepts/`, but they are not executable extension packages.

They do not register executable capabilities, introduce custody authority or alter economic outcomes merely by being loaded.

### 6.2 Bundle categories

Initially support these logical groups:

| Bundle | Scope |
|---|---|
| `concepts-framework` | Currency, identity, content roots, provenance, evidence policy, sensitivity |
| `concepts-prf-research` | Research definitions, governance, agreement and review concepts |
| `concepts-sew` | Escrow, dispute resolution, settlement and custody vocabulary |
| `concepts-stablecoins` | Stablecoin-specific mechanisms, shortfalls, privacy and use-case mappings |

These are proposed packaging boundaries. Physical extraction is deferred until an independent consumer or versioning requirement justifies it.

### 6.3 Bundle contract

A concept bundle must declare:

- Stable bundle ID.
- Version and immutable content root.
- Catalogue schema version.
- Concept ID namespace and ownership.
- External bundle dependencies.
- Definitions and mappings.
- Provenance and maintainer.
- Validation requirements.

A bundle may refer to concepts in another bundle through declared dependencies.

A bundle must not silently redefine or shadow another bundle's identifiers.

### 6.4 Resolution and merge semantics

The concept loader must:

1. Resolve explicitly selected bundles.
2. Validate schemas and declared dependencies.
3. Reject unknown or incompatible dependency versions.
4. Reject accidental identifier collisions.
5. Preserve mapping ownership.
6. Produce a deterministic resolved catalogue and content root.
7. Record the exact bundle identities and roots used.

An explicit alias, migration or override mechanism may be designed separately; implicit shadowing is prohibited.

The loader must be deterministic for the same set of pinned inputs, independent of filesystem discovery order.

### 6.5 Authority and evidence distinction

General concepts are descriptive.

Loading a concept bundle does not change simulation rules, execution semantics, entitlement computation, governance authority or validity of economic evidence.

If a research artifact or policy explicitly binds to a concept or policy root, that binding must be independently recorded and verified.

A newer concept-bundle release must not retroactively reinterpret evidence committed under an earlier root.

Any transition from descriptive concepts to normative policy inputs requires a separate contract and explicit approval mechanism.

### 6.6 Migration policy

Shared framework concepts should remain under existing canonical ownership during initial adoption.

Domain-specific bundles may be extracted when there is a concrete need for independent distribution, versioning, contribution or consumption.

Existing concept identifiers must remain stable unless a versioned migration explicitly records the change.

The shared catalogue should remain usable during migration.

## 7. Release model

### 7.1 Independent package releases

The shared extensions repository has no mandatory single repository-wide version.

Executable extensions and concept bundles have independent release identities.

A change to one package must not require new versions of unrelated packages.

### 7.2 Development and publication

The repository supports three stages:

**Incubation**

- Incomplete APIs and local development dependencies may exist.
- Experimental status and limitations must be explicit.
- No standalone qualification claim.

**Release candidate**

- Public contracts defined.
- Dependency closure pinned.
- Standalone consumer checks passing.
- Scope-specific qualification in progress or completed.

**Qualified release**

- Immutable artifact published.
- Required qualification gates passing.
- Evidence and manifests available.
- Claims accurately scoped.

A qualified release does not imply a production deployment or security audit.

### 7.3 Publication identity

Every released artifact must identify:

- Package ID and semantic version.
- Source revision.
- Artifact digest.
- Public API compatibility.
- Dependency closure or lock manifest.
- Qualification evidence reference.
- Build and release procedure.

For evidence-sensitive artifacts, publication should support independent reproduction of the artifact or its behavior.

Any source/artifact identity mismatch blocks release qualification until resolved.

### 7.4 Distribution

Supported distribution mechanisms may include immutable Git revisions and conventional package artifacts.

For stable Clojure libraries, prefer independently consumable versioned artifacts where practical.

Git-based development consumption must use pinned revisions, not floating branch references.

Do not create additional registries or infrastructure unless existing package distribution mechanisms are insufficient.

### 7.5 Release ownership

Each package declares an owner responsible for compatibility, release approval, security response and support status.

Shared repository maintainers remain responsible for repository-wide integration policy.

Package owners may approve package releases without requiring unrelated packages to release.

The release workflow must not equate the ability to merge source changes with authorization to publish qualified or production artifacts.

## 8. Qualification gates

### Q0 — Contract and ownership

- Public capability contract defined.
- Maintainer and support scope identified.
- Version and license declared.
- Limitations and status accurate.

### Q1 — Independent dependency resolution

- Clean-checkout consumer test.
- No private core imports.
- No undeclared sibling dependencies.
- Immutable dependencies resolved.
- Dependency closure recorded.

### Q2 — Functional correctness

- Positive and negative behavior tests.
- Schema validation.
- Deterministic behavior where claimed.
- Explicit failure behavior.
- No success-shaped unimplemented entrypoints.

### Q3 — Composition

- Required providers available and compatible.
- Missing-provider behavior checked.
- Incompatible-provider behavior checked.
- Conflicting-capability behavior checked.
- No implicit privilege escalation from composition.

### Q4 — Authority and economic safety

Required when an extension affects governed decisions, custody, settlement, balances, entitlements or other privileged effects.

- Authority source authenticated.
- Grant scope bounded.
- Expiry and consumption behavior verified where applicable.
- Replay and substitution resistance tested where required.
- Effect evidence bound to the relevant operation.
- Unauthorized and invalid paths fail closed.

A simulation of authority does not satisfy this gate for a live execution environment.

### Q5 — Evidence and reproducibility

- Source and artifact identities retained.
- Test inputs and outputs retained where applicable.
- Evidence scope explicitly recorded.
- Independent re-execution or verification possible.
- Known exclusions and unsupported claims stated.

### Q6 — Distribution

- Standalone installation tested.
- Package manifest validated.
- Published artifact matches qualified artifact.
- Independent consumer tests pass.
- No dependency on developer-local state.

### Q7 — Deployment qualification

Applied only when deployment is requested.

- Target environment identified.
- Deployed dependencies verified.
- Deployment identity matched.
- Required authority and operational approvals established.
- Relevant chain or runtime evidence retained.
- Production assertions made only when independently supported.

Passing Q0–Q6 is not equivalent to passing Q7.

### 8.1 Concept-bundle qualification

Concept bundles use a separate, lighter qualification profile:

- Schema validation.
- Namespace ownership.
- Dependency closure.
- Collision detection.
- Deterministic resolution.
- Mapping consistency.
- Root reproducibility.
- Provenance and version retention.

Executable behavior, custody and deployment gates do not apply to purely declarative bundles.

A bundle that introduces executable rules must be reclassified or paired with a separately qualified executable package.

### 8.2 Qualification evidence

Qualification results must be retained in machine-readable form where practical.

A failed or incomplete qualification is an evidence outcome and must not be silently replaced by a successful summary.

Qualification references should bind to immutable source, artifact, dependency and policy identities.

## 9. Force-authorisation extraction

Force-authorisation is the first candidate used to validate this architecture.

Its current state does not establish standalone-package readiness.

Known blockers include:

- Local dependency on PRF core source.
- Direct internal namespace imports in tests.
- Incomplete public extension API boundary.
- Unimplemented or placeholder capability entrypoints.
- Missing standalone dependency-closure qualification.
- Unestablished live execution authority.

### 9.1 Logical modules

Initially distinguish:

**Force-authorisation scope**

Protocol-neutral verification of scoped authorization statements and related lifecycle rules.

**Governance adapter**

Integration with governed-authority providers and their authenticated policy or approval evidence.

**Sew adapter**

Sew-specific custody execution and effect-evidence integration.

These are logical boundaries and do not automatically require three released packages.

### 9.2 Extraction sequence

1. Stabilize the narrow public PRF extension API.
2. Replace internal PRF imports with public API dependencies.
3. Define executable entrypoints and schemas.
4. Implement or accurately mark incomplete capabilities.
5. Make standalone tests resolve against pinned public dependencies.
6. Validate provider composition and fail-closed negative cases.
7. Retain and reconcile existing evidence identities.
8. Extract the package into the shared extensions repository.
9. Publish only when the required qualification profile passes.

### 9.3 Safety constraint

Force-authorisation remains simulation-only unless a separately specified and qualified authority path establishes real execution rights.

Repository extraction, manifest registration and successful simulation tests must not be represented as authorization to move funds or override dispute resolution on-chain.

The Sew execution adapter requires separate custody and deployment qualification.

## 10. Criteria for promoting a package into its own repository

A package may be promoted from `prf-extensions` into a separate repository when one or more strong justifications exist:

### R1 — Independent governance

An external organization or researcher group needs genuine repository ownership, review rights or release control.

### R2 — Independent release lifecycle

The package has a materially different release cadence, compatibility policy or long-term support obligation that creates significant coupling in the shared repository.

Separate version numbers alone do not satisfy this criterion.

### R3 — Security or confidentiality boundary

Different access controls, vulnerability handling, secret management or legally required segregation justify repository separation.

Repository separation alone is not a sufficient security control.

### R4 — Independent ecosystem adoption

The package has consumers or maintainers outside PRF, and independent source ownership materially improves contribution or adoption.

### R5 — Operational scale

Package size, dependency footprint, test cost or CI activity creates measurable and persistent overhead for unrelated packages.

### R6 — Distinct licensing or intellectual property

The package requires ownership or licensing terms that cannot be managed appropriately within the shared repository.

### 10.1 Promotion prerequisites

Even when a justification exists, promotion must not proceed until:

- The public API is stable enough for intended consumers.
- Private implementation dependencies have been removed.
- A clean consumer build passes.
- Artifact and version identity remain unambiguous.
- Existing releases and evidence remain resolvable.
- Ownership and release authority are documented.
- A migration and compatibility plan exists.

### 10.2 Promotion decision

Promotion requires a short decision record identifying:

1. Why separate repository ownership is preferable.
2. Which concrete problem cannot be adequately solved through package boundaries.
3. The intended owner and lifecycle.
4. Dependency and compatibility implications.
5. Release/evidence migration arrangements.
6. Expected operational cost.

### 10.3 Non-criteria

The following do **not**, by themselves, justify a separate repository:

- The package has a distinct name.
- The package has its own tests.
- The package has its own version.
- The package is logically separate.
- The package has a long implementation.
- The package should be installable independently.

All are compatible with a shared repository.

### 10.4 Reversibility

Moving a package between repositories should not require changing its semantic package identity, externally documented public contract or previous immutable release references.

Releases must remain resolvable after promotion.

## 11. Migration plan

### Phase A — Establish package boundaries

- Document the target ownership structure.
- Inventory extension imports and dependencies.
- Identify private PRF coupling.
- Define the minimum public extension API.
- Introduce dependency and namespace checks.

**Exit:** package boundaries are documented and mechanically checkable.

### Phase B — Establish independent qualification

- Add clean-consumer tests.
- Add manifest validation.
- Add dependency closure reporting.
- Define qualification profiles for executable extensions and concept bundles.
- Require immutable source and artifact identities for releases.

**Exit:** a package can demonstrate independence without moving repositories.

### Phase C — Create the shared repository

- Create `prf-extensions`.
- Move the first qualified extraction candidate.
- Preserve package identity and historical references.
- Add cross-extension composition tests.
- Keep PRF core free of reverse implementation dependencies.

**Exit:** at least one package is independently consumable from the new repository.

### Phase D — Introduce concept bundles

- Define a small declarative bundle manifest.
- Add explicit load, validate and merge operations.
- Preserve canonical IDs and mapping ownership.
- Add deterministic root generation and collision rejection.
- Extract domain bundles only when justified.

**Exit:** separately versioned concept bundles can be resolved without affecting executable behavior.

### Phase E — Selective independent repositories

Promote packages only after meeting Section 10 criteria.

This phase is optional and need not be completed for the architecture to be successful.

## 12. Alternatives considered

### A. Repository per extension

**Rejected as the default.**

Benefits include independent ownership and clear repository boundaries.

Costs include repository proliferation, duplicated setup, integration overhead and fragmented cross-package qualification.

Use selectively when justified.

### B. All extensions within PRF core

**Rejected as the target architecture.**

This offers simple local development but retains unnecessary implementation coupling and weakens independent distribution.

Temporary incubation remains acceptable.

### C. Single monolithic extensions package

**Rejected.**

A shared repository is desirable; a shared runtime dependency and release artifact are not.

This would make unrelated extensions depend on each other unnecessarily.

### D. Shared repository with independent packages

**Accepted.**

Provides shared development infrastructure, independent releases and a manageable path to external ownership.

## 13. Consequences

### Positive

- Fewer repositories to operate.
- Clearer core/extension boundary.
- Independent package distribution.
- Easier cross-extension composition testing.
- Lower overhead for automated agent workflows.
- Better external research participation.
- Explicit evidence and authority boundaries.
- Reversible path to independent repositories.

### Negative

- Package isolation requires active enforcement.
- Shared-repository changes may affect common CI.
- Cross-package changes require deliberate compatibility management.
- An independent release workflow must be implemented.
- Concept-bundle distribution requires a small new loader and validation contract.

### Risks and mitigations

**Hidden dependencies:** enforce dependency closure and clean-consumer tests.

**Misleading qualification:** bind claims to specific artifacts, profiles and environments.

**Monorepo coupling:** prohibit undeclared cross-package imports and repository-wide releases by default.

**Premature fragmentation:** require promotion justification and exit gates.

**Semantic drift in concepts:** retain immutable IDs, versions and roots.

**Authority confusion:** keep installation, execution, qualification and authorization as separate states.

## 14. Acceptance criteria for this ADR

This architectural decision is implemented when:

- [ ] `prf-extension-api-v1` provides a documented independent consumer contract.
- [ ] Extension packages can be tested without private PRF imports.
- [ ] Release manifests declare immutable dependency closure and qualification scope.
- [ ] The shared extensions repository supports separate package versions and releases.
- [ ] An executable extension passes clean-consumer and composition tests.
- [ ] Concept bundles have independent schemas and deterministic resolution.
- [ ] Concept-ID collisions and implicit overrides fail validation.
- [ ] Publication preserves immutable artifact and qualification references.
- [ ] Repository promotion criteria are documented and applied.
- [ ] PRF core does not acquire new dependencies on optional extension implementations.

## 15. Final decision summary

**Use one shared repository for first-party extensions, with independent packages and releases.**

Use separate repositories when ownership, security, adoption or lifecycle requirements justify them.

Treat concept bundles as declarative, independently versioned assets rather than executable extensions.

Establish public API independence and qualification before repository extraction.

Preserve immutable evidence, explicit authority boundaries and the separation of research claims from live execution authority.

