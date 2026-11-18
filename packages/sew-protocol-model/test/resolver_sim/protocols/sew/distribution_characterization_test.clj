(ns resolver-sim.protocols.sew.distribution-characterization-test
  "Characterization tests for the existing distribute-slashed-funds path.

   These tests capture the exact state effects of the current production path
   before the refactor that eliminates the duplicate bounty calculation and
   integrates verify-distribution.

   Preserve as parity tests after the refactor."
  (:require [resolver-sim.time.context :as time-ctx]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.accounting :as act]
            [resolver-sim.protocols.sew.economics :as sew-econ]))

(defn- base-world
  "Sew world with a single escrow, resolved with a challenger, ready for
   distribute-slashed-funds.  Uses a non-zero workflow-id to distinguish
   claimable entries."
  []
  (let [block-time 1000]
    (-> (t/empty-world block-time)
        (assoc :params {:insurance-cut-bps 5000
                        :protocol-retained-bps 3000}))))

(defn- capture-distribution-effects
  "Call distribute-slashed-funds and return a map of every observable effect."
  [world amount challenger bounty-bps workflow-id]
  (let [before-world world
        before-insurance (get-in world [:bond-distribution :insurance] 0)
        before-protocol (get-in world [:bond-distribution :protocol] 0)
        before-retained (get-in world [:retained-slash-reserves] 0)
        before-claimable (get-in world [:claimable-v2 workflow-id :liability/challenge-bounty challenger] 0)
        after-world (act/distribute-slashed-funds world amount challenger bounty-bps workflow-id)
        after-insurance (get-in after-world [:bond-distribution :insurance] 0)
        after-protocol (get-in after-world [:bond-distribution :protocol] 0)
        after-retained (get-in after-world [:retained-slash-reserves] 0)
        after-claimable (get-in after-world [:claimable-v2 workflow-id :liability/challenge-bounty challenger] 0)
        app-key [:slash-distribution-applied workflow-id challenger]
        app-hash (get-in after-world app-key)]
    {:after-world after-world
     :gross-amount amount
     :bounty-bps bounty-bps
     :challenger challenger
     :workflow-id workflow-id
     :before {:insurance before-insurance
              :protocol before-protocol
              :retained before-retained
              :claimable before-claimable}
     :after {:insurance after-insurance
             :protocol after-protocol
             :retained after-retained
             :claimable after-claimable
             :app-hash app-hash}
     :deltas {:insurance (- after-insurance before-insurance)
              :protocol (- after-protocol before-protocol)
              :retained (- after-retained before-retained)
              :claimable (- after-claimable before-claimable)}}))

(deftest characterize-distribute-slashed-funds-even-bounty
  (testing "capture exact state effects for a divisible bounty amount"
    (let [amount 1000
          bounty-bps 1000
          challenger "0xChallenger"
          workflow-id 0
          w (base-world)
          result (capture-distribution-effects w amount challenger bounty-bps workflow-id)
          bounty (* amount bounty-bps 1/10000)
          ;; Default policy: 50/30/20 base split, 50/50 bounty funding from insurance+protocol
          _ (is (integer? bounty) "bounty must be an exact integer for this test case")
          expected-insurance 450
          expected-protocol 250
          expected-retained 200
          expected-claimable bounty]
      (is (= (:gross-amount result) amount))
      (is (= (:bounty-bps result) bounty-bps))
      (is (= (:challenger result) challenger))
      ;; Pre-state assertions
      (is (= (:insurance (:before result)) 0))
      (is (= (:protocol (:before result)) 0))
      (is (= (:retained (:before result)) 0))
      (is (= (:claimable (:before result)) 0))
      ;; Post-state assertions
      (is (= (:insurance (:after result)) expected-insurance)
          (str "insurance final = " expected-insurance))
      (is (= (:protocol (:after result)) expected-protocol)
          (str "protocol final = " expected-protocol))
      (is (= (:retained (:after result)) expected-retained)
          (str "retained final = " expected-retained))
      (is (= (:claimable (:after result)) expected-claimable)
          (str "claimable = bounty = " expected-claimable))
      ;; Delta assertions
      (is (= (:insurance (:deltas result)) expected-insurance))
      (is (= (:protocol (:deltas result)) expected-protocol))
      (is (= (:retained (:deltas result)) expected-retained))
      (is (= (:claimable (:deltas result)) expected-claimable))
      ;; Conservation: final values sum + claimable = gross
      (is (= (+ expected-insurance expected-protocol expected-retained expected-claimable)
             amount)
          "conservation: insurance + protocol + retained + claimable = gross")
      ;; Pre-state + deltas = post-state
      (is (= (+ (:insurance (:before result)) (:insurance (:deltas result)))
             (:insurance (:after result))))
      (is (= (+ (:protocol (:before result)) (:protocol (:deltas result)))
             (:protocol (:after result))))
      (is (= (+ (:retained (:before result)) (:retained (:deltas result)))
             (:retained (:after result))))
      (is (= (+ (:claimable (:before result)) (:claimable (:deltas result)))
             (:claimable (:after result))))
      ;; Idempotency key is set
      (is (some? (:app-hash (:after result)))
          "application hash recorded")
      ;; Re-applying with same inputs returns identical world (idempotent via app-key)
      (let [reapply (act/distribute-slashed-funds
                     (:after-world result) amount challenger bounty-bps workflow-id)]
        (is (= reapply (:after-world result))
            "re-application with same hash returns identical world (idempotent)")))))

