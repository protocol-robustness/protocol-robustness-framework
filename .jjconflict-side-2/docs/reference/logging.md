# Logging subsystem (`resolver-sim.logging`)

This project now includes a lightweight logging facade designed for robustness simulations.

## Goals

- zero-config default logger
- never fail if logger is absent/misconfigured
- REPL/notebook friendly
- structured events for future evidence integration
- no effect on deterministic replay state

## Namespace

`resolver-sim.logging`

## API

- `*logger*` (dynamic): current logger fn
- `*log-context*` (dynamic): ambient context map
- `(log! level message)`
- `(log! level message context-map)`
- helpers: `trace!`, `debug!`, `info!`, `warn!`, `error!`
- `(with-log-context {:run-id "..."} ...)`
- `(with-timing "label" expr...)`

Supported levels:

- `:trace`
- `:debug`
- `:info`
- `:warn`
- `:error`

## Default behavior

By default, events are printed to stdout via `prn` as EDN maps:

```clojure
{:ts "2026-05-26T17:00:00Z"
 :level :info
 :message "scenario/end"
 :context {:scenario-id "S01" :outcome :pass}}
```

## Safety guarantees

- If `*logger*` is invalid, fallback logger is used.
- If `*logger*` throws, fallback logger is attempted.
- If fallback also fails, logging silently no-ops.
- Logging never throws to caller.

## REPL usage

```clojure
(require '[resolver-sim.logging :as log])

(log/info! "starting replay" {:scenario-id "S26"})

(log/with-log-context {:run-id "run-123"}
  (log/debug! "step" {:seq 1}))

(log/with-timing "replay-run"
  (Thread/sleep 10)
  :ok)
```

## Notebook usage

```clojure
(require '[resolver-sim.logging :as log])

(log/info! "notebook/server-starting" {:port 7777})
```

## Extension path (future)

The facade is intentionally small so we can later add:

- file appenders
- structured artifact logs
- notebook telemetry streams
- OpenTelemetry adapters
- scenario correlation IDs

without changing call sites.

## Telemetry grounding contract (notebook)

The telemetry notebook (`notebooks/telemetry.clj`) is intentionally treated as a
**read-only observability view**, not a source of simulation truth.

### Canonical grounding

- Datasource anchor: `evaluation.xtdb/->datasource`
- Canonical simulation/projection truth is produced in replay/projection paths,
  not reconstructed from notebook UI state.
- Notebook rendering is expected to align with these schema contracts:
  - `test-run.v1`
   - `test-artifacts.v1.2`
  - `trace-end-projection.v1`
  - `claimable-classification.v2` (v1 retained for historical artifacts)
    - `provenance` — `run_id`, `git_sha`, `produced_at`
    - `terminal_observations` — `classified_claimable_total` / `terminal_value_total` (legacy `:claimable` sum), `coverage_status`, `coverage_matrix`, `by_class`, `by_domain`, `boundaries`, `boundary_headroom` (`workflows_tracked`), `funds_ledger` (`by_token` string keys only), `scenario_highlights`, `scenario_id` + `scenario_id_status`, `warnings`
    - `observations_status` — `taxonomy-only` | `terminal-aggregated` | `single-scenario` (bb scenario run / `from-result` on evidence output)

### Preflight schema checks (fail-closed)

Before rendering trial/event detail views, the notebook performs preflight
column checks against `information_schema.columns` and blocks rendering when
required columns are missing.

Required columns:

- `sim_trial_results`
  - `_id`
  - `batch_id`
  - `protocol_id`
  - `outcome`
  - `invariants_ok`
  - `divergence`

- `sim_entity_events`
  - `trial_id`
  - `block_time`
  - `entity_id`
  - `event_type`
  - `entity_state`

When any required column is missing (or metadata queries fail), the notebook
shows explicit red callouts and prevents Trial Results / Event Trace Viewer
rendering. This avoids ungrounded interpretation under schema drift.

### Migration note

If DB schema changes, update all of the following together:

1. DB migration
2. `notebooks/telemetry.clj` required-column contract
3. Any downstream docs or artifact schema references

Treat partial updates as a contract violation.
