(ns resolver-sim.commands.scenario-extraction
  "Pure scenario-bundle extraction projections."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
                        [resolver-sim.protocols.sew.claimable-classification :as claimable])
  (:import [java.nio.file Files StandardCopyOption]))

(defn normalize-replay [bundle]
  (if (= "bundle-root.v1" (:bundle/schema-version bundle))
    (let [results (get-in bundle [:overview :results])
          raw-results (:run/scenario-results bundle)
          _ (when (not= 1 (count results))
              (throw (ex-info "Structured scenario extraction requires exactly one scenario result" {:count (count results)})))
          _ (when (not= 1 (count raw-results))
              (throw (ex-info "Structured scenario extraction requires exactly one raw scenario result" {:count (count raw-results)})))
          overview (first results)
          raw (first raw-results)]
      (assoc bundle :scenario-id (:scenario-id overview) :outcome (:outcome overview)
             :events-processed (count (:trace raw)) :source {:scenario-id (:scenario-id overview)}
             :trace (:trace raw) :metrics (:metrics raw) :world (:world raw)))
    bundle))

(defn- action [event] (or (:action event) (:event-type event) "?"))
(defn- action-name [event] (let [value (action event)] (if (keyword? value) (name value) (str value))))
(defn- actor [event] (or (:caller event) (:actor event) (:agent event)))
(defn trace-summary [replay provenance]
  (let [trace (vec (:trace replay))]
    {"schema_version" "trace-summary.v1" "scenario_id" (:scenario-id replay)
     "scenario_title" (or (get-in replay [:source :description]) (get-in replay [:source :scenario-id]))
     "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) (count trace))
     "steps" (mapv (fn [index event] (let [seq (or (:seq event) index) action (action-name event)]
                                        {"seq" seq "time" (or (:time event) (:block-time event))
                                         "actor" (actor event) "action" action "result" (or (:result event) (:outcome event) "?")
                                         "evidence_refs" [(format "evidence/events/%03d-%s.json" seq action)]}))
                    (range) trace)
     "derived_from" provenance}))

(defn- metric [metrics name default] (get metrics (keyword name) default))
(defn- available-ratio [world]
  (some (fn [module-data]
          (some (fn [token-data] (get-in token-data [:shortfall :available-ratio]))
                (vals (or module-data {}))))
        (vals (or (:yield/risk world) {}))))

(defn metrics-summary [replay provenance]
  (let [metrics (:metrics replay {})]
    {"schema_version" "scenario-metrics.v1" "scenario_id" (get-in replay [:source :scenario-id])
     "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) 0)
     "metrics" {"escrow_unrealized" (metric metrics "escrow-unrealized" 0)
                "escrow_realized" (metric metrics "escrow-realized" 0)
                "invariant_violations" (metric metrics "invariant-violations" 0)
                "attack_attempts" (metric metrics "attack-attempts" 0)
                "claimable" (metric metrics "claimable" 0)
                "batch_conflicts" (metric metrics "batch-conflicts" 0)
                "available_ratio" (or (metric metrics "available-ratio" nil) (available-ratio (:world replay)))}
     "derived_from" provenance}))

