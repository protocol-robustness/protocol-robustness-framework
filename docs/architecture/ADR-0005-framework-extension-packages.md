# ADR-0005: Framework Extension Packages and Economics Capabilities

Status: Proposed (Revision 3 — resolves the six-item acceptance bar)

Date: 2026-08-04

Scope: framework-wide extension architecture; economics calculation
capabilities are its first registered capability kind.

## Context

The slash-distribution engine dispatches on a small, hardcoded set of
`:method` keywords:

| Dimension | Dispatch site (`slash_distribution.clj`) | Built-in methods |
|---|---|---|
| Base allocation | `compute-allocation` | `:weighted` |
| Award amount | `compute-award-amount` | `:rate-of-gross`, `:resolved-amount` |
| Award funding | `compute-award-funding` | `:weighted-deduction` |

Each dispatch site is a `case`/`or`, mirrored by policy validation
(`supported-*-method?`, `:violation/unsupported-*-method`) and by the
independent verifier (`independent-award` repeats the `case`). A custom
calculation therefore requires editing core or living outside the
content-addressed engine.

The larger problem is structural: if each extensible surface invents its own
registry, bootstrap, and loading convention, PRF will soon have separate
systems for calculations, protocols, invariants, benchmarks, evidence
verifiers, reports, and adapters. The opportunity is a **framework-wide
extension architecture** whose first registered capability is calculation.

The core idea: make specialised economics logic **opt-in, replayable,
evidence-producing, and independently distributable**, rather than forcing
every method into core.

### Desired model

```text
extension package
    ↓
declared capabilities
    ↓
frozen resolution snapshot (transitive)
    ↓
contractually pure capability execution
    ↓
normalised, versioned effects
    ↓
protocol adapter (effect support validation)
    ↓
evidence envelope
    ↓
replay and independent verification
```

### What is already strong (preserved)

- Unknown methods fail loudly; never a silent fallback.
- Generic economics remains protocol-agnostic.
- Existing built-ins go through the same dispatch abstraction as extensions.
- Extension outputs are structured records, not bare amounts.
- Step order and calculation bases become visible in evidence.
- Custody mutation stays outside the economics kernel.
- Existing one-award policies remain valid.
- A reference extension demonstrates the model rather than leaving it
  theoretical.

### Revision note

This revision incorporates the second-round review: a complete sealed
reproducibility boundary (Section 1), transitive frozen resolution
(Section 4), independent verifier capabilities (Section 11), versioned effect
contracts with adapter support validation (Section 9), multidimensional
assurance classification (Section 12), and capability-specific layering
(Section 13). These six items form the acceptance bar; remaining details
(lockfile syntax, application-plan v2 schema, classloader mechanics, full
conformance suite) are deferred to implementation specifications.

## 1. Sealing and the reproducibility boundary

Loading arbitrary Clojure code from `extra-paths` gives that code the full
privileges of the PRF process: file reads, network access, environment
inspection, global-state mutation, and load-order-dependent behaviour.
"Pure and content-addressed" cannot be an enforced property — an API contract
can *require* purity, but an in-process Clojure function cannot *guarantee*
it.

Two explicit modes are defined:

| Mode | Loading | Assurance |
|---|---|---|
| **Development (unsealed)** | `extra-paths` overlay or `:researcher/paths` | Convenient iteration; trusted local code; marked `:unsealed`; not eligible for reproducibility claims |
| **Sealed** | Pinned JAR, git commit, or content-hashed source tree, resolved via an extension lockfile | Package digests committed into execution evidence |

### The complete declared execution closure

A pinned JAR, a git commit, and a content-hashed source tree are **not
equivalent**. A git commit identifies source but not necessarily resolved
Maven dependencies, the Clojure and JVM versions, compiler options, generated
source, resources included in the JAR, build tooling, dependency
substitutions, or native libraries. A source-tree digest does not necessarily
identify the executable code that ran.

