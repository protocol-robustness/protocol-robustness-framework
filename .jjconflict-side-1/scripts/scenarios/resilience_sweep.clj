(ns scripts.scenarios.resilience-sweep
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]))

(def fees [50 150 500])
(def severities [0.1 0.3 0.6 0.9])

(defn run-sweep []
  (println "Running resilience sweep...")
  (doseq [fee fees severity severities]
    (let [scenario-path "scenarios/edn/dynamic-sweep.edn"
          ;; Simple template modification
          scenario-content (format "{\"scenario-id\": \"sweep-%d-%s\", \"title\": \"Sweep\", \"protocol-params\": {\"resolver-fee-bps\": %d}, \"yield-config\": {\"modules\": {\"aave-v3\": {\"policy\": \"de-risking\"}}}, \"events\": [{\"seq\": 0, \"time\": 1000, \"action\": \"set-yield-risk\", \"params\": {\"module-id\": \"aave-v3\", \"shortfall\": {\"available-ratio\": %.1f}}}]}" 
                                   fee severity fee (- 1.0 severity))
          _ (spit scenario-path scenario-content)
          result (shell/sh "clojure" "-M:run" "--scenario" scenario-path "--invariants" "--output-file" "results/sweep.json")
          output (json/read-str (slurp "results/sweep.json") :key-fn keyword)]
      (printf "Fee: %d, Severity: %.1f -> TimeToCollapse: %s\n" 
              fee severity (get-in output [:cascade-report :time-to-collapse] "NONE")))))

(-main)
