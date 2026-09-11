# Attribution Guide

Evidence artifacts are automatically annotated with execution context. In normal scenario replay, researchers usually do not need to call `with-attribution` directly. The event dispatcher binds core context keys:

- `:ctx/run-id`
- `:ctx/scenario-id`
- `:ctx/event-index`
- `:ctx/event-type`

## Capturing evidence

Protocol code can capture evidence directly. The attribution context flows automatically from the event pipeline:

```clojure
;; Inside protocol code running under normal replay:
(capture-event-evidence! :slashing pre post inputs)
```

The resulting evidence artifact includes the dispatcher-supplied `:ctx/run-id`, `:ctx/scenario-id`, etc. automatically.

## Adding semantic context

Use `with-attribution` when the local code knows something the event dispatcher does not — for example, which resolver was slashed and why:

```clojure
(with-attribution {:subject/type :resolver
                   :subject/id   resolver-addr
                   :action/type  :slash
                   :evidence/reason :fraud-slash}
  (capture-event-evidence! :slashing pre post inputs))
```

The inner keys merge with the outer dispatcher context. No outer keys are overwritten unless you reuse the same namespaced key.

## Attribution paths (canonical vs non-canonical)

`capture-event-evidence!` accepts attribution through multiple channels. Only one is intended for routine use:

| Path | Canonical? | When to use |
|------|-----------|-------------|
| Dynamic binding via `with-attribution` | **Yes** | All protocol code, research scripts |
| `:attribution-context` key in opts map | No — tests/adapters only | Unit tests, migration shims, non-pipeline capture |
| Opts map passed as raw attribution | No — tests/adapters only | Legacy call sites |
| Reason map doubling as attribution | No — legacy only | Being phased out |

**Rule: always use `with-attribution` to add context. Never pass an attribution map directly to `capture-event-evidence!`.**

```clojure
;; CANONICAL — use with-attribution:
(with-attribution {:subject/type :resolver}
  (capture-event-evidence! :slashing pre post inputs))

;; NON-CANONICAL — do not do this in protocol code:
(capture-event-evidence! :slashing pre post inputs
  {:attribution-context {:subject/type :resolver}})
```

The non-canonical paths exist for legacy compatibility. New code should never use them.

## Key rules

1. **Keys must be namespaced.** Use `:subject/type`, not `:type`. Non-namespaced keys are silently dropped by `sanitize-attribution` and will not appear in evidence artifacts. `warn-invalid-attribution!` fires at bind time inside `with-attribution` to catch this immediately.

   ```clojure
   ;; GOOD — will appear in evidence:
   :subject/type :resolver

   ;; BAD — silently dropped:
   :type :resolver
   ```

2. **Values must be artifact-safe.** Strings, keywords, numbers, booleans, nil, and maps/vectors of those types are safe. Functions, atoms, records, and Java objects will cause the entry to be dropped.

3. **Inner keys override outer keys.** If the dispatcher already set `:ctx/event-type :release`, your inner `(with-attribution {:ctx/event-type :slash} ...)` overrides it for the scope of the body.

## Examples

### Simple evidence capture (no additional context needed)

```clojure
;; Dispatcher supplies :ctx/run-id, :ctx/scenario-id, etc.
(capture-event-evidence! :slashing pre post inputs)
```

### Evidence capture with semantic context

```clojure
(with-attribution {:subject/type :resolver
                   :subject/id   resolver-addr
                   :action/type  :slash}
  (capture-event-evidence! :fraud-slash-executed pre post inputs))
```

### Wrapping a computation for logging

```clojure
(let [result (with-attribution {:yield/target-type :resolver
                                :yield/resolver-addr addr}
               (calculate-accrual world params))]
  (if (:ok result)
    (assoc (t/ok (:world result)) :accrued true)
    result))
```

## Strict validation (tests and CI)

Use `with-attribution-strict` in test code to catch invalid keys early:

```clojure
(with-attribution-strict
  {:subject/type :resolver
   :evidence/reason :slashing}
  (capture-event-evidence! :slashing pre post inputs))
```

This throws immediately if any key is non-namespaced or any value is artifact-unsafe.

## Reference: known attribution keys

Defined in `resolver-sim.util.attribution/known-attribution-keys` (provenance context):

| Key | Namespace | Description |
|-----|-----------|-------------|
| `:ctx/run-id` | `:ctx` | Unique run identifier |
| `:ctx/scenario-id` | `:ctx` | Scenario identifier |
| `:ctx/event-index` | `:ctx` | Zero-based event index |
| `:ctx/event-type` | `:ctx` | Dispatched event/action type |
| `:subject/type` | `:subject` | Entity kind (`:resolver`, `:escrow`, etc.) |
| `:subject/id` | `:subject` | Stable entity identifier |
| `:action/type` | `:action` | Semantic operation being performed |
| `:evidence/reason` | `:evidence` | Reason for capturing evidence |

Payload keys (inside evidence `:before` / `:after` / `:inputs` maps) are registered separately
in `known-evidence-payload-keys` — these describe what changed, not where it came from.
Key domains include `:yield/*`, `:accrual/*`, `:settlement/*`, `:escrow/*`, `:finalize/*`,
`:stake/*`, `:appeal/*`, `:appeal-resolution/*`, `:escalation/*`, `:challenge/*`, `:bond/*`,
`:dispute/*`, `:unavailability/*`, `:unfreeze/*`, `:world/*`, `:proposal/*`, `:evidence/*`.

Click [source](../../src/resolver_sim/util/attribution.clj) for the full registries with descriptions.

## How it works

1. The event dispatch infrastructure in `replay.clj` wraps each event in `with-attribution` with `:ctx/scenario-id`, `:ctx/run-id`, `:ctx/event-index`, `:ctx/event-type`.
2. Protocol code may add nested `with-attribution` layers with domain-specific keys.
3. `capture-event-evidence!` reads the active `*attribution*` dynamic var and embeds it into the evidence artifact.
4. At persistence time, `sanitize-attribution` drops any entry with a non-namespaced key or non-serializable value.
5. `warn-invalid-attribution!` fires at bind time (inside `with-attribution`) so the developer sees the problem immediately, not when inspecting artifacts.
