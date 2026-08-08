(ns resolver-sim.accounting.held-position-policy-test
  "Tests for the shared held-position policy: reason vocabulary coverage, the
   committed policy-exempt extension set, position derivation, and the replay
   reason-position-policy classification."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.accounting.held-position-policy :as hp]))

(deftest policy-covers-documented-production-reasons
  (testing "every position-bearing production reason has a policy entry"
    (is (= #{:escrow-principal-deposited
             :escrow-settlement-released
             :escrow-settlement-refunded
             :force-authorised-release
             :force-authorised-refund
             :deferred-yield-reclassified-out
             :deferred-yield-reserved
             :appeal-bond-posted
             :appeal-bond-returned
             :appeal-bond-slashed
             :appeal-bond-forfeited
             :yield-accrued
             :yield-distributed
             :deferred-yield-claimed
             :resolver-yield-accrued
             :resolver-yield-loss
             :resolver-yield-withdrawn
             :resolver-slash-custody-debited
             :partial-fill-principal-loss
             :yield-negative-excess}
           (set (keys hp/held-position-policy))))))

(deftest policy-exempt-reasons-are-committed-extension
  (testing "the committed extension set is intentional and documented"
    (is (= #{:governance-authorised-correction
             :replay-fixture-setup
             :replay-migration
             :bounty-reserve-reservation
             :held/unspecified}
           (set (keys hp/policy-exempt-reasons))))
    (is (every? string? (map hp/policy-exempt-rationale (keys hp/policy-exempt-reasons))))
    (testing "an exempt reason is known but not position-bearing"
      (is (hp/known-reason? :bounty-reserve-reservation))
      (is (hp/policy-exempt? :bounty-reserve-reservation))
      (is (nil? (hp/policy-for :bounty-reserve-reservation))))))

(deftest unknown-reason-is-not-known
  (is (not (hp/known-reason? :totally-unknown-reason)))
  (is (not (hp/policy-exempt? :totally-unknown-reason)))
  (is (nil? (hp/policy-for :totally-unknown-reason))))

(deftest position-components-derives-escrow-position
  (let [c (hp/position-components :USDC :escrow-principal-deposited
                                  {:held/workflow-id 7
                                   :owner/address "0xalice"})]
    (is (= :escrow-principal (:held/account c)))
    (is (= [:held/position :USDC :escrow-principal 7] (:held/position-id c)))
    (is (= "0xalice" (:owner/address c)))))

(deftest position-components-derives-bond-position-with-full-scope
  (let [c (hp/position-components :USDC :appeal-bond-posted
                                  {:held/slash-id 1 :held/bond-id "b"
                                   :held/workflow-id 2 :held/actor "0xactor"})]
    (is (= :appeal-bond (:held/account c)))
    (is (= [:held/position :USDC :appeal-bond 1 "b" 2 "0xactor"]
           (:held/position-id c)))))

(deftest position-components-exempt-reason-has-no-position
  (let [c (hp/position-components :USDC :bounty-reserve-reservation
                                  {:held/workflow-id 0})]
    (is (nil? (:held/position-id c)))
    (is (nil? (:held/account c)))))

(deftest required-owner-attribution-classification
  (is (hp/required-owner-attribution? :escrow-principal-deposited))
  (is (hp/required-owner-attribution? :escrow-settlement-released))
  (is (hp/required-owner-attribution? :governance-authorised-correction))
  (is (not (hp/required-owner-attribution? :yield-accrued))))

(deftest position-policy-check-error-classifications
  (testing "position-bearing reason with correct derived position passes"
    (is (nil? (hp/position-policy-check-error
               {:token :USDC
                :held/reason :escrow-principal-deposited
                :held/account :escrow-principal
                :held/position-id [:held/position :USDC :escrow-principal 7]
                :held/workflow-id 7}))))
  (testing "account mismatch is a position-mismatch violation"
    (is (= :position-mismatch
           (hp/position-policy-check-error
            {:token :USDC
             :held/reason :escrow-principal-deposited
             :held/account :yield-custody
             :held/position-id [:held/position :USDC :yield-custody 7]
             :held/workflow-id 7}))))
  (testing "exempt reason carrying a position-id is a violation"
    (is (= :exempt-with-position-id
           (hp/position-policy-check-error
            {:token :USDC
             :held/reason :bounty-reserve-reservation
             :held/account :bounty-reserve
             :held/position-id [:held/position :USDC :bounty-reserve 7]
             :held/workflow-id 7}))))
  (testing "unknown reason is a violation"
    (is (= :unknown-reason-outside-policy
           (hp/position-policy-check-error
            {:token :USDC
             :held/reason :totally-unknown-reason})))))
