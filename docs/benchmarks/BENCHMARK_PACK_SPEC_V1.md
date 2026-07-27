# Benchmark Pack Spec V1

## Registry Format

### Global Registry (`benchmarks/registry.edn`)

```clojure
{:registry/spec :spec/benchmark-registry-v1
 :registry/version 1
 :domains [{:domain/id <qualified-kw>
            :domain/description <string>} ...]
 :packs   [{:pack/id <qualified-kw>
            :pack/description <string>
            :pack/registry <path>} ...]}
```

### Pack Registry (`benchmarks/packs/<protocol>/registry.edn`)

```clojure
{:registry/spec :spec/pack-registry-v1
 :registry/version 1
 :pack/id <qualified-kw>
 :pack/description <string>
 :pack/domain <qualified-kw>
 :benchmarks [{:benchmark/id <qualified-kw>
               :benchmark/domain <qualified-kw>
               :benchmark/file <path>
               :benchmark/status <:active|:deprecated|:experimental>} ...]}
```

## Benchmark Definition (Bundle Format)

A benchmark is a bundle of references — an evaluation contract:

```clojure
{:benchmark/id              <qualified-kw>   ;; globally unique
 :benchmark/version         <int>            ;; extracted from filename suffix
 :benchmark/domain          <qualified-kw>   ;; domain reference
 :benchmark/protocol        <qualified-kw>   ;; protocol reference
 :benchmark/scenario-suite  <qualified-kw>   ;; scenario suite reference
 :benchmark/suite-pinned-version <string>    ;; optional, ISO date when suite has a versioned manifest
 :benchmark/runner-policy   <qualified-kw>   ;; runner policy reference
 :benchmark/evidence-policy <qualified-kw>   ;; evidence policy reference
 :benchmark/scoring-rule    <qualified-kw>   ;; scoring rule reference
 :benchmark/claims          [<qualified-kw> ...]}  ;; claims under test
```

All references resolve through the registry or named definitions under
`scenarios/`, `runners/`, `scoring/`, etc.

### Shared-suite benchmarks ("lenses")

Multiple benchmarks may reference the same scenario suite. This is
expected and intentional. Each benchmark acts as a *lens* over the
shared suite — it applies different claims, scoring rules, property
types, and concept mappings to the same set of scenario results.

For example, `escrow-dispute-v1`, `dispute-liveness-v1`, and
`resolver-slashing-v1` all share `:suite/sew-dispute-safety-v1`.
A single suite run provides evidence for all three, but each
benchmark:

- Evaluates different claims against the results.
- Uses a different scoring rule (severity-weighted vs binary-claims).
- Assigns different `:benchmark/property-types`.
- Has a distinct `:benchmark/purpose` and `:benchmark/description`.

When reviewing a shared-suite benchmark, look at its claims, scoring,
and description to understand what it measures — not just its suite
reference.

### `:benchmark/suite-pinned-version`

Optional. Should be set when the scenario suite has a versioned
manifest (`suites/<suite-name>/manifest.edn`) and the benchmark
expects a specific manifest version. Only set when both conditions
are met — not as a general-purpose version field. Omission is
documented by the absence of a versioned manifest for that suite.

Optional fields:

| Field | Type | Description |
|-------|------|-------------|
| `:concept/shadows-global?` | `Boolean` | When true, this benchmark-local concept intentionally shadows a global concept with the same ID in `data/concepts/`. Required when a local concept ID duplicates a global one. Without it, `bb benchmarks:validate` reports a collision error. |
| `:benchmark/deferred-scenario-claims` | `#{<qualified-kw> …}` | Scenario-level claims (`:benchmark/scenarios[*].:claim`) that are not in the claim registry because they represent Level 3 semantic claims not yet evaluated. Without this field, unresolved scenario claims cause validation failure. |


## Lifecycle

Benchmark packs follow a defined lifecycle with explicit status values.
Each benchmark in a pack registry MUST carry a `:benchmark/status` field
and SHOULD carry a `:benchmark/status-updated` date.

### Status values

| Status | Meaning | Promotion criteria | Deprecation criteria |
|--------|---------|--------------------|----------------------|
| `:active` | Ready for use. All Level 1 mechanical claims have evaluators. Suite scenarios execute and produce reproducible evidence. | Promoted from `:experimental` when all claims have evaluators, evidence is reproducible, and the pack passes `bb benchmarks:validate` cleanly for one release cycle. | N/A |
| `:experimental` | Under development. Some claims may be deferred (Level 3 semantic). Scenarios or evaluators may change without notice. | Created at pack inception. Remains `:experimental` until all claims are evaluable and evidence is reproducible. | May be promoted to `:active` or retired to `:deprecated` without a formal sunset. |
| `:deprecated` | No longer maintained. Replaced by a newer pack version or superseded by a different approach. | No promotion path. | Should set `:deprecated-on` date and `:replaced-by` pointing to the replacement benchmark ID. May be removed from the registry after one quarter. |

