<!-- public-claims: registry -->
# Benchmarks

Canonical benchmark root. Protocol-agnostic benchmark infrastructure
lives here; protocol-specific packs appear under `packs/<protocol>/`.

## Public Showcase

A concept or scenario mapping is not evidence by itself. A property is
demonstrated only when an active benchmark executes its registered evaluator.

Public-facing headline copy is separately generated from
`public-headline-claims.edn`. A headline is publishable only when its stable ID,
exact text, scope, assumptions, evaluator implementation/version hash, policy
hash, and immutable evidence-package references validate. See
[`docs/generated/public-headline-claims.md`](../docs/generated/public-headline-claims.md).

| Capability | Concept | Claims | Evaluator | Active benchmark | Status |
|---|---|---|---|---|---|
| Sew yield shortfall handling | shortfall, partial fill | preservation, closed-form allocation correctness, cap bounds, and leakage | named Sew invariants plus closed-form partial-fill checks | none | Experimental until the workload emits and passes qualifying decision artifacts |
| Sew-backed deterministic replay | evidence integrity | identical results, canonical hash consistency, no nondeterminism | benchmark consistency evaluators | `:benchmark/prf-deterministic-replay-v1` | Secondary showcase for the included Sew workload only |
| Escrow dispute and slashing invariants | authority, liveness, conservation | named safety and liveness claims | named Sew invariants | Sew active benchmarks | Demonstrated within named suites |
| Broad protocol robustness | accountability, adversarial liveness, fund safety | deferred semantic claims | none | none | Experimental research profile |
| PRF shortfall assurance profile | allocation completeness and conservation | deferred scenario claims | none | none | Experimental, not semantic assurance |
| Evidence-reference integrity | evidence DAG reference integrity | no active claim yet | none | none | Roadmap |
| Game-theory validation | equilibrium, SPE, cancellation dominance | trace-end and bounded equilibrium checks | fixture-suite validators | none | Research validation; not active benchmark coverage |

### Reversal-slashing review

The canonical executable review entry point for reversal-reviewer behavior is
[`sew/reversal-slashing-v1`](packs/sew/reversal-slashing-v1.edn). It exercises
appeals, multi-level vindication, governance force slashing, challenger bounties,
and reversal-slash accounting through `:suite/sew-reversal-slashing-v1`.

Start with the [reversal-slashing reviewer guide](packs/sew/REVERSAL_SLASHING_REVIEW.md)
for the headline claims, scenario coverage, and evidence boundaries. The guide
maps each claim to its registered scenarios; it is not a substitute for a
completed, hash-verified benchmark evidence bundle.

### First Run

```bash
bb benchmark:run :benchmark/sew-yield-shortfall-v1 --run-root results/sew-yield-shortfall-showcase
bb benchmark:verify results/sew-yield-shortfall-showcase/completion.json
bb benchmark:share-summary results/sew-yield-shortfall-showcase/completion.json
```

### Game-Theory Research Run

```bash
bb benchmark:game-theory --suite :suites/spe-validation --out /tmp/prf-game-theory
```

This emits a research-validation artifact for the selected fixture suite. It
is not an active benchmark result and does not establish a full equilibrium
proof.

The evidence bundle contains scenario results, claim outcomes, configuration,
derived concept coverage, and an evidence hash. The yield benchmark remains
experimental until a completed run establishes all required closed-form claims.

### Historical Scenario Run

The existing local bundle is `results/evidence/latest.edn` and predates the
closed-form claim evaluators:

| Field | Value |
|---|---|
| Benchmark | `:benchmark/sew-yield-shortfall-v1` |
| Scenario results | 15 / 15 passed |
| Evaluated claim results | 45 passed under the previous invariant-only claim mapping |
| Not exercised | 15 cap checks; this is why the bundle is not active semantic evidence |
| Evidence hash | `3e04dd556d30d87016452f5e14df0e7d17b9a238cf5334510e9faa1d013cc8ad` |

`bb benchmark:verify results/evidence/latest.edn` accepts the recorded bundle
through its explicit `legacy-v1` hash path. That confirms the historical hash
under the old layout, where run metadata and certification were added after
hashing; new bundles cover those fields in the bundle hash.

