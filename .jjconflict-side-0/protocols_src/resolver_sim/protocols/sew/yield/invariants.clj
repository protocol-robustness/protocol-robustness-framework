(ns resolver-sim.protocols.sew.yield.invariants
  "Sew-specific invariants for yield-bearing escrows."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.yield.invariants :as yield-inv]))

(defn- sew-live-position?
  [world oid pos]
  (if (vector? oid)
    (let [[owner-type escrow-id] oid]
      (and (= owner-type :sew/escrow)
           (contains? t/live-states (:escrow-state (t/get-transfer world escrow-id)))))
      ;; For resolver stakes, we consider them always live if the status is active
    (t/resolver-yield-owner-id? oid)))

(defn- held-for-yield-exposure
  "Available funds for yield exposure check.  For resolver-owned positions,
   resolver-stakes back the exposure; for escrow positions, total-held covers it."
  [world token]
  (+ (get-in world [:total-held token] 0)
     (if (= token :USDC)
       (reduce + 0 (vals (:resolver-stakes world {})))
       0)))

(defn check-sew-yield-exposure
  "Check that the protocol has enough funds in held to cover all escrow principal and realized yield."
  [world]
  (yield-inv/check-yield-exposure
   world
   (partial sew-live-position? world)
   #(held-for-yield-exposure world %)))

(defn check-all [world]
  {:sew/yield-exposure (check-sew-yield-exposure world)})