Sealing therefore distinguishes four roots:

```clojure
{:extension/source
 {:type :git
  :repository "..."
  :commit "..."
  :source-root "sha256:..."}

 :extension/artifact
 {:type :jar
  :artifact-root "sha256:..."}

 :extension/dependencies
 {:dependency-resolution-root "sha256:..."}

 :extension/runtime
 {:prf/version "..."
  :clojure/version "..."
  :jvm/profile :jvm-21
  :runtime-profile-root "sha256:..."}}
```

**Recommended wording**

> Sealing identifies the complete declared execution closure: extension
> manifest, executable artifact, resolved extension dependencies, schema
> versions, and compatible PRF runtime profile. A source commit alone
> establishes source identity but does not by itself establish executable
> reproducibility.

The strongest replay claim is based on the resolved executable artifact and
dependency closure, not merely the git commit. A source-only extension can
still be sealed, but its reproduction classification is **source-pinned**, not
**artifact-replayable** (see Section 12).

## 2. Extension packages

A package is the unit of authorship, release, distribution, and assurance. It
is a **data manifest**, not a collection of closures.

```clojure
{:extension/id              :organisation/package
 :extension/version         "1.2.0"
 :extension/api-version     1
 :extension/manifest-version 1

 :extension/capabilities
 [{:capability/kind    :economics/award-amount
   :capability/id      :organisation/rate-with-cap
   :capability/version 1
   :entrypoint         'organisation.rate/calculate
   :input-schema       :prf/award-amount-context.v1
   :output-schema      :prf/calculation-result.v1}]

 :extension/license     "Apache-2.0"
 :extension/maintainers [...]
 :extension/support-policy ...
 :extension/funding-status ...
 :extension/supersedes [...]
 :extension/fork-of ...
 :extension/source {...}}
```

A sealed distribution adds externally computed roots (Section 1). The
`:extension/package-root` identifies the actual code artifact; version numbers
remain for humans, but the digest is what makes a resolution reproducible.

### Ownership is explicit but non-exclusive

Ownership of a capability name does not imply ownership of its semantics.
Manifests support forks, alternative implementations, independent verifiers,
compatibility claims, supersession declarations, and deprecated releases —
allowing teams to take over abandoned work without rewriting history.

## 3. Capabilities

A package exposes one or more capabilities. Each capability has **exactly one
kind**, one input contract, and one output contract.

```clojure
{:capability/kind       :economics/award-amount
 :capability/id         :example-org/foo
 :capability/version    2
 :capability/contract-version 1
 :entrypoint            'example.foo/calculate
 :input-schema          :prf/award-amount-context.v1
 :output-schema         :prf/calculation-result.v1
 :verification/contract :prf/award-amount-verification.v1
 :composition-contract  {...}
 :declared-dependencies [...]}
```

### Function objects must not be the descriptor

An extension-map containing closures (`:calculate (fn [ctx] ...)`) cannot be
meaningfully content-addressed: functions are runtime objects that can close
over hidden state. Manifests contain **entrypoint symbols and schema
references**. The loader resolves the Vars only after verifying and freezing
the manifest. This separates:

1. the hashable extension description;
2. the resolved executable implementation;
3. the per-run resolution record.

### Registry key = capability kind + capability id

A method keyword alone is ambiguous across allocation, award amount, and
funding. Registry entries are keyed by:

```clojure
[:economics/award-amount :custom/foo]
[:economics/allocation   :custom/foo]
[:economics/funding      :custom/foo]
```

This generalises to other surfaces:

```clojure
[:evidence/verifier :custom/foo]
[:benchmark/pack    :custom/foo]
[:protocol/adapter  :custom/foo]
[:invariant/check   :custom/foo]
```

Capability and package identifiers are namespaced (e.g. `:organisation/foo`).

### `:method` becomes a resolved capability reference

Inside compatibility policy syntax, `:method` can remain; internally it
resolves to a capability reference:

