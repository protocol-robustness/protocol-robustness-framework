(ns resolver-sim.economics.terminal-payoff-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.terminal-payoff :as tp]))

(deftest coalition-ev-basic
  (testing "coalition-ev-from-payoff sums member payoffs"
    (let [members [{:resolver-id :r1 :net-payoff 100}
                   {:resolver-id :r2 :net-payoff 60}]
          result (tp/coalition-ev-from-payoff members)]
      (is (= 160 (:coalition-total result)))
      (is (= 160 (:net-of-costs result))))))

(deftest coalition-ev-with-coordination-cost
  (testing "coalition-ev-from-payoff deducts coordination cost"
    (let [members [{:resolver-id :r1 :net-payoff 100}
                   {:resolver-id :r2 :net-payoff 60}]
          result (tp/coalition-ev-from-payoff members :coordination-cost 20)]
      (is (= 160 (:coalition-total result)))
      (is (= 140 (:net-of-costs result))))))

(deftest coalition-ev-with-side-payments
  (testing "coalition-ev-from-payoff applies side payments"
    (let [members [{:resolver-id :r1 :net-payoff 100}
                   {:resolver-id :r2 :net-payoff 20}]
          result (tp/coalition-ev-from-payoff members
                                              :coordination-cost 5
                                              :side-payments [{:from :r1 :to :r2 :amount 15}])]
      (is (= 120 (:coalition-total result)))
      (is (= 115 (:net-of-costs result)))
      (is (= 85 (get-in (:member-payoffs result) [0 :after-side-payment])))
      (is (= 35 (get-in (:member-payoffs result) [1 :after-side-payment]))))))

(deftest coalition-ev-empty
  (testing "coalition-ev-from-payoff with empty members"
    (let [result (tp/coalition-ev-from-payoff [])]
      (is (= 0 (:coalition-total result)))
      (is (= 0 (:net-of-costs result))))))

(deftest incentive-margin-honest-preferred
  (testing "incentive-margin returns pass when honest beats malice"
    (let [result (tp/incentive-margin :honest-ev 100 :malicious-ev 80)]
      (is (= :pass (:verdict result)))
      (is (= 20 (:margin result)))
      (is (= :malicious (:deviation-type result))))))

(deftest incentive-margin-malicious-wins
  (testing "incentive-margin returns fail when malice beats honest"
    (let [result (tp/incentive-margin :honest-ev 50 :malicious-ev 80)]
      (is (= :fail (:verdict result)))
      (is (= -30 (:margin result))))))

(deftest incentive-margin-multiple-deviations
  (testing "incentive-margin finds worst deviation among multiple types"
    (let [result (tp/incentive-margin :honest-ev 100 :malicious-ev 120 :lazy-ev 90)]
      (is (= :fail (:verdict result)))
      (is (= -20 (:margin result)))
      (is (= :malicious (:deviation-type result))))))

(deftest ir-check-passes
  (testing "ir-check returns rational? true when net >= outside-option"
    (let [result (tp/ir-check 100)]
      (is (true? (:rational? result)))
      (is (= 100 (:net result))))))

(deftest ir-check-fails
  (testing "ir-check returns rational? false when net < outside-option"
    (let [result (tp/ir-check 5 :outside-option 10)]
      (is (false? (:rational? result)))
      (is (= 5 (:deficit result))))))

(deftest ir-check-binds-definition-root
  (testing "ir-check carries the outside-option definition root when provided"
    (let [result (tp/ir-check 100 :outside-option 5 :definition-root "oo-root-0")]
      (is (true? (:rational? result)))
      (is (= "oo-root-0" (:definition-root result)))))
  (testing "ir-check omits definition root when not provided"
    (let [result (tp/ir-check 100)]
      (is (not (contains? result :definition-root))))))

(deftest budget-balance-check-passes
  (testing "budget-balance-check returns balanced? true when sum = 0"
    (let [result (tp/budget-balance-check [{:role :resolver :net 100}
                                           {:role :protocol :net -100}])]
      (is (true? (:balanced? result)))
      (is (= 0 (:imbalance result))))))

(deftest budget-balance-check-fails
  (testing "budget-balance-check returns balanced? false when sum != 0"
    (let [result (tp/budget-balance-check [{:role :resolver :net 100}
                                           {:role :protocol :net -50}])]
      (is (false? (:balanced? result)))
      (is (= 50 (:imbalance result))))))

(deftest budget-balance-check-with-epsilon
  (testing "budget-balance-check with epsilon tolerance"
    (let [result (tp/budget-balance-check [{:role :resolver :net 100}
                                           {:role :protocol :net -99}]
                                          :epsilon 1)]
      (is (true? (:balanced? result))))))
