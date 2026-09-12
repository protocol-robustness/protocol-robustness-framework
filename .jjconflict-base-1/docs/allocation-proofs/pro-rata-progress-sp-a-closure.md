# SP-A Closure — pro-rata progress observability slice

Implementation slice SP-A (progress event model, atom-as-adapter, observer
non-interference) is closed. The table below is the authoritative closure
status.

## Closure status

| Item                                                | Status                                   |
| --------------------------------------------------- | ---------------------------------------- |
| Progress event model                                | CLOSED                                   |
| Atom-as-adapter boundary                            | CLOSED                                   |
| Callback-only observation                           | CLOSED                                   |
| Observer semantic non-interference                 | CLOSED                                   |
| Atomic parallel progress                            | CLOSED                                   |
| Truthful indeterminate proving state                | CLOSED                                   |
| Scenario exactly-once progress                     | CLOSED                                   |
| Canonical request unchanged                        | CLOSED                                   |
| Canonical result/evidence invariant                | CLOSED                                   |
| Statement-root non-interference                    | CLOSED (assertion added at existing surface) |
| Legacy untyped-event compatibility                 | ACCEPTED, compatibility-only             |
| Proof integration                                  | OUT OF SCOPE as intended                 |
| Programme sequencing                               | OUT OF SCOPE until SP-C                  |

## Contract: typed events normative, untyped maps legacy-only

`pro-rata-progress.v1` must not quietly become "typed events OR arbitrary old
maps forever." The division of responsibility is now explicit:

- **Normative** — the vocabulary in `resolver-sim.pro-rata.progress/event-types`
  (which now includes `:proving` for a coherent set), dispatched through the
  explicit `reducer` cases, IS the pro-rata-progress.v1 API. SP-A and SP-C emit
  only typed events.
- **Legacy compatibility-only** — pre-SP-A untyped field maps or unknown event
  keywords are routed through the isolated `legacy-untyped-event->snapshot`
  adapter, which stamps `:progress/compat :untyped-event` so any such use is
  visibly detectable. The legacy `:redistribution-pass` → `:pass-index` remap
  lives only on that legacy path.

The fallback is **isolated in an adapter**, not embedded in reducer semantics:
typed events never traverse the compat adapter, and normative snapshots never
carry the `:progress/compat` marker. There is no urgency to remove the legacy
path now, but it must not be extended. New work adds a typed event to
`event-types` and a `reducer` case.

## Verification note

- `test/resolver_sim/pro_rata/progress_test.clj`
  `normative-typed-events-never-touch-the-legacy-path` asserts no event in
  `event-types` yields a legacy-marked snapshot.
- `untyped-events-are-legacy-compat-with-visible-marker` asserts untyped/unknown
  events and the legacy `:redistribution-pass` remap are marked compat-only.
- `test/resolver_sim/allocation/realized_statement_test.clj`
  `progress-observation-does-not-alter-realized-statement-root` asserts the
  statement root and all six sub-roots are invariant across observer variants
  on the existing allocation → statement surface.