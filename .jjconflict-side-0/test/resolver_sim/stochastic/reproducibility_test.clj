(ns resolver-sim.stochastic.reproducibility-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.detection :as det]
            [resolver-sim.stochastic.correlated-failures :as corr]
            [resolver-sim.stochastic.liveness-failures :as liveness]
            [resolver-sim.oracle.detection :as detection]))

(deftest seeded-correlated-failures-are-deterministic
  (testing "shared-bias-effect with same rng seed yields identical decisions"
    (let [rng-a (rng/make-rng 99)
          rng-b (rng/make-rng 99)
          run   (fn [r] (corr/shared-bias-effect 0.4 true 5 0.6 r))]
      (is (= (:decisions (run rng-a))
             (:decisions (run rng-b)))))))

(deftest seeded-boredom-threshold-is-deterministic
  (testing "boredom-threshold with same rng seed yields identical participation"
    (let [rng-a (rng/make-rng 7)
          rng-b (rng/make-rng 7)
          run   (fn [r] (liveness/boredom-threshold 0.2 50 10 0.2 r))]
      (is (= (:will-participate? (run rng-a))
             (:will-participate? (run rng-b)))))))

(deftest seeded-oracle-detection-is-deterministic
  (testing "detect-fraud with :rng in params is reproducible"
    (let [base {:fraud-detection-probability 0.5
                :reversal-detection-probability 0.25
                :timeout-detection-probability 0.02}
          p1 (assoc base :rng (rng/make-rng 12345))
          p2 (assoc base :rng (rng/make-rng 12345))]
      (is (= (detection/detect-fraud p1)
             (detection/detect-fraud p2))))))

(deftest phase-i-oracle-uses-seeded-rng
  (testing "PhaseIOracle detect-fraud? respects :rng in params"
    (let [oracle (detection/->PhaseIOracle)
          params {:fraud-detection-probability 0.99
                  :rng (rng/make-rng 1)}
          params' (assoc params :rng (rng/make-rng 1))]
      (is (= (detection/detect-fraud? oracle nil params)
             (detection/detect-fraud? oracle nil params'))))))

(deftest oracle-fixture-static-no-slash-suppresses-detection
  (testing ":static-no-slash forces detection rolls to fail"
    (let [params {:rng (rng/make-rng 7)
                  :oracle-fixture {:mode :static-no-slash}
                  :fraud-detection-probability 1.0
                  :timeout-detection-probability 1.0}
          out (detection/detect-probabilistic-violations params :malicious false 1.0)]
      (is (false? (:fraud-detected? out)))
      (is (false? (:timeout-detected? out)))
      (is (false? (:l1-slashed? out))))))

(deftest oracle-fixture-fixed-sequence-consumes-rolls-in-order
  (testing ":fixed-roll-sequence consumes rolls in call order"
    (let [params {:rng (rng/make-rng 42)
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls [0.90 0.01 0.50]
                                   :scope #{:detection}
                                   :on-exhaustion :throw}
                  :oracle-roll-cursor (atom 0)
                  :fraud-detection-probability 0.1
                  :timeout-detection-probability 0.1}
          out (detection/detect-probabilistic-violations params :malicious false 0.1)]
      (is (false? (:fraud-detected? out)))
      (is (true? (:timeout-detected? out)))
      (is (false? (:l1-slashed? out))))))

(deftest fixed-or-shorthand-vector-consumes-rolls-in-order
  (testing ":fixed-or [rolls...] is equivalent to :fixed-roll-sequence"
    (let [params {:rng (rng/make-rng 42)
                  :fixed-or [0.90 0.01 0.50]
                  :oracle-roll-cursor (atom 0)
                  :fraud-detection-probability 0.1
                  :timeout-detection-probability 0.1}
          out (detection/detect-probabilistic-violations params :malicious false 0.1)]
      (is (false? (:fraud-detected? out)))
      (is (true? (:timeout-detected? out)))
      (is (false? (:l1-slashed? out))))))

(deftest fixed-or-mode-alias-normalizes-to-fixed-roll-sequence
  (testing ":oracle-mode :fixed-or normalizes to :fixed-roll-sequence"
    (let [norm (det/normalize-oracle-fixture {:oracle-mode :fixed-or
                                              :oracle-roll-sequence [0.5]})]
      (is (= :fixed-roll-sequence (:mode norm)))
      (is (= [0.5] (:rolls norm))))))

(deftest oracle-fixture-fixed-sequence-exhaustion-throws
  (testing ":fixed-roll-sequence throws when rolls are exhausted with :throw policy"
    (let [params {:rng (rng/make-rng 1)
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls [0.2]
                                   :scope #{:detection}
                                   :on-exhaustion :throw}
                  :oracle-roll-cursor (atom 0)
                  :fraud-detection-probability 1.0
                  :timeout-detection-probability 1.0}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"exhausted"
           (detection/detect-probabilistic-violations params :malicious false 1.0))))))

(deftest oracle-fixture-static-always-detect-forces-detection
  (testing ":static-always-detect forces fraud/timeout detection; L1 is skipped when fraud fires (mutually exclusive, see detect-probabilistic-violations)"
    (let [params {:rng (rng/make-rng 3)
                  :oracle-fixture {:mode :static-always-detect}
                  :fraud-detection-probability 0.25
                  :timeout-detection-probability 0.25}
          out (detection/detect-probabilistic-violations params :malicious false 0.25)]
      (is (true? (:fraud-detected? out)))
      (is (true? (:timeout-detected? out)))
      (is (false? (:l1-slashed? out))
          "L1 detection is skipped when fraud is already detected — mutually exclusive per detect-probabilistic-violations lines 606-609"))))

(deftest oracle-fixture-static-no-slash-suppresses-l2
  (testing ":static-no-slash suppresses L2 backstop detection"
    (let [params {:rng (rng/make-rng 9)
                  :oracle-fixture {:mode :static-no-slash}
                  :l2-detection-prob 0.99}]
      (is (false? (detection/l2-slashed? params
                                         {:verdict-correct? false
                                          :appealed? true}))))))

(deftest timeout-detection-probability-is-active
  (testing ":timeout-detection-probability controls timeout detection rolls"
    (let [params {:rng (rng/make-rng 11)
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls [0.04]
                                   :scope #{:detection}
                                   :on-exhaustion :throw}
                  :oracle-roll-cursor (atom 0)
                  :timeout-detection-probability 0.05
                  :fraud-detection-probability 0.0}
          out (detection/detect-probabilistic-violations params :lazy true 0.0)]
      (is (true? (:timeout-detected? out))))))

(deftest oracle-fixture-per-kind-roll-map-uses-independent-sequences
  (testing "per-kind roll maps consume independent cursors by roll-kind"
    (let [params {:rng (rng/make-rng 21)
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls {:fraud-detection [0.90]
                                           :timeout-detection [0.01]
                                           :l1-detection [0.80]}
                                   :scope #{:detection}
                                   :on-exhaustion :throw}
                  :oracle-roll-cursors (atom {})
                  :fraud-detection-probability 0.1
                  :timeout-detection-probability 0.1}
          out (detection/detect-probabilistic-violations params :malicious false 0.1)]
      (is (false? (:fraud-detected? out)))
      (is (true? (:timeout-detected? out)))
      (is (false? (:l1-slashed? out))))))

(deftest oracle-fixture-per-kind-roll-map-exhaustion-is-per-kind
  (testing "exhaustion is evaluated per roll-kind sequence"
    (let [params {:rng (rng/make-rng 22)
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls {:fraud-detection [0.5]
                                           :default [0.1 0.2]}
                                   :scope #{:detection}
                                   :on-exhaustion :throw}
                  :oracle-roll-cursors (atom {})
                  :fraud-detection-probability 1.0
                  :timeout-detection-probability 0.0}]
      ;; First fraud roll consumes the only fraud-detection entry.
      (is (true? (:fraud-detected?
                  (detection/detect-probabilistic-violations params :malicious false 0.0))))
      ;; Second fraud roll should exhaust that per-kind sequence.
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"exhausted"
           (detection/detect-probabilistic-violations params :malicious false 0.0))))))

