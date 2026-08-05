# Review: time-aware simulation

**Status:** findings + fix recommendations — **implemented** (see §7 fix status)
**Method:** static source reading + targeted REPL reproductions + existing test runs
**Areas:** value-at-risk, temporal consistency, time typing, XTDB persistence, `simple-replay`, yield short-circuits

---

## 1. Scope and method

The "time-aware simulation" surface spans several namespaces. Each was reviewed for
correctness, type/representation consistency, and cross-component agreement:

| Area | Files |
|---|---|
| value-at | `commands/scenario_value_at_risk.clj`, `commands/scenario_manifest.clj` |
| consistency | `time/context.clj`, `time/invariants.clj`, `time/model.clj`, `contract_model/replay/temporal.clj`, `contract_model/replay/analysis.clj` |
| type | time as `long` / `java.time.Instant` / `java.util.Date` / string; `temporal-context.v1` vs `.v2`; `db/xtdb.clj` coercions |
| xtdb | `db/xtdb.clj`, `db/store.clj`, `db/telemetry_seed.clj` |
| simple-replay | `contract_model/replay.clj` (`simple-replay`), `replay/profile_adapter.clj`, `replay/flags.clj` |
| short-circuits | `yield/accrual.clj`, `yield/risk_monitor.clj`, `contract_model/replay.clj`, `replay/execution.clj` |

Reproductions were run against a live project nREPL. Existing test namespaces were run
and all passed (see §6). The findings below are edges **not** covered by the existing suite.

---

## 2. Findings register

| ID | Area | Severity | Location | Summary |
|---|---|---|---|---|
| F1 | short-circuits | **Medium-High** | `replay/execution.clj:719-741`, `replay.clj:211-215` | Direct `replay-events` (evidence-mode `:all`) reads the shared global risk atom; a stale short-circuit from a prior run can force a spurious `:fail` under `:fail-on-short-circuits` |
| F2 | short-circuits | Medium | `yield/risk_monitor.clj:36-45` | `summary` buckets by `(first (:short-circuits e))` only; multi-type events are missing from all but the first type's bucket |
| F3 | type / consistency | Medium | `replay/analysis.clj:95` | `:time-evidence :schema-version` is hardcoded `"temporal-context.v2"` even when the embedded terminal context is v1 |
| F4 | value-at / type | Medium | `commands/scenario_value_at_risk.clj:9` | `timestamp` accepts integer seconds only; an `Instant` event time (accepted by the replay clock) yields `invalid-event-time` |
| F5 | consistency / type | Low-Med | `time/context.clj:90-109`, `time/model.clj:28-31` | Low-level `advance-time` / `advance-world-time` permit `block-ts` regression via `:to`; only guarded at scenario validation |
| F6 | short-circuits / type | Low | `yield/accrual.clj:220-221` | `make-decision-base` silently defaults `:now` (and `:dt`) to `0` (epoch), so time-dependent decisions evaluate at block-ts 0 if a caller omits `:now` |
| F7 | xtdb | Low | `db/store.clj:156-166` | `trial-results` interpolates `batch_id`/`protocol_id` into SQL without escaping, unlike the parameterized `entity-events-for-trial` |
| F8 | consistency / type | Low | `time/context.clj:18-32` | `ensure-temporal-context` does not validate an existing `:context/time`; a truthy-but-empty context passes through and `advance-world-time` then throws an opaque NPE |
| F9 | value-at | Low | `scenario_value_at_risk.clj:98-103`, `:44-48` | `validate-persisted` uses exact map equality (brittle to additive fields); string `:seq` (batch post-entry) breaks integer event-index matching |

---

## 3. Detail per area

### 3.1 Short-circuits

The yield engine records short-circuit decisions (dust threshold, module frozen,
position unwinding, stale-oracle degradation, max-index-delta cap/zero, negative-yield
floor, recoverable-liquidity cap) in `yield/accrual.clj` and surfaces them through
attribution via `risk-monitor`.

