(ns resolver-sim.research.sew.adversarial.collusion-ring-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.sew.adversarial.collusion-ring :as collusion-ring]))

(deftest ring-scenario-is-deterministic-without-runner-registration
  (let [params {:rng-seed 42 :n-trials 2 :escrow-size 1000}
        run (fn [] (collusion-ring/run-ring-scenario params 1 0.1 5000))
        first-run (run)
        second-run (run)]
    (is (= first-run second-run))
    (is (= 2 (:n-trials first-run)))
    (is (= 1 (:junior-count first-run)))
    (is (number? (:ring-total-profit first-run)))
    (is (contains? first-run :ring-profitable?))))
