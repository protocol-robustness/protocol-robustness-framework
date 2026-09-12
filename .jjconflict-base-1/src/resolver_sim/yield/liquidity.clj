(ns resolver-sim.yield.liquidity
  "Data-driven withdrawal fulfillment and shortfall handling.
   Implements policies like partial-fill, defer, and haircut based on market state."
  (:require [resolver-sim.yield.market-state :as market-state]
            [resolver-sim.time.context :as time-ctx]))

(defn- calculate-fulfillment
  "Determine fulfilled vs shortfall amounts based on available ratio."
  [amount ratio policy shortfall-model]
  (let [available (long (Math/floor (* amount (double ratio))))
        remainder (- amount available)]
    (case (:on-shortfall policy :fail)
      :fail
      (if (< ratio 1.0)
        (throw (ex-info "Withdrawal failed due to liquidity shortfall"
                        {:amount amount :ratio ratio}))
        {:fulfilled amount :shortfall nil})

      :partial-fill
      {:fulfilled available
       :shortfall {:reason (or (:type shortfall-model) :liquidity-shortfall)
                   :basis-amount amount
                   :fulfilled-amount available
                   :deferred-amount (if (:recoverable shortfall-model true) remainder 0)
                   :haircut-amount (if (:recoverable shortfall-model true) 0 remainder)}}

      :defer
      {:fulfilled 0
       :shortfall {:reason :deferred-liquidity
                   :basis-amount amount
                   :fulfilled-amount 0
                   :deferred-amount amount
                   :haircut-amount 0}}

      :haircut
      {:fulfilled available
       :shortfall {:reason :permanent-loss
                   :basis-amount amount
                   :fulfilled-amount available
                   :deferred-amount 0
                   :haircut-amount remainder}}

      ;; Fallback to partial-fill
      {:fulfilled available
       :shortfall {:reason :liquidity-shortfall
                   :basis-amount amount
                   :fulfilled-amount available
                   :deferred-amount remainder
                   :haircut-amount 0}})))

(defn apply-withdrawal-policy
  "Apply market-state withdrawal policy to a gross amount."
  [world module-id token gross-amount principal]
  (let [ms (market-state/get-market-state world module-id token (time-ctx/block-ts world))
        policy (:withdrawal-policy ms)
        shortfall-model (:shortfall-model ms)
        ratio (:available-ratio ms 1.0)]
    (if (>= ratio 1.0)
      {:fulfilled gross-amount :shortfall nil}
      (calculate-fulfillment gross-amount ratio policy shortfall-model))))