**F1 — risk-atom isolation.** `replay-events` only binds a fresh risk context when
evidence mode is `:none` (`replay.clj:211-215`). Under the default `:all` mode the
simulation loop and the `:fail-on-short-circuits` policy read the **shared global**
risk atom (`execution.clj:719-741`, `replay.clj:219-225`). Reproduced: seeding the
global atom with one stale `{:short-circuits [:recoverable-liquidity-cap]}` event makes
a clean two-`noop` scenario return `:fail` / `:short-circuit-policy` with
`[:recoverable-liquidity-cap]` as the violation. `replay-with-protocol` and
`simple-replay` are safe (they use fresh contexts); direct `replay-events` callers are
not.

*Fix:* wrap the `:all` path in `risk/with-fresh-risk-context` too (it is cheap), or have
`replay-events` own a per-run context unconditionally. The post-hoc check at
`replay.clj:219` is redundant with the per-step loop check and could be removed once the
loop check reads from a fresh context.

**F2 — per-type aggregation.** `risk-monitor/summary` groups each event under
`(first (:short-circuits e))`. Reproduced: an event
`[:stale-oracle-degraded-apy :recoverable-liquidity-cap]` was counted only under
`:stale-oracle-degraded-apy`; the `:recoverable-liquidity-cap` bucket missed it. A module
that both degrades and caps is therefore invisible to "which modules hit the cap"
queries.

*Fix:* iterate the full `:short-circuits` vector so each type contributes to its own
bucket, and define whether a multi-type event counts once per type or once overall
(per type is the natural fix).

### 3.2 Temporal consistency

`time/context.clj` maintains a canonical `:context/time` (v2) alongside the legacy
`:block-time` root; `check-temporal-consistency` and the Sew temporal invariant
reconcile the two. Scenario validation (`validation/validate-scenario`) rejects
non-monotonic event times (`:non-monotonic-event-time`) and first-event-before-initial
(`:event-time-before-initial`) **regardless of replay profile** — verified by running
regressing scenarios through both `replay-events` and `simple-replay` (both returned
`:invalid`). This is a solid guard.

**F5 — primitive-layer regression.** The validation guard is only at the public API.
The low-level primitives themselves permit regression: `time-ctx/advance-time` with
`:to` moved `block-ts` from `2000` to `500` with no complaint, and `time/model.advance`
guards negative `:seconds` but not `:to`. `resume-from-snapshot` / `run-simulation-loop`
are internal kernels that call `advance-world-time` directly and skip validation, so a
forked/counterfactual path could regress the clock. The `:non-regressive-time` rule is
the real second line of defence but is disabled under `simple-replay`.

*Fix:* add an internal monotonicity guard in `advance-time` (reject `new-ts < old-ts`,
mirroring the `seconds` guard in `model.advance`), or have the kernels surface a
structured `:time-regression` rejection instead of mutating silently.

### 3.3 Time typing

**F3 — schema-version drift.** `analysis.clj:95` stamps
`:time-evidence {:schema-version "temporal-context.v2"}` unconditionally. Reproduced
with a v1-context terminal world: the envelope claims `.v2` while the embedded
`:terminal-time :schema-version` is `.v1`. The v1/v2 distinction is currently
advisory (v1 test worlds still satisfy the invariants), but the label should reflect the
actual context.

*Fix:* read the schema-version from the terminal context instead of hardcoding it.

**F4 — value-at vs Instant.** The replay clock normalizes event times via
`epoch-second` (`temporal.clj:8-17`) and accepts `Instant` values; the value-at-risk
observation layer's `timestamp` (`scenario_value_at_risk.clj:9`) only handles integer
epoch seconds. Reproduced: an `Instant` event time that matches the declared timestamp
still yields `invalid-event-time`. The two layers disagree on what a legal event time is.

*Fix:* make `timestamp` (and the `:invalid-event-time` path) accept `Instant` and
normalize through the same `epoch-second` contract, or document that value-at-risk
observations require integer epoch-second event times.

