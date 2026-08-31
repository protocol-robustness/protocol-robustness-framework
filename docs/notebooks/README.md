# Interactive Workbenches (Clerk Notebooks)

The repository ships 31 interactive workbenches built with
[Clerk](https://clerk.vision). They are evidence-oriented, not dashboards —
each one shows raw data, artifact provenance, and honest confidence levels
rather than status indicators alone.

See `data/notebooks.edn` for the full registry with descriptions, maturity
levels, and per-notebook notes.

Key workbenches include:

| File | Purpose | Audience |
|------|---------|----------|
| `notebooks/report.clj` | Evidence dashboard — suite-level pass/fail statistics, parameter sweeps, confidence tiers | Project leads, grant reviewers |
| `notebooks/dispute_resolution.clj` | Dispute resolution validation workbench — lifecycle, state machine, adversarial coverage, invariants, escalation, Kleros integration, open gaps | Protocol reviewers, integrators, security researchers |
| `notebooks/invariant_failures.clj` | Invariant failure inspector — per-scenario violation drilldown, trace replay, failure classification | Engineers debugging invariant regressions |
| `notebooks/game_theory_artifact.clj` | Game theory — validation story with equilibrium analysis | Protocol researchers |
| `notebooks/workbench_v2.clj` | Adversarial validation workbench (v2) — generalized scenario exploration | Engineers, security researchers |
| `notebooks/xtdb_temporal_explorer.clj` | XTDB Temporal Explorer — bitemporal “effective then” vs “known then” queries, immutable correction history, replay failure archaeology, explicit-scope execution convergence | Engineers, operators, adopters |

---

## Running workbenches

### Via nREPL / REPL (recommended for development)

```clojure
;; In a Clojure REPL with Clerk on the classpath:
(require '[nextjournal.clerk :as clerk])
(clerk/show! "notebooks/dispute_resolution.clj")
```

### Via CLI

```bash
clojure -M:clerk notebooks/dispute_resolution.clj
```

### Static export (for sharing)

```bash
clojure -M:clerk-build notebooks/dispute_resolution.clj
```

This writes a self-contained HTML artifact to `public/build/`.

### XTDB Temporal Explorer (one command)

The explorer demonstrates completion-gated execution indexing, replay failure
archaeology, bitemporal “effective then” vs “known then” queries, immutable
correction history, and explicit-scope execution convergence — over a small,
curated, rebuildable dataset seeded into a live XTDB.

```bash
bb explorer:demo
```

This starts XTDB, waits until it is healthy, seeds the curated explorer
dataset, and serves the notebook in your browser. For manual steps instead:

```bash
make xtdb          # or: bb xtdb:up
bb explorer:seed
clojure -X:clerk :paths '["notebooks/xtdb_temporal_explorer.clj"]'
```

`bb explorer:seed` truncates and repopulates the explorer `sim_*` tables; it is
destructive by design (demo data only) and never modifies a completed/rooted PRF
artifact. See `dev/resolver_sim/db/explorer_seed.clj` for the exact seeded cases.

---

## Evidence model

### What these workbenches prove and do not prove

Workbenches present **simulator-backed evidence**, not formal proofs.

Every claim in a workbench is classified by:

| Tier | Label | Meaning |
|------|-------|---------|
| **High** | `simulator-backed` | Replayed against the live state machine; invariants checked at every transition |
| **Medium** | `scenario-backed` | Covered by a deterministic or adversarial scenario; limited parameter range |
| **Low** | `derivation-backed` | Derived analytically or by manual review; not yet backed by a replay run |
| **Missing** | `not-covered` | No meaningful evidence; the workbench marks these explicitly |

**A "High" rating means**: this specific scenario passed in the current code at the
current parameter values. It does not guarantee:
- generalization to all parameter ranges,
- the absence of edge cases not yet modeled,
- formal correctness.

**A "Missing" rating is intentional and honest**, not a bug in the workbench.
The workbench is designed to make gaps visible.

---

## Confidence tiers in the dispute resolution workbench

The `dispute_resolution.clj` workbench uses a structured summary table with four
confidence levels:

| Level | Criteria |
|-------|---------|
| **High** | Simulator-backed, deterministically replayed, invariant-checked across ≥10 scenarios; golden-file regression test in place |
| **Medium** | Covered by ≥1 scenario; narrow parameter range; no known failures but untested at boundary conditions |
| **Low** | Derivation only, or single happy-path scenario; no adversarial coverage |
| **Missing** | No scenario, no test, no derivation; acknowledged open gap |

---

## Provenance

All scenarios backed by artifact files:

- Deterministic invariant scenarios: `data/fixtures/` (EDN fixture files)
- Adversarial scenarios: canonical EDN fixtures under `scenarios/edn/`
- Golden replay reports: `data/fixtures/traces/` (expected trace outputs)
- Evidence pack: `docs/evidence/` (reproducibility artifacts for external review)

Each workbench section links to the backing artifact where available.

---

## Architecture

Workbenches are pure Clojure files that:
1. Require the same `protocols/sew/` and `contract_model/` namespaces used by the
   production simulation — there is no separate "notebook data model."
2. Call `clerk/html` and `clerk/md` to render results as rich HTML inside the Clerk
   viewer.
3. Are evaluated top-to-bottom in Clerk's dependency-aware incremental evaluation
   engine; cells are memoized.

No notebook has side effects (no file writes, no XTDB writes). The `telemetry.clj`
workbench reads from XTDB but does not write to it.