### Field requirements per lifecycle status

```clojure
;; In pack registry entry:
{:benchmark/id              <qualified-kw>   ;; globally unique
 :benchmark/domain          <qualified-kw>   ;; domain reference
 :benchmark/file            <path>
 :benchmark/status           <:active | :experimental | :deprecated>
 :benchmark/status-updated   <string>}        ;; ISO date (e.g. "2026-06-30")

;; In benchmark manifest:
{:benchmark/id              <qualified-kw>
 :benchmark/version         <int>            ;; extracted from filename suffix
 :benchmark/suite-pinned-version <string>    ;; when suite has a versioned manifest
 ...}
```

## Benchmark Descriptions

Each active benchmark manifest SHOULD include:

| Field | Required? | Purpose |
|-------|-----------|---------|
| `:benchmark/id` | Required | Globally unique keyword identifier |
| `:benchmark/version` | Required | Integer extracted from filename suffix |
| `:benchmark/domain` | Required | Domain keyword registered in `benchmarks/registry.edn` |
| `:benchmark/protocol` | Required | Protocol keyword |
| `:benchmark/scenario-suite` | Required | Suite keyword registered in `suites.clj` or `pack-suites` |
| `:benchmark/runner-policy` | Required | Runner policy keyword |
| `:benchmark/evidence-policy` | Required | Evidence policy keyword |
| `:benchmark/scoring-rule` | Required | Scoring rule keyword |
| `:benchmark/purpose` | Required | Human-readable intent |
| `:benchmark/description` | Recommended | Concise scope/coverage summary |
| `:benchmark/claims` | Required | Vector of claim ID keywords |
| `:benchmark/concepts` | Recommended | Vector of concept keywords |
| `:benchmark/property-claims` | Required for active benchmarks | Map from each advertised property type to executable claim IDs |

`:benchmark/purpose` explains *why* someone would run the benchmark.
`:benchmark/description` explains *what* it measures, its suite scope,
and whether it is an independent benchmark or a lens over a shared suite.

### Active benchmark invariant

An active benchmark may be narrow, but each advertised property must map to at
least one runnable claim evaluator. Active manifests may not contain deferred
claims, unknown claims, claims without evaluators, or only mechanical pipeline
checks such as evidence-root presence and scenario completion.

Experimental manifests may retain deferred claims when their descriptions make
clear that those properties are research coverage rather than evaluated
assurance.

## Claims

Claims are the atomic units of benchmark evaluation. Each claim
encodes a property the protocol must satisfy. Scoring rules interpret
claim outcomes to produce a benchmark result.

Claim definitions live in `benchmarks/claim-registry.edn` and are
referenced by keyword from the benchmark manifest.

### Evaluator naming convention

The `:claim/evaluator` field in the claim registry is always identical
to the claim ID keyword. For Level 1 mechanical claims the evaluator
keyword has no prefix (e.g. `:evidence-root-present`); for Level 2+
claims it uses the full `:claim/<id>` keyword. In both cases the
evaluator and claim share the same keyword — there is no separate
evaluator namespace. This is a convention, not a requirement enforced
by the evaluator dispatch.

## Runner Policies

Runner policies specify execution parameters:

| Field                        | Description                          |
|------------------------------|--------------------------------------|
| `:runner-policy/id`          | Qualified keyword identifier          |
| `:runner-policy/adapter`     | RepositoryAdapter implementation      |
| `:runner-policy/concurrency` | Thread pool size                     |
| `:runner-policy/output`      | Output directory                     |
| `:runner-policy/consensus-mode` | `:single-run` or `:multi-run`     |

## Scoring Rules

Scoring rules define how claim outcomes are reduced to a score:

| Field                | Description                           |
|----------------------|---------------------------------------|
| `:scoring/id`        | Qualified keyword identifier           |
| `:scoring/rules`     | Classifier, pass/fail/mixed thresholds |
| `:scoring/description` | Human-readable explanation           |

## Evidence Policies

Evidence policies specify what evidence is collected and how it is
hashed. The default `:evidence-policy/forensic-standard-v1` collects
all scenario traces, invariant results, and environment metadata,
then hashes them using canonical EDN serialization + SHA-256.
