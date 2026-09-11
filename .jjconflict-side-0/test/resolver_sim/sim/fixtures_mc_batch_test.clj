(ns resolver-sim.sim.fixtures-mc-batch-test
  (:require [clojure.test :refer :all]
            [resolver-sim.sim.fixtures :as fixtures]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.params :as params]
            [resolver-sim.stochastic.rng :as rng]))

(deftest mc-batch-from-protocol-params-only
  (testing "MC batch from a scenario that has only protocol-params"
    (let [scenario {:protocol-params {:resolver-fee-bps 150}
                    :scenario-id "test-mc-default"}
          mc-params (params/scenario->mc-params scenario)
          rng-inst  (rng/make-rng 42)
          result    (batch/run-batch rng-inst 50 mc-params)]
      (is (number? (:honest-mean result)))
      (is (number? (:malice-mean result)))
      (is (= 150 (:resolver-fee-bps mc-params)) "pp value survives"))))

(deftest mc-batch-override-wins
  (testing ":mc-params overrides protocol-params derived value"
    (let [scenario {:protocol-params {:resolver-fee-bps 150}
                    :mc-params {:resolver-fee-bps 250}
                    :scenario-id "test-mc-override"}
          mc-params (params/scenario->mc-params scenario)]
      (is (= 250 (:resolver-fee-bps mc-params))))))

(deftest mc-batch-runtime-override
  (testing "runtime n-trials overrides scenario default"
    (let [scenario  {:protocol-params {:resolver-fee-bps 150}
                     :scenario-id "test-mc-runtime"}
          mc-params (params/scenario->mc-params scenario)
          final     (merge mc-params {:n-trials 25 :rng-seed 99})
          rng-inst  (rng/make-rng 99)
          result    (batch/run-batch rng-inst 25 final)]
      (is (= 25 (:n-trials result))))))

(deftest mc-batch-reproducibility
  (testing "same scenario + same seed → same aggregate"
    (let [scenario  {:protocol-params {:resolver-fee-bps 150}
                     :scenario-id "test-mc-repro"}
          mc-params (params/scenario->mc-params scenario)
          r1 (rng/make-rng 42)
          r2 (rng/make-rng 42)]
      (let [agg1 (batch/run-batch r1 100 mc-params)
            agg2 (batch/run-batch r2 100 mc-params)]
        (is (= (:honest-mean agg1) (:honest-mean agg2)))
        (is (= (:malice-mean agg1) (:malice-mean agg2)))))))

(deftest mc-batch-by-scenario-id
  (testing "MC batch driven by invariant scenario ID"
    (let [result (fixtures/run-mc-batch-for-scenario
                  "s01-baseline-happy-path"
                  :n-trials 25 :rng-seed 42)]
      (is (number? (:honest-mean result)))
      (is (number? (:malice-mean result)))
      (is (= 25 (:n-trials result))))))

(deftest missing-scenario-id-throws
  (testing "Unknown scenario-id throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fixtures/run-mc-batch-for-scenario
                  "nonexistent-scenario"
                  :n-trials 10 :rng-seed 1)))))
