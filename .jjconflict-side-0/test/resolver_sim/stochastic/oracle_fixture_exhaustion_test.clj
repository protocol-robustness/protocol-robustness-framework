(ns resolver-sim.stochastic.oracle-fixture-exhaustion-test
  "Exhaustion policies (:repeat-last, :cycle) and replay boundary guards."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.invariant-scenarios.baseline :as baseline]
            [resolver-sim.stochastic.detection :as det]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.rng :as rng]))

(defn- prepare-fixed
  [fixture]
  (det/prepare-oracle-params
   {:oracle-fixture (assoc fixture :mode :fixed-roll-sequence :scope #{:detection})}))

(defn- replay-equivalence-fields
  [result]
  (select-keys result [:outcome :halt-reason :events-processed]))

(deftest fixed-roll-repeat-last-exhaustion-test
  (testing ":repeat-last repeats final value and marks exhaustion"
    (let [params (prepare-fixed {:rolls [0.1 0.9] :on-exhaustion :repeat-last})
          r1 (det/oracle-roll-event params :fraud-detection)
          r2 (det/oracle-roll-event params :fraud-detection)
          r3 (det/oracle-roll-event params :fraud-detection)]
      (is (= 0.1 (:roll/value r1)))
      (is (= 0.9 (:roll/value r2)))
      (is (= 0.9 (:roll/value r3)))
      (is (:roll/exhausted? r3))
      (is (= :repeat-last (:roll/on-exhaustion r3)))
      (is (= 1 (:roll/repeated-index r3))))))

(deftest fixed-roll-cycle-exhaustion-test
  (testing ":cycle wraps with exhaustion metadata after sequence end"
    (let [params (prepare-fixed {:rolls [0.1 0.9] :on-exhaustion :cycle})
          events [(det/oracle-roll-event params :fraud-detection)
                  (det/oracle-roll-event params :fraud-detection)
                  (det/oracle-roll-event params :fraud-detection)
                  (det/oracle-roll-event params :fraud-detection)]]
      (is (= [0.1 0.9 0.1 0.9] (map :roll/value events)))
      (is (:roll/exhausted? (last events)))
      (is (= :cycle (:roll/on-exhaustion (last events)))
          (= 1 (:roll/cycled-index (last events)))))))

(deftest collect-warnings-repeat-last-evidence-quality
  (testing "evidence-quality + exhausted :repeat-last yields :error warning"
    (let [params (prepare-fixed {:rolls [0.5] :on-exhaustion :repeat-last})
          _ (det/oracle-roll-event params :fraud-detection)
          _ (det/oracle-roll-event params :fraud-detection)
          warnings (det/collect-oracle-fixture-warnings
                    params {:evidence-quality? true})]
      (is (some #(= :error (:level %)) warnings))
      (is (some #(= :oracle-fixture-repeat-last (:code %)) warnings)))))

(deftest replay-ignores-oracle-fixture-exhaustion-test
  (testing "oracle fixture on :protocol-params does not change replay outcome"
    (let [base baseline/s02
          scenario-b (update base :protocol-params merge
                             {:oracle-fixture {:mode :fixed-roll-sequence
                                               :rolls [0.0]
                                               :on-exhaustion :repeat-last}})
          replay-a (replay/replay-with-protocol sew/protocol base)
          replay-b (replay/replay-with-protocol sew/protocol scenario-b)]
      (is (= (replay-equivalence-fields replay-a)
             (replay-equivalence-fields replay-b))))))

(deftest mc-trial-surfaces-exhaustion-warnings
  (testing "resolve-dispute returns exhaustion flag and warnings"
    (let [rng (rng/make-rng 99)
          result (dispute/resolve-dispute
                  rng 10000 150 700 2.5 :malicious 0.05 0.99 0.1
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls [0.0 0.0]
                                   :scope #{:detection}
                                   :on-exhaustion :repeat-last}
                  :reversal-detection-probability 0.5
                  :reversal-slash-bps 2500
                  :evidence-quality? true)]
      (is (vector? (:oracle-fixture/warnings result)))
      (is (boolean? (:oracle-fixture/exhausted? result))))))