```clojure
{:method :rate-of-gross
 :resolved-capability {:capability/kind :economics/award-amount
                       :capability/id   :prf/rate-of-gross
                       :capability/version 2
                       :extension/package-root "sha256:..."}}
```

New policies may eventually use an explicit capability reference directly,
preventing the old economics term from leaking into the framework-wide model.

### Built-ins are a virtual core package

Built-ins use the same immutable capability interface and resolve against a
virtual core package:

```clojure
{:extension/id         :prf/core-economics
 :extension/version    prf-version
 :extension/package-root prf-artifact-root
 :capability/id        :prf/rate-of-gross
 :capability/kind      :economics/award-amount}
```

This ensures built-in and external execution evidence have the same shape; a
PRF version change naturally changes capability identity; external
verification can determine precisely which built-in implementation ran; and
promotion from external extension to core does not erase provenance. The
logical capability identity may remain stable while the package root changes
across PRF releases.

### Capability kinds

```text
:economics/award-amount
:economics/allocation
:economics/funding
:economics/eligibility

:protocol/adapter
:protocol/action
:protocol/effect-handler

:evidence/projector
:evidence/verifier
:invariant/check

:benchmark/pack
:benchmark/generator
:benchmark/oracle

:report/section
:cli/command
```

The existing protocol registry (`resolver-sim.protocols.registry`) initially
remains a **compatibility facade** over the generic registry.

## 4. Registry and frozen resolution

### Registration semantics

Load-time registration into a process-global atom introduces load-order
dependence, accidental replacement, parallel-test interference, drift between
generation and verification, and hidden leftovers. Rules:

- registration under an existing key is idempotent **only when the descriptor
  root is identical**;
- same key with a different descriptor is a **hard collision**;
- built-ins cannot be replaced;
- the registry is **frozen before policy validation or execution**;
- capability loading occurs **before** the registry is frozen.

The stronger design passes an explicit registry snapshot into execution rather
than consulting global state:

```clojure
(execute-distribution
  {:registry resolved-extension-registry
   :policy policy
   :context context})
```

Global lazy bootstrap remains a development convenience whose only job is to
produce the snapshot. The term **registry** is reserved for the mutable
discovery structure that exists before resolution.

### Frozen resolution includes the transitive closure

"All requested capabilities" is too narrow: a capability may depend on another
extension capability, a schema package, an effect vocabulary version, a
verifier package, a shared arithmetic primitive, a protocol adapter, or a
parameter profile. The snapshot therefore contains the complete dependency
graph:

```clojure
{:extensions/resolution-version 1

 :extensions/packages [...]
 :extensions/capabilities [...]

 :extensions/dependencies
 [{:from [:economics/award-amount :org/rate-with-cap]
   :to   [:arithmetic/profile :prf/scaled-share-v1]
   :requirement {:version 1}}]

 :extensions/schema-roots [...]
 :extensions/effect-schema-roots [...]
 :extensions/runtime-profile {...}

 :extensions/resolution-root "sha256:..."}
```

The resolver **fails** on:

- missing dependencies;
- dependency cycles, unless explicitly supported;
- ambiguous providers;
- incompatible contract versions;
- unsealed transitive dependencies in a sealed run;
- multiple roots for the same supposedly exact dependency.

Otherwise a top-level package can be pinned while its meaningful behaviour
still depends on uncommitted code.

### Method identity binds an exact implementation

Two environments may register different functions under `:custom/foo`; both
would produce valid-looking evidence with the same keyword and version.
Evidence must commit a **resolved descriptor**:

```clojure
{:extension/id                 :example-org/foo-extension
 :extension/version            "1.3.0"
 :extension/api-version        1
 :extension/manifest-root      "sha256:..."
 :extension/package-root       "sha256:..."
 :capability/kind              :economics/award-amount
 :capability/id                :example-org/foo
 :capability/version           2
 :capability/contract-version  1}
```

