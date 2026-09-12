# Finding: `:partial-fill-affected?` sticky-historical semantics

## Summary

Audit of the `:partial-fill-affected?` position field repo-wide. It was produced
with three inconsistent derivations across the settlement paths and confusingly
combined with the terminal `:status`. The field is now defined once, centrally,
as a **sticky-historical provenance marker**: it is set to `true` the first time
a position participates in a genuine partial-fill settlement and is never
cleared by later full-fill / full-resolution events. `:status` is decoupled:
`:unwinding` while any deferred/haircut consequence is outstanding,
`:withdrawn` once fully resolved.

## Semantic contract (chosen)

The `*affected?` flag family in this codebase is historical provenance, not
current state. `accrual.clj:698-705` sets `:oracle-stale-affected?`,
`:capital-event-affected?`, `:shortfall-affected?` with sticky `assoc true`
logic and never clears them. `:partial-fill-affected?` lacked a commit to the
same contract, which is what produced the inconsistencies below.

Invariant after this change:

- `:partial-fill-affected?` = OR ( previous value, current event is a partial fill ).
  - Event basis = `:settlement-mode :partial-fill` (already authoritative via
    `partial-fill?` in `partial_fill.clj`).
  - `partial-fill-outstanding?` = true iff any `:deferred` or `:haircut` bucket
    is positive (covers zero-fill, deferred-only, haircut-only, and mixed).
- `:status` = `:unwinding` iff `partial-fill-outstanding?`, else `:withdrawn`
  at final resolution; a partial fill with no outstanding consequence settles
  straight to `:withdrawn` but leaves the sticky flag `true`.

## Findings prior to the change

### P1 — Inconsistent derivation across producers (correctness/audit risk)

Three independent producers disagreed about when the field should be `true`:

| Site | Old logic | Gap |
|---|---|---|
| `partial_fill.clj` `post-partial-fill-position` (~2150) | unconditional `true` | Full-fill through the batch path mislabeled as partial-fill; `:status :unwinding` even when fully resolved. |
| `liquid_lending.clj:618` `withdraw` | `(boolean shortfall)` | Non-sticky; overwrote prior history. |
| `liquid_lending.clj:2094` `compute-withdrawal-result` | `(boolean shortfall)` | Non-sticky; overwrote prior history. |
| `liquid_lending.clj:1499` deferred-successor (pro-rata propagation) | `(pos? deferred)` | Excluded haircut-only outcome (deferred = 0); inconsistent with `(boolean shortfall)` for the "affected" meaning. |

None referenced the prior value, so a position could lose its historical
"was affected" marker on a later settlement, contradicting the flag family's
provenance intent.

### P2 — `:status` conflated with the affected flag (naming/model error)

`:status :unwinding` was set whenever any partial-fill decision ran through
`post-partial-fill-position` — including a **full** fill. A fully-satisfied
position was left `:unwinding` forever. Status reflects current outstanding
exposure, not whether a partial fill ever happened; the two are now decoupled.

### P1 — Batch ordering test locked in the bug

`partial_fill_test.clj` `test-batch-partial-fill-deterministic-ordering`
asserted a **full-liquidity** input produced `:partial-fill-affected? true` and
`:status :unwinding`. This was a regression lock on the unconditional-set
behavior, not a spec. Corrected to reflect intended semantics (full-fill →
`false` / `:withdrawn`; zero-liquidity → `true` / `:unwinding`).

## Canonical / evidence impact

- The field is committed via `ledger-state-cutpoint-root`
  (`partial_fill.clj:791-893`, applies `:yield/positions world` at line 878)
  for withdrawal-ledger evidence. `invariants.clj:428-435` recomputes the same
  root over the same world with `not=`, so the value is self-consistent at the
  run level, not a golden/fixture-pinned hash. No conformance fixture or
  benchmark pack asserts the field's value (placeholders use `"sha256:mr"`).
- The **values** written into produced-withdrawal worlds change relative to old
  runs in the three corrected cases (full-fill through batch, sticky history,
  haircut-only deferred-successor). No repo fixture pins those values, so no
  golden test breaks.
- Only source reader of the field is the public projection
  `position.clj:184` (`:partial_fill_affected`). No downstream gating logic
  consumes it, so the semantic change is safe and behavioral to external
  readers is preserved (projection still reflects history once sticky).

## Changes made

- `src/resolver_sim/yield/partial_fill.clj`:
  - Added `partial-fill-outstanding?` (true iff any deferred/haircut bucket
    positive) beside the existing `partial-fill?`.
  - `post-partial-fill-position` now sets
    `:partial-fill-affected? (or prior partial-fill?)` (sticky) and
    `:status (if (partial-fill-outstanding? decision) :unwinding :withdrawn)`.
- `src/resolver_sim/yield/modules/liquid_lending.clj`:
  - `:618` and `:2094` now sticky OR of prior + `partial-fill? settlement`;
    `:status` decoupled via the same event basis.
  - `:1499` deferred-successor now sticky OR of predecessor + `(pos? deferred)`.
- `test/resolver_sim/yield/partial_fill_test.clj`:
  - Corrected `test-batch-partial-fill-deterministic-ordering`.
  - Added: full-fill-not-a-partial-fill-event; sticky-through-later-full-
    resolution; haircut-only; zero-liquidity; fresh-defaults-false.

## Verification

- `partial_fill_test`: 116 tests / 309 assertions, 0 failures, 0 errors.
- `liquid_lending_v2` + `accrual`: 91 / 723, 0 / 0 (incl. pro-rata, fcfs,
  pool-conservation properties).
- `strategic_partial_fill` + `ops_liveness` + `parity` + `exact_math`: 30 / 108,
  0 / 0.
- `invariants` + `invariants_hardening` + `accounting_partial_shortfall` +
  `merge_policy` + `pro_rata_accounting` + `deferred_class` +
  `lineage_conservation` + `risk` + `priority_by_original_time` +
  `queue_policy`: 137 / 408, 0 / 0.
- `clj-kondo --lint` on the three changed namespaces: 0 errors; remaining
  warnings are all pre-existing (redundant `let`, unused bindings).

## Out of scope

Rounding/carry order, strict `:floor`, the parallel architecture, and canonical
renames were intentionally left untouched. No invariant or conformance
semantics changed beyond the affected/status contract described here.