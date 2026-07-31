(ns resolver-sim.economics.award-policy-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.award-policy :as ap]))

(deftest build-award-policy-valid
  (let [p (ap/build-award-policy
           {:policy/id "policy-001"
            :policy/required-check-ids [:claim-valid :beneficiary-active]})]
    (is (= :award-policy.v1 (:artifact/type p)))
    (is (some? (:policy/check-set-root p)))
    (is (some? (:artifact/hash p)))))

(deftest build-award-policy-deterministic
  (let [a (ap/build-award-policy
           {:policy/id "policy-001"
            :policy/required-check-ids [:claim-valid :beneficiary-active]})
        b (ap/build-award-policy
           {:policy/id "policy-001"
            :policy/required-check-ids [:beneficiary-active :claim-valid]})]
    (is (= (:artifact/hash a) (:artifact/hash b))
        "check-set permutation does not change policy hash")))

(deftest build-award-policy-duplicate-ids-rejected
  (is (thrown? Exception
               (ap/build-award-policy
                {:policy/id "policy-dup"
                 :policy/required-check-ids [:a :a]}))))

(deftest build-award-policy-empty-ids-rejected
  (is (thrown? Exception
               (ap/build-award-policy
                {:policy/id "policy-empty"
                 :policy/required-check-ids []}))))

(deftest verify-award-policy-passes
  (let [p (ap/build-award-policy
           {:policy/id "policy-001"
            :policy/required-check-ids [:a :b]})]
    (is (:valid? (ap/verify-award-policy p)))))

(deftest verify-award-policy-detects-tampered-check-set-root
  (let [p (assoc (ap/build-award-policy
                  {:policy/id "policy-001"
                   :policy/required-check-ids [:a :b]})
                 :policy/check-set-root "sha256:fake")]
    (is (false? (:valid? (ap/verify-award-policy p))))))

(deftest verify-award-policy-detects-tampered-hash
  (let [p (assoc (ap/build-award-policy
                  {:policy/id "policy-001"
                   :policy/required-check-ids [:a :b]})
                 :artifact/hash "sha256:fake")]
    (is (false? (:valid? (ap/verify-award-policy p))))))