### Descriptor roots and canonical projections

The idempotency rule depends on "descriptor root is identical", so the
descriptor hash projection is defined. It **excludes runtime-resolved
objects** and includes at least:

```text
:capability/kind
:capability/id
:capability/version
:capability/contract-version
:entrypoint
:input-schema-ref
:output-schema-ref
:composition-contract
:declared-dependencies
:verification-contract
```

Schema references resolve to exact schema roots, not bare keywords:

```clojure
{:schema/id :prf/calculation-result.v1
 :schema/root "sha256:..."}
```

Otherwise two environments could associate different schemas with the same
symbolic name.

## 5. Fail closed on classpath and namespace ambiguity

JVM classpaths can hide ambiguity: two JARs containing the same namespace; an
`extra-paths` directory shadowing a sealed JAR; a namespace already loaded
before resolution; different classpath orders resolving different source
files. For sealed runs, the loader must verify that:

- the resolved namespace came from the expected package artifact;
- only one provider exists for the entrypoint namespace;
- development paths cannot shadow sealed packages;
- an already-loaded Var has the expected package origin;
- capability loading occurs before the registry is frozen.

A practical implementation may require a package-specific classloader
eventually; at minimum, sealed mode detects and rejects ambiguous namespace
ownership. Without this, the package root in evidence could describe one
artifact while the JVM invokes code loaded from another.

## 6. Execution envelope

### Invocation identity

Invocation IDs must be deterministic, derived from semantic location, so
identical inputs produce identical evidence and cross-references are stable:

```clojure
{:invocation/policy-root ...
 :invocation/award-id ...
 :invocation/step-id ...
 :invocation/index ...
 :invocation/id
 (hash [policy-root award-id step-id index capability-ref])}
```

If an invocation ID is operational metadata only, it is explicitly excluded
from calculation identity and that exclusion is documented.

### Envelope and result

Every capability invocation receives a core-controlled envelope:

```clojure
{:invocation/id ...
 :capability/ref ...
 :policy/input ...
 :event/context ...
 :parameter-context ...
 :resolved-inputs ...
 :extensions/resolution-root ...}
```

and returns a structured result:

```clojure
{:result/value ...
 :result/classification ...
 :result/rounding ...
 :result/remainder ...
 :result/effects [...]
 :result/domain-evidence {...}}
```

### Purity language

Execution is best described as **contractually pure capability execution** or
**deterministic capability evaluation under a declared runtime profile** — not
"pure execution". The conformance kit detects common violations (repeated
invocation produces different results; result depends on map iteration order;
result depends on current time; result mutates supplied input; result mutates
the registry; unauthorised I/O under an instrumented test environment), but
passing such tests does not prove purity. Malicious-extension tests establish
**containment of declared interfaces and detection of known violations**, not
arbitrary-code safety. A future out-of-process execution profile is a
non-goal.

## 7. Economics capability contracts

Award amount, allocation, and funding are separate capability kinds. Built-in
methods (`:weighted`, `:rate-of-gross`, `:resolved-amount`,
`:weighted-deduction`) are exposed through the same immutable capability
interface, with explicit input and result schemas (Section 3).

- `:calculate` returns at minimum `:amount`, `:rounding-remainder`, and
  `:classification`, matching the shape already committed per award
  (`calculate-scaled-share`).
- `:calculate-allocation` splits an amount across allocation identities.
- `:economics/eligibility` is a distinct capability kind (Section 10).
- Existing single-award policies remain valid (implicit one-step policies).

## 8. Composition: ordered steps are order-significant

A policy may declare an ordered `:steps` vector. If any step uses
`:basis :remaining`, order is semantically significant. **Step order is not
normalised or sorted** — the declared step vector is encoded canonically
*while preserving its declared order*. Reordering steps changes the policy
root and may change the result. Ordered-step hashing is **not**
"ordering-independent".