`bb benchmark:share-summary results/evidence/latest.edn` reports this bundle
as `EXPERIMENTAL: SCENARIOS PASS`, not a semantic benchmark pass.

### Hierarchy

- **Concept:** Stakeholder-facing property or assurance objective.
- **Claim:** Machine-evaluable proposition with a registered evaluator.
- **Scenario/workload:** Behavior used to exercise a claim.
- **Benchmark:** Workload, executable claims, scoring rule, and evidence criteria.
- **Pack:** Curated catalogue of benchmarks; it does not itself prove a capability.

Concept maturity is derived as `:defined`, `:mapped`, `:claimed`,
`:evaluated`, or `:benchmarked`. Only `:benchmarked` means the concept is in
an active manifest whose required claims resolve to runnable evaluators.

## Directory Layout

```
benchmarks/
  README.md
  BENCHMARK_PACK_SPEC_V1.md
  BENCHMARK_RESULT_SPEC_V1.md

  registry.edn                  Global pack + domain index

  packs/                        Protocol/domain benchmark packs
    prf-core/                   Core PRF infrastructure benchmarks
    sew/                        Sew protocol benchmarks

  concepts/                     Benchmark-specific concept definitions
    protocol-value-conservation-v1.edn
                                Domain-level protocol value accounting,
                                allocation, and liability explanation
    protocol-robustness-v0.edn  Concepts for the protocol-robustness-v0 pack
    shortfall-allocation-v0.edn Concepts for the shortfall-allocation-v0 pack

  mechanisms/                   Versioned mechanism maps for derived analysis
    shortfall-v1.edn            PRF shortfall mechanism map v1

  scoring/                      Scoring rule definitions
    severity-weighted-robustness-v1.edn
    binary-claims-v1.edn
    robustness-dimensions-v0.edn
    shortfall-allocation-v0.edn

  archived/                     Legacy/experimental material
```

## Status Meanings

| Status | Meaning |
|--------|---------|
| `active` | Ready for production use. All Level 1 mechanical claims have evaluators. Suite scenarios execute and produce reproducible evidence. |
| `experimental` | Under development. Some claims may be deferred (Level 3 semantic). Scenarios or evaluators may change without notice. |
| `deprecated` | No longer maintained. Replaced by a newer benchmark version or superseded by a different approach. May be removed from the registry after one quarter. |
| `planned` | Not yet materialized as scenario files or manifests. Reference from documentation only. |

Benchmarks within a pack are listed in the pack's `registry.edn` (e.g.
`packs/prf-core/registry.edn`). Each entry carries a `:benchmark/status`
and optionally `:benchmark/status-updated`.

## Terminology

| Term | Meaning |
|------|---------|
| **Benchmark ID** | Global keyword identifying a benchmark pack (e.g. `:benchmark/prf-protocol-robustness-v0`). |
| **Claim ID** | Keyword identifying an atomic property under test (e.g. `:claim/replay-identical-results`). Defined in `claim-registry.edn`. |
| **Evaluator ID** | Always identical to the Claim ID (no separate namespace). The evaluator function that checks whether the claim holds. |
| **Concept reference** | Keyword identifying a stakeholder-facing explanation of a benchmark dimension (e.g. `:robustness/evidence-integrity`). Defined in concept files under `concepts/`. |
| **Scenario ID** | String identifier for a scenario within a suite (e.g. `malicious-resolver-verdict-v1`). |
| **Scenario file** | On-disk EDN or JSON file containing scenario data. Scenario IDs map to scenario files via suite registration. |
| **Suite** | A named collection of scenarios registered in `suites.clj` or `pack-suites`. A suite may be shared by multiple benchmarks. |

## Shared-Suite Benchmarks (Lenses)

Multiple benchmarks can reference the same scenario suite. This is not
an error — it is a design pattern. Each benchmark acts as a *lens* over
the shared suite, applying different:

- Claims (which properties to evaluate)
- Scoring rules (pass/fail vs severity-weighted)
- Property types (safety vs liveness vs fairness)
- Concept mappings (stakeholder-facing explanations)

