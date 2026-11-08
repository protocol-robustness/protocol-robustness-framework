(ns resolver-sim.protocols.sew.yield.risk-policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.yield.risk-policy :as risk-policy]))

(deftest de-risking-tightens-liquidity-as-available-ratio-falls
  (is (= {:apy-multiplier 1.0 :liquidity-mode :available}
         (risk-policy/calculate-risk-adjustment :de-risking 0.81)))
  (is (= {:apy-multiplier 0.5 :liquidity-mode :shortfall}
         (risk-policy/calculate-risk-adjustment :de-risking 0.8)))
  (is (= {:apy-multiplier 0.0 :liquidity-mode :frozen}
         (risk-policy/calculate-risk-adjustment :de-risking 0.5))))

(deftest attraction-policy-increases-apy-during-shortfall
  (is (= {:apy-multiplier 1.0 :liquidity-mode :available}
         (risk-policy/calculate-risk-adjustment :liquidity-attraction 0.81)))
  (is (= {:apy-multiplier 2.0 :liquidity-mode :shortfall}
         (risk-policy/calculate-risk-adjustment :liquidity-attraction 0.8)))
  (is (= {:apy-multiplier 5.0 :liquidity-mode :shortfall}
         (risk-policy/calculate-risk-adjustment :liquidity-attraction 0.5))))

(deftest fixed-and-default-policies-are-stable
  (testing "bad policy remains deliberately risky"
    (is (= {:apy-multiplier 1.5 :liquidity-mode :available}
           (risk-policy/calculate-risk-adjustment :bad-policy 0.0))))
  (testing "the explicit default is neutral"
    (is (= {:apy-multiplier 1.0 :liquidity-mode :available}
           (risk-policy/calculate-risk-adjustment :default 0.0)))))
