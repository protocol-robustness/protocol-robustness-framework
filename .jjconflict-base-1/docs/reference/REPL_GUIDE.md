# REPL Guide

This document outlines standard procedures for interacting with the Protocol Robustness Framework project via REPL.

## Prerequisites
Ensure your environment is set up with `clojure` and the project dependencies.

## Starting the REPL
From the project root, start your preferred Clojure REPL (e.g., `clj` or via your editor integration):

```bash
clj
```

## Initial Setup
Once the REPL is ready, load the necessary fixtures:

```clojure
(require '[resolver-sim.sim.fixtures :as f])
```

## Running Suites
You can run test suites with varying levels of output verbosity using `f/run-suite`:

### Summary Level (Default)
```clojure
(f/run-suite :suites/equivalence-escalation-boundaries nil nil {:result-display-level :summary})
```

### Failures Level
```clojure
(f/run-suite :suites/equivalence-escalation-boundaries nil nil {:result-display-level :failures})
```

### Verbose Level
```clojure
(f/run-suite :suites/equivalence-escalation-boundaries nil nil {:result-display-level :verbose})
```

## Managing Simulation State
### Clearing V2 Claimables
To clear v2 claimables for a workflow and kind, use:

```clojure
(resolver-sim.protocols.sew.accounting/clear-claimable-v2-kind world workflow-id kind)
```

This is idempotent and helps maintain clean accounting state.

### Configuring Stochastic Oracle Fixtures (MC-only)
`:on-exhaustion` on `:oracle-fixture` is **not** used by `replay.clj`. For
replay-comparable evidence, use `:throw` with fully specified `:rolls`.

```clojure
;; Exploratory MC only — holds final scripted roll after exhaustion
{:oracle-fixture {:mode :fixed-roll-sequence
                   :rolls [0.1 0.9]
                   :on-exhaustion :repeat-last}}
```

See `docs/architecture/ORACLE_FIXTURE_EXHAUSTION.md`.

## Comparing Traces
...

## Common Commands
- `(refresh)`: Reload modified code (if using `clojure.tools.namespace.repl`).
- `(in-ns 'resolver-sim.sim.fixtures)`: Switch to the fixture namespace.