Tests must establish:

- identical ordered vectors hash identically;
- map-key ordering inside each step does not affect hashes;
- reordering non-commutative steps changes the policy hash;
- reordering steps changes results where bases depend on preceding outputs;
- reordering provably commutative steps still changes the declared policy
  identity unless the policy explicitly uses an unordered composition
  construct.

### Declarative, core-resolved bases

Bases are declarative references resolved by the engine; evidence commits both
the reference and the resolved value:

```clojure
:basis {:source :distribution/gross}
:basis {:source :step/output
        :step-id :insurance-deduction
        :field :remaining}
:basis {:source :parameter
        :address [:distribution :available-reserve]}
```

Specialised basis derivation remains possible only through a separate
capability contract that returns a committed derivation record — never by
silently reading arbitrary context.

### Typed composition contracts

A boolean `:concatenable?` does not establish whether two steps compose
safely. Composition requires input type, output type, consumed and produced
balance, conservation expectations, terminality, whether multiple effects are
produced, and which prior outputs a step may reference. A first version:

```clojure
{:composition/input-type  :amount
 :composition/output-type :amount-with-effects
 :composition/mode        :sequential
 :composition/terminal?   false}
```

Sequential `:steps` is the first supported composition model, treated as a
**typed pipeline**, not arbitrary concatenation. A future DAG can be added
without redefining the extension concept.

### Step-local failure semantics

The policy distinguishes at least:

```clojure
:step/on-ineligible :omit
:step/on-failure    :abort
```

A strong first contract:

- ineligible step → recorded as **skipped** with committed eligibility
  evidence (omission cannot be confused with an altered policy);
- malformed or failing step → **entire calculation fails**;
- unsupported effect → application planning fails **before mutation**;
- unresolved basis → loud violation;
- conservation breach → loud violation;
- later steps do not execute after failure.

Skipped steps remain visible in step evidence.

## 9. Normalised effects and the versioned admissibility model

Booleans such as `:custody-affecting?` and `:custody/account` ask the
extension to classify a consequence the core and adapter must still interpret,
and they lack token, owner, direction, authorization, source position, and
lifecycle semantics. Calculations instead return **normalised effect
intents**, each carrying a **versioned effect contract reference**:

```clojure
{:effect/type     :custody/held-adjustment
 :effect/contract :prf.effect/custody-held-adjustment.v1
 :effect/direction :add
 :effect/account  :appeal-bond
 :effect/token    token-id
 :effect/owner    owner-id
 :effect/amount   amount
 :effect/provenance {...}}
```

```clojure
{:effect/type     :balance/credit
 :effect/contract :prf.effect/balance-credit.v1
 :effect/token    token-id
 :effect/account  recipient
 :effect/amount   amount}
```

```clojure
{:effect/type       :obligation/create
 :effect/contract   :prf.effect/obligation-create.v1
 :obligation/type   :bounty-payable
 :obligation/amount amount
 :obligation/owner  claimant}
```

### Who owns the effect vocabulary

The protocol adapter cannot safely infer arbitrary extension-defined effect
semantics. Core defines either a **closed set of core effect contracts** or a
**registry of versioned effect contracts**. Each adapter publishes a support
declaration:

```clojure
{:adapter/id :sew/v1
 :adapter/supported-effects
 #{:prf.effect/custody-held-adjustment.v1
   :prf.effect/balance-credit.v1
   :prf.effect/obligation-create.v1}}
```

### Fail before mutation

Execution must fail before mutation when:

- an emitted effect has no known schema;
- the effect fails schema validation;
- the selected adapter does not support the effect contract;
- an effect requires an unavailable assurance or authorization context.

The application plan contains **fully validated effects**, not merely
extension outputs waiting to be interpreted:

```text
extension result
→ effect schema validation
→ adapter support validation
→ application plan v2
→ plan verification
→ protocol mutation
→ transition evidence
```

