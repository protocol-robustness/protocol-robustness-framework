(ns resolver-sim.commands.verify-benchmark
  (:require [resolver-sim.benchmark.verify :as verify]))

(defn run [{:keys [run-root]}]
  (if-not run-root
    {:exit-code 2 :message "Usage: verify-benchmark --run-root DIR"}
    (try
      (let [result (verify/verify! run-root)]
        (println "Benchmark verification:" (get result "status"))
        {:exit-code (if (= "passed" (get result "status")) 0 1) :result result})
      (catch Exception error
        {:exit-code 4 :message (.getMessage error)}))))
