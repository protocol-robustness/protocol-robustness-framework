(ns dev.yield)

(defn run-shortfall
  []
  ((requiring-resolve 'resolver-sim.scenario.runner/run-scenario) :S103))

(defn explain-partial-fill
  [input]
  (let [f (requiring-resolve
           'resolver-sim.yield.partial-fill/calculate-fulfillment-pro-rata)
        result (f input)]
    (tap> {:type :yield/partial-fill
           :input input
           :result result})
    result))
