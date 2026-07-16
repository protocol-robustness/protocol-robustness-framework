(ns resolver-sim.commands.scenario-extraction
  "Pure scenario-bundle extraction projections."
  (:require [clojure.string :as str]))

(defn normalize-replay [bundle]
  (if (= "bundle-root.v1" (:bundle/schema-version bundle))
    (let [overview (first (get-in bundle [:overview :results]))
          raw (first (:run/scenario-results bundle))]
      (assoc bundle :scenario-id (:scenario-id overview) :outcome (:outcome overview)
             :events-processed (count (:trace raw)) :source {:scenario-id (:scenario-id overview)}
             :trace (:trace raw) :metrics (:metrics raw) :world (:world raw)))
    bundle))

(defn- action [event] (or (:action event) (:event-type event) "?"))
(defn- actor [event] (or (:caller event) (:actor event) (:agent event)))
(defn trace-summary [replay provenance]
  (let [trace (vec (:trace replay))]
    {"schema_version" "trace-summary.v1" "scenario_id" (:scenario-id replay)
     "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) (count trace))
     "steps" (mapv (fn [index event] {"seq" (or (:seq event) index) "time" (or (:time event) (:block-time event))
                                       "actor" (actor event) "action" (action event) "result" (or (:result event) (:outcome event) "?")})
                    (range) trace)
     "derived_from" provenance}))

(defn metrics-summary [replay provenance]
  {"schema_version" "scenario-metrics.v1" "scenario_id" (get-in replay [:source :scenario-id])
   "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) 0)
   "metrics" (:metrics replay {}) "derived_from" provenance})

(defn world-final [replay provenance profile]
  (let [world (:world replay {})]
    {"schema_version" "world-final.v1" "scenario_id" (get-in replay [:source :scenario-id])
     "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) 0)
     "world" (if (= profile :public) world world) "derived_from" provenance}))

(defn plain-trace [replay]
  (str "# Plain Language Trace Summary\n\n"
       (str/join "\n" (map-indexed (fn [i event] (str (inc i) ". **" (action event) "**")) (:trace replay)))))
