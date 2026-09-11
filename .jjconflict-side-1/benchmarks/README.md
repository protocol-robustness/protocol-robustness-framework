# Benchmarks

This directory is the canonical source for benchmark definitions, registries,
packs, scenarios, scoring policies, runner configuration, and public-claims.

Human-readable specifications and design documentation are maintained in
`docs/benchmarks/`.

## Publication status

Benchmark **definitions and runners are implemented** and runnable from a clean
checkout. Some **generated reference outputs** may exist under `results/`, but
`results/` is fully git-ignored: nothing there is committed or authoritative.

**No public headline claim is currently published.** Every entry in
`public-headline-claims.edn` is `:draft`, and the generated
`docs/generated/public-headline-claims.md` states "No public headline claims
are currently published."

The intended admission rule is deliberately strict. A claim may be published
only when its exact evaluator implementation, evaluation policy, and evidence
package are each pinned by SHA-256. We will not promote a claim to `:published`
merely because this directory is receiving attention. A claim being `:draft`
is a statement of discipline, not incompleteness.

- **Reference output** — a committed, reproducible execution package (or its
  generated artifacts) that substantiates behaviour for inspection.
- **Admitted evidence** — a reference output that satisfies the formal
  evidence-admission requirements and is therefore eligible to back a
  `:published` headline claim.

These are distinct. Reference outputs help a visitor reproduce and inspect a
benchmark; only admitted evidence can support a published claim.

## Lending and yield benchmarks (start here)

If you are here from a lending / yield / credit-markets discussion, the
benchmarks that exercise those properties are:

| Benchmark (CLI ID) | Registry ID | Status | What it checks |
|---|---|---|---|
| `sew/sew-yield-shortfall-v1` | `:benchmark/sew-yield-shortfall-v1` | `:experimental` | Yield preservation and closed-form partial-fill correctness across 15 yield-integration scenarios (AAVE-style partial liquidity, negative yield, shortfall recovery, reorg races, governance interactions). |
| `prf-core/prf-shortfall-allocation-v0` | `:benchmark/prf-shortfall-allocation-v0` | `:experimental` | Shortfall detection, pro-rata allocation, and deferred claim recording across four conditions including yield-backed shortfall. |
| `prf-core/prf-redistribution-fairness-v0` | `:benchmark/prf-redistribution-fairness-v0` | `:experimental` | Surplus cascade across priority levels, inter-epoch deferred-claim recovery, and cross-pool boundary enforcement. |

All lending/yield benchmarks are currently `:experimental` — their definitions
and runners are implemented, but none is a formal published claim.

### One complete runnable path

Follow a single scenario → benchmark → run → verify path for the representative
yield benchmark without needing to understand the full PRF hierarchy:

```bash
# List benchmarks and confirm the ID
bb benchmark:list

# Run the yield-shortfall benchmark into a canonical run-root bundle
bb benchmark:run sew/sew-yield-shortfall-v1 --run-root /tmp/prf-benchmark

# The run writes an evidence bundle, e.g.:
#   /tmp/prf-benchmark/benchmark/evidence/evidence.edn
#   /tmp/prf-benchmark/benchmark/conclusion.json   (pass/fail summary)

# Reproduce / verify from the evidence bundle
bb benchmark:reproduce /tmp/prf-benchmark/benchmark/evidence/evidence.edn
```

`benchmark:reproduce` reads the evidence bundle, re-runs the benchmark, and
compares evidence hashes. It reports `✓ Hash match! Results are reproducible.`
when the recomputed hash equals the original.

> **Reproducibility.** The committed `:evidence/hash` (the `:bundle-root`
> commitment) is reproducible: runtime function objects in scenario results are
> normalized to a deterministic marker before hashing, and since the
> writer-boundary fix the evidence writer serializes stable yield-module
> descriptors instead of runtime function values — the persisted
> `evidence.edn` contains no `#object[...]` function tags.
>
> `conclusion.json` and the package index distinguish two commitments:
> **`hash`** is the semantic, reproducible bundle root (the substantive
> commitment `benchmark:reproduce` compares), while **`file_sha256`** is an
> exact-instance transport checksum proving only that a stored file is
> unchanged. The transport checksum is **not** the benchmark outcome identity
> and is not expected to match across an original and a reproduced run.
>
> One known limitation remains: legacy serialization embeds JVM
> object-identity hex inside `#object[java.time.Instant 0x1100dccc "..."]`
> tags, so the raw evidence-file bytes are not byte-reproducible across
> processes. This affects only the transport checksum, never the reproducible
> `:evidence/hash` comparison performed by `benchmark:reproduce`.

