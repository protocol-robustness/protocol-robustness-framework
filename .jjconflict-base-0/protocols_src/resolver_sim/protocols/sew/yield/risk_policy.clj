(ns resolver-sim.protocols.sew.yield.risk-policy
  "Dynamic yield risk policy engine for liquid-lending modules."
  (:require [resolver-sim.protocols.sew.types :as t]))

(defmulti calculate-risk-adjustment
  "Compute dynamic APY and liquidity mode adjustments based on current shortfall ratio.
   Policies:
     :de-risking           — drop APY, constrain withdrawals during crunch.
     :liquidity-attraction — increase APY, aggressive payout.
     :bad-policy           — maintain high APY despite crunch (risk cascade)."
  (fn [policy-id _available-ratio] policy-id))

(defmethod calculate-risk-adjustment :de-risking
  [_ available-ratio]
  (cond
    (> available-ratio 0.8) {:apy-multiplier 1.0 :liquidity-mode :available}
    (> available-ratio 0.5) {:apy-multiplier 0.5 :liquidity-mode :shortfall}
    :else                  {:apy-multiplier 0.0 :liquidity-mode :frozen}))

(defmethod calculate-risk-adjustment :liquidity-attraction
  [_ available-ratio]
  (cond
    (> available-ratio 0.8) {:apy-multiplier 1.0 :liquidity-mode :available}
    (> available-ratio 0.5) {:apy-multiplier 2.0 :liquidity-mode :shortfall}
    :else                  {:apy-multiplier 5.0 :liquidity-mode :shortfall}))

(defmethod calculate-risk-adjustment :bad-policy
  [_ _available-ratio]
  {:apy-multiplier 1.5 :liquidity-mode :available})

(defmethod calculate-risk-adjustment :default
  [_ _available-ratio]
  {:apy-multiplier 1.0 :liquidity-mode :available})
