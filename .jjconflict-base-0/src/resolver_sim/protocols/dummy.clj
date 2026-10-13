(ns resolver-sim.protocols.dummy
  "DummyProtocol — minimal always-pass tiered protocol implementation.

   A protocol test double used to verify that the tiered protocol interfaces
   work and that the generic replay engine machinery (metrics, trace shape)
   does not crash when optional protocols are missing or no-op.

   Behaviour:
   - All actions: succeed without modifying world state.
   - Invariant checks: always pass.

   This is a test double, not a useful protocol.

   Layering: may import protocols/protocol only.
   Must NOT import contract_model/*, model/*, db/*, io/*."
  (:require [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.time.context       :as time-ctx]))

;; ---------------------------------------------------------------------------
;; DummyProtocol implementation (Core only)
;; ---------------------------------------------------------------------------

(deftype DummyProtocol []
  proto/SimulationAdapter

  (protocol-id [_] "dummy")

  (init-world [_ scenario]
    (time-ctx/ensure-temporal-context
     {:block-time (get scenario :initial-block-time 1000)}))

  (build-execution-context [_ agents _protocol-params]
    {:agent-index (into {} (map (juxt :id identity) agents))})

  (dispatch-action [_ _context world _event]
    {:ok true :world world})

  (check-invariants-single [_ _world]
    {:ok? true :violations nil})

  (check-invariants-transition [_ _world-before _world-after]
    {:ok? true :violations nil})

  (world-snapshot [_ world]
    (let [tctx (time-ctx/temporal-context world)]
      {:block-time (:block-ts tctx)
       :time       tctx}))

  (open-entities [_ _world]
    [])

  proto/EconomicModel

  (adversarial-event? [_ _event _agent]
    false)

  (classify-event [_ _event _result-kw _error-kw]
    #{})

  (metric-vocabulary [_]
    #{})

  (accum-protocol-metrics [_ metrics _ _ _ _ _ _]
    metrics)

  (summarise-batch [_ outcomes]
    {:n (count outcomes)
     :by-outcome (->> outcomes
                      (group-by :trial/outcome)
                      (map (fn [[k vs]] [k (count vs)]))
                      (into {}))})

  (advisory [_ _world _request-type _context]
    {:not-supported true}))

;; ---------------------------------------------------------------------------
;; Shared singleton
;; ---------------------------------------------------------------------------

(def protocol
  "Ready-made DummyProtocol instance."
  (DummyProtocol.))
