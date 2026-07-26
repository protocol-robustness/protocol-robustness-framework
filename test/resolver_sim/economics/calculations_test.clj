(ns resolver-sim.economics.calculations-test
  "Portability test: economic calculations work without Sew protocol code.
   Verifies that the core economic functions accept amounts and return
   results with no protocol state required."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.calculations :as calc]))

(deftest bps-amount-calculates-correctly
  (is (= 50 (calc/calculate-bps-amount 1000 500)))
  (is (= 0 (calc/calculate-bps-amount 0 500)))
  (is (= 150 (calc/calculate-bps-amount 1000 1500))))

(deftest bps-fee-deducts-correctly
  ;; Returns map with :fee and :net keys
  (is (= {:fee 10, :net 990} (calc/calculate-bps-fee 1000 100)))
  (is (= {:fee 0, :net 1000} (calc/calculate-bps-fee 1000 0))))

(deftest bounty-returns-zero-for-non-positive-rate
  (is (= 20 (calc/calculate-bounty 1000 200)))
  (is (= 0 (calc/calculate-bounty 1000 0)))
  (is (= 0 (calc/calculate-bounty 1000 -50))))

(deftest slash-amount-calculates-correctly
  (is (= 25 (calc/calculate-slash-amount 1000 250)))
  (is (= 0 (calc/calculate-slash-amount 0 250)))
  (is (= 500 (calc/calculate-slash-amount 10000 500))))

(deftest capacity-limit-calculates-correctly
  (is (= 1000.0 (calc/calculate-capacity-limit 1000)))
  (is (= 1500.0 (calc/calculate-capacity-limit 1000 1.5)))
  (is (= 4000.0 (calc/calculate-capacity-limit 1000 4.0))))

(deftest no-sew-dependency
  (testing "calculations namespace does not depend on Sew protocol code"
    (let [ns-requires (keys (ns-imports 'resolver-sim.economics.calculations))]
      (is (not-any? #(re-find #"protocols\.sew" (str %)) ns-requires))
      (is (not-any? #(re-find #"sew" (str %)) ns-requires)))))
