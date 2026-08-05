(ns resolver-sim.allocation.round-state-test
  "Tests for the coprocessor round-state → probabilistic-allocation-window
   mapper and its integration into allocation-assurance-certificate.v1.

   These tests consume the finished cancellation vocabulary
   (probabilistic-allocation-window, classify-cancellation,
   cancellation-window-assertion) and pin the integration invariants this
   slice owns:
     - the authoritative randomness-request cutpoint closes the window;
     - cancellation is refused once randomness is requested, even with a
       conforming decision profile;
     - the certificate carries a passing, independent-replay cancellation
       assertion at the cutpoint;
     - unknown round-state tokens fail closed."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.certificate :as cert]
            [resolver-sim.allocation.round-state :as rs]
            [resolver-sim.allocation.test-fixtures :as fixtures]))

(def conforming-opts {:profile-id "alloc/2-3"})

(deftest lifecycle-target-state-covers-all-round-states
  (testing "every coprocessor round state maps onto the canonical lifecycle"
    (doseq [token rs/coprocessor-round-states]
      (is (some? (rs/lifecycle-target-state token))
          (str "unmapped round state: " token)))))

(deftest cutpoint-randomness-request-closes-window
  (testing "the authoritative randomness-request cutpoint closes the window"
    (let [w (rs/classify-round-state :randomness-requested)]
      (is (= :closed (:window/state w)))
      (is (false? (:window/possible? w)))
      (is (= [:authoritative-randomness-requested] (:window/blocking-reasons w))))))

(deftest cutpoint-invariants-randomness-request
  (testing "cancellation is refused once randomness is requested, even with a conforming profile"
    (let [r (rs/classify-round-cancellation conforming-opts :randomness-requested)]
      (is (:cancellation/profile-conforming? r))
      (is (= :closed (:cancellation/window r)))
      (is (false? (:cancellation/possible? r)))
      (is (= [:authoritative-randomness-requested] (:cancellation/blocking-reasons r)))))
  (testing "the certificate lifts the refusal into a passing independent-replay assertion"
    (let [a (rs/cancellation-assertion conforming-opts :randomness-requested)]
      (is (= :passing (:status a)))
      (is (= :independent-replay (:assurance a)))
      (is (= :closed (:cancellation/window a)))
      (is (false? (:cancellation/possible? a)))
      (is (= [:authoritative-randomness-requested] (:blocking-reasons a)))
      (is (= :randomness-requested (:evidence/derived-state a))))))

(deftest deterministic-window-precutpoint-valid
  (testing "a round before the cutpoint is still cancellable"
    (let [w (rs/classify-round-state :allocation-committed)]
      (is (= :open (:window/state w)))
      (is (true? (:window/possible? w)))))
  (testing "a conforming profile may cancel before randomness is requested"
    (let [r (rs/classify-round-cancellation conforming-opts :allocation-committed)]
      (is (:cancellation/profile-conforming? r))
      (is (true? (:cancellation/possible? r))))))

(deftest unknown-token-fails-closed
  (testing "an unrecognised token fails closed (no open window)"
    (is (nil? (rs/lifecycle-target-state :not-a-real-state)))
    (let [w (rs/classify-round-state :not-a-real-state)]
      (is (= :invalid (:window/state w)))
      (is (false? (:window/possible? w)))
      (is (= [:unknown-target-state] (:window/blocking-reasons w)))))
  (testing "cancellation of an unrecognised token is refused conservatively"
    (let [r (rs/classify-round-cancellation conforming-opts :not-a-real-state)]
      (is (:cancellation/profile-conforming? r))
      (is (false? (:cancellation/possible? r)))
      (is (some? (:cancellation/blocking-reasons r))))))

(deftest certificate-carries-round-lifecycle-assertion
  (testing "with a round token the certificate carries a :round-lifecycle block"
    (let [c (cert/compose-certificate (fixtures/kernel-result) :randomness-requested)
          lc (:round-lifecycle c)
          assertion (get-in c [:round-lifecycle :cancellation/assertion])]
      (is (some? lc))
      (is (= :randomness-requested (:round-state lc)))
      (is (= :passing (:status assertion)))
      (is (= :independent-replay (:assurance assertion)))
      (is (= "cancellation-decision.v1" (:decision-schema assertion)))
      (is (false? (:cancellation/possible? assertion)))))
  (testing "without a round token there is no :round-lifecycle block"
    (is (nil? (get (cert/compose-certificate
                    (fixtures/kernel-result)) :round-lifecycle))))
  (testing "the lifecycle assertion never claims :zk-proof"
    (let [c (cert/compose-certificate (fixtures/kernel-result) :randomness-requested)
          a (get-in c [:round-lifecycle :cancellation/assertion])]
      (is (not (contains? a :zk-proof)))
      (is (not= :zk-proof (:assurance a))))))

(deftest round-lifecycle-public-projection
  (testing "pre-cutpoint state projects an open, cancellable window"
    (let [l (rs/round-lifecycle conforming-opts :allocation-committed)]
      (is (= "open" (:cancellation-window l)))
      (is (true? (:cancellation-possible l)))
      (is (= [] (:cancellation-blocking-reasons l)))
      (is (= "passing" (:lifecycle-assertion-status l)))
      (is (= "allocation-committed" (:derived-state l)))
      (is (= "evidence/derived-state" (:evidence-status l)))
      (is (= "independent-replay" (:assurance l)))))
  (testing "the randomness-request cutpoint projects a closed, blocking window"
    (let [l (rs/round-lifecycle conforming-opts :randomness-requested)]
      (is (= "closed" (:cancellation-window l)))
      (is (false? (:cancellation-possible l)))
      (is (= ["authoritative-randomness-requested"] (:cancellation-blocking-reasons l)))
      (is (= "passing" (:lifecycle-assertion-status l)))))
  (testing "unknown, missing, and malformed tokens fail closed distinctly"
    (let [unknown (rs/round-lifecycle conforming-opts :not-a-real-state)
          missing (rs/round-lifecycle conforming-opts nil)
          malformed (rs/round-lifecycle conforming-opts 42)]
      (is (= "invalid" (:cancellation-window unknown)))
      (is (= "failing" (:lifecycle-assertion-status unknown)))
      (is (= ["unknown-target-state"] (:cancellation-blocking-reasons unknown)))
      (is (nil? (:derived-state unknown)))
      (is (= ["missing-target-state"] (:cancellation-blocking-reasons missing)))
      (is (= ["malformed-round-state"] (:cancellation-blocking-reasons malformed)))))
  (testing "profile identity and window schema are stable"
    (let [l (rs/round-lifecycle conforming-opts :allocation-committed)]
      (is (= "prf.lifecycle-window/probabilistic-allocation" (:lifecycle-profile-id l)))
      (is (= 1 (:lifecycle-profile-version l)))
      (is (= "cancellation-window.v1" (:cancellation-window-schema l))))))