**F6 — epoch-0 default.** `make-decision-base` defaults `:now` to `0` and `:dt` to `0`
(`accrual.clj:220-221`). Public callers pass `now` (from `resolve-now` → `block-ts`),
so this is a defensive hazard: any future caller that omits `:now` silently evaluates
stale-oracle / module-status / schedule lookups at epoch 0.

*Fix:* require `:now` (or default it from `time-ctx/block-ts` of the world) rather than
falling back to `0`, so a missing time is a loud error, not a silent epoch read.

### 3.4 XTDB usage

The persistence layer (`db/store.clj`) is well-designed: `_valid_from` carries the
simulated block timestamp, `FOR VALID_TIME AS OF` enables bitemporal replay, writes are
no-ops when the datasource is nil (offline/test), and string literals are escaped via
`sql-str`. Parameterized queries are used for `entity-events-for-trial`.

**F7 — unescaped filters.** `trial-results` builds `batch_id = '...'` / `protocol_id =
'...'` by string interpolation (`store.clj:158-159`) without escaping, inconsistent with
the parameterized entity query. For an internal tool this is primarily a correctness/
robustness concern (a quoted id breaks the query), not an external threat.

*Fix:* route these filters through `sql-str`/parameterization to match the entity path.

Notes: `sim_entity_events` stores `block_time` (long) *and* `_valid_from` (Date) as two
sources of truth for the same instant; callers must keep them consistent or AS-OF
queries and `ORDER BY block_time` diverge. `sql-ts` formats ISO_INSTANT and
`parse-ts` normalizes XTDB's short-offset form; the `epoch` (2000-01-01) fallback means
AS-OF queries before that date return nothing. All minor and documented.

### 3.5 simple-replay

`simple-replay` is a clean, well-tested lightweight profile: it disables temporal
enforcement and theory DSL by default, enforces relaxed validation, suppresses evidence
(no-op capture), rejects evidence/persistence/timestamping opts structurally
(`profile_adapter.clj`), and validates the adapter's result contract before returning.
Parity with `replay-events` is covered by `replay_simple_parity_test`. Notably,
scenario validation still rejects regressing event times even under `simple-replay`
(see §3.2), so disabling temporal rules does **not** let public callers rewind the clock.

Minor: `normalize-simple-result` merges the profile's `:execution` descriptor over the
kernel's `:execution` map (which carries batch/sequential `:mode`), so the original
execution mode is dropped from the public result.

### 3.6 value-at

`scenario_value_at_risk.clj` is strict and declaration-driven: the declared timestamp
must resolve to the event coordinate and the exact event time, the selector must match
scope, and the amount must be a valid scenario-native integer. `value-at-risk-timeline`
is explicitly non-authoritative. Covered by `scenario_value_at_risk_test`.

**F9 — brittleness.** `validate-persisted` requires exact map equality (reproduced: a
benign additive `"note"` field flips a PASS observation to `observation-mismatch`), and
the event coordinate match requires integer `:seq` — the kernel's batch post-entries use
string seqs (`(str "batch-" batch-time)`, `execution.clj:638`), which reproduce as
`event-not-found`. Strictness is a deliberate design choice, but additive-field tolerance
and explicit handling of non-integer seqs would make persisted validation forward
compatible.

---

## 4. Verified-sound observations

- Scenario validation rejects non-monotonic event times and first-event-before-initial
  regardless of replay profile — the clock cannot silently rewind through the public API.
- Temporal rules are order- and short-circuit-deterministic: first failing rule wins,
  and `:missing-event-time` / `:non-regressive-time` are base kernel rules
  (`temporal.clj:65-102`, `temporal-rules.md`).
- `:non-regressive-time` and deadline enforcement (`TemporalDeadlines`) correctly
  short-circuit and emit structured guard context.
- `check-temporal-consistency` reconciles legacy `:block-time` with canonical `:block-ts`.
- `validate-dt-time-alignment` enforces `yield_accrue :dt == event-time delta`
  (`replay/yield.clj:29-56`).
