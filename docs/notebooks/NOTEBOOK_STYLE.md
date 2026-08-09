# Notebook Style Guide

## Principles

1. Notebooks are thin presentation layers over stable helper functions.
2. Reusable logic lives in `src/resolver_sim/notebook/`.
3. Every loaded artifact must pass a shape check before display.
4. Every claim in prose must be backed by a value, table, chart, or artifact reference.

## Recommended 4-Layer Structure

```
;; Layer 1 — Research claim
;; What this notebook demonstrates (plain language, 1 paragraph)

;; Layer 2 — Scenario and temporal context
;; Scenario ID, run ID, parameters, event count, block/time context

;; Layer 3 — Evidence-backed result
;; Trace table, evidence hash, artifact registry entries, invariant status

;; Layer 4 — Reproduction
;; Commands, fixture path, registry hash, expected output summary
```

## Visibility Standards

Every notebook must be **collapsed by default**. Enforced by `bb notebook:visibility`
(a dependency of `bb notebook:ci`). Rules:

- **Namespace-level default is required.** Declare `{:nextjournal.clerk/visibility {:code :fold}}`
  on the `ns` form (either as `^{...}` metadata or the first map arg). Clerk otherwise
  shows all code (`:show`).
- **The namespace-level code default must be `:fold` or `:hide` — never `:show`.**
- **`:fold`** = collapsed but expandable. This is the default for supporting/content code
  so it stays auditable.
- **`:hide`** = code never shown, not expandable. Reserved for plumbing only
  (data loading, `:result :hide`).
- **`:show`** = code shown by default. Reserved for key invariants and formulas.

Use Clerk visibility metadata to guide reader attention. The rule for each section type:

| Section | Code visibility | Result visibility | Reason |
|---------|----------------|-------------------|--------|
| Title / prose | hidden | shown | Reader orientation |
| Scenario parameters | shown or folded | shown | Important assumptions |
| Data loading | hidden | hidden | Plumbing |
| Data normalization | folded | shown only if relevant | Audit trail |
| Key invariant / formula | shown | shown | Must be inspectable |
| Main result table / chart | folded | shown | Result matters most |
| Raw evidence dump | folded | shown with budget | Available but not dominant |
| Debugging section | folded | folded / hidden | Optional |

### How to apply

```clojure
;; Default at namespace level
^{:nextjournal.clerk/toc true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.my-notebook
  ...)

;; Show formulas and invariants
^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn conservation-holds?
  [{:keys [requested debited unmet waived]}]
  (= requested (+ debited unmet waived)))

;; Fold evidence tables
^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table (map project-event (:events run)))

;; Hide loading/plumbing
^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def run (-> (data/load-run) checks/assert-run-shape!))

;; Curated data with budget
^{:nextjournal.clerk/visibility {:code :fold :result :show}
  ::clerk/auto-expand-results? true
  ::clerk/budget 1000}
(clerk/table (map compact-event (:events run)))
```

## Shape Checks

Every artifact loaded by a notebook must be validated. Use `resolver-sim.notebook.checks`:

```clojure
^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def run
  (-> (speds-data/load-summary)
      checks/assert-test-summary!))
```

Available checks:
- `checks/assert-golden-report!` — single golden report
- `checks/assert-golden-reports!` — all golden reports
- `checks/assert-trace-metadata!` — trace metadata
- `checks/assert-test-summary!` — test summary artifact
- `checks/assert-shape!` — generic Malli schema check

## Evidence Tables

Prefer projected tables over raw maps:

```clojure
;; Good
(def event-columns
  [:event-index :event-type :actor :workflow-id :result :evidence/hash])
(clerk/table (map #(select-keys % event-columns) events))

;; Avoid
(clerk/table events)
```

Sort all tables before display:

```clojure
(->> events (sort-by :event-index) (map project-event) clerk/table)
```

## Dependencies

Notebooks may require:
- `[nextjournal.clerk :as clerk]` — display
- `[resolver-sim.notebook.views :as views]` — cards, RAG, emoji, render helpers
- `[resolver-sim.notebook.checks :as checks]` — shape validation
- `[resolver-sim.notebooks.common :as common]` — safe-slurp, read-json, safe-render
- `[resolver-sim.notebook-support.speds.data :as speds-data]` — SPEDS data loading

## Agent Rules

Before committing notebook changes:

- [ ] Notebook namespace loads without error.
- [ ] No simulation/protocol logic was added to the notebook.
- [ ] All loaded artifacts pass shape checks.
- [ ] Important formulas are visible (`:code :show`).
- [ ] Plumbing/setup code is hidden (`:code :hide`).
- [ ] Supporting code is folded (`:code :fold`), not deleted.
- [ ] Large nested data uses selected projections or a Clerk budget.
- [ ] Tables have deterministic ordering (`sort-by`).
- [ ] Claims in prose correspond to displayed values.
- [ ] `bb notebook:ci` passes.

## CLI Tasks

```shell
bb notebook            # Serve notebooks
bb notebook:check      # Load namespaces + validate shapes
bb notebook:lint       # clj-kondo lint
bb notebook:visibility # Enforce collapsed-by-default visibility policy
bb notebook:ci         # lint + visibility + check
```
