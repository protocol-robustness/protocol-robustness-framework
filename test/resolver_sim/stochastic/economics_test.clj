(ns resolver-sim.stochastic.economics-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.stochastic.economics :as e]))

;; ───────────────────────────────────────────────────────────────────────────
;; Endogenous appeal participation
;; ───────────────────────────────────────────────────────────────────────────

(deftest appeal-when-benefit-exceeds-cost
  (testing "appeal-participation-constraint returns should-appeal? true when benefit > cost"
    (let [r (e/appeal-participation-constraint 1000 0.85 100)]
      (is (true? (:should-appeal? r)))
      (is (= 850.0 (:expected-benefit r)))
      (is (= 750.0 (:net-benefit r))))))

(deftest no-appeal-when-cost-exceeds-benefit
  (testing "appeal-participation-constraint returns should-appeal? false when cost > benefit"
    (let [r (e/appeal-participation-constraint 1000 0.3 500)]
      (is (false? (:should-appeal? r)))
      (is (= -200.0 (:net-benefit r))))))

(deftest appeal-participation-epsilon-gate
  (testing "appeal-participation-constraint respects epsilon threshold"
    (let [r1 (e/appeal-participation-constraint 1000 0.85 100 :epsilon 1000)
          r2 (e/appeal-participation-constraint 1000 0.85 100 :epsilon 10)]
      (is (false? (:should-appeal? r1)))
      (is (true? (:should-appeal? r2))))))

(deftest derive-appeal-probability-rational
  (testing "derive-appeal-probability returns 1.0 when rational agents appeal"
    (let [r (e/derive-appeal-probability 1000 0.85 100)]
      (is (= 1.0 (:p-appeal-wrong r)))
      (is (true? (:rational? r))))))

(deftest derive-appeal-probability-not-rational
  (testing "derive-appeal-probability returns 0.0 when appeal is not worth it"
    (let [r (e/derive-appeal-probability 100 0.1 500)]
      (is (= 0.0 (:p-appeal-wrong r)))
      (is (false? (:rational? r))))))

(deftest derive-appeal-probability-partial-rationality
  (testing "derive-appeal-probability with partial agent rationality"
    (let [r (e/derive-appeal-probability 1000 0.85 100 :agent-rational-fraction 0.6)]
      (is (= 0.6 (:p-appeal-wrong r)))
      (is (true? (:rational? r))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Coalition aggregation
;; ───────────────────────────────────────────────────────────────────────────

(deftest coalition-aggregate-empty-members
  (testing "coalition-aggregate-payoff with no members"
    (let [resolvers []
          result (e/coalition-aggregate-payoff resolvers :coll)]
      (is (= 0 (:member-count result)))
      (is (= 0 (:coalition-net result))))))

(deftest coalition-aggregate-two-members
  (testing "coalition-aggregate-payoff with two members"
    (let [resolvers [{:resolver-id :r1 :strategy :honest :net-payoff 100 :coalition-id :coll}
                     {:resolver-id :r2 :strategy :malicious :net-payoff 50 :coalition-id :coll}
                     {:resolver-id :r3 :strategy :honest :net-payoff -30 :coalition-id nil}]
          result (e/coalition-aggregate-payoff resolvers :coll)]
      (is (= 2 (:member-count result)))
      (is (= 150 (:total-payoff result)))
      (is (= 0 (:coordination-cost result))))))

(deftest coalition-aggregate-with-coordination-cost
  (testing "coalition-aggregate-payoff deducts coordination cost"
    (let [resolvers [{:resolver-id :r1 :strategy :honest :net-payoff 100 :coalition-id :coll}
                     {:resolver-id :r2 :strategy :malicious :net-payoff 50 :coalition-id :coll}]
          result (e/coalition-aggregate-payoff resolvers :coll
                                               :coordination-cost-fn (fn [n] (* n 10)))]
      (is (= 130 (:coalition-net result)))
      (is (= 20 (:coordination-cost result))))))

(deftest coalition-aggregate-outside-option
  (testing "coalition-aggregate-payoff with outside option exceeding total"
    (let [resolvers [{:resolver-id :r1 :strategy :honest :net-payoff 20 :coalition-id :coll}
                     {:resolver-id :r2 :strategy :malicious :net-payoff 10 :coalition-id :coll}]
          result (e/coalition-aggregate-payoff resolvers :coll :outside-option 20)]
      (is (= -10 (:coalition-surplus result)))
      (is (true? (:side-payment-feasible? result))
          "coalition net 30 > 0, so internal redistribution is feasible even though outside option not met"))))

(deftest coalition-aggregate-side-payment-feasible
  (testing "side payments are feasible when coalition net > 0"
    (let [resolvers [{:resolver-id :r1 :strategy :honest :net-payoff 100 :coalition-id :coll}
                     {:resolver-id :r2 :strategy :malicious :net-payoff 50 :coalition-id :coll}]
          result (e/coalition-aggregate-payoff resolvers :coll)]
      (is (true? (:side-payment-feasible? result)))
      (is (= 0 (:min (:feasible-side-payments result))))
      (is (pos? (:max (:feasible-side-payments result)))))))

(deftest coalition-aggregate-binds-definition-root
  (testing "coalition-aggregate-payoff carries the outside-option definition root"
    (let [resolvers [{:resolver-id :r1 :strategy :honest :net-payoff 20 :coalition-id :coll}
                     {:resolver-id :r2 :strategy :malicious :net-payoff 10 :coalition-id :coll}]
          result (e/coalition-aggregate-payoff resolvers :coll :outside-option 20
                                               :definition-root "oo-root-0")]
      (is (= "oo-root-0" (:definition-root result)))))
  (testing "definition root is nil when not provided"
    (let [resolvers [{:resolver-id :r1 :strategy :honest :net-payoff 20 :coalition-id :coll}]
          result (e/coalition-aggregate-payoff resolvers :coll)]
      (is (nil? (:definition-root result))))))
