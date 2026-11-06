(ns resolver-sim.protocols.sew.research-models.ring-batch
  "Sew runner adapter for the optional resolver-ring research simulation."
  (:require [resolver-sim.protocols.sew.research-models.resolver-ring :as ring]
            [resolver-sim.stochastic.types :as types]
            [resolver-sim.sim.common-kwargs :refer [common-kwargs]]))

(defn run-ring-batch
  "Run N trials of the Sew resolver-ring simulation and aggregate its metrics."
  [rng n-trials params ring-spec]
  (let [params (merge types/default-params params)
        kw-args (common-kwargs params)
        initial-ring (ring/create-ring ring-spec)
        ring-results
        (reduce
         (fn [ring-state _trial]
           (let [result
                 (apply ring/simulate-ring-dispute
                        rng ring-state
                        (:escrow-size params)
                        (:resolver-fee-bps params)
                        (:appeal-bond-bps params)
                        (:slash-multiplier params)
                        (:appeal-probability-if-correct params)
                        (:appeal-probability-if-wrong params)
                        (:slashing-detection-probability params)
                        kw-args)]
             (:ring result)))
         initial-ring
         (range n-trials))
        profitability (ring/ring-profitability ring-results)
        member-states (:member-states profitability)
        senior-state (first (filter #(= (:tier %) :senior) member-states))
        junior-states (filter #(= (:tier %) :junior) member-states)]
    {:n-trials n-trials
     :ring-type (str (count junior-states) "-junior-ring")
     :ring-total-profit (double (:total-profit profitability))
     :ring-avg-profit-per-dispute (double (:average-profit-per-dispute profitability))
     :ring-catch-rate (double (:catch-rate profitability))
     :ring-viable? (:viable? profitability)
     :ring-senior-exhausted? (:senior-exhausted? profitability)
     :senior-bond-remaining (double (:bond-remaining senior-state))
     :senior-slashed-amount (double (:slashed-amount senior-state))
     :juniors-count (count junior-states)
     :juniors-avg-bond-remaining
     (double (if (empty? junior-states)
               0
               (/ (reduce + (map :bond-remaining junior-states))
                  (count junior-states))))
     :juniors-total-slashed (double (reduce + (map :slashed-amount junior-states)))
     :ring-profitable? (:ring-profitable? profitability)
     :ring-solvent? (:ring-solvent? profitability)}))
