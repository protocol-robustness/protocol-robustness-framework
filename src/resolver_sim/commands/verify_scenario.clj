(ns resolver-sim.commands.verify-scenario
  (:require [resolver-sim.scenario.verify :as verify]))

(defn run [{:keys [run-root]}]
  (if-not run-root
    {:exit-code 2 :message "Usage: verify-scenario --run-root DIR"}
    (let [result (verify/verify! run-root)]
      (println "Scenario verification:" (get result "status"))
      {:exit-code (if (= "passed" (get result "status")) 0 1)
       :result result})))
