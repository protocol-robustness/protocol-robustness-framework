# Protocol Robustness Framework: Attribution System Guide

## Overview

The Protocol Robustness Framework's attribution system provides a structured way to track context and metadata across complex state transitions. It enables observability, debugging, risk monitoring, and evidence capture without cluttering function signatures.

## Why Use `with-attribution`

### Core Benefits

1. **Observability**: Track the flow of operations through complex state transitions
2. **Debugging**: Provide rich context when issues occur
3. **Risk Monitoring**: Capture risk-relevant events for analysis
4. **Evidence Chain**: Maintain tamper-evident audit trails
5. **Separation of Concerns**: Keep business logic clean while enabling cross-cutting concerns

### What It Enables

- **Automatic evidence capture**: Context is available to evidence emitters without explicit passing
- **Risk event detection**: System can monitor for risky patterns across operations
- **Performance monitoring**: Track operation metrics with full context
- **Compliance auditing**: Maintain complete operation histories
- **Error diagnosis**: Rich context available in error handlers

## Where to Use `with-attribution`

### Current Usage Patterns

The framework currently uses `with-attribution` in these critical operations:

1. **Yield Accrual** (`apply-accrual-decision-with-attribution`)
   - Captures: accrual mode, short-circuits, yield deltas, module/token/position IDs
   - Enables: risk monitoring for accrual failures

2. **Partial Fill Settlements** (`apply-partial-fill-with-attribution`)
   - Captures: settlement mode, filled/deferred/haircut amounts
   - Enables: shortfall tracking and liquidity monitoring

### Missing Usage (Should Be Added)

1. **Deferred Claim Reclamation** (`claim-deferred`)
   - Currently missing attribution context
   - Should capture: reclaimed amounts, before/after states, ratio thresholds
   - Impact: Risk monitoring cannot track claim recovery patterns

2. **Emergency Unwind Operations** (`emergency-unwind`)
   - Currently missing attribution context  
   - Should capture: unwind reasons, affected positions, timing
   - Impact: Cannot correlate unwinds with system stress events

## How to Use `with-attribution`

### Basic Pattern

```clojure
(require '[resolver-sim.util.attribution :as attr])

(defn operation-with-attribution [world params]
  (let [context {:operation/type :yield-claim
                :operation/module (:module-id params)
                :operation/token (:token params)
                :operation/amount (:amount params)}]
    
    (attr/with-attribution context
      ;; Business logic here
      (let [result (perform-operation world params)]
        
        ;; Context is automatically available to:
        ;; - Evidence capture functions
        ;; - Risk monitoring
        ;; - Logging
        ;; - Error handlers
        
        (emit-evidence! :operation-complete 
                       {:before world :after result})
        
        result))))
```

### Advanced Pattern: Context Updates

```clojure
(defn complex-operation-with-attribution [world params]
  (let [initial-context {:operation/type :multi-stage
                         :operation/id (generate-id)
                         :stage :started}]
    
    (attr/with-attribution initial-context
      
      ;; Stage 1: Validation
      (let [validation-result (validate-params params)]
        
        ;; Update context with validation results
        (attr/with-attribution (merge (attr/current-attribution)
                                    {:stage :validated
                                     :validation/status (:status validation-result)})
          
          ;; Stage 2: Execution
          (let [execution-result (execute-operation world params)]
            
            ;; Final context update
            (attr/with-attribution (merge (attr/current-attribution)
                                        {:stage :completed
                                         :execution/time-ms (:duration execution-result)})
              
              ;; Evidence and monitoring
              (capture-if-risk-event)
              (emit-operation-evidence! execution-result)
              
              (:world execution-result)))))))
```

### Real-World Example: Deferred Claim with Attribution

**Current Implementation (Missing Attribution):**

```clojure
(defn claim-deferred
  "Attempt to reclaim deferred yield from an unwinding position."
  [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)]
    (cond
      (= (:status pos) :unwinding)
      (let [old-pos pos
            new-pos (acct/claim-deferred world mid pos)
            reclaimed (:reclaimed-amount new-pos 0)]
        (if (pos? reclaimed)
          (let [world-final (assoc-in world pos-key new-pos)]
            (ye/emit-shortfall-event world-final :yield.shortfall/deferred-reclaimed oid
                                     {:reclaimed-amount reclaimed
                                      :deferred-before (get-in old-pos [:shortfall :deferred-amount] 0)})
            world-final)
          world))
      
      ;; ... other cases
      )))
```

**Recommended Implementation (With Attribution):**

