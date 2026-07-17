(ns resolver-sim.commands.scenario-diagnostics
  "Compact reviewer-facing diagnostic projection for canonical scenario bundles."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.commands.run-lifecycle :as lifecycle]))

(defn write! [context execution]
  (let [root (:scenario/root context)
        trace-file (io/file (str root) "summaries/trace-summary.json")
        trace (json/read-str (slurp trace-file))
        steps (get trace "steps")
        ;; A completed semantic failure may contain earlier, expected guard
        ;; rejections. Prefer the terminal error/fail signal; use rejection
        ;; only when it is the strongest diagnostic available.
        result-label (fn [step] (some-> (get step "result") str str/lower-case))
        failing-step (or (first (filter #(contains? #{"error" "fail" "violated" "invariant-violated"} (result-label %)) steps))
                         (first (filter #(= "rejected" (result-label %)) steps)))
        outcome (if (zero? (:exit-code execution)) "pass" "fail")
        value {"schema_version" "scenario-diagnostic-summary.v1"
               "scenario_id" (get trace "scenario_id")
               ;; This phase runs only after the scenario bundle has been
               ;; successfully materialized. Semantic failure is recorded
               ;; independently so it cannot be mistaken for lifecycle failure.
               "execution_status" "completed"
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
