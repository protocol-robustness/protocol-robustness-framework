(ns resolver-sim.commands.scenario-diagnostics
  "Compact reviewer-facing diagnostic projection for canonical scenario bundles."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]))

(defn write! [context execution]
  (let [root (:scenario/root context)
        trace-file (io/file (str root) "summaries/trace-summary.json")
        trace (json/read-str (slurp trace-file))
        failing-step (first (filter #(contains? #{"rejected" "error" "fail"} (get % "result"))
                                    (get trace "steps")))
        outcome (if (zero? (:exit-code execution)) "pass" "fail")
        value {"schema_version" "scenario-diagnostic-summary.v1"
               "scenario_id" (get trace "scenario_id")
               "execution_status" (if (= outcome "pass") "completed" "failed")
               "semantic_outcome" outcome
               "primary_diagnostic" (when failing-step
                                      {"classification" (get failing-step "result")
                                       "event" (select-keys failing-step ["seq" "time" "actor" "action"])
                                       "trace_ref" "summaries/trace-summary.json"})
               "evidence" {"trace_ref" "summaries/trace-summary.json"
                           "metrics_ref" "summaries/metrics.json"
                           "run_finalization_ref" "evidence/finalizations/run/evidence-finalization.json"}}
        target (io/file (str (:manifest/dir context)) "diagnostic-summary.json")]
    (lifecycle/atomic-json! target value)
    value))
