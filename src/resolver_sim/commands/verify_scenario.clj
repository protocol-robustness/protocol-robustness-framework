(ns resolver-sim.commands.verify-scenario
  (:require [clojure.string :as str]
            [resolver-sim.scenario.verify :as verify]))

(def ^:private check-labels
  {:schema_version "Supported assurance schema"
   :assurance_kind "Recognized assurance kind"
   :status "Assurance status passed"
   :run_id "Run ID match"
   :run_finalization_match "Run finalization hash match"
   :content_registry_match "Content registry hash match"
   :run_finalization_verified "Run finalization verified"
   :pre_assurance_registry_valid "Pre-assurance registry validation"
   :operator_identity_excluded "Operator identity excluded from scope"
   :runtime_isolation_excluded "Runtime isolation excluded from scope"
   :forensic_schema_version "Forensic claims schema version"
   :forensic_status "Forensic claims status deferred"
   :forensic_reason_code "Forensic reason code match"
   :forensic_integrity_ref "Forensic integrity reference match"})

(def ^:private check-evidence
  {:pre_assurance_registry_valid ["manifest/artifact-registry-validation.json"
                                  "manifest/final-artifact-registry.json"]
   :run_finalization_verified ["evidence/finalizations/run/evidence-finalization.json"]
   :run_finalization_match ["evidence/finalizations/run/evidence-finalization.json"
                            "manifest/canonical-integrity.json"]
   :content_registry_match ["evidence/content-registry.json"
                            "manifest/canonical-integrity.json"]
   :status ["manifest/canonical-integrity.json"]
   :schema_version ["manifest/canonical-integrity.json"]
   :assurance_kind ["manifest/canonical-integrity.json"]
   :operator_identity_excluded ["manifest/canonical-integrity.json"]
   :runtime_isolation_excluded ["manifest/canonical-integrity.json"]
   :forensic_schema_version ["manifest/forensic-claims-status.json"]
   :forensic_status ["manifest/forensic-claims-status.json"]
   :forensic_reason_code ["manifest/forensic-claims-status.json"]
   :forensic_integrity_ref ["manifest/forensic-claims-status.json"]})

(def ^:private check-failed-causes
  {:pre_assurance_registry_valid "final artifact registry validation did not pass"
   :run_finalization_verified "run finalization verification did not pass"
   :run_finalization_match "run finalization hash does not match committed value"
   :content_registry_match "content registry hash does not match committed value"
   :status "canonical-integrity assurance did not report passed status"
   :schema_version "unsupported or missing assurance schema version"
   :assurance_kind "unrecognized assurance kind"
   :operator_identity_excluded "operator identity is not excluded from scope"
   :runtime_isolation_excluded "runtime isolation is not excluded from scope"
   :forensic_schema_version "forensic claims schema version mismatch"
   :forensic_status "forensic claims status is not deferred"
   :forensic_reason_code "forensic reason code mismatch"
   :forensic_integrity_ref "forensic integrity reference mismatch"})

(defn- check-label
  [k]
  (get check-labels k (name k)))

(defn- passed?
  [v]
  (or (true? v) (= "passed" v) (= :passed v)))

(defn- count-passed
  [checks]
  (count (filter passed? (vals checks))))

(defn- ci-summary-line
  [integrity-checks]
  (let [total (count integrity-checks)
        n-pass (count-passed integrity-checks)
        n-fail (- total n-pass)
        status (if (zero? n-fail) "PASS" "FAIL")
        failed-names (when (pos? n-fail)
                       (->> integrity-checks
                            (keep (fn [[k v]] (when-not (passed? v) (name k))))
                            (str/join ",")))]
    (str "ASSURANCE pre-assurance " status " scope=canonical-integrity checks=" n-pass "/" total
         (when (pos? n-fail) (str " failed=" failed-names)))))

(defn run [{:keys [run-root]}]
  (if-not run-root
    {:exit-code 2 :message "Usage: verify-scenario --run-root DIR"}
    (let [result (verify/verify! run-root)
          status (get result "status")
          passed (= "passed" status)
          checks (get result "checks" {})
          integrity-valid? (true? (get checks "canonical-integrity"))
          integrity-checks (get checks "canonical-integrity-checks" {})
          total (count integrity-checks)
          n-pass (count-passed integrity-checks)]
      (println "Scenario verification:" (if passed "PASSED" "FAILED"))
      (println)
      (println "Assurance")
      (printf "  Level:                %s%n" (if integrity-valid? "PRE-ASSURANCE" "NOT-ESTABLISHED"))
      (printf "  Status:               %s %s%n" (if integrity-valid? "✓" "✗") (if integrity-valid? "ESTABLISHED" "NOT ESTABLISHED"))
      (printf "  Scope:                canonical-integrity%n")
      (printf "  Checks:               %d/%d passed%n" n-pass total)
      (println)
      (doseq [[k v] (sort integrity-checks)]
        (printf "  %s %s%n" (if (passed? v) "✓" "✗") (check-label k))
        (when-not (passed? v)
          (printf "       Cause: %s%n" (get check-failed-causes k ""))
          (let [ev (get check-evidence k)]
            (when (seq ev)
              (println "       Inspect:")
              (doseq [ref ev]
                (printf "         %s%n" ref))))))
      (println)
      (println "  Pre-assurance verifies the integrity and internal consistency of")
      (println "  this unsigned canonical package. Producer identity is not claimed.")
      (println "  See manifest/forensic-claims-status.json for signing and release")
      (println "  policy requirements.")
      (println)
      (println (ci-summary-line integrity-checks))
      {:exit-code (if passed 0 1)
       :result result})))
