# Protocol Robustness Framework Fixtures

This directory contains deterministic fixtures used for protocol validation and regression testing.

## Directory Structure

- `protocol/`: Protocol parameters (`ModuleSnapshot`).
- `states/`: Initial world states (stakes, existing escrows).
- `actors/`: Behavioral profiles for agents.
- `authority/`: Governance and Oracle oversight parameters.
- `tokens/`: Token behavior profiles (decimals, fee-on-transfer).
- `traces/`: Replayable event sequences (`.trace.json`).
- `thresholds/`: Data-driven invariant acceptance criteria.
- `suites/`: Canonical entry points that compose the above fixtures.
- `golden/`: (Generated) Reference execution reports for drift detection.

## Suite Schema

A suite EDN file defines the composition of a test scenario:

```clojure
{:suite/id :suites/my-test
 :suite/title "Test Title"
 :suite/purpose "What this tests"
 :suite/class :governance | :ordering | :economic
 
 :protocol :protocol/baseline
 :state    :states/minimal-world
 :traces   [:traces/some-scenario]}
```

## Running Suites

Use the Clojure runner:

```bash
clojure -M -e "(require '[resolver-sim.sim.fixtures :as f]) (f/run-suite :suites/my-test)"
```

### Result display level (stdout only)

`run-suite` accepts `:result-display-level` in the opts map (4th argument). This
controls human-readable terminal output only. It does **not** change evaluation,
pass/fail semantics, golden EDN, metrics, or the returned suite map.

| Level | Use case |
|-------|----------|
| `:summary` | CI and scripts (default) — suite PASS/FAIL, N/M, elapsed, failed IDs |
| `:failures` | Local triage — summary plus compact failure reasons |
| `:standard` | Daily dev — one row per scenario (outcome, theory label, expectations) |
| `:verbose` | Debugging — standard rows plus violations, theory evidence, yield metrics |
| `:audit` | Reserved; currently aliases `:verbose` (golden diff UX TBD) |

Examples:

```clojure
;; CI default
(f/run-suite :suites/my-test)

;; Triage a failing suite
(f/run-suite :suites/my-test nil nil {:result-display-level :failures})

;; Full scenario table + failure detail
(f/run-suite :suites/my-test nil nil {:result-display-level :verbose})

;; Suppress stdout (e.g. tests)
(f/run-suite :suites/my-test nil nil {:silent? true})
```

Legacy aliases `:verbose?` and `:show-failures?` map to display levels. Unknown
levels (e.g. `:verbsoe`) throw — there is no silent fallback.

Display-only print opts (never on the returned suite map):

- `:elapsed-ms` — wall time for the summary header
- `:expectations-by-trace-id` — yield/expectation display hints
