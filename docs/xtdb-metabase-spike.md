# XTDB × Metabase — External Explorer V1

**Status:** EXPLORER V1 (reproducible; no checked-in Metabase app state)

**Question answered:** Can an ordinary analytics/browser tool (Metabase) expose
XTDB's temporal SQL capabilities directly over XTDB's pgwire endpoint?

**Answer:** Yes. Metabase connects read-only to XTDB as a PostgreSQL database and
runs native SQL questions using all three bitemporal forms
(`FOR VALID_TIME AS OF`, `FOR SYSTEM_TIME AS OF`, `FOR ALL SYSTEM_TIME`) with no
bespoke PRF frontend or custom adapter.

## One command

```bash
bb explorer:metabase
```

This starts XTDB, waits for it to be healthy, seeds the curated explorer
dataset, starts Metabase (compose `analytics` profile, `metabase/metabase:v0.58.31.x`),
performs first-run setup if needed, ensures the read-only XTDB datasource, the
`PRF / XTDB Explorer` collection, 13 saved native-SQL questions, and a 4-tab
dashboard, then prints the dashboard URL.

The provisioning is idempotent (`dev/resolver_sim/db/metabase_provision.clj`):
re-running creates no duplicate datasource / collection / question / dashboard /
dashboard-card, and refreshes the Time Travel hero's `known-at` default when the
seed's correction timestamps change.

## Why this matters

Two complementary demo surfaces:

- **Clerk** (`notebooks/xtdb_temporal_explorer.clj`) — canonical PRF explanation,
  repo-versioned, tells the assurance story.
- **Metabase** — an ordinary external data tool, proving the XTDB data is freely
  explorable by anyone with a SQL editor.

## Dashboard (4 tabs)

- **Overview** — completed/failure/benchmark/distinct-root counts + recent executions.
- **Time Travel (HERO)** — run as-known-then (`FOR SYSTEM_TIME AS OF`), best-known-now,
  and full system-time history with the four temporal bounds; parameterized by
  `run_id` and `known_at`. Default shows the synthetic index-correction
  `INDEX-ERROR-WRONG-ROOT → result-correct`.
- **Failure Archaeology (HERO)** — temporal runs → ordered steps → invariants,
  parameterized by `run_id`.
- **Root Convergence** — executions grouped by result root under an explicit
  scenario scope (CONVERGENT / DIVERGENT; no inferred equivalence).

Every tab carries a persistent "Derived evidence index — rows are projections
over completed/rooted PRF artifacts; XTDB does not confer authority" notice.

## Key compatibility findings

- **Timestamp parameterization requires `CAST({{var}} AS TIMESTAMP WITH TIME ZONE)`.**
  Metabase converts `{{var}}` to a JDBC bind parameter (`$N`). XTDB's SQL parser
  rejects `TIMESTAMP $1` (a bind in the temporal-literal position), and the naive
  `CAST($1 AS TIMESTAMP)` (timestamp *without* time zone) compares with a
  timezone mismatch. `CAST($1 AS TIMESTAMP WITH TIME ZONE)` with an ISO-Z string
  (`2026-08-31T17:19:26.934Z`) works and returns the correct historical row.
- Text parameters in Metabase native SQL are auto-quoted; put the value only in
  the `CAST(...)` and pass ISO-Z.
- XTDB pgwire introspection lists tables fine (`/api/database/:id/metadata`
  returns `sim_execution_runs`, `sim_benchmark_executions`, `sim_temporal_*`, `txs`);
  native SQL does not depend on field-level sync.
- Setup token is at `GET /api/session/properties` under kebab-case `"setup-token"`
  (not `/api/setup` GET, which 404s in v0.58). `POST /api/card` requires
  `"visualization_settings": {}`. Dashboards are updated whole via
  `PUT /api/dashboard/:id` (no `/cards`/`/tabs` sub-endpoint); new tabs/cards need
  unique ids, and dashcard fields are snake_case (`size_x`/`size_y`).

## Hero vs capability: system-time vs valid-time

The External Explorer's showpiece is **system time** — "what did our index know
then?" — because the synthetic index-correction fixture gives it a compelling,
immediately-visible story (`INDEX-ERROR-WRONG-ROOT → result-correct`).

**Valid time** (`FOR VALID_TIME AS OF`) is proven and supported, but it is not
yet a first-class hero. Execution-package valid time currently has weak
semantics: completion timestamps are unavailable, so the deterministic epoch
fallback (`2000-01-01`) is used, giving every row the same effective time. There
is no naturally temporal effective-time source to make a valid-time evolution
story meaningful. We deliberately do **not** fabricate one just to achieve
symmetry between the two dimensions.

- **V1 hero:** system-time / knowledge correction.
- **V1 capability:** valid-time querying is supported and proven.
- **Future hero:** valid-time evolution, once a naturally temporal canonical or
  projection source provides meaningful effective times (the existing temporal
  replay/event material may provide that naturally).

This is an instance of not letting the database's bitemporal capability outrun
the semantics of the underlying source data.

## Caveats

- This is a disposable local demo; H2 is Metabase's app DB, and no Metabase app
  state, dashboards, or questions are checked in — the provisioning definitions
  are the source of truth. The compose `metabase` service IS added (analytics
  profile, `metabase/metabase:v0.58.31.x`).
- `bb explorer:metabase` reseeds XTDB (destructive demo data) so the correction
  timestamps move each run; the provisioning refreshes the Time Travel default
  to match.