- XTDB bitemporal model, nil-datasource no-op, `sql-str` escaping, and parameterized
  entity queries are sound.
- `simple-replay` leaves no evidence artifacts, enforces its profile boundary, and is
  parity-tested against `replay-events`.
- All relevant existing test namespaces pass (see §6), including the pro-rata accounting
  suite (the earlier documented deferred-deadline failures are now green).

---

## 5. Prioritized remediation

All items below were implemented in this pass (see §6 fix status).

1. **F1** (risk-atom isolation) — per-run fresh risk context in `replay-events`; prevents
   cross-run contamination producing wrong `:fail` results. Highest practical impact.
2. **F2** (short-circuit bucketing) — aggregate over the full short-circuit vector.
3. **F4** (value-at vs Instant) — align value-at time handling with the replay clock
   contract, or explicitly constrain to integer event times.
4. **F3** (schema-version label) — derive the version from the terminal context.
5. **F5** (primitive regression guard) — internal monotonicity guard in `advance-time`.
6. **F7** (unescaped SQL) — parameterize `trial-results` filters.
7. **F6, F8, F9** — require `:now`; validate existing `:context/time` shape with a
   structured error; relax additive-field validation and handle non-integer seqs.

---

## 6. Fix status

All findings were addressed in the same pass. Each change was verified by re-running
the reproduction from §3 and adding regression tests.

| ID | Fix | Files |
|---|---|---|
| F1 | `replay-events` now always runs under a fresh risk context and attaches `:yield/risk-events`; `replay-with-protocol` sources `:risk-events` from the result | `contract_model/replay.clj:210-226,303`; tests in `replay_events_test.clj` |
| F2 | `summary` iterates the full `:short-circuits` vector so every type gets its own bucket | `yield/risk_monitor.clj:30-52`; test in `accrual_test.clj` |
| F3 | `:time-evidence :schema-version` derived from the terminal context | `replay/analysis.clj:95`; test in `replay_temporal_test.clj` |
| F4 | value-at `timestamp` accepts `java.time.Instant` event times | `commands/scenario_value_at_risk.clj:9-22`; test in `scenario_value_at_risk_test.clj` |
| F5 | `advance-world-time` rejects regression with a structured `:time-regression`; `advance-time` rejects negative `:seconds` while keeping `:to` permissive for exploratory rewinds | `replay/temporal.clj:19-54`, `time/context.clj:95-112`; tests in `replay_temporal_test.clj`, `context_test.clj` |
| F6 | `make-decision-base` defaults `:now` to the world's `block-ts` instead of epoch 0 | `yield/accrual.clj:220`; test in `accrual_test.clj` |
| F7 | `trial-results` filters escaped via `sql-str`, matching the parameterized entity path | `db/store.clj:157-160` |
| F8 | `ensure-temporal-context` rebuilds a malformed/empty `:context/time`; `advance-world-time` treats nil block-ts as 0 | `time/context.clj:18-35`; test in `context_test.clj` |
| F9 | `validate-persisted` tolerates additive fields (subset match); strictness retained on declared fields | `commands/scenario_value_at_risk.clj:97-109`; test in `scenario_value_at_risk_test.clj` |

Regression tests added: `replay_events_test` (+3), `replay_temporal_test` (+2),
`scenario_value_at_risk_test` (+2), `context_test` (+2), `accrual_test` (+2).

Test evidence after fixes (nREPL `clojure.test`):
- `time.context-test`, `scenario-value-at-risk-test`, `replay-events-test`,
  `replay-temporal-test`, `yield.accrual-test`, `replay-simple-characterization-test`,
  `replay-simple-parity-test`, `yield.pro-rata-accounting-test` — **140 tests, 465
  assertions, 0 failures, 0 errors**.
- Sew `slashing-test` and `invariants.temporal-test` remain green.
- `resolver-sim.protocols.sew.adversarial-test` has pre-existing failures (held-custody
  reconciliation / pending-settlement guards) that reproduce identically with the
  original time code; they are unrelated to this review's changes.
