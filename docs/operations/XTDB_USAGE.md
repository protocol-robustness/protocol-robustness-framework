# XTDB usage today

## Status and scope

XTDB is an **optional, local telemetry and temporal-analysis store**. It is not
an input to the protocol state machine, deterministic replay, invariant checks,
or economic evaluation. Normal scenario execution, tests, and offline
simulations do not require an XTDB service.

The implementation boundary is deliberate:

```text
scenario / trial inputs
        |
        v
pure replay and protocol execution  ---> in-memory result, trace, world, metrics
        |
        | explicit, opt-in adapter call
        v
XTDB telemetry / temporal evidence  ---> analysis queries and Clerk views
```

The local service is configured through `config/docker-compose.yaml`; see
[LOCAL_SERVICES.md](LOCAL_SERVICES.md) for lifecycle commands and its important
disposability warning.

## What XTDB stores

`resolver-sim.db.store` writes four groups of protocol-agnostic records:

| Table | Granularity | Important content |
|---|---|---|
| `sim_trial_results` | One completed simulation trial | protocol/batch id, outcome, invariant/divergence flags, EDN parameters, metrics, and violations |
| `sim_entity_events` | One projected entity transition in a trial | trial/entity ids, event type, entity state, simulated block time |
| `sim_temporal_runs` | One replay run when temporal recording is enabled | run/batch/protocol/scenario identity, outcome, metrics |
| `sim_temporal_steps`, `sim_temporal_invariants`, `sim_temporal_coverage` | Detail rows for that temporal run | per-step time/projection, invariant observations, and optional coverage |

Rows use XTDB valid time (`_valid_from`). For normal telemetry and temporal
records, it is derived from simulated block time. The exception is a missing
block time: the adapters use host wall-clock time as a fallback, so callers
that need simulation-time claims should always supply a block time.

The records are observability projections, not a complete authoritative replay
artifact. In particular, they do not replace scenario inputs, trace artifacts,
world snapshots, evidence-chain files, or a persisted protocol state store.

## When it is written

There is no implicit database write inside either replay implementation.
Writing happens only when a caller deliberately supplies a datasource to one of
the persistence adapters.

### Trial telemetry

`resolver-sim.db.telemetry/record-trial!` converts an already-completed trial
result into one `sim_trial_results` row and zero or more `sim_entity_events`
rows. `record-batch!` applies the same conversion across a supplied collection
of completed trials.

The adapter relies on the protocol's `AnalysisModule/io-projection` for
protocol-specific telemetry metrics and event records. It is downstream of the
simulation: it cannot influence the calculated result.

### Replay temporal evidence

`resolver-sim.contract-model.replay/replay-events` can call a temporal recorder
at terminal state only when the replay configuration enables temporal evidence
and provides `:temporal-evidence {:recorder ...}`. The generic replay engine
only invokes that callback; it neither creates an XTDB datasource nor selects
an XTDB implementation.

`resolver-sim.db.temporal/record-from-replay!` is the XTDB-oriented recorder
callback. It writes a run record plus records projected from the replay trace
and invariant results. `record-temporal-run!` rejects decreasing valid-time
within its step or invariant sequence before writing those records.

### Important non-write paths

- `contract_model/replay.clj` executes scenarios in memory. Its full profile
  may write evidence-chain files and optionally obtain timestamps, but it has
  no direct XTDB dependency.
- `contract_model/replay/simple-replay` deliberately disables evidence,
  checkpoints, signing, timestamping, and persistence-like instrumentation.
- `economics/with_bounty/replay.clj` only recomputes an economic evaluation
  from captured inputs and returns an in-memory reconciliation report.

## When it is read

Reads are explicit analysis operations, not replay dependencies:

| API | Read semantics | Empty/unavailable datasource behavior |
|---|---|---|
| `db.store/trial-results` | Latest rows, optionally filtered by batch/protocol | Returns `[]` for `nil` datasource |
| `db.store/trial-results-at` | `FOR VALID_TIME AS OF <time>` snapshot | Returns `[]` for `nil` datasource |
| `db.store/entity-events-for-trial` | Latest projected event timeline | Returns `[]` for `nil` datasource |
| `db.store/entity-events-for-trial-at` | Explicit valid-time event snapshot | Returns `[]` for `nil` datasource |
| `db.telemetry/batch-summary` | Reads latest trial outcomes, then uses the protocol's pure `summarise-batch` | Returns `{}` for `nil` datasource or no `EconomicModel` |
| `db.telemetry/batch-summary-at` | Same summary against an explicit valid-time snapshot | Returns `{}` for `nil` datasource or no `EconomicModel` |

The Clerk telemetry and XTDB overview notebooks are consumers of this analysis
surface. They require a running populated XTDB instance; they are not part of
replay correctness.

## What happens without XTDB

### No datasource supplied

This is the supported offline mode, not an error:

- `db.store` insert functions are no-ops.
- `db.telemetry/record-trial!` and `record-batch!` still return the derived
  record maps, but persist nothing.
- `db.temporal/record-temporal-run!` builds and returns its records, but its
  store inserts are no-ops.
