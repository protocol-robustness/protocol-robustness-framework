(ns resolver-sim.commands.scenario-manifest
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.commands.scenario-value-at-risk :as value-at-risk]
            [resolver-sim.config.paths :as paths])
  (:import [java.nio.file Files StandardCopyOption]))

(defn- atomic-json! [file value]
  (let [target (io/file file) temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str value))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn- read-json [file]
  (when (.isFile (io/file file))
    (json/read-str (slurp file))))

(defn- event-action [event]
  (let [action (:action event)]
    (if (keyword? action) (name action) (str action))))

(defn- declared-protected-amounts [snapshot]
  (->> (:events snapshot)
       (filter #(= "create_escrow" (event-action %)))
       (keep (fn [event]
               (let [{:keys [token amount]} (:params event)]
                 (when (and (string? token) (number? amount) (not (neg? amount)))
                   [token amount]))))
       (reduce (fn [amounts [unit amount]] (update amounts unit (fnil + 0) amount)) {})
       (sort-by key)
       (mapv (fn [[unit amount]] {"unit" unit "amount" amount}))))

(defn- terminal-held-amounts [world]
  (let [held (:total-held world)]
    (cond
      (map? held) (->> held
                       (keep (fn [[unit amount]]
                               (when (number? amount)
                                 {"unit" (if (keyword? unit) (name unit) (str unit))
                                  "amount" amount})))
                       (sort-by #(get % "unit"))
                       vec)
      :else [])))

(defn- read-snapshot [execution]
  (when-let [path (get-in execution [:input/provenance :input/snapshot])]
    (try (edn/read-string (slurp path))
         (catch Exception _ nil))))

(defn value-at-risk-summary
  "Conservative reviewer projection. Declared protected amounts come only from
   persisted create_escrow input events; terminal custody comes only from the
   replay world's :total-held map. It deliberately does not infer a monetary
   loss where a scenario has not declared an expected-loss model."
  [execution]
  (let [snapshot (read-snapshot execution)
        world (get-in execution [:run-result :results 0 :world] {})
        declared (if snapshot (declared-protected-amounts snapshot) [])
        terminal-held (terminal-held-amounts world)
        snapshot-ref (get-in execution [:input/provenance :input/snapshot-relative])]
    {"schema_version" "scenario-value-at-risk.v1"
     "status" (if snapshot "available" "input-snapshot-unavailable")
     "declared_protected_amount" {"basis" "sum of persisted create_escrow event amounts"
                                  "by_unit" declared}
     "custody" {"before" {"basis" "declared protected amount; not an initial world-balance snapshot"
                          "by_unit" declared}
                "after" {"basis" "terminal replay world total-held"
                         "by_unit" terminal-held}}
     "exposure" {"expected_loss" {"status" "not-declared-by-scenario"}
                 "observed_loss" {"status" "not-derived"
                                  "note" "Terminal held custody is not, by itself, a loss measure."}}
     "source_artifacts" (vec (remove nil?
                                     [{"ref" snapshot-ref "role" "declared protected amount"}
                                      {"ref" "state/world-final.json" "role" "terminal custody"}
                                      {"ref" "summaries/metrics.json" "role" "execution metrics"}]))}))

(defn write! [context execution]
  (let [dir (io/file (str (:manifest/dir context)))
        status (if (zero? (:exit-code execution)) "pass" "fail")
        enrichment (or (read-json (io/file dir "run-enrichment.json")) {})
        run (merge {"manifest" {"schema_version" "run-manifest.v1"}
                    "run" {"id" (:run/id context) "type" "scenario" "sensitivity_profile" (name (or (:sensitivity/profile context) :internal)) "status" (if (= status "pass") "complete" "failed")
                           "exit_code" (:exit-code execution) "duration_ms" (:duration-ms execution 0)}
                    "scenario" {"id" (:scenario/ref context) "path" (:scenario/ref context)}
                    "input" (when-let [input (:input/provenance execution)]
                              {"origin" (:input/origin input) "snapshot" (str "inputs/scenarios/" (.getName (io/file (:input/snapshot input))))
                               "sha256" (:input/sha256 input) "bytes" (:input/bytes input)})
                    "outcome" {"status" status "total" 1 "passed" (if (= status "pass") 1 0) "failed" (if (= status "pass") 0 1)}} enrichment)
        snapshot (read-snapshot execution)
        replay (get-in execution [:run-result :results 0] {})
        value-at-risk (value-at-risk/build-observation
                       snapshot replay (:input/provenance execution)
                       (str (paths/scenarios-root) "/" (:scenario/slug context) "/execution/replay-output.json"))
        value-at-risk-timeline (value-at-risk/value-at-risk-timeline
                                snapshot replay
                                (str (paths/scenarios-root) "/" (:scenario/slug context) "/execution/replay-output.json"))
        value-at-risk-validation (value-at-risk/validate-persisted
                                  value-at-risk snapshot replay (:input/provenance execution)
                                  (str (paths/scenarios-root) "/" (:scenario/slug context) "/execution/replay-output.json"))
        _ (when (and (not= "not-declared" (get value-at-risk "status"))
                     (not= "pass" (get value-at-risk-validation "status")))
            (throw (ex-info "Declared value-at-risk observation failed validation"
                            {:code :value-at-risk/invalid-observation
                             :reasons (get value-at-risk-validation "reason_codes")})))
        summary {"manifest" {"schema_version" "summary.v1"}
                 "run" {"id" (:run/id context) "overall_status" status
                        "outcome" {"status" status "exit_code" (:exit-code execution) "duration_ms" (:duration-ms execution 0)}}
                 "value_at_risk" value-at-risk
                 "value_at_risk_timeline_ref" "manifest/value-at-risk-timeline.json"
                 "value_at_risk_overview" (value-at-risk-summary execution)}
        claimable {"schema_version" "claimable-classification.v2" "run_id" (:run/id context)}]
    (atomic-json! (io/file dir "run.json") run)
    (atomic-json! (io/file dir "summary.json") summary)
    (atomic-json! (io/file dir "value-at-risk.json") value-at-risk)
    (atomic-json! (io/file dir "value-at-risk-timeline.json") value-at-risk-timeline)
    (atomic-json! (io/file dir "claimable-classification.json") claimable)
    {:run run :summary summary :claimable claimable}))

(defn write-classification! [manifest-dir classification]
  (atomic-json! (io/file (str manifest-dir) "claimable-classification.json") classification)
  classification)