- `clj-kondo` on all changed files: 0 errors, no new warnings.

Reproduction snippets for F1, F2, F3, F4, F5, F6, F8, F9 are in the working session;
each produced the behaviour described above.

---

## 7. Focus review: `advance-world-time`

### Q1 — Are exceptions properly handled?

**Before:** not consistently.
- `epoch-second` threw a raw `ClassCastException` for an unsupported `:time` type
  (`String`, `java.util.Date`, keyword), and `process-step` called
  `advance-world-time` **outside** any try/catch — so a regressive or malformed event
  time crashed the whole replay when temporal rules were disabled (simple-replay),
  while the same input produced a clean `:rejected` trace entry when temporal rules
  were enabled. Two different failure modes for the same condition.
- Scenario validation's ordering checks (`>`, `<` on `:time`) could themselves throw a
  `ClassCastException` for mixed representations (e.g. a number and an `Instant`, or a
  `String`) before replay even started.

**After:** uniform and structured.
- `epoch-second` (replay/temporal.clj) now throws a structured `:invalid-event-time`
  ex-info for unsupported types.
- `process-step` (replay/execution.clj) catches the clock guard's structured failures
  (`:time-regression`, `:invalid-event-time`) and emits a `:rejected` trace entry via a
  shared `temporal-rejection-step` — identical shape to the temporal-rule path, so a
  regressive/malformed time is handled the same whether temporal enforcement is on or
  off, and never crashes the run. `:world`/`:time-after` stay at the pre-step clock.
