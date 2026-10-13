(ns scripts.scenarios.speds-generate-comparator-shadow
  (:require [clojure.string :as str]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.issues :as issues]))

(defn- parse-strategy [s]
  (keyword (str/trim s)))

(defn -main [& args]
  (try
    (let [strategies-arg (first args)
          strategies (if (seq strategies-arg)
                       (->> (str/split strategies-arg #",")
                            (map parse-strategy)
                            vec)
                       [:nearest-baseline-by-id :matched-by-purpose :matched-by-tags])
          artifacts (data/load-run-artifacts)
          report (issues/save-comparator-shadow-report!
                  artifacts
                  {:strategies strategies
                   :enabled? true})]
      (println "Generated comparator shadow report:")
      (println "  path: results/test-artifacts/comparator-shadow.json")
      (println "  strategies:" (pr-str (:strategies report)))
      (doseq [r (:runs report)]
        (println "  -" (name (:strategy r))
                 "findings=" (:finding-count r)
                 "issues=" (:issue-count r))))
    (catch Exception e
      (println "Failed to generate comparator shadow report:" (.getMessage e))
      (System/exit 1))))
