(ns resolver-sim.generators.equilibrium-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.generators.equilibrium :as geq]))

(deftest evaluate-generated-scenario-shape
  (let [r (geq/evaluate-generated-scenario {:seed 77 :max-steps 6 :profile :phase1-lifecycle})]
    (is (= 77 (:seed r)))
    (is (contains? r :mechanism-status))
    (is (contains? r :equilibrium-status))
    (is (map? (:metrics r)))
    (is (map? (:payoff r)))
    (is (contains? (:payoff r) :basis))
    (is (contains? (:payoff r) :honest))
    (is (contains? (:payoff r) :malicious))
    (is (keyword? (:convergence r)))))

(deftest evaluate-generated-batch-summary
  (let [b (geq/evaluate-generated-batch {:start-seed 1 :n 5 :max-steps 6 :profile :timeout-boundary})
        s (:summary b)]
    (is (= [1 5] (:seed-range b)))
    (is (= 5 (count (:runs b))))
    (is (map? (:outcome-counts s)))
    (is (map? (:liveness s)))
    (is (map? (:payoffs s)))
    (is (map? (:payoff-basis-counts s)))
    (is (map? (:convergence-counts s)))
    (is (map? (:mechanism-status-counts s)))
    (is (map? (:equilibrium-status-counts s)))))
