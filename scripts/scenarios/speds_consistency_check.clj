(ns scripts.scenarios.speds-consistency-check
  (:require [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.findings :as findings]
            [resolver-sim.notebook-support.speds.issues :as issues]
            [resolver-sim.notebook-support.speds.validation :as validation]))

(defn -main
  "Run SPEDS consistency checks and exit with code 0 on success, 1 on failure."
  [& _args]
  (try
    (let [artifacts (data/load-run-artifacts)
          findings-bundle (findings/generate-findings-bundle artifacts)
          issues-bundle (issues/generate-issues-bundle artifacts)
          report (validation/run-speds-consistency-checks
                  {:frames [{:header "sample"
                             :footer-left "left"
                             :footer-right "right"
                             :content [:div "ok"]
                             :claims [{:claim-id :sample
                                       :value "ok"
                                       :source-artifact "script"
                                       :source-path [:sample :path]}]}]
                    :files ["src/resolver_sim/notebook_support/speds/story.clj"
                           "notebooks/dispute_resolution.clj"
                           "notebooks/report.clj"
                           "notebooks/workbench_v2.clj"]
                   :artifacts artifacts
                   :findings (:findings findings-bundle)
                   :issues (:issues issues-bundle)
                   :claim-sources {:git-sha {:path [:summary :git_sha] :required? false}
                                   :coverage-scenarios {:path [:coverage :scenarios] :required? true}}})]
      (println "SPEDS consistency report:")
      (prn report)
      (let [errors (count (remove :passed (flatten (vals report))))]
        (if (pos? errors)
          (do (println (str errors " check(s) failed")) (System/exit 1))
          (println "All checks passed."))))
    (catch Exception e
      (println "SPEDS consistency check failed:" (.getMessage e))
      (System/exit 1))))
