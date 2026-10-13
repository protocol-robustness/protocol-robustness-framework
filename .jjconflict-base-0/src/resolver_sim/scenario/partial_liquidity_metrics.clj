(ns resolver-sim.scenario.partial-liquidity-metrics
  "Metrics for partial-liquidity / may-be-partially-deferred provider scenarios."
  (:require [resolver-sim.scenario.yield-provider-metrics :as ypm]))

(defn partial-liquidity-outcome-metrics
  "Summarize Y03-style provider replay for manifest and workbench cards."
  [replay-result scenario]
  (let [m (merge (:metrics replay-result)
                 (ypm/compute-provider-metrics replay-result scenario))
        outcome (:outcome replay-result)]
    {:yield/shortfall-outcome (if (and (= :pass outcome)
                                       (pos? (:yield/position-deferred m 0)))
                                :may-be-partially-deferred
                                (if (= :pass outcome) :fully-immediate :none))
     :yield/position-principal (:yield/position-principal m)
     :yield/position-realized (:yield/position-realized m)
     :yield/position-deferred (:yield/position-deferred m)
     :yield/position-reclaimed (:yield/position-reclaimed m)
     :pass? (= :pass outcome)}))