Extensions must **never** call `adjust-held` (or any custody mutation)
directly.

## 10. Eligibility vs produced actions

The two meanings of "availability" are separate surfaces:

- `:economics/eligibility` capability — whether an extension step may execute
  for a given event (feeds the existing trigger/eligibility logic that
  includes or omits an award).
- typed effects (`:obligation/create`, ...) — what execution produces.

The protocol's existing `available-actions` (`SimulationAdapter`,
`protocol.clj`) derives actionable claims from the **resulting world state**.
An economics extension must not separately inject protocol actions into the
simulation interface.

```text
eligibility → calculation → effects → protocol transition → resulting available actions
```

## 11. Verification levels and verifier capabilities

Re-executing the same extension implementation used during production proves
deterministic replay, not independent correctness. PRF distinguishes:

| Level | Definition |
|---|---|
| **Structural** | Schemas, hashes, roots, declared inputs, result consistency |
| **Implementation replay** | Re-runs the exact sealed implementation and compares output |
| **Independent** | A distinct verifier, declarative equation, reference model, proof object, or alternative implementation |

Evidence must not label the second category as independent verification.

### Verifiers are separate capability references

A calculation capability declares only its **verification contract**; it does
not appoint its own verifier:

```clojure
{:capability/kind :economics/award-amount
 :capability/id   :example-org/foo
 :verification/contract :prf/award-amount-verification.v1}
```

A separate package provides:

```clojure
{:capability/kind :evidence/verifier
 :capability/id   :assurance-lab/foo-verifier
 :verifies
 {:capability/kind :economics/award-amount
  :capability/id   :example-org/foo
  :capability-contract-version 1}
 :entrypoint 'assurance-lab.foo/verify}
```

Run evidence binds the selected verifier:

```clojure
{:verification/profile :independent-verifier
 :verification/verifier-ref {...}
 :verification/verifier-package-root "sha256:..."
 :verification/result-root "sha256:..."}
```

This keeps the mechanism author from silently redefining what counts as
independent verification, and permits multiple independent verifiers,
verifier disagreement, verifier replacement without changing the calculation
package, and distinct funding and authorship for verification work.

## 12. Multidimensional assurance classification

The linear ladder (experimental → conformant → reproducible → independently
verified → reference → core) is not truly linear: a core capability may lack
an independent verifier; a highly assured external extension need not become
core; "reference" describes endorsement, not assurance; "core" describes
distribution and governance; an independently verified extension may cease to
be maintained.

Assurance is therefore multidimensional:

```clojure
{:extension/status
 {:lifecycle    :active
  :distribution :external
  :conformance  :conformant
  :reproduction :artifact-replayable
  :verification :independently-verified
  :maintenance  :maintained
  :adoption     :multi-protocol}}
```

| Dimension | Example values |
|---|---|
| Lifecycle | experimental, active, deprecated, withdrawn |
| Distribution | local, external, reference, core |
| Conformance | unknown, conformant, non-conformant |
| Reproduction | unsealed, source-pinned, artifact-replayable |
| Verification | structural, replayed, independently-verified, disputed |
| Maintenance | unmaintained, maintained, supported |
| Adoption | untested, single-adapter, multi-adapter |

A simplified headline label can still be shown to users, but the committed
artifact preserves the independent dimensions. Contributors can improve
verification, portability, maintenance, or adoption independently rather than
being forced along one promotion track.

## 13. Capability-specific layering and dependency rules

A universal "extensions must not depend on protocol namespaces" rule
contradicts the framework-wide ambition — a protocol effect handler must
depend on the protocol package. Rules are capability-specific:

| Capability layer | May depend on |
|---|---|
| Generic economics | Core schemas, arithmetic, parameter contracts |
| Evidence verifier | Core evidence and relevant capability contracts |
| Benchmark pack | Core benchmark APIs and declared capability contracts |
| Protocol adapter | Core protocol APIs, generic effects, protocol package |
| Protocol effect handler | Protocol package and effect contracts |
| Report extension | Core report API and evidence schemas |

