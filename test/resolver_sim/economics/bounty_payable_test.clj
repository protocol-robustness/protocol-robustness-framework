(ns resolver-sim.economics.bounty-payable-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.bounty-payable :as bp]))

(deftest build-bounty-payable-valid
  (let [p (bp/build-bounty-payable
           {:distribution-root "sha256:dist1"
            :award-id :sew.award/challenge-bounty
            :beneficiary "0xChallenger"
            :amount 100
            :kind :sew.obligation/challenge-bounty})]
    (is (= "bounty-payable.v1" (:schema-version p)))
    (is (= "payable-:sew.award/challenge-bounty" (:payable/id p)))
    (is (= 100 (:payable/amount p)))
    (is (= "0xChallenger" (:payable/beneficiary p)))
    (is (= :pending-backing (:payable/lifecycle p)))
    (is (string? (:payable/hash p)))))

(deftest build-bounty-payable-deterministic
  (let [args {:distribution-root "sha256:dist1"
              :award-id :sew.award/challenge-bounty
              :beneficiary "0xChallenger"
              :amount 100
              :kind :sew.obligation/challenge-bounty}
        p1 (bp/build-bounty-payable args)
        p2 (bp/build-bounty-payable args)]
    (is (= (:payable/hash p1) (:payable/hash p2)))))

(deftest build-bounty-payable-custom-id
  (let [p (bp/build-bounty-payable
           {:payable/id "custom-payable-1"
            :distribution-root "sha256:dist1"
            :award-id :award-1
            :beneficiary "0xC"
            :amount 50})]
    (is (= "custom-payable-1" (:payable/id p)))))

(deftest build-bounty-payable-missing-beneficiary-rejected
  (is (thrown? Exception
               (bp/build-bounty-payable
                {:distribution-root "sha256:dist1"
                 :award-id :award-1
                 :amount 50}))))

(deftest build-bounty-payable-negative-amount-rejected
  (is (thrown? Exception
               (bp/build-bounty-payable
                {:distribution-root "sha256:dist1"
                 :award-id :award-1
                 :beneficiary "0xC"
                 :amount -1}))))

(deftest validate-bounty-payable-valid
  (let [p (bp/build-bounty-payable
           {:distribution-root "sha256:dist1"
            :award-id :sew.award/challenge-bounty
            :beneficiary "0xChallenger"
            :amount 100})
        v (bp/validate-bounty-payable p)]
    (is (:valid? v))))

(deftest verify-bounty-payable-untampered
  (let [p (bp/build-bounty-payable
           {:distribution-root "sha256:dist1"
            :award-id :sew.award/challenge-bounty
            :beneficiary "0xChallenger"
            :amount 100})
        v (bp/verify-bounty-payable p)]
    (is (:valid? v))))

(deftest verify-bounty-payable-tampered
  (let [p (bp/build-bounty-payable
           {:distribution-root "sha256:dist1"
            :award-id :sew.award/challenge-bounty
            :beneficiary "0xChallenger"
            :amount 100})
        tampered (assoc p :payable/amount 999)
        v (bp/verify-bounty-payable tampered)]
    (is (not (:valid? v)))))

(deftest transition-payable-lifecycle
  (let [p (bp/build-bounty-payable
           {:distribution-root "sha256:dist1"
            :award-id :sew.award/challenge-bounty
            :beneficiary "0xChallenger"
            :amount 100})]
    (is (= :pending-backing (:payable/lifecycle p)))
    (let [backed (bp/transition-payable-lifecycle p :backed)]
      (is (= :backed (:payable/lifecycle backed)))
      (is (not= (:payable/hash p) (:payable/hash backed))
          "lifecycle transition changes hash"))
    (let [settled (bp/transition-payable-lifecycle p :settled)]
      (is (= :settled (:payable/lifecycle settled))))
    (is (nil? (bp/transition-payable-lifecycle p :invalid-state)))))