(def ^:private sensitive-key? #"(?i)(api[_-]?key|password|secret|private[_-]?key|access[_-]?token|authorization)")
(def ^:private sensitive-value? #"(?i)(-----BEGIN (?:RSA |EC |OPENSSH |)?PRIVATE KEY-----|authorization:\s*bearer\s+)")

(defn- public-world [value]
  (cond
    (map? value) (into {} (keep (fn [[key item]]
                                  (when-not (re-find sensitive-key? (name key))
                                    [key (public-world item)])) value))
    (vector? value) (mapv public-world value)
    (seq? value) (mapv public-world value)
    (and (string? value) (re-find sensitive-value? value)) "[REDACTED]"
    :else value))

(defn world-final
  ([replay provenance profile] (world-final replay provenance profile (:world replay {})))
  ([replay provenance profile world]
   {"schema_version" "world-final.v1" "scenario_id" (get-in replay [:source :scenario-id])
    "outcome" (or (:outcome replay) "unknown") "events_processed" (or (:events-processed replay) 0)
    "sensitivity_profile" (name profile)
    "world" (if (= profile :public) (public-world world) world) "derived_from" provenance}))

(def ^:private schema-map
  {"mechanism-summary.v1" {"description" "High-level summary of simulation mechanism outcomes (escrow, disputes, slashing)."
                            "fields" {"scenario_id" "Unique identifier of the scenario." "outcome" "Final simulation outcome (pass/fail)."
                                      "escrow" "Aggregated stats on escrow lifecycles." "dispute" "Aggregated stats on dispute resolutions."
                                      "slashing" "Aggregated stats on slashing actions." "claimable" "Summary of claimable fund classifications."
                                      "temporal" "Temporal statistics (steps, consistency)."}}
   "scenario-metrics.v1" {"description" "Raw numeric metrics collected during simulation."
                           "fields" {"scenario_id" "Unique identifier of the scenario." "metrics" "Detailed numeric metrics."}}})

(defn mechanism-summary [replay provenance]
  (let [trace (:trace replay []) metrics (:metrics replay {})
        count-action (fn [target] (count (filter #(= target (action-name %)) trace)))]
    {"schema_version" "mechanism-summary.v1" "scenario_id" (get-in replay [:source :scenario-id])
     "outcome" (or (:outcome replay) "unknown")
     "escrow" {"created" (count-action "create_escrow") "released" (count-action "release_escrow")
               "disputed" (count-action "raise_dispute")
               "finalized" (+ (count-action "release_escrow") (count-action "release_claimable") (count-action "finalize_claimable"))}
     "dispute" {"raised" (count-action "raise_dispute") "resolved" (count-action "execute_resolution")
                "appealed" (count-action "escalate_dispute") "outcome" (:outcome replay)}
     "slashing" {"attempts" (count-action "propose_fraud_slash") "executed" (count-action "execute_fraud_slash")
                 "unmet_obligations" (metric metrics "unrealized" 0)}
     "claimable" {"total_claimable" (metric metrics "claimable" 0) "stale_claimables" 0}
     "temporal" {"steps" (count trace) "clock_mode" "discrete-step"
                 "consistent" (every? #(or (:time %) (:block-time %)) trace)}
     "derived_from" provenance}))

(defn extraction-schema-map [provenance]
  {"schema_version" "schema-map.v1" "map" schema-map "derived_from" provenance})

(defn claimable-classification [replay run-id]
  (let [world (:world replay {})
        scenario-id (get-in replay [:source :scenario-id])]
    (claimable/build-document
     :worlds [world]
     :contexts [{:world world :scenario-id scenario-id :outcome (:outcome replay)}]
     :scope (str "scenario/" scenario-id)
     :scenarios-passed (if (= :pass (:outcome replay)) 1 0)
     :observations-status "single-scenario"
     :aggregation "single-terminal-world"
     :provenance {:run_id run-id :producer "scenario-extraction.clj"})))

(defn plain-trace [replay]
  (str "# Plain Language Trace Summary\n\n"
       (str/join "\n" (map-indexed (fn [i event] (str (inc i) ". **" (action event) "**")) (:trace replay)))))

(defn- atomic-write! [file content]
  (let [target (io/file file) temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp content)
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn write-basic-projections!
  ([scenario-root replay provenance profile run-id]
   (write-basic-projections! scenario-root replay provenance profile run-id (:world replay {})))
  ([scenario-root replay provenance profile run-id raw-world]
  (let [root (io/file (str scenario-root))
        classification (claimable-classification replay run-id)
        write-json (fn [relative value] (atomic-write! (io/file root relative) (json/write-str value)))]
    (write-json "summaries/trace-summary.json" (trace-summary replay provenance))
    (write-json "summaries/metrics.json" (metrics-summary replay provenance))
    (write-json "summaries/claimable-classification.json" classification)
    (write-json "summaries/mechanism-summary.json" (mechanism-summary replay provenance))
    (write-json "summaries/schema-map.json" (extraction-schema-map provenance))
    (write-json "state/world-final.json" (world-final replay provenance profile raw-world))
    (atomic-write! (io/file root "summaries/trace-plain.md") (plain-trace replay))
    {:classification classification
     :written ["summaries/trace-summary.json" "summaries/metrics.json"
               "summaries/claimable-classification.json" "summaries/mechanism-summary.json"
               "summaries/schema-map.json" "state/world-final.json" "summaries/trace-plain.md"]})))

(defn extract! [context]
  (let [replay-file (io/file (str (:replay/file context)))
        source (slurp replay-file)
        raw (json/read-str source)
        replay (normalize-replay (json/read-str source :key-fn keyword))
        raw-world (if (= "bundle-root.v1" (get raw "bundle/schema-version"))
                    (get-in raw ["run/scenario-results" 0 "world"] {})
                    (get raw "world" {}))
        provenance {"path" (str (:replay/file context))}
        result (write-basic-projections! (:scenario/root context) replay provenance
                                         (:sensitivity/profile context) (:run/id context) raw-world)]
    result))
