(ns resolver-sim.conformance.coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.coverage :as coverage]))

(deftest coverage-complete-when-all-covered
  (is (coverage/coverage-complete?
       [:a :b :c] [] [:a :b :c] [:a :b :c] [:a :b :c]))
  (testing "excluded subjects do not need coverage"
    (is (coverage/coverage-complete?
         [:a :b :c] [:c] [:a :b] [:a :b] [:a :b]))))

(deftest coverage-incomplete-on-gaps
  (testing "unvalidated subject blocks completeness"
    (is (not (coverage/coverage-complete?
              [:a :b] [] [:a] [:a :b] [:a :b]))))
  (testing "unexecuted subject blocks completeness"
    (is (not (coverage/coverage-complete?
              [:a :b] [] [:a :b] [:a] [:a :b]))))
  (testing "uncompared subject blocks completeness"
    (is (not (coverage/coverage-complete?
              [:a :b] [] [:a :b] [:a :b] [:a])))))

(deftest coverage-receipt-shape
  (let [r (coverage/coverage-receipt
           {:universe-root "sha256:u"
            :required-subjects [:a :b]
            :validated-subjects [:a :b]
            :executed-subjects [:a :b]
            :compared-subjects [:a :b]
            :excluded-subjects []})]
    (is (:coverage/complete? r))
    (is (= [:a :b] (:coverage/required-subjects r)))
    (is (string? (coverage/coverage-root r)))))

(deftest coverage-root-deterministic
  (let [mk (fn [] (coverage/coverage-receipt
                   {:universe-root "sha256:u"
                    :required-subjects [:a]
                    :validated-subjects [:a]
                    :executed-subjects [:a]
                    :compared-subjects [:a]
                    :excluded-subjects []}))]
    (is (= (coverage/coverage-root (mk)) (coverage/coverage-root (mk))))))
