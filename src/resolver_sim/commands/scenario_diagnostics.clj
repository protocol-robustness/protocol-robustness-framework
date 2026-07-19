(ns resolver-sim.commands.scenario-diagnostics
  "Compact reviewer-facing diagnostic projection for canonical scenario bundles."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.commands.run-lifecycle :as lifecycle]))

(defn- run-relative-path
  [context suffix]
  (let [run-root (io/file (str (or (:run/root context) (:scenario/root context))))
        scenario-root (io/file (str (:scenario/root context)))
        prefix (str (.relativize (.toPath run-root) (.toPath scenario-root)))]
    (str/replace (if (str/blank? prefix)
                   suffix
                   (str prefix "/" suffix))
                 "\\" "/")))

(defn write! [context execution]
  (let [root (:scenario/root context)
        trace-ref (run-relative-path context "summaries/trace-summary.json")
        metrics-ref (run-relative-path context "summaries/metrics.json")
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
                                       "trace_ref" trace-ref})
               "evidence" {"trace_ref" trace-ref
                            "metrics_ref" metrics-ref
                            "run_finalization_ref" "evidence/finalizations/run/evidence-finalization.json"}
                "assurance" {"kind" "pre-assurance"
                             "note" "Pre-assurance verifies unsigned canonical package integrity and internal consistency. It does not establish producer identity, signer trust, or forensic-release eligibility."
                             "verification_command" "verify-scenario --run-root <run-root>"
                             "evidence" {"canonical_integrity" "manifest/canonical-integrity.json"
                                         "forensic_claims_status" "manifest/forensic-claims-status.json"
                                         "verdict_policy" "manifest/verdict-policy.json"}}}
         target (io/file (str (:manifest/dir context)) "diagnostic-summary.json")]
    (lifecycle/atomic-json! target value)
    value))