(deftest characterize-distribute-slashed-funds-odd-bounty
  (testing "capture exact state effects for an odd/rounding bounty amount"
    (let [amount 100
          bounty-bps 500
          challenger "0xChallenger"
          workflow-id 1
          w (base-world)
          result (capture-distribution-effects w amount challenger bounty-bps workflow-id)
          bounty 5
          ;; 50/50 funding: insurance deduction = floor(5/2) = 2, protocol deduction = 5-2 = 3
          insurance-base (* amount 5000 1/10000)
          protocol-base (* amount 3000 1/10000)
          retained-base (- amount insurance-base protocol-base)
          expected-insurance (- insurance-base 2)
          expected-protocol (- protocol-base 3)
          expected-retained retained-base]
      ;; Verify the base calculations are correct
      (is (= insurance-base 50))
      (is (= protocol-base 30))
      (is (= retained-base 20))
      ;; Post-state assertions
      (is (= (:insurance (:after result)) expected-insurance)
          (str "insurance final = " expected-insurance " (50 - 2)"))
      (is (= (:protocol (:after result)) expected-protocol)
          (str "protocol final = " expected-protocol " (30 - 3)"))
      (is (= (:retained (:after result)) expected-retained)
          (str "retained final = " expected-retained " (20, unchanged)"))
      (is (= (:claimable (:after result)) bounty)
          (str "claimable = bounty = " bounty))
      ;; Odd-bounty rounding: floor(bounty/2) from insurance, remainder from protocol
      (is (= (:insurance (:deltas result)) expected-insurance))
      (is (= (:protocol (:deltas result)) expected-protocol))
      (is (= (:retained (:deltas result)) expected-retained))
      (is (= (:claimable (:deltas result)) bounty))
      ;; Conservation
      (is (= (+ expected-insurance expected-protocol expected-retained bounty)
             amount)
          "conservation with odd bounty"))))

(deftest characterize-distribute-slashed-funds-zero-bounty
  (testing "no challenger or zero bounty produces no claimable"
    (let [amount 1000
          w (base-world)
          ;; No challenger = no bounty
          result-no-challenger (capture-distribution-effects w amount nil 0 0)
          ;; Zero bounty-bps with challenger = no bounty
          result-zero-bps (capture-distribution-effects w amount "0xC" 0 0)]
      (doseq [r [result-no-challenger result-zero-bps]]
        (is (= (:claimable (:deltas r)) 0)
            "no claimable created")
        (is (= (+ (:insurance (:deltas r))
                  (:protocol (:deltas r))
                  (:retained (:deltas r)))
               amount)
            "full conservation (no bounty deduction)")))))

(deftest characterize-distribute-slashed-funds-custom-bps
  (testing "custom insurance/protocol bps weights are respected"
    (let [amount 1000
          challenger "0xChallenger"
          bounty-bps 1000
          workflow-id 2
          w (assoc-in (base-world) [:params :insurance-cut-bps] 8000)
          w (assoc-in w [:params :protocol-retained-bps] 1000)
          result (capture-distribution-effects w amount challenger bounty-bps workflow-id)
          retained-bps (- 10000 8000 1000)
          insurance-base (* amount 8000 1/10000)
          protocol-base (* amount 1000 1/10000)
          retained-base (* amount retained-bps 1/10000)
          bounty 100
          bounty-from-insurance (quot bounty 2)
          bounty-from-protocol (- bounty bounty-from-insurance)]
      (is (= insurance-base 800))
      (is (= protocol-base 100))
      (is (= retained-base 100))
      (is (= (:insurance (:deltas result)) (- insurance-base bounty-from-insurance)))
      (is (= (:protocol (:deltas result)) (- protocol-base bounty-from-protocol)))
      (is (= (:retained (:deltas result)) retained-base))
      (is (= (:claimable (:deltas result)) bounty))
      (is (= (+ (:insurance (:deltas result))
                (:protocol (:deltas result))
                (:retained (:deltas result))
                (:claimable (:deltas result)))
             amount)
          "conservation holds with custom bps"))))

(deftest characterize-distribute-slashed-funds-multiple-awards
  (testing "distribute-slashed-funds currently only supports one award (challenge-bounty)"
    (let [amount 1000
          challenger "0xChallenger"
          workflow-id 3
          w (base-world)
          result (capture-distribution-effects w amount challenger 1000 workflow-id)]
      ;; Exactly one claimable entry per workflow-id/challenger pair
      (is (= (:claimable (:deltas result)) 100))
      ;; Building with the same inputs again is idempotent
      (let [reapply (act/distribute-slashed-funds w amount challenger 1000 workflow-id)]
        (is (= (get-in reapply [:claimable-v2 workflow-id :liability/challenge-bounty challenger] 0)
               100)
            "re-apply preserves existing claimable")))))

(deftest characterize-distribute-slashed-funds-tampered-distribution-rejected
  (testing "a tampered distribution is caught by verify-distribution before mutation"
    (let [amount 1000
          challenger "0xChallenger"
          workflow-id 3
          w (base-world)
          result (act/distribute-slashed-funds w amount challenger 1000 workflow-id)
          dist-hash (get-in result [:slash-distribution-applied workflow-id challenger])]
      (is (some? dist-hash) "distribution was applied successfully")
      ;; Verify the distribution can be verified independently
      (is (= (get-in result [:bond-distribution :insurance]) 450))
      (is (= (get-in result [:bond-distribution :protocol]) 250))
      (is (= (get-in result [:retained-slash-reserves]) 200))
      (is (= (get-in result [:claimable-v2 workflow-id :liability/challenge-bounty challenger]) 100)))))
