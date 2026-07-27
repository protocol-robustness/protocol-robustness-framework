# Benchmarks

This directory is the canonical source for benchmark definitions, registries,
packs, scenarios, scoring policies, runner configuration, and public-claims.

Human-readable specifications and design documentation are maintained in
`docs/benchmarks/`.

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
`benchmarks/outputs/` have been moved to `results/benchmarks/` (see
`.gitignore`).

## Hierarchy

- **Concept:** Stakeholder-facing property or assurance objective.
- **Claim:** Machine-evaluable proposition with a registered evaluator.
- **Scenario/workload:** Behavior used to exercise a claim.
- **Benchmark:** Workload, executable claims, scoring rule, and evidence criteria.
- **Pack:** Curated catalogue of benchmarks.

Concept maturity: `:defined` → `:mapped` → `:claimed` → `:evaluated` → `:benchmarked`.
Only `:benchmarked` means the concept is in an active manifest whose required
claims resolve to runnable evaluators.

## Running a Benchmark

```bash
bb benchmark:list
bb benchmark:run :benchmark/prf-protocol-robustness-v0
bb benchmark:reproduce <evidence-path>
```

See `docs/benchmarks/` for specification details.
