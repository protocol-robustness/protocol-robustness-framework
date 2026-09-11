(ns scripts.scenarios.speds-generate-issues
  (:require [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.findings :as findings]
            [resolver-sim.notebook-support.speds.issues :as issues]))

(defn -main
  "Generate SPEDS findings and issues bundles from run artifacts.
   Exits with code 0 on success, 1 on failure."
  [& _args]
  (try
    (let [artifacts (data/load-run-artifacts)
          findings-bundle (findings/save-findings! artifacts)
          bundle (issues/save-issues! artifacts)]
      (println "Generated findings bundle:")
      (println "- path:" findings/findings-path)
      (println "- run:" (get-in findings-bundle [:run :run_id]))
      (println "- finding-count:" (count (:findings findings-bundle)))
      (println "Generated issues bundle:")
      (println "- path:" issues/issues-path)
      (println "- run:" (:run/id bundle))
      (println "- issue-count:" (:issue-count bundle)))
    (catch Exception e
      (println "Failed to generate SPEDS artifacts:" (.getMessage e))
      (System/exit 1))))
