# Deterministic scenario run & report contract

This document describes how **deterministic** scenario runs (invariants, JSON
scenarios, EDN fixture suites) are executed, judged, and reported.

Monte Carlo / batch simulation (`core.phases`, `sim/batch`) is **out of scope**
here — it produces statistical distributions, not per-scenario verdicts.

---

## Layering

| Layer | Namespace | Role |
|-------|-----------|------|
| Judgement | `scenario.runner` | Replay entry construction, `:pass?`, `:checks` |
| Golden diff | `scenario.golden` | Pure golden report compare; `:mismatches` extraction |
| Summary | `scenario.summary` | Aggregate `:passed`, `:total`, `:ok?`, `:results` |
| Table report | `scenario.report` | Canonical stdout table; **reads `:pass?` only** |
| Legacy detail | `sim.result-display`, `sim.reporter` | Theory/yield columns, display levels |
| Data runner | `sim.fixtures` | EDN fixture suites; returns summary map |
| CLI shell | `io.scenario-runner` | Paths, exit codes, dispatches report format |

---

## Summary map shape

All deterministic collection runners return:

```clojure
{:passed int
 :total int
 :elapsed-ms long
 :ok? bool          ; (= passed total)
 :suite-id keyword? ; optional
 :results [entry ...]}
```

Each **entry**:

```clojure
{:name string
 :scenario-id ...
 :pass? bool              ; single source of truth for pass/fail
 :checks {...}            ; explains failures; not used to infer :pass?
 :outcome :pass|:fail|...
 :halt-reason ...
 :steps int
 :reverts int
 :replay-result {...}     ; when available
 ;; fixture legacy keys: :trace-id, :expectations, :theory, :golden-report, ...
}
```

`:pass?` is set only by `scenario.runner/scenario-pass?` (via
`build-entry-result` or `finalize-fixture-entry`).

---

## Judgement (`scenario.runner`)

- **`scenario-pass?`** — unified predicate (outcome, expectations, theory,
  thresholds, golden, expected halt).
- **`build-entry-result`** — reuses `:expectations` from replay when present
  (no double evaluation).
- **`runner-opts-for-scenario`** — `:evaluate-theory?` defaults from
  `(:theory scenario)`; suite opts may force or suppress.
- **`finalize-fixture-entry`** — adds fixture metadata and `:checks` for
  outcome/halt/threshold/golden.

Theory opts:

```clojure
{:evaluate-theory? true|false   ; default: (boolean (:theory scenario))
 :require-theory? false
 :strict-theory? false}
```

---

## Reporting

### Canonical table — `scenario.report/print-report`

- Status column: **`:pass?` only** (including ✓ XFAIL when `:expected-fail?`).
- Failure detail: **`format-check-failures`** reading `:checks` and `:halt-reason`.
- Golden failures render from `:checks :golden` `:summary` and `:mismatches`.
- `:report-detail :compact` (default) — one mismatch shows path/expected/actual;
  multiple show count plus up to two inline examples.
- `:report-detail :verbose` or `:audit` — all mismatch lines.
- Does **not** re-derive pass from `:outcome`, `:expectations`, or `:theory`.

### Golden verify (`:checks :golden`)

**Judgement rule:** Golden verification does not define pass/fail at render time.
The runner records comparison under `:checks :golden`; `scenario-pass?`
incorporates that into `:pass?`; reporters display `:pass?` and explain golden
detail from `:checks :golden` only.

In `fixtures/run-suite` `:verify` mode, comparison is pure (`scenario.golden`).
Golden EDN on disk is unchanged.

Three distinct states:

| State | `:checks :golden` | Meaning |
|-------|-------------------|---------|
| Verify disabled | absent (`nil`) | No golden check expected |
| Snapshot missing | `{:error :missing-golden ...}` | Fixture/config problem |
| Snapshot compared | `{:ok? ... :mismatches [...]}` | Match or regression/drift |

Preferred check shape (mismatch):

```clojure
{:ok? false
 :summary "replay snapshot mismatch"
 :mismatches [{:path [:metrics :yield/escrow-principal]
               :expected 10000
               :actual 9950}]
 :golden-verify-mode :replay-and-theory
 :replay-ok? false
 :theory-ok? true}
```

Compatibility/debug only on mismatch (legacy; may move to verbose artifacts):
`:expected`, `:actual` full report maps.

Mismatch paths are sorted deterministically for stable CI output.

Table report compact example:

```text
golden: replay snapshot mismatch
  path: [:metrics :yield/escrow-principal]
  expected: 10000
  actual:   9950
```

| Mode | Passing golden | Failing golden |
|------|----------------|----------------|
| compact | omitted | summary + bounded examples |
| verbose/audit | `golden: match` | all mismatch lines |

### Legacy fixture display — `sim.reporter` / `sim.result-display`

- Gating: **`:pass?`** on each row (`scenario-entry-ok?`).
- Failure lines: delegate to `scenario.report/format-check-failures`.
- `:result-display-level` — `:summary | :failures | :standard | :verbose | :audit`.

### `fixtures/run-suite`

- Returns the summary map (data-first).
- **`:silent? true`** — skip automatic legacy printing (for REPL/tests).
- When not silent, prints via `sim.reporter` (legacy format).

Future option shape (not implemented): `{:report? false}` and
`{:report-format :table|:fixture}` instead of `:silent?`.

---

## CLI

```bash
# Invariant registry (S01–S100)
clojure -M:run -- --invariants

# JSON path suite
clojure -M:run -- --invariants --suite yield-scenarios

# Single JSON file
clojure -M:run -- --invariants --scenario scenarios/S108_negative-yield-mild.json

# EDN fixture suite (table report)
clojure -M:run -- --invariants --fixture-suite suites/equivalence-escalation-boundaries
```

```bash
./scripts/test.sh invariants
./scripts/test.sh yield-scenarios
./scripts/test.sh suites    # fixture verify (legacy reporter + golden)
```

---

## REPL examples

```clojure
(require '[resolver-sim.sim.fixtures :as f]
         '[resolver-sim.scenario.report :as report])

(def summary (f/run-suite :suites/equivalence-escalation-boundaries nil nil {:silent? true}))
(report/print-report summary {:title "My suite"})
(:pass? (first (:results summary)))  ; => true|false
```

---

## Monte Carlo

Unchanged. Use `clojure -M:run -p data/params/....edn` and phase flags.
Statistical reporting (dominance, win rates) stays in `core.phases` / `io.results`.

Deterministic trials inside Monte Carlo may eventually emit the same entry
shape for nested reporting; that migration is not part of Phase 2/3.

---

## Phase 3 acceptance (golden verify)

- [x] Golden EDN format unchanged
- [x] `fixtures/compare-golden-reports` delegates to `scenario.golden/compare-reports`
- [x] Golden mismatches populate `:checks :golden`
- [x] Golden mismatch causes `:pass? false` at judgement (`scenario-pass?` / `finalize-fixture-entry`)
- [x] Report status column reads only `:pass?`
- [x] Compact output: summary + bounded examples; passing golden omitted
- [x] Verbose/audit: all mismatches; optional `golden: match` on pass
- [x] `sim.result-display` and `scenario.report` share `format-check-failures`
- [x] Mismatch ordering deterministic (`sort-mismatches`)
- [x] Missing snapshot (`:missing-golden`) vs disabled vs mismatch distinguished
