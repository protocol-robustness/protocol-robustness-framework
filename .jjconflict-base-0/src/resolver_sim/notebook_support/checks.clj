(ns resolver-sim.notebook-support.checks
  "Shape checks and schema validation for loaded notebook artifacts.
   Uses Malli for concise data-shape declarations at notebook boundaries."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def GoldenReport
  [:map
   [:golden-schema-version {:optional true} string?]
   [:trace-id string?]
   [:suite-id keyword?]
   [:outcome keyword?]
   [:metrics [:map
              [:invariant-violations int?]
              [:attack-successes int?]
              [:reverts int?]
              [:resolutions-executed int?]]]])

(def TraceMetadata
  [:map
   [:scenario-id string?]
   [:title {:optional true} string?]
   [:purpose string?]
   [:threat-tags {:optional true} [:vector string?]]])

(def TestSummary
  [:map
   [:overall_status {:optional true} string?]
   [:run_id {:optional true} string?]
   [:acceptance_decision {:optional true} string?]
   [:status_counts {:optional true} map?]
   [:risk_digest {:optional true} [:maybe map?]]])

(def BatchResult
  [:map
   [:n-trials int?]
   [:slash-rate double?]
   [:fraud-slashed-count int?]
   [:honest-mean double?]
   [:malice-mean double?]
   [:strategy keyword?]])

(defn assert-shape!
  "Validate data against a Malli schema. Throws with a descriptive message on failure."
  ([schema value]
   (assert-shape! "Data shape check failed" schema value))
  ([label schema value]
   (when-not (m/validate schema value)
     (throw
      (ex-info (str label ": " (me/humanize (m/explain schema value)))
               {:schema schema
                :value  value
                :errors (m/explain schema value)})))
   value))

(defn assert-batch-shape!
  "Validate a batch result map against the expected shape."
  [result]
  (assert-shape! "Batch result" BatchResult result))

(defn assert-golden-report!
  "Validate a single golden report map."
  [report]
  (assert-shape! "Golden report" GoldenReport report))

(defn assert-golden-reports!
  "Validate all golden reports in a map keyed by trace-id.
   Returns reports (or nil) unchanged for chaining."
  [reports]
  (when reports
    (doseq [[k v] reports]
      (try
        (assert-golden-report! v)
        (catch Exception e
          (throw (ex-info (str "Golden report " (pr-str k) " failed validation")
                          {:trace-id k :error (.getMessage e)}))))))
  reports)

(defn assert-trace-metadata!
  "Validate a single trace metadata map."
  [trace]
  (assert-shape! "Trace metadata" TraceMetadata trace))

(defn assert-test-summary!
  "Validate the test-summary artifact."
  [summary]
  (when summary
    (assert-shape! "Test summary" TestSummary summary))
  summary)
