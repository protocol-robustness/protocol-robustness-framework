(ns resolver-sim.io.trace-score
  "I/O-layer façade for trace scoring — delegates to pure sim.trace-score."
  (:require [resolver-sim.sim.trace-score :as sim-ts]))

(defn score-result
  [result]
  (sim-ts/score-result result))

(defn score-category
  [scored-result]
  (sim-ts/score-category scored-result))

(defn select-top-n
  [n scored-results]
  (sim-ts/select-top-n n scored-results))

(defn select-top-percentile
  [p scored-results]
  (sim-ts/select-top-percentile p scored-results))