```clojure
(defn claim-deferred-with-attribution
  "Apply a deferred claim reclaim with full attribution context."
  [world module op]
  (let [oid     (:owner/id op)
        pos-key [:yield/positions oid]
        pos     (get-in world pos-key)
        mid     (:module/id module)
        token   (:token pos)
        risk    (acct/risk-map world mid token)
        available-ratio (double (get-in risk [:shortfall :available-ratio] 1.0))
        min-ratio (double (get-in risk [:min-available-ratio-for-claim] 1.0))
        shortfall (:shortfall pos)
        deferred-before (:deferred-amount shortfall 0)
        status-before (:status pos)]

    (attr/with-attribution {:claim/reclaimed-amount 0
                           :claim/deferred-before deferred-before
                           :claim/module-id mid
                           :claim/token token
                           :claim/position-id oid
                           :claim/available-ratio available-ratio
                           :claim/min-ratio min-ratio
                           :claim/status-before status-before
                           :claim/status-after status-before}
      
      (let [old-pos pos
            new-pos (acct/claim-deferred world mid pos)
            reclaimed (:reclaimed-amount new-pos 0)]
        
        (if (pos? reclaimed)
          (let [world-final (assoc-in world pos-key new-pos)
                status-after (:status new-pos)
                ctx-update {:claim/reclaimed-amount reclaimed
                           :claim/status-after status-after}]
            
            ;; Update context with actual results
            (attr/with-attribution (merge (attr/current-attribution) ctx-update)
              
              ;; Evidence emission now has full context
              (ye/emit-shortfall-event world-final :yield.shortfall/deferred-reclaimed oid
                                       {:reclaimed-amount reclaimed
                                        :deferred-before deferred-before})
              
              ;; Risk monitoring can now detect patterns
              (risk-monitor/capture-if-risk-event)
              
              world-final))
          
          world))))
```

## Consequences of Using `with-attribution`

### Before: Without Attribution

```clojure
;; Operation executes
(result world (claim-deferred world module op))

;; Evidence emitted, but context is limited
{:event :yield.shortfall/deferred-reclaimed
 :reclaimed-amount 100
 :deferred-before 100}

;; Risk monitoring sees only the event
❌ Cannot correlate with system state
❌ Cannot detect patterns across operations  
❌ Limited debugging information
❌ No attribution chain for auditing
```

### After: With Attribution

```clojure
;; Operation executes with context
(attr/with-attribution {:claim/module-id :aave-v3
                       :claim/token :USDC
                       :claim/position-id "escrow:42"
                       :claim/available-ratio 1.0
                       :claim/min-ratio 0.9}
  (claim-deferred world module op))

;; Evidence emitted with full context
{:event :yield.shortfall/deferred-reclaimed
 :reclaimed-amount 100
 :deferred-before 100
 :attribution {
   :claim/module-id :aave-v3
   :claim/token :USDC
   :claim/position-id "escrow:42"
   :claim/available-ratio 1.0
   :claim/min-ratio 0.9
   :claim/status-before :unwinding
   :claim/status-after :withdrawn
   :claim/reclaimed-amount 100
   :timestamp "2026-06-26T15:23:56Z"
   :chain-seq 42
   :chain-prev-hash "abc123..."
   :chain-self-hash "def456..."}}

;; Risk monitoring can now:
✅ Correlate claims with module health
✅ Detect anomalous reclaim patterns
✅ Track recovery rates by module/type
✅ Audit complete operation history
✅ Provide rich debugging context
```

## Attribution Context Keys Reference

### Standard Keys

| Key Category | Example Keys | Purpose |
|-------------|-------------|---------|
| **Operation** | `:operation/type`, `:operation/id` | Identify operation type and instance |
| **Module** | `:module/id`, `:module/type` | Track which module is involved |
| **Token** | `:token`, `:token-decimals` | Currency context |
| **Position** | `:position-id`, `:status-before`, `:status-after` | Entity lifecycle tracking |
| **Risk** | `:available-ratio`, `:min-ratio`, `:short-circuits` | Risk parameter capture |
| **Timing** | `:operation/start-ms`, `:operation/duration-ms` | Performance monitoring |
| **Results** | `:reclaimed-amount`, `:fulfilled-amount` | Operation outcomes |

### Yield-Specific Keys

| Context | Keys |
|---------|------|
| **Accrual** | `:accrual/mode`, `:accrual/yield-delta`, `:accrual/deferred-delta` |
| **Settlement** | `:settlement/mode`, `:settlement/filled`, `:settlement/deferred` |
| **Claims** | `:claim/reclaimed-amount`, `:claim/deferred-before`, `:claim/status-before` |
| **Risk** | `:risk/available-ratio`, `:risk/min-ratio`, `:risk/short-circuits` |

## Best Practices

### 1. Context Granularity

```clojure
;; ✅ Good: Specific, actionable context
{:operation/type :yield-claim
 :claim/module-id :aave-v3
 :claim/position-id "escrow:42"
 :claim/status-before :unwinding
 :claim/status-after :withdrawn}

;; ❌ Avoid: Too vague
{:operation :claim
 :status :changed}

;; ❌ Avoid: Too detailed (clutters context)
{:operation/type :yield-claim
 :claim/module-id :aave-v3
 :claim/position-id "escrow:42"
 :claim/full-position-data {...huge map...}}
```

### 2. Context Updates

