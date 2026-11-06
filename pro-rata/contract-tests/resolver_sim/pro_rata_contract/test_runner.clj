(ns resolver-sim.pro-rata-contract.test-runner
  (:require [clojure.test :as test]
            [resolver-sim.pro-rata-contract.contract-test]))

(defn -main [& _]
  (let [result (test/run-tests 'resolver-sim.pro-rata-contract.contract-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