The governing rule:

> Generic capability kinds must not depend on more specialised layers.
> Capability contracts declare their permitted dependency layer, and the
> resolver rejects dependencies that violate the layer DAG.

## 14. Evidence envelope and terminology

Each invocation commits:

```clojure
{:extension/package-ref ...
 :extension/capability-ref ...
 :extension/input-root ...
 :extension/output-root ...
 :extension/evidence-root ...
 :extension/verification-profile ...
 :extensions/resolution-root ...}
```

Two distinct evidence concepts are named precisely:

- `:result/domain-evidence` — extension-defined evidence produced by the
  capability;
- `:invocation/evidence-envelope` — core-defined evidence about the
  invocation.

Core commits both, but only the first is extension-defined. Core hashes the
normalised input and the complete normalised result; extension-declared
fields provide additional indexed or human-readable projections and do not
determine the hash boundary.

The run-level closure root is **`:extensions/resolution-root`**, used
consistently everywhere. "Registry" names only the mutable discovery structure
that exists before resolution.

## 15. Assurance attestations and protocol-compatibility evidence

Assurance work produces **first-class, content-addressed attestations**, not
mere manifest field updates — so extension maintainers cannot self-declare
maturity, and the work is independently fundable and citable:

```clojure
{:attestation/type :extension/conformance
 :subject/package-root ...
 :suite/root ...
 :result/root ...
 :attestor ...}
```

```clojure
{:attestation/type :extension/independent-verification
 :subject/capability-ref ...
 :verifier/capability-ref ...
 :result ...
 :limitations [...]
 :attestor ...}
```

Protocol compatibility is evidenced, not claimed. "Protocols tested: Sew v1"
must refer to an actual compatibility artifact containing package and
capability roots, adapter root, protocol version/root, test-suite root,
scenario set root, result summary, known unsupported effects, and date or run
identity.

## 16. Incentives

| Participant | Ownership surface | Incentive created |
|---|---|---|
| Calculation authors | Named, versioned capability | Attribution, adoption, publication, maintainership |
| Independent verifier teams | Separate verification packages | Funding and recognition for assurance, not just features |
| Protocol teams | Effect adapters and parameter profiles | Direct integration without forking generic economics |
| Benchmark researchers | Extension-specific packs and adversarial suites | Citable findings, measurable assurance improvements |
| Core maintainers | Stable capability contracts | Less pressure to merge every specialised method |
| Users | Locked, assured extension bundles | Choice without sacrificing reproducibility |
| Funders | Clearly bounded extension milestones | Easier grants, bounties, outcome verification |

**Avoiding perverse incentives.** Recognition and funding should favour reuse
across protocols, independent verification, adversarial coverage,
interoperability, maintained compatibility, clear semantic specifications,
reproducible packages, and resolution of known assurance gaps — not merely
registering more methods. The multidimensional classification (Section 12)
directs effort toward strengthening existing extensions.

**Incentives for use.** A run or benchmark should visibly report:

```text
Capability: example-org/rate-with-cap v2
Package:    example-org/foo-extension 1.3.0
Package root: sha256:...
Assurance:  independently verified
Verifier:   assurance-lab/rate-with-cap-verifier 1.0.1
Protocols tested: Sew v1 (compatibility artifact sha256:...)
Benchmark packs passed: 7/7
```

## 17. Impact on existing artifacts

- **Application plan v2.** Adding validated extension effects to the committed
  projection changes what a valid plan means. Use
  `slash-distribution-application-plan.v2`; retain a v1 verifier for
  historical artifacts. This avoids two v1 implementations disagreeing about
  whether extension effects are part of the committed preimage.
- **Hash projections.** Distribution evidence must commit method, capability
  ref, `:extensions/resolution-root`, input root, and output root.
  `verify-distribution` recomputes through the frozen registry; an unresolved
  capability is a loud violation (mirrors `missing-parameter`).
