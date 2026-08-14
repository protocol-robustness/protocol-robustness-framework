(ns resolver-sim.commands.verify-benchmark
  (:require [clojure.string :as str]
            [resolver-sim.benchmark.verify :as verify]
            [resolver-sim.io.paths :as paths]))

(def ^:private check-labels
  {"completion-first-package-index" "Completion-bound package index and role closure"
   "completion-finalization-hash" "Completion finalization hash"
   "completion-lifecycle" "Completion lifecycle status"
   "completion-bundle-root" "Completion semantic bundle-root binding"
   "completion-artifact-set-root" "Completion artifact-set binding"
   "completion-closure-commitment" "Completion closure commitment"
   "completion-semantic-outcome" "Completion semantic outcome"
   "completion-registry-hash" "Completion artifact registry hash"
   "completion-validation-hash" "Completion registry validation hash"
   "evidence-content-registry-hash" "Evidence content registry hash"
   "content-registry-recalculated" "Evidence content registry recalculated"
   "artifact-registry-recalculated" "Artifact registry recalculated"
   "execution-plan-index-closure" "Execution plan and index closure"
   "one-round-canonical-work-closure" "One-round canonical work closure"
   "input-set-root" "Input-set root consistency"
   "input-set-recalculated" "Input-set recalculated"
   "conservation-assurance" "Conservation assurance binding"
   "conservation-recalculated" "Conservation recalculated"
   "canonical-integrity" "Canonical integrity assurance"
   "forensic-status-deferred" "Forensic status correctly deferred"
   "verdict-policy" "Verdict policy verification"
   "final-ref" "Final reference recalculated"
   "terminal-artifacts-readable" "Terminal artifacts readable"})

(def ^:private check-evidence
  {"completion-first-package-index" [paths/completion paths/run-package-index]
   "completion-finalization-hash" [paths/completion "benchmark/finalization.json"]
   "completion-lifecycle" [paths/completion]
   "completion-bundle-root" [paths/completion "manifest/run-package-index.json" "benchmark/evidence/evidence.edn"]
   "completion-artifact-set-root" [paths/completion "benchmark/evidence/content-registry.json"]
   "completion-closure-commitment" [paths/completion "benchmark/evidence/evidence.edn"]
   "completion-semantic-outcome" [paths/completion "benchmark/assertions/benchmark-assurance.json"]
   "completion-registry-hash" [paths/completion paths/artifacts-registry]
   "completion-validation-hash" [paths/completion paths/artifacts-validation]
   "evidence-content-registry-hash" ["benchmark/finalization.json" "benchmark/evidence/content-registry.json"]
   "content-registry-recalculated" ["benchmark/evidence/content-registry.json"]
   "artifact-registry-recalculated" [paths/artifacts-registry]
   "execution-plan-index-closure" ["benchmark/execution-plan.edn" "benchmark/index.edn"]
   "one-round-canonical-work-closure" ["benchmark/evidence/evidence.edn" "benchmark/execution-plan.edn" "benchmark/index.edn"]
   "input-set-root" ["benchmark/assertions/benchmark-assurance.json" "benchmark/finalization.json" paths/completion]
   "input-set-recalculated" ["benchmark/assertions/benchmark-assurance.json"]
   "conservation-assurance" ["benchmark/assertions/benchmark-assurance.json" "benchmark/assertions/conservation.json"]
   "conservation-recalculated" ["benchmark/assertions/conservation.json"]
   "canonical-integrity" ["benchmark/assertions/canonical-integrity.json"]
   "forensic-status-deferred" ["benchmark/assertions/forensic-claims-status.json"]
   "verdict-policy" ["manifest/verdict-policy.json"]
   "final-ref" ["benchmark/finalization.json" paths/completion]})

(defn- check-label [check]
  (get check-labels check (-> check (str/replace #"-" " ") str/capitalize)))

(defn- print-checks! [checks]
  (doseq [[check passed?] (sort-by key checks)]
    (printf "  %s %s%n" (if passed? "✓" "✗") (check-label check))
    (when-not passed?
      (when-let [evidence (seq (get check-evidence check))]
        (println "       Inspect:")
        (doseq [path evidence]
          (printf "         %s%n" path))))))

(defn run [{:keys [run-root]}]
  (if-not run-root
    {:exit-code 2 :message "Usage: verify-benchmark --run-root DIR"}
    (try
      (let [result (verify/verify! run-root)
            passed (= "passed" (get result "status"))
            checks (get result "checks" {})]
        (println "Benchmark verification:" (if passed "PASSED" "FAILED"))
        (when (seq checks)
          (println)
          (println "Checks")
          (print-checks! checks))
        {:exit-code (if passed 0 1) :result result})
      (catch Exception error
        {:exit-code 4 :message (.getMessage error)}))))
