(ns resolver-sim.assurance.final-held-summary-test
  "Focused tests for final-held-summary: multi-token workflow rows, per-token
   reconstruction reconciliation, fully-released (zero-out) token rows, and the
   direction guard."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.custody :as custody]))

(defn- adj [id token dir amount before after workflow account pos reason owner]
  {:held-adjustment/id id
   :held/direction dir
   :token token
   :amount amount
   :held/before before
   :held/after after
   :held/workflow-id workflow
   :held/account account
   :held/position-id pos
   :held/reason reason
   :owner/address owner})

(defn- summary-of [adjustments]
  (let [idx (custody/replay-held-adjustment-state adjustments)]
    (custody/final-held-summary adjustments (:held-ledger/index idx) (:total-held idx))))

(deftest final-held-summary-by-workflow-lists-all-tokens
  (testing "a multi-token workflow reports the full token set and the lexically-first primary"
    (let [adjs [(adj "h0" :USDC :in 100 0 100 7 :escrow-principal
                     [:held/position :USDC :escrow-principal 7] :create-escrow "0xa")
                (adj "h1" :ETH :in 50 0 50 7 :escrow-principal
                     [:held/position :ETH :escrow-principal 7] :create-escrow "0xa")]
          row (get-in (summary-of adjs) [:by-workflow 7])]
      (is (= [:ETH :USDC] (:tokens row)))
      (is (= :ETH (:token row)))
      (is (= 150 (:final-held row)))
      (is (= 50 (:principal-final row))))))

(deftest final-held-summary-reports-token-reconciliation-failure
  (testing "a per-token opening/in/out/final mismatch is surfaced with the failing token"
    (let [adjs [(adj "h0" :USDC :in 100 0 100 7 :escrow-principal
                     [:held/position :USDC :escrow-principal 7] :create-escrow "0xa")]
          idx (custody/replay-held-adjustment-state adjs)
          summary (custody/final-held-summary adjs (:held-ledger/index idx) {:USDC 999})
          row (get-in summary [:by-token :USDC])]
      (is (false? (:reconstruction-valid? summary)))
      (is (= {:token-reconciliation-failed [:USDC]} (:reconstruction-issue summary)))
      (is (= 999 (:final row))))))

(deftest final-held-summary-keeps-fully-released-token-row
  (testing "a token drained back to zero still appears in :by-token"
    (let [adjs [(adj "h0" :USDC :in 100 0 100 7 :escrow-principal
                     [:held/position :USDC :escrow-principal 7] :create-escrow "0xa")
                (adj "h1" :USDC :out 100 100 0 7 :escrow-principal
                     [:held/position :USDC :escrow-principal 7] :release "0xa")]
          summary (summary-of adjs)
          row (get-in summary [:by-token :USDC])]
      (is (contains? (:by-token summary) :USDC))
      (is (= 0 (:final row)))
      (is (= 100 (:in row)))
      (is (= 100 (:out row)))
      (is (true? (:reconstruction-valid? summary))))))

(deftest final-held-summary-rejects-unknown-direction
  (testing "an adjustment with a non-in/out direction throws rather than absorbing as outflow"
    (let [bad (assoc (adj "h0" :USDC :in 100 0 100 7 :escrow-principal
                          [:held/position :USDC :escrow-principal 7] :create-escrow "0xa")
                     :held/direction :sideways)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (custody/final-held-summary [bad] {} {:USDC 100}))))))