- **Fixture key.** The test-local `:test.parameter/reward-rate` key is
  unchanged.

## 18. Open questions / non-goals

- Solidity-equivalent checked-width semantics for extension arithmetic
  (`mulDiv`-equivalent profile is a separate follow-up).
- Normalisation of semantically-equal ratios across scales (`500/10000` vs
  `5/100`); the committed pair is used, not a normalised fraction.
- Exact `slash-distribution-application-plan.v2` schema.
- Package-specific classloader mechanics.
- Full conformance-suite contents.
- Out-of-process execution profile.
- Canonical production parameter naming for the slash reward share.

## 19. Acceptance bar

This revision decides the six acceptance items:

1. **Sealed execution boundary** — complete declared execution closure,
   including dependencies and runtime profile (Section 1).
2. **Transitive frozen resolution** — full dependency graph, not only
   direct capabilities, with resolver failure conditions (Section 4).
3. **Separate verifier capabilities** — calculation capabilities declare a
   verification contract; `:evidence/verifier` capabilities are selected
   independently (Section 11).
4. **Versioned effect contracts and adapter support validation** —
   fail-before-mutation admissibility model (Section 9).
5. **Multidimensional assurance classification** — independent lifecycle,
   distribution, conformance, reproduction, verification, maintenance, and
   adoption dimensions (Section 12).
6. **Capability-specific layering and dependency rules** — layer DAG enforced
   by the resolver (Section 13).

## 20. Revised implementation sequence

The phases are reordered so that extension identity is solved before
calculation-specific machinery.

**Phase 1 — generic package and resolution substrate**
- Extension manifest schema.
- Package and capability identities.
- Sealed vs unsealed classifications; the four sealing roots.
- Registry collision semantics.
- Frozen resolution snapshots, transitive dependency graph, and roots.
- Lockfile format.
- Core fixture extension.

**Phase 2 — economics capability contracts**
- Award amount, allocation, and funding capability kinds.
- Built-ins exposed through the virtual core package.
- Explicit input and result schemas; descriptor roots.
- Structural verification and implementation replay.
- Existing behaviour unchanged.

**Phase 3 — evidence and assurance**
- Per-invocation evidence envelope; `:result/domain-evidence` vs
  `:invocation/evidence-envelope`.
- Package roots and `:extensions/resolution-root` committed into
  distributions.
- Independent verifier capability interface.
- Conformance test kit.
- Determinism and malicious-extension tests.

**Phase 4 — typed composition**
- Ordered step IDs.
- Declarative basis references.
- Typed sequential composition; step-local failure semantics.
- Step-level evidence roots.
- Conservation and remainder checks.

**Phase 5 — typed effects and adapters**
- Versioned effect contracts and adapter support declarations.
- Application-plan v2.
- Sew effect handler.
- Held-custody routing through the adapter.
- Derivation of available actions from resulting state.

**Phase 6 — ecosystem reference**
- Generic with-bounty composition.
- Sew bounty-payable mapping.
- Parity tests.
- Published extension manifest, lockfile, and compatibility artifact.
- Example external verifier and benchmark pack.

Canonical CI runs at least one fixture extension and one reference extension.

## Bottom line

The extension direction improves PRF's development model and contributor
incentives, but only if an extension becomes a **sealed, attributable,
versioned, independently assessable framework object** whose sealing covers
the complete dependency and runtime closure, and whose verification, effects,
and maturity are independent first-class surfaces. The key conceptual shift:

> Do not build a calculation registry that happens to load from extra-paths.
> Build a framework extension system whose first registered capability is
> calculation.

That gives PRF a durable division of labour: core defines trustworthy
interfaces; extension teams develop specialised semantics; verifier teams
independently assess them; protocol teams map generic effects; benchmark
teams challenge them; users select pinned, assured bundles.
