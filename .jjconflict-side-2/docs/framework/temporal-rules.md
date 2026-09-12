# Temporal rules: ordering, precedence, and trace contract

This document describes how temporal validation works in replay.

## Where rules run

Temporal rules are evaluated in `resolver-sim.contract-model.replay/process-step`
before protocol `dispatch-action`.

## Temporal Context Schema (v2)

To ensure high-precision and deterministic temporal semantics, the simulation uses a canonical `temporal-context.v2` schema, defined in `resolver-sim.time.context`.

A world state holds this context under the `:context/time` key:

```clojure
{:schema-version "temporal-context.v2"
 :step 0
 :event-seq 0
 :block-ts 1718534400    ;; Unix seconds
 :instant #inst "..."    ;; java.time.Instant
 :clock/source :legacy   ;; or :scenario
 :clock/mode :discrete-step
 :tick-seconds 86400}
```

Utilities:
- `ensure-temporal-context`: Normalizes legacy worlds to v2.
- `with-temporal-context`: Safely updates temporal state.
- `temporal-context`: Canonical accessor.

## Rule sources

Effective rules are:

1. **Base kernel rules** (always present)
   - `:missing-event-time`
   - `:non-regressive-time`
2. **Protocol/context rules** (optional)
   - provided via execution context `:temporal-rules`

## Ordering and precedence

- Rules are evaluated in vector order.
- Evaluation is short-circuiting.
- **First failing rule wins** and determines rejection metadata.

This guarantees deterministic, explainable temporal rejection semantics.

## Trace contract for temporal rejection

When a temporal rule rejects, trace entry includes:

- `:result :rejected`
- `:error <keyword>`
- `:temporal-rule-id <keyword>`

Example:

```clojure
{:seq 3
 :action "execute_pending_settlement"
 :result :rejected
 :error :appeal-window-not-expired
 :temporal-rule-id :sew/appeal-window-open}
```

## Artifact propagation

In trace export, rejected temporal metadata is preserved by:

- expected error (`:expected :error`)
- step attribute `:temporal_rule_id`

so downstream tooling can classify temporal rejection reasons.