For example, all three Sew dispute benchmarks (`escrow-dispute-v1`,
`dispute-liveness-v1`, `resolver-slashing-v1`) reference
`:suite/sew-dispute-safety-v1`. A single suite run provides evidence
for all three, but each produces a different evaluation report.

When reviewing a benchmark, look at its claims, scoring rule, and
description — not just its suite reference — to understand what it
measures.

## Benchmark Domains

Benchmark domains live in `benchmarks/registry.edn`. They are taxonomy
entries for grouping packs and reports under a review area such as
`:domain/evidence-integrity` or `:domain/protocol-value-conservation`.

Keep the layers distinct:

- framework concept: reusable explanation in `data/concepts/`
- benchmark domain: registry grouping for packs
- benchmark concept file: benchmark-local operationalisation in `benchmarks/concepts/`
- benchmark pack: executable evaluation contract in `benchmarks/packs/`

## Benchmark as Contract

Each benchmark is a registered evaluation contract — a bundle of
references that specifies:

| Field                     | Reference                      |
|---------------------------|--------------------------------|
| domain                    | Problem domain under test       |
| protocol                  | Protocol being benchmarked      |
| scenario suite            | Set of scenarios to execute     |
| runner policy             | Execution parameters             |
| evidence policy           | Evidence collection + hashing   |
| scoring rule              | Pass/fail/mixed classification  |
| concepts                  | Stakeholder-facing explanations  |
| claims                    | Atomic properties under test    |

Derived research artifacts such as per-mechanism persistence summaries
should live under `mechanisms/` and be treated as analysis inputs, not
as part of the canonical benchmark contract.

## Concepts Layer

Benchmark-specific concept definitions live under `concepts/`. These
map low-level scenario mechanics, claims, and evidence types to
stakeholder-readable language. Unlike the reusable use-case,
framework, and decision-quality concepts in `data/concepts/`,
benchmark concepts are specific to what a benchmark or benchmark
domain evaluates. Packs may provide their own concept file when the
benchmark needs report-specific language that is not part of the
shared registry.

Runner execution, report rendering, and `bb benchmarks:validate` all use
the same benchmark concept resolver. Resolution order is:
- benchmark-local concept with `:concept/shadows-global? true`
- otherwise global concept from `data/concepts/`
- otherwise validation failure / missing concept warning

Each concept entry includes:
- `:concept/title` and `:concept/summary` — what the concept means
- `:concept/stakeholder-language` — plain-language explanation
- `:concept/maps-to` — references to scenarios, claims, invariants, evidence
- `:concept/why-it-matters` — why this property matters to users

### Concept Shadowing Policy

Benchmark-local concept files under `benchmarks/concepts/` may define
concept IDs that already exist in the global registry (`data/concepts/`).
This is called *shadowing* and is only allowed when the local concept
explicitly declares `:concept/shadows-global? true`.

When a local concept shadows a global ID:
1. The local definition takes precedence for the benchmark's report rendering.
2. The validator prints an informational message, not an error.
3. Without `:concept/shadows-global? true`, the validator fails with an
   actionable error showing both files.

This policy prevents accidental naming collisions while allowing packs
to intentionally override concept text for benchmark-specific contexts.

## Key Boundaries

**PRF** owns the benchmark infrastructure: registry, scenario selection,
runner selection, canonical result format, evidence policy, scoring
rules, artifact emission, runner attestations, and consensus.

**Sew** (and other protocols) are benchmark subjects — they provide
the protocol-specific scenarios, claims, and adapters that PRF runs
through its infrastructure.

## Running a Benchmark

```bash
# List available benchmarks
bb benchmark:list

# Run a benchmark by ID
bb benchmark:run :benchmark/prf-protocol-robustness-v0

# Build derived mechanism persistence artifacts for a forensic run
python3 -m scripts.forensic.mechanism_persistence <run-dir> \
  --mechanism-map benchmarks/mechanisms/shortfall-v1.edn \
  --benchmark benchmarks/packs/prf-core/shortfall-allocation-v0.edn

# Reproduce from an evidence bundle
bb benchmark:reproduce <evidence-path>
```