```clojure
;; ✅ Good: Update context as operation progresses
(attr/with-attribution initial-context
  (let [validation-result (validate)]
    (attr/with-attribution (merge (attr/current-attribution) {:stage :validated})
      (let [execution-result (execute)]
        (attr/with-attribution (merge (attr/current-attribution) {:stage :completed})
          (emit-evidence! execution-result)))))

;; ❌ Avoid: Creating entirely new context (loses history)
(attr/with-attribution initial-context
  (let [result (execute)]
    (attr/with-attribution {:completely :new :context}  ; ❌ Loses initial context
      (emit-evidence! result)))
```

### 3. Error Handling

```clojure
;; ✅ Good: Preserve context in errors
(attr/with-attribution operation-context
  (try
    (perform-risky-operation world params)
    (catch Exception e
      (emit-error-evidence! 
        {:error (ex-message e)
         :context (attr/current-attribution)  ; ✅ Full context available
         :stacktrace (ex-stacktrace e)})
      (throw e)))

;; ❌ Avoid: Losing context in errors
(try
  (perform-risky-operation world params)
  (catch Exception e
    (emit-error-evidence! {:error (ex-message e)}))  ; ❌ No context
```

## Integration with Other Systems

### Evidence Chain

`with-attribution` automatically integrates with the evidence chain:

```clojure
;; Each attributed operation adds to the chain
(attr/with-attribution {...}
  (operation-1 world))  ; Adds evidence with seq=1, prev-hash=none

(attr/with-attribution {...}
  (operation-2 world))  ; Adds evidence with seq=2, prev-hash=hash-of-1

(attr/with-attribution {...}
  (operation-3 world))  ; Adds evidence with seq=3, prev-hash=hash-of-2
```

### Risk Monitoring

Risk monitors consume attribution context:

```clojure
;; In risk-monitor.clj
(defn capture-if-risk-event []
  (let [attr* attr/*attribution*
        short-circuits (:accrual/short-circuits attr*)]
    (when (risk-relevant? short-circuits)
      (record-risk-event!
        {:type :yield-short-circuit
         :short-circuits short-circuits
         :context (dissoc attr* :accrual/short-circuits)})))
```

### Performance Monitoring

```clojure
;; Track operation durations with context
(attr/with-attribution (assoc initial-context :operation/start-ms (System/currentTimeMillis))
  (let [result (perform-operation)]
    (attr/with-attribution (merge (attr/current-attribution)
                                {:operation/duration-ms (- (System/currentTimeMillis) (:operation/start-ms (attr/current-attribution)))})
      (record-performance-metrics!)
      result))
```

## Migration Guide

### Adding Attribution to Existing Functions

1. **Identify context** that should be tracked
2. **Wrap operation** in `with-attribution`
3. **Update context** as operation progresses
4. **Ensure evidence emitters** have access to context
5. **Add tests** for attribution context

### Example Migration

**Before:**
```clojure
defn process-withdrawal [world op]
  (let [result (calculate-fulfillment world op)]
    (emit-withdrawal-evidence! result)
    (update-world-world result)))
```

**After:**
```clojure
defn process-withdrawal-with-attribution [world op]
  (let [context {:operation/type :yield-withdrawal
                :operation/module (:module-id op)
                :operation/token (:token op)
                :operation/position-id (:position-id op)}]
    
    (attr/with-attribution context
      (let [fulfillment (calculate-fulfillment world op)
            result (apply-fulfillment world fulfillment)]
        
        (attr/with-attribution (merge (attr/current-attribution)
                                    {:operation/fulfilled (:fulfilled fulfillment)
                                     :operation/deferred (:deferred fulfillment)})
          
          (emit-withdrawal-evidence! result)
          (capture-if-risk-event)
          result))))
```

## Troubleshooting

### Common Issues

**Problem:** `attr/current-attribution` returns nil

**Solution:** Ensure you're calling it within a `with-attribution` block

```clojure
;; ❌ Outside attribution context
(attr/current-attribution)  ; => nil

;; ✅ Inside attribution context
(attr/with-attribution {...}
  (attr/current-attribution))  ; => {...}
```

**Problem:** Context not available in async operations

**Solution:** Use `attr/with-attribution-bound` for dynamic binding across threads

```clojure
(attr/with-attribution-bound context
  (future
    (attr/current-attribution))  ; Context available in future
```

**Problem:** Too much context clutter

**Solution:** Use selective context passing

```clojure
;; Only pass relevant context to sub-operations
(attr/with-attribution full-context
  (sub-operation-1)
  
  (attr/with-attribution (select-keys (attr/current-attribution) [:module-id :token])
    (sub-operation-2))  ; Limited context
```

## Conclusion

The attribution system is a powerful tool for building observable, debuggable, and monitorable systems. By consistently using `with-attribution` for all major operations, the Protocol Robustness Framework maintains:

- **Complete audit trails** for compliance and debugging
- **Rich context** for risk monitoring and analysis
- **Separation of concerns** between business logic and cross-cutting concerns
- **Future extensibility** for new monitoring and analysis features

**Recommendation:** All state-modifying operations in yield modules should use `with-attribution` to maintain consistency with the framework's observability standards.