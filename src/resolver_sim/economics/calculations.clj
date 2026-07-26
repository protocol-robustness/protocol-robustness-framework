(ns resolver-sim.economics.calculations
  "Protocol-independent economic calculations.

   Pure arithmetic functions: accept amounts, return results.
   No protocol state, no world dependencies, no side effects.

   Sew protocol-specific wrappers live in resolver-sim.protocols.sew.economics
   and delegate to these core functions for their arithmetic."
  (:require [resolver-sim.economics.payoffs :as payoffs]))

(defn calculate-bps-amount
  "Calculate amount * bps / 10000.
   Pure math — no protocol state required."
  [amount bps]
  (payoffs/calculate-bps-amount amount bps))

(defn calculate-bps-fee
  "Calculate the net amount after deducting a bps-rate fee."
  [amount fee-bps]
  (payoffs/calculate-net-after-bps-fee amount fee-bps))

(defn calculate-bounty
  "Calculate bounty from a slash amount at bounty-bps rate.
   Returns 0 when bounty-bps is zero or negative."
  [slash-amount bounty-bps]
  (if (pos? bounty-bps)
    (payoffs/calculate-bps-amount slash-amount bounty-bps)
    0))

(defn calculate-slash-amount
  "Calculate slash amount from slashable stake and bps rate."
  [slashable-stake slash-bps]
  (payoffs/calculate-bps-amount slashable-stake slash-bps))

(defn calculate-capacity-limit
  "Compute the maximum capacity from stake and a multiplier."
  ([stake] (calculate-capacity-limit stake 1.0))
  ([stake multiplier]
   (payoffs/calculate-capacity-limit stake multiplier)))