> **Note on IDs:** the CLI accepts IDs in `pack/benchmark` form
> (`sew/sew-yield-shortfall-v1`). This differs from the internal registry
> keyword (`:benchmark/sew-yield-shortfall-v1`) and the human catalogue slug
> (`"sew/yield-shortfall-v1"`). See [Identifier roles](#identifier-roles).

## Directory Layout

```
benchmarks/
  README.md               This file — canonical-directory guide
  registry.edn            Content-addressed authoritative benchmark registry
  BENCHMARKS.edn          Human-curated benchmark catalogue / suite list
  claim-registry.edn      Claim definitions
  public-headline-claims.edn  Public-facing headline claim registry
  packs/                  Protocol/domain benchmark packs
  scenarios/              Benchmark-owned fixed regression scenarios
  scoring/                Scoring rule definitions
  runners/                Runner execution configuration
  concepts/               Benchmark-specific concept definitions
  mechanisms/             Versioned mechanism maps for derived analysis (not part of canonical benchmark contract)
  archived/               Legacy / experimental material — excluded from canonical registry
```

## Key Entry Points

| File / Directory | Role |
|---|---|
| `registry.edn` | Canonical benchmark registry — content-addressed authoritative index |
| `BENCHMARKS.edn` | Human-curated benchmark catalogue |
| `claim-registry.edn` | Machine-evaluable claim definitions |
| `packs/` | Benchmark pack definitions by protocol/domain |
| `scenarios/` | Benchmark-owned fixed regression scenarios |
| `runners/` | Runner configuration and execution policies |
| `scoring/` | Evaluation and scoring policy definitions |

## Relationship to Other Directories

| Directory | Role |
|---|---|
| `/scenarios/` | Reusable protocol scenarios (shared across tools) |
| `/benchmarks/scenarios/` | Benchmark-owned fixed regression cases |
| `/data/concepts/` | Reusable framework-level concept definitions |
| `/benchmarks/concepts/` | Benchmark-local concept definitions (may shadow global) |

## Generated vs Maintained

All files in this tree are maintained (canonical, intentionally committed).

Generated run artefacts (benchmark projections, execution logs) previously at
`benchmarks/outputs/` are written to `results/benchmarks/`. `results/` is
**git-ignored** (`results/` in `.gitignore`), so these are local, transient
outputs — they are **not** committed, tracked, or authoritative. If a specific
run should become a reference output or admitted evidence, it must be exported,
committed, and referenced by SHA-256 in `public-headline-claims.edn`; it will
not be picked up automatically from `results/`.

## Hierarchy

- **Concept:** Stakeholder-facing property or assurance objective.
- **Claim:** Machine-evaluable proposition with a registered evaluator.
- **Scenario/workload:** Behavior used to exercise a claim.
- **Benchmark:** Workload, executable claims, scoring rule, and evidence criteria.
- **Pack:** Curated catalogue of benchmarks.

Concept maturity: `:defined` → `:mapped` → `:claimed` → `:evaluated` → `:benchmarked`.
Only `:benchmarked` means the concept is in an active manifest whose required
claims resolve to runnable evaluators.

## Identifier Roles

Three identifier forms appear across this tree. They refer to the same
benchmark from different scopes; they are not interchangeable in tooling.

| Form | Example | Scope / source of truth |
|---|---|---|
| CLI ID | `sew/sew-yield-shortfall-v1` | Input to `bb benchmark:run` / `bb benchmark:list` |
| Registry keyword | `:benchmark/sew-yield-shortfall-v1` | Internal registry (`registry.edn`, `packs/*/registry.edn`) |
| Catalogue slug | `"sew/yield-shortfall-v1"` | Human-facing catalogue (`BENCHMARKS.edn`) |

The catalogue slug and registry keyword encode the same `pack/benchmark`
pair. Catalogue entries are classified as **active**, **experimental**,
**deprecated**, or **compatibility alias** in `BENCHMARKS.edn`; see that file
for the mapping and statuses.

## Running a Benchmark

```bash
bb benchmark:list
bb benchmark:run sew/sew-yield-shortfall-v1 --run-root /tmp/prf-benchmark
bb benchmark:reproduce /tmp/prf-benchmark/benchmark/evidence/evidence.edn
```

A clean-checkout gate covering run → verify → reproduce plus the
results-policy git assertion is at `scripts/benchmark-clean-checkout.sh`
and in `scripts/benchmarks_validate.clj` (`bb backstop`).

See `docs/benchmarks/` for specification details.
