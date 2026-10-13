(ns resolver-sim.accounting.reductions-test-runner
  (:require [clojure.test :as test]
            [resolver-sim.accounting.reductions-test]))

(defn -main [& _]
  (let [result (test/run-tests 'resolver-sim.accounting.reductions-test)]
    (System/exit (+ (:fail result) (:error result)))))