- Query functions return empty data (`[]` or `{}` as shown above).
- Deterministic replay, simulated event-time progression, deadline checks,
  transition invariants, metrics, and terminal world computation remain
  available in memory.

The loss is **persistence-backed history and database-derived analysis**, not
protocol time awareness.

### Service absent or unreachable while a real datasource is used

The write/query is an ordinary JDBC/XTDB operation. This code does not convert
connection or SQL exceptions into an offline result; such exceptions propagate
to the caller. In other words, `nil` explicitly selects offline/no-op behavior;
a non-nil broken datasource is a failure at the persistence boundary.

Because writes occur after a trial has been computed (or through the optional
terminal temporal-recorder callback), this does not retroactively alter pure
replay semantics. It can, however, cause the surrounding caller to fail and
leave telemetry incomplete or absent. No transaction spans simulation execution
and all telemetry rows, so partial persistence is possible—for example, a
trial row may have been inserted before a later entity-event insert fails.

### Disposing the local container

The supplied Compose configuration has no host volume. Stopping/removing it can
lose local XTDB data. That removes retained telemetry and valid-time query
history, but does not erase time embedded in scenario files, replay traces,
world snapshots, or other filesystem artifacts that were separately retained.

## Time-awareness clarification

There are three different clocks:

1. **Event time**: each scenario event's `:time` and the replay world's
   `:block-time`. It drives protocol semantics, ordering, deadlines, and
   invariants.
2. **XTDB valid time**: `_valid_from`, derived from simulated block time for
   persisted records. It supports historical analysis queries such as
   `FOR VALID_TIME AS OF ...`.
3. **Record/system time**: when the host wrote a row. It is operational
   metadata only and is not used for protocol semantics.

Therefore, lack of XTDB causes **no total or partial loss of protocol-time
awareness during replay**. It does remove the optional ability to query the
telemetry projection at an XTDB valid-time snapshot. It can also make a
notebook/report that depends solely on XTDB appear empty, which must not be
interpreted as a protocol result.

## Review: the two `replay.clj` namespaces

The same filename denotes two intentionally different replay boundaries.

| Namespace | Unit replayed | State/effects | XTDB role | Claim made |
|---|---|---|---|---|
| `resolver-sim.contract-model.replay` | A complete ordered protocol scenario | Builds/evolves a protocol world; records trace, metrics, temporal checks, and invariants | None directly; may call a caller-provided temporal recorder | Protocol-transition reproduction |
| `resolver-sim.economics.with-bounty.replay` | One completed with-bounty evaluation | Re-runs sealed eligibility/amount capabilities from `:replay/inputs`; compares status, receipt, plan, and effect | None | Deterministic implementation replay only |

### `contract_model/replay.clj`

This is the general deterministic scenario kernel. `replay-events` validates a
scenario, builds protocol execution context, initializes a world, orders events,
applies them through the protocol adapter, and checks invariants at transition
boundaries. Its full wrapper, `replay-with-protocol`, manages evidence-chain
finalization; `simple-replay` is a deliberately stripped library profile; and
`resume-from-snapshot` supports continuation/counterfactual execution.

A rejected action is usually recorded as a trace event and replay continues;
invalid scenario input returns a structured `:invalid` result before processing;
an invariant failure halts the replay. These are simulation semantics and do
not depend on XTDB.

### `economics/with_bounty/replay.clj`

This is a narrow verifier for the economics composition described in ADR-0006.
`replay-with-bounty` reads captured `:replay/inputs`, invokes
`evaluate-with-bounty` again, and reports mismatches in `:status`, `:receipt`,
`:plan`, and `:effect`.

It deliberately stops at the capability boundary. It does not create a protocol
world, apply effects to a protocol state, reproduce event ordering/time,
validate transition invariants, emit evidence, or independently validate the
sealed capability implementations. Reusing the same sealed implementations can
show deterministic reproduction, but **cannot establish independent
correctness**.

### Why they remain separate

Merging them would conflate two distinct contracts:

- scenario replay requires a protocol runtime, event trace, world state,
  temporal semantics, and invariant policy;
- with-bounty implementation replay requires a captured economics evaluation
  input and compares economics artifacts without claiming application to a
  protocol world.

The separate namespaces make that difference explicit and prevent a narrow
capability re-execution from being mistaken for an end-to-end protocol replay.

## Source map

- `src/resolver_sim/db/xtdb.clj` — XTDB pgwire datasource and SQL coercions.
- `src/resolver_sim/db/store.clj` — table writes and generic/latest/as-of reads.
- `src/resolver_sim/db/telemetry.clj` — completed-trial persistence and batch
  summaries.
- `src/resolver_sim/db/temporal.clj` — optional replay temporal recorder.
- `src/resolver_sim/contract_model/replay.clj` and `replay/temporal.clj` —
  deterministic replay and recorder callback boundary.
- `src/resolver_sim/economics/with_bounty/replay.clj` — capability-level
  implementation replay.
- `docs/architecture/ARCHITECTURE.md` — authoritative temporal terminology.
- `docs/operations/LOCAL_SERVICES.md` — local service lifecycle and retention.
