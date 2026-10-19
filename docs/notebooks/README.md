# Interactive Workbenches (Clerk Notebooks)

Notebook sources are maintained in the separately versioned
[`prf-notebooks` repository](https://github.com/protocol-robustness/prf-notebooks/tree/10a81790037103356f0ea31119c4ca7f1aeddc69).
PRF keeps host-side simulation helpers and a mirrored registry. The workbenches are built with
[Clerk](https://clerk.vision). They are evidence-oriented, not dashboards —
each one shows raw data, artifact provenance, and honest confidence levels
rather than status indicators alone.

See `data/notebooks.edn` for the full registry with descriptions, maturity
levels, and per-notebook notes.

Common examples include:

| Notebook ID | Purpose | Audience |
|------|---------|----------|
| `report` | Evidence dashboard — suite-level pass/fail statistics and confidence tiers | Project leads, reviewers |
| `dispute-resolution` | Dispute lifecycle, adversarial coverage, invariants, escalation, and open gaps | Protocol reviewers, integrators |
| `invariant-failures` | Per-scenario violation drilldown and trace replay | Engineers debugging regressions |
| `game-theory-artifact` | Game theory and equilibrium analysis | Protocol researchers |
| `workbench-v2` | Adversarial scenario exploration | Engineers, security researchers |
| `xtdb-temporal-explorer` | Bitemporal indexing, replay failure archaeology, and explicit-scope execution comparison | Engineers, operators |

---

## Running workbenches

### Via nREPL / REPL (recommended for development)

```bash
bb notebook
```

### Via CLI

```bash
bb notebook:check
bb notebook:ci
```

### Static export (for sharing)

```bash
bb clerk-build
# Build the public demo subset instead:
bb demo:public-lab
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
clojure -M:notebooks:with-sew -m notebooks.serve
```

`bb explorer:seed` truncates and repopulates the explorer `sim_*` tables; it is
destructive by design (demo data only) and never modifies a completed/rooted PRF
artifact. See `dev/resolver_sim/db/explorer_seed.clj` for the exact seeded cases.

**Three headline demonstrations:**

1. **Time Travel** — the same run identity, best-known-now vs as-known-at: XTDB
   system time records a synthetic *derived-index* correction (`bundle_root`
   changed) while the completed/rooted artifact never changed. This is
   `FOR SYSTEM_TIME AS OF` / `FOR ALL SYSTEM_TIME`.
2. **Failure Archaeology** — drill a failed run into its temporal replay: step →
   action → invariant → violation → halt/outcome → root/evidence checksum.
3. **Root Convergence / Divergence** — two explicitly comparable executions:
   same result root (CONVERGENT) or distinct result roots (DIVERGENT), grouped
   only under an explicit caller-supplied scope (no inferred semantic
   equivalence).

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

Workbenches are Clojure files that:
1. Require the same `protocols/sew/` and `contract_model/` namespaces used by the
   production simulation — there is no separate "notebook data model."
2. Call `clerk/html` and `clerk/md` to render results as rich HTML inside the Clerk
   viewer.
3. Are evaluated top-to-bottom in Clerk's dependency-aware incremental evaluation
   engine; cells are memoized.

Some notebooks read or write host-owned files or databases as part of explicit
workflows. In particular, the explorer demo reseeds its demo tables; use the
documented `bb explorer:demo` opt-in and inspect its warning before running it.
Evaluation results are workload-specific evidence and do not establish general
correctness or release qualification.
# Extracted notebook source

Notebook sources and their runner are maintained in
[`prf-notebooks` at commit `10a817900371`](https://github.com/protocol-robustness/prf-notebooks/tree/10a81790037103356f0ea31119c4ca7f1aeddc69).
PRF pins this exact revision under the optional `:notebooks` dependency alias in
`deps.edn`. Use `bb notebook` or the `bb notebook:*` validation/build tasks to
resolve it. PRF-owned support namespaces and the PRF registry projection remain
in this repository; `bb notebook:validate-registry` checks that the projection
matches the pinned package registry.
