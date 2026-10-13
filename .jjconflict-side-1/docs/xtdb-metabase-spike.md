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

## Dashboard (4 tabs, story-first)

The Metabase layer maps fixture ids to human-readable titles via SQL `CASE` (the
XTDB rows keep their canonical ids). Each card leads with a story title/verdict
and keeps raw evidence (`run_id`, roots, temporal bounds) as drill-down columns.

- **Overview** — question-centric: *What completed?* / *What failed?* /
  *Where did executions converge?* / *Where did they diverge?* /
  *What indexed knowledge changed?* / *Expected rejection (scenario pass)*.
- **Time Travel (HERO)** — *Derived Index Correction*: **As Known Then**
  (`FOR SYSTEM_TIME AS OF`, wrong indexed root) vs **Best Known Now** (correct
  root), parameterized by `run_id` + `known_at`, plus full system-time history
  with the four temporal bounds. Shows `INDEX-ERROR-WRONG-ROOT → result-correct`
  while the underlying completed/rooted artifact is unchanged.
- **Failure Archaeology (HERO)** — *Execution — Failed at Step (Invariant)*:
  Summary (story + outcome) → ordered Step timeline → Failed invariant
  (step/invariant/holds/severity/violation), parameterized by `run_id`.
- **Root Convergence** — *Comparable executions (explicit scope)*: Execution A/B
  with explicit `scenario_id` scope, `result_root`, and a `CONVERGENT` /
  `DIVERGENT` verdict (no inferred semantic equivalence).

Every tab carries a persistent "Derived evidence index — rows are projections
over completed/rooted PRF artifacts; XTDB does not confer authority" notice.

## Demo V2: story vs evidence layer

The Demo V2 pass made the presentation story-first without touching canonical
data:

- **Story layer** (Metabase SQL/titles): human title, short interpretation,
  `CONVERGENT / DIVERGENT / FAILED / CORRECTED` verdict.
- **Evidence layer** (drill-down columns): run ID, scenario ID, package/bundle/
  result roots, temporal bounds, raw XTDB fields.

Reused / synthetic:
- **Reused (real mechanics):** the temporal replays exercise the real Sew replay
  kernel / invariant machinery; the execution projection + completion gate are
  real.
- **Intentionally synthetic:** the seeded scenarios are fixture packages
  (`scenario-success`, `scenario-convergent`, `run-synthetic-correction`, etc.)
  built for the demo — there is no "Protected Pro-Rata" or "Disputed Escrow"
  fixture in the current seed, so the SQL `CASE` titles describe what the data
  actually is (e.g. "Convergent Execution Pair", "Execution — Failed at Step
  (Invariant)", "Derived Index Correction"). The convergent pair is labelled
  "Execution A"/"Execution B" (not Serial/Parallel — the fixtures carry no such
  context).
- **Expected rejection:** the `temporal-expected-error` replay now records
  outcome `pass`, representing an operation rejected where that rejection is the
  expected result (so the scenario passes). The Overview card shows the rejected
  step/action.

Semantics that could NOT be made human-readable without changing canonical data:
- Execution-package valid time (epoch fallback) — see "Hero vs capability"
  below; no fabricated valid-time story was added.
- A genuine "Protected Pro-Rata — Serial/Parallel" narrative requires real
  pro-rata fixtures (not present); the current demo presents the convergent
  pair generically rather than invent serial/parallel context.

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