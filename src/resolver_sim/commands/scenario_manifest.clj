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
    (spit temp (json/write-str value :indent true))
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
       (filter #(#{ "create_escrow" "yield_deposit"} (event-action %)))
       (keep (fn [event]
               (let [{:keys [token amount]} (:params event)]
                 (when (and (string? token) (number? amount) (not (neg? amount)))
                   [token amount]))))
       (reduce (fn [amounts [unit amount]] (update amounts unit (fnil + 0) amount)) {})
       (sort-by key)
       (mapv (fn [[unit amount]] {"unit" unit "amount" amount}))))

(defn- declared-available-ratio
  "Return the available-ratio declared by a set-yield-risk shortfall event, or nil."
  [snapshot]
  (some (fn [event]
          (when (= "set-yield-risk" (event-action event))
            (get-in event [:params :shortfall :available-ratio])))
        (:events snapshot)))

(defn- unit-total [declared]
  (reduce + (map #(get % "amount") declared)))

(defn- shortfall-projection
  "Given declared protected amounts and an optional available-ratio, return the
   available custody and shortfall (value-at-risk) for each affected unit.
   Returns {:available [...] :shortfall [...] :basis str}."
  [declared ratio]
  (if (and ratio (seq declared))
    (let [shortfall? (< ratio 1.0)
          projected (mapv (fn [entry]
                            (let [amount (get entry "amount")
                                  avail (long (Math/floor (* amount ratio)))
                                  short (- amount avail)]
                              {"unit" (get entry "unit") "amount" amount
                               "available" avail "shortfall" short}))
                          declared)]
      {:available (mapv #(select-keys % ["unit" "available"]) projected)
       :shortfall (mapv (fn [p] {"unit" (get p "unit") "amount" (get p "shortfall")})
                        projected)
       :basis (str "declared available-ratio " ratio " applied to protected amount")})
    {:available [] :shortfall [] :basis "no shortfall ratio declared; no loss projected"}))

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

(defn value-at-risk-projection
  "Conservative reviewer projection (pure). Declared protected amounts come from
   persisted create_escrow and yield_deposit input events; terminal custody comes
   from the replay world's :total-held map. When a scenario declares a liquidity
   shortfall (set-yield-risk :shortfall :available-ratio), the projection derives
   the available custody and the shortfall as the value-at-risk. It does not
   otherwise infer a monetary loss where no expected-loss model is declared."
  [snapshot world snapshot-ref]
  (let [declared (if snapshot (declared-protected-amounts snapshot) [])
        terminal-held (terminal-held-amounts world)
        ratio (declared-available-ratio snapshot)
        projection (shortfall-projection declared ratio)
        expected-loss (if (seq (:shortfall projection))
                        {"status" "declared-by-scenario"
                         "basis" (:basis projection)
                         "by_unit" (:shortfall projection)}
                        {"status" "not-declared-by-scenario"})
        observed-loss (if (seq (:shortfall projection))
                        {"status" "derived"
                         "basis" "protected amount minus projected available custody"
                         "by_unit" (:shortfall projection)}
                        {"status" "not-derived"
                         "note" "Terminal held custody is not, by itself, a loss measure."})]
    {"schema_version" "scenario-value-at-risk.v1"
     "status" (if snapshot "available" "input-snapshot-unavailable")
     "declared_protected_amount" {"basis" "sum of persisted create_escrow / yield_deposit event amounts"
                                  "by_unit" declared}
     "custody" {"before" {"basis" "declared protected amount; not an initial world-balance snapshot"
                          "by_unit" declared}
                "after" (if (seq (:available projection))
                          {"basis" (:basis projection)
                           "by_unit" (:available projection)}
                          {"basis" "terminal replay world total-held"
                           "by_unit" terminal-held})}
     "exposure" {"expected_loss" expected-loss
                 "observed_loss" observed-loss}
     "source_artifacts" (vec (remove nil?
                                     [{"ref" snapshot-ref "role" "declared protected amount"}
                                      {"ref" "state/world-final.json" "role" "terminal custody"}
                                      {"ref" "summaries/metrics.json" "role" "execution metrics"}]))}))

(defn value-at-risk-summary [execution]
  (value-at-risk-projection
   (read-snapshot execution)
   (get-in execution [:run-result :results 0 :world] {})
   (get-in execution [:input/provenance :input/snapshot-relative])))

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
        overview (value-at-risk-summary execution)
        value-at-risk-persisted (if (= "not-declared" (get value-at-risk "status"))
                                  overview
                                  value-at-risk)
        summary {"manifest" {"schema_version" "summary.v1"}
                 "run" {"id" (:run/id context) "overall_status" status
                        "outcome" {"status" status "exit_code" (:exit-code execution) "duration_ms" (:duration-ms execution 0)}}
                 "value_at_risk" value-at-risk-persisted
                 "value_at_risk_timeline_ref" "manifest/value-at-risk-timeline.json"
                 "value_at_risk_overview" overview}
        claimable {"schema_version" "claimable-classification.v2" "run_id" (:run/id context)}]
    (atomic-json! (io/file dir "run.json") run)
    (atomic-json! (io/file dir "summary.json") summary)
    (atomic-json! (io/file dir "value-at-risk.json") value-at-risk-persisted)
    (atomic-json! (io/file dir "value-at-risk-timeline.json") value-at-risk-timeline)
    (atomic-json! (io/file dir "claimable-classification.json") claimable)
    {:run run :summary summary :claimable claimable}))

(defn write-classification! [manifest-dir classification]
  (atomic-json! (io/file (str manifest-dir) "claimable-classification.json") classification)
  classification)
