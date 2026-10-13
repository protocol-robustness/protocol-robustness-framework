(ns resolver-sim.yield.loss
  "Canonical yield loss reasons and position annotations.

   :negative-accrual-yield — mark-to-market erosion on an active position (accrue).
   :negative-carry-loss    — principal drawdown crystallized at withdraw (gross < principal)."
  (:require [resolver-sim.yield.risk :as risk]))

(defn- normalize-token [token]
  (cond
    (keyword? token) token
    (string? token)  (keyword token)
    :else            token))

(defn- risk-for-position [world position]
  (let [mid (:module/id position)
        tok (normalize-token (:token position))]
    (or (get-in world [:yield/risk mid tok])
        (get-in world [:yield/risk mid (name tok)])
        {})))

(defn negative-accrual-yield?
  "True when risk map describes active negative-yield accrual (mark-to-market)."
  [risk]
  (= :mark-to-market (risk/effective-loss-mode risk)))

(defn accrual-loss-amount
  "Positive magnitude of negative unrealized yield, or 0."
  [unrealized-yield]
  (if (neg? (long unrealized-yield))
    (- (long unrealized-yield))
    0))

(defn annotate-accrual-loss
  "Tag active position with :yield-loss when unrealized yield is negative under mark-to-market.

   Clears :yield-loss when unrealized recovers to non-negative."
  [world position current-index]
  (if (and (negative-accrual-yield? (risk-for-position world position))
           (neg? (long (or (:unrealized-yield position) 0))))
    (assoc position :yield-loss
           {:reason :negative-accrual-yield
            :amount (accrual-loss-amount (:unrealized-yield position))
            :as-of-index current-index})
    (dissoc position :yield-loss)))

(defn intrinsic-carry-shortfall
  "Shortfall map when gross redemption is below principal (permanent haircut)."
  [principal gross-amount current-index]
  (let [haircut (max 0 (- principal gross-amount))]
    {:reason :negative-carry-loss
     :basis-amount principal
     :fulfilled-amount gross-amount
     :deferred-amount 0
     :haircut-amount haircut
     :as-of-index current-index}))
