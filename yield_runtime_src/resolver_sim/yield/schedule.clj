(ns resolver-sim.yield.schedule
  "Generic schedule lookup for yield parameters (APY, Index, Liquidity).
   Supports constant, steps, and external (JSON) schedules."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn- lookup-step
  "Find the value in a step-based schedule for the given time.
   Values is a vector of {:time t :value v} sorted by time."
  [values time default-value]
  (let [matches (filter #(<= (:time %) time) values)]
    (if (seq matches)
      (:value (last matches))
      default-value)))

(defn get-value-at-time
  "Resolve the value for a schedule at a specific time.
   - Constant: {:type :constant :value v}
   - Steps:    {:type :steps :values [{:time t :value v} ...]}
   - External: Handled by loading into one of the above during config application."
  [schedule time default-value]
  (case (:type schedule)
    :constant (:value schedule default-value)
    :steps    (lookup-step (:values schedule) time default-value)
    default-value))

(defn load-external-json
  "Load a yield schedule from an external JSON file."
  [path]
  (try
    (let [content (json/read-str (slurp (io/file path)) :key-fn keyword)]
      ;; Expecting the same schema as in-scenario schedules
      content)
    (catch Exception e
      (println (str "[yield-schedule] WARN: Failed to load external schedule from " path ": " (.getMessage e)))
      nil)))