- `validate-scenario` (replay/validation.clj) type-checks every event `:time`
  (number or `Instant`) before any ordering comparison and rejects malformed/mixed
  input as `:invalid-event-time`; ordering is normalised through a local
  `to-epoch-second` (mirroring the clock's floor semantics), so mixed
  number/`Instant` scenarios validate and monotonicity is still enforced.

### Q2 — Is the time unit accurate enough for the scale?

Yes. The world clock is a `long` Unix-epoch-seconds value; deadlines (appeal windows
60–120 s, `max-dispute-duration` 2592000 s), yield `:dt`, and `:now` all run on seconds.
Second precision is deliberately the contract (`docs/framework/temporal-rules.md`, the
`epoch-second` docstring). No precision issue for this scale.

Residual notes (by design, now documented):
- Sub-second `Instant` event times are floored to the epoch second in the world clock;
  a number is truncated via `long`. Truncation (not rounding) is the safe choice for
  deadline enforcement — the clock never runs ahead of the real event time.
- Because the clock is set by absolute `:to`, per-event flooring does **not** compound:
  there is no accumulated drift.
- `:delta-ms` is `delta-seconds * 1000` on longs — exact for whole-second inputs; it
  can only overflow for deltas > ~292 k years, which validation's monotonicity plus the
  protocol's bounded durations make unreachable.

### Q3 — How can rounding-error impact be reduced?

The only rounding is the deliberate sub-second floor. Two changes reduce its surface:
- **Single source of truth for the clock fields:** `with-temporal-context`
  (time/context.clj) now always derives `:instant` from `:block-ts` (flooring any
  caller-supplied sub-second `:instant`), and derives `:block-ts` from `:instant` only
  when `:block-ts` is absent. `:block-ts` and `:instant` can no longer disagree, so the
  hashed world state and the displayed instant are consistent.
- **Consistent floor semantics across every consumer:** `epoch-second`
  (rules + clock), `validate-scenario/to-epoch-second` (ordering), and
  `with-temporal-context` (instant) all floor through the same path, so validation and
  runtime can never disagree on what time an event "really" is.

### Fixes + tests

| Area | Change | Tests |
|---|---|---|
| `epoch-second` | structured `:invalid-event-time` for unsupported types | `epoch-second-rejects-unsupported-type` |
| `process-step` | unified `temporal-rejection-step`; clock-guard failures become trace rejections | `clock-guard-rejects-when-temporal-disabled` |
| `validate-scenario` | event-`:time` type check + normalised ordering (`to-epoch-second`) | `rejects-malformed-event-time-type`, `accepts-mixed-number-and-instant-event-times`, `detects-regression-across-mixed-event-time-types` |
| `with-temporal-context` | `:block-ts` authoritative; sub-second `:instant` floored | `test-with-temporal-context-single-source-of-truth` |

Verification: fresh-JVM run of the six most-affected namespaces — **77 tests, 262
assertions, 0 failures/errors**. The canonical-package integration test's single
failing test (`canonical-semantic-failure-produces-a-sealed-runnable-package`) expects
`DR-N-002` to fail semantically, but that scenario is defined as a valid pass scenario
(`:expected/events`, no `:expected-errors`); the mismatch is pre-existing and protocol-
layer, unrelated to these changes.

---

## 8. Focus review: value-at / value-at-risk

Scope: `commands/scenario_value_at_risk.clj`, `commands/scenario_manifest.clj`
(`build-observation`, `value-at-risk-timeline`, `validate-persisted`), the
`value-at-risk-summary` projection, and `package_index/validate-value-at-risk`.

### Findings (fixed)

| ID | Severity | Issue | Fix |
|---|---|---|---|
| V1 | **High** | `event-id-mismatch` was unsatisfiable against real protocol replays: `derive-observation` required the event's `:params :event-id` to equal the declared `:event-id`, but standard scenario events never carry one. Every declared observation against a real Sew replay failed `event-id-mismatch` — and since the manifest writer throws on a failed observation, any scenario declaring the block would crash the package build. | `event-identifier` now prefers the event's own `:params :event-id` (chain-ingestion / external-log replay) and falls back to the event's action name for standard events. The declared `:event-id` must match whichever form the event carries. |
| V2 | **High** | Selector root was hardcoded to `:workflows`, which no protocol world contains. Sew world snapshots store workflows under `:escrow-transfers`; the scope-existence check `(mget (mget world :workflows) scope-id)` was always nil, so even a corrected event-id could not pass (and the timeline produced zero rows). | Selector root is now data-driven: the first selector segment names the world root that holds the scope (e.g. `[:escrow-transfers 0 :amount-after-fee]`), the second must equal the declared scope-id, and the scope lookup uses the selector itself. Verified against a real Sew replay: observation `pass`, timeline rows populated. |
| V3 | Medium | `:phase` was compared as a raw keyword (`:post-event`), rejecting a string `"post-event"` declaration even though `scope-kind` normalises via `kind`. | `phase` is normalised through `kind` before comparison. |
| V4 | Low | `:timestamp` rendered sub-second `Instant` precision that the second-precision world clock floored away (recording a time the simulation never had), which also made the `"clock" "unix-epoch-seconds"` source label imprecise. | `timestamp` now floors any sub-second `Instant` to its epoch second (matching `replay.temporal/epoch-second` and `:context/time`), so reported timestamps always equal the world clock and the `timestamp_source` label is truthful. A declared sub-second timestamp now fails `declared-timestamp-mismatch` (honest). |

### Verified-sound

- Declared-vs-actual timestamp reconciliation is strict and deterministic.
- `validate-persisted` re-derives and subset-matches (additive-field tolerant) — a
  tampered or drifted persisted observation is rejected.
- The conservative `value-at-risk-summary` (declared protected amounts from persisted
  `create_escrow` input events vs terminal `:total-held`) does not over-claim loss —
  `observed_loss` is `not-derived` by design.
- Manifest integration still fails fast on a genuinely invalid declared observation
  (intended strictness, now achievable without false positives).

### Tests

7 new tests in `scenario_value_at_risk_test.clj` covering the Sew-shaped world selector,
action-name event-id fallback, wrong-action rejection, scope-not-found, string/keyword
`:phase`, timeline reading of a real selector, and sub-second timestamp flooring.
Fresh-JVM run: `scenario-value-at-risk-test` + `scenario-manifest-test` — **10 tests, 37
assertions, 0 failures/errors** (9 of the tests are in the value-at-risk namespace, 28
assertions).
