(ns resolver-sim.util.attribution.logging
  "Logging helpers that include attribution context."
  (:require [resolver-sim.logging :as log]
            [resolver-sim.util.attribution.context :as context]))

(defn log-with-attr [level msg & [data]]
  (log/log! level msg (merge data (context/current-attribution))))

(defn log-annotated! [level msg ctx data]
  (log/log! level msg (merge data ctx)))