(deftest l2-slashed?-is-deterministic
  (testing "l2-slashed? with same rng seed yields identical detection"
    (let [a (det/l2-slashed?
             {:rng (rng/make-rng 42) :l2-detection-prob 0.5}
             {:verdict-correct? false :appealed? true})
          b (det/l2-slashed?
             {:rng (rng/make-rng 42) :l2-detection-prob 0.5}
             {:verdict-correct? false :appealed? true})]
      (is (= a b) "l2-slashed? must be deterministic with same rng seed"))))

(deftest l2-slashed?-has-kleros-guard-is-deterministic
  (testing "l2-slashed? with has-kleros? false is deterministic (always false)"
    (let [a (det/l2-slashed?
             {:rng (rng/make-rng 1) :l2-detection-prob 0.99 :has-kleros? false}
             {:verdict-correct? false :appealed? true})
          b (det/l2-slashed?
             {:rng (rng/make-rng 99) :l2-detection-prob 0.99 :has-kleros? false}
             {:verdict-correct? false :appealed? true})]
      (is (= a b false) "has-kleros? false must always suppress L2 detection"))))

(deftest oracle-roll-trace-metadata-is-emitted
  (testing "roll trace entries include kind/source/value/threshold/detected"
    (let [trace (atom [])
          params {:rng (rng/make-rng 31)
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls {:fraud-detection [0.90]
                                           :timeout-detection [0.01]
                                           :l1-detection [0.80]}
                                   :scope #{:detection}
                                   :on-exhaustion :throw}
                  :oracle-roll-trace-enabled? true
                  :oracle-roll-trace trace
                  :oracle-roll-cursors (atom {})
                  :fraud-detection-probability 0.1
                  :timeout-detection-probability 0.1}]
      (detection/detect-probabilistic-violations params :malicious false 0.1)
      (is (= 3 (count @trace)))
      (doseq [entry @trace]
        (is (contains? entry :roll/kind))
        (is (contains? entry :roll/source))
        (is (contains? entry :roll/value))
        (is (contains? entry :threshold))
        (is (contains? entry :detected?)))
      (is (= :fraud-detection (:roll/kind (first @trace))))
      (is (= :fixed-roll-sequence (:roll/source (first @trace))))
      (is (false? (:detected? (first @trace)))))))
