# Deviation Events (seq 0): Synthetic Event-ID Policy

## Policy

**Deviation events (seq 0 in fork continuations) do NOT receive synthetic
`event-id` values.**

This is a deliberate design choice documented in
`scenario/subgame_counterfactual.clj`:

```clojure
:event-identity :inherit-from-main-trace
;; Continuation events inherit :params (including optional event-id) from the
;; main-line trace. Deviation events (seq 0) do not receive synthetic ids.
```

## Rationale

1. **No provenance requirement.** Deviation events are counterfactuals that
   exist only in the SPE analysis output. They are never replayed from
   external logs and never persisted as evidence.

2. **No idempotency risk.** Deviation events are executed exactly once per
   tree-expansion pass. There is no reorg path for counterfactuals.

3. **No identity collision.** Deviation events use distinct action params
   that differ from the main-line event at the same seq. There is no risk
   of a deviation `event-id` colliding with a subsequent continuation `event-id`.

## When this would change

Synthetic `event-id` values for deviation events would be needed if either:

1. **Fork traces are persisted as evidence.** If counterfactual replay traces
   are stored alongside main-line traces for audit, deviation steps become
   unaddressable in dedupe audits.

2. **Fork traces are replayed cross-session.** If a counterfactual trace from
   one session needs to be replayed in another context, deviation events
   without `event-id` would be rejected by `:require-event-id?` replay flags.

3. **Fork traces enter the dedupe pipeline.** If counterfactual execution
   ever feeds into the replay-boundary dedupe system, deviation events need
   stable identity for `apply-once` correctness.

## Current behaviour

- Continuation events: inherit `event-id` from main-line trace → dedupe works
- Deviation events (seq 0): no `event-id` → pass through to business logic

## Test coverage

- `spe_fork_event_id_test.clj`: verifies continuation inheritance and dedupe
- No test currently verifies deviation event identity (by design)
