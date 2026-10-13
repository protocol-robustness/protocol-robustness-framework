(ns resolver-sim.protocols.sew.lifecycle-test
  "Unit tests for contract_model/lifecycle.clj.

   Covers createEscrow, release, senderCancel, recipientCancel,
   autoCancelDisputedEscrow — happy paths and every revert condition."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.protocols.sew.types        :as t]
            [resolver-sim.protocols.sew.lifecycle    :as lc]
            [resolver-sim.protocols.sew.accounting   :as acct]
            [resolver-sim.protocols.sew.registry     :as reg]
            [resolver-sim.protocols.sew.resolution   :as res]
            [resolver-sim.protocols.sew              :as sew]
            [resolver-sim.time.context :as time-ctx]))

;; ---------------------------------------------------------------------------
;; Shared fixtures
;; ---------------------------------------------------------------------------

(def alice "0xAlice")
(def bob   "0xBob")
(def carol "0xCarol")
(def usdc  :0xUSDC)

(def base-snapshot
  (snap-fix/escrow-snapshot
   {:escrow-fee-bps            50    ; 0.5%
    :default-auto-release-delay 0
    :default-auto-cancel-delay  0
    :max-dispute-duration       3600
    :appeal-window-duration     1800}))

(def base-settings
  (t/make-escrow-settings {}))

(def allow-all-strategy
  "Release strategy that always allows."
  (fn [_world _wf _caller] {:allowed? true :reason-code 0}))

(def deny-strategy
  "Release strategy that always denies (not authorized = reason code 1)."
  (fn [_world _wf _caller] {:allowed? false :reason-code 1}))

(defn- create-one
  "Helper: create one escrow from alice → bob, 1000 USDC. Returns result."
  ([] (create-one {}))
  ([settings-overrides]
   (lc/create-escrow
    (t/empty-world 1000)
    alice usdc bob 1000
    (merge base-settings settings-overrides)
    base-snapshot)))

(defn- world-with-one-escrow
  "World with a single created escrow."
  []
  (:world (create-one)))

(defn- world-disputed
  "World with escrow 0 in :disputed state."
  []
  (-> (world-with-one-escrow)
      (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
      (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
      (assoc-in [:dispute-timestamps 0] 1000)))

(defn- world-disputed-with-auto-cancel-time
  "World with escrow 0 in :disputed state with auto-cancel-time set."
  ([] (world-disputed-with-auto-cancel-time 2000))
  ([auto-cancel-time]
   (-> (world-disputed)
       (assoc-in [:escrow-transfers 0 :auto-cancel-time] auto-cancel-time))))

;; ---------------------------------------------------------------------------
;; create-escrow
;; ---------------------------------------------------------------------------

(deftest create-escrow-happy
  (let [r (create-one)]
    (is (true? (:ok r)))
    (is (= 0 (:workflow-id r)))
    (let [w  (:world r)
          et (t/get-transfer w 0)]
      (is (= :pending (:escrow-state et)))
      (is (= alice (:from et)))
      (is (= bob (:to et)))
      (is (= usdc (:token et)))
      ;; fee = 1000 * 50 / 10000 = 5
      (is (= 995 (:amount-after-fee et)) "amount-after-fee = 1000 - 5")
      (is (= 995 (get-in w [:total-held usdc])) "total-held increases by amount-after-fee")
      (is (= 5   (get-in w [:total-fees usdc])) "total-fees increases by fee"))))

(deftest create-escrow-assigns-sequential-workflow-ids
  (let [w0 (:world (create-one))
        r1 (lc/create-escrow w0 carol usdc alice 2000 base-settings base-snapshot)]
    (is (= 1 (:workflow-id r1)) "second escrow gets workflow-id 1")))

(deftest create-escrow-zero-fee
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000 base-settings snap)
        et   (t/get-transfer (:world r) 0)]
    (is (= 1000 (:amount-after-fee et)) "no fee deducted when fee-bps=0")
    (is (= 0    (get-in (:world r) [:total-fees usdc] 0)))))

(deftest create-escrow-guard-nil-token
  (let [r (lc/create-escrow (t/empty-world) alice nil bob 1000 base-settings base-snapshot)]
    (is (false? (:ok r)))
    (is (= :invalid-token (:error r)))))

(deftest create-escrow-guard-nil-to
  (let [r (lc/create-escrow (t/empty-world) alice usdc nil 1000 base-settings base-snapshot)]
    (is (false? (:ok r)))
    (is (= :invalid-recipient (:error r)))))

(deftest create-escrow-guard-zero-amount
  (let [r (lc/create-escrow (t/empty-world) alice usdc bob 0 base-settings base-snapshot)]
    (is (false? (:ok r)))
    (is (= :amount-zero (:error r)))))

(deftest create-escrow-guard-both-auto-times
  (let [r (create-one {:auto-release-time 2000 :auto-cancel-time 3000})]
    (is (false? (:ok r)))
    (is (= :cannot-set-both-auto-times (:error r)))))

(deftest create-escrow-applies-default-auto-release
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0 :default-auto-release-delay 3600})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000 base-settings snap)
        et   (t/get-transfer (:world r) 0)]
    (is (= (+ 1000 3600) (:auto-release-time et))
        "auto-release-time = block-time + default-delay")))

(deftest create-escrow-applies-default-auto-cancel
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0 :default-auto-cancel-delay 7200})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000 base-settings snap)
        et   (t/get-transfer (:world r) 0)]
    (is (= (+ 1000 7200) (:auto-cancel-time et)))))

(deftest create-escrow-explicit-auto-times-override-defaults
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0 :default-auto-release-delay 3600})
        sett (t/make-escrow-settings {:auto-release-time 9999})
        r    (lc/create-escrow (t/empty-world 1000) alice usdc bob 1000 sett snap)
        et   (t/get-transfer (:world r) 0)]
    (is (= 9999 (:auto-release-time et)) "explicit setting overrides default")))

(deftest create-escrow-custom-resolver-in-settings
  (let [sett (t/make-escrow-settings {:custom-resolver "0xResolver"})
        r    (lc/create-escrow (t/empty-world) alice usdc bob 1000 sett base-snapshot)
        et   (t/get-transfer (:world r) 0)]
    (is (= "0xResolver" (:dispute-resolver et)))))

(deftest create-escrow-snapshot-frozen
  ;; Verify the snapshot stored in world does not reference live config.
  ;; After creation, modifying global config does NOT change the per-escrow snapshot.
  (let [w1    (world-with-one-escrow)
        ;; "change governance" — modify the snapshot reference outside the stored map
        snap2 (snap-fix/escrow-snapshot {:escrow-fee-bps 999})
        w2    (assoc-in w1 [:module-snapshots 99] snap2)]  ; different id, shouldn't matter
    (is (= 50 (:escrow-fee-bps (t/get-snapshot w2 0)))
        "snapshot for workflow 0 is frozen at creation")))

;; ---------------------------------------------------------------------------
;; release
;; ---------------------------------------------------------------------------

(deftest release-happy
  (let [w (world-with-one-escrow)
        r (lc/release w 0 alice allow-all-strategy)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0)))
    (is (= 0 (get-in (:world r) [:total-held usdc] 0))
        "total-held decremented on release")))

(deftest release-invalid-workflow
  (let [r (lc/release (world-with-one-escrow) 99 alice allow-all-strategy)]
    (is (false? (:ok r)))
    (is (= :invalid-workflow-id (:error r)))))

(deftest release-not-pending
  (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] :disputed)
        r (lc/release w 0 alice allow-all-strategy)]
    (is (false? (:ok r)))
    (is (= :transfer-not-pending (:error r)))))

(deftest release-no-strategy
  (let [r (lc/release (world-with-one-escrow) 0 alice nil)]
    (is (false? (:ok r)))
    (is (= :release-strategy-not-set (:error r)))))

(deftest release-strategy-denies
  (let [r (lc/release (world-with-one-escrow) 0 carol deny-strategy)]
    (is (false? (:ok r)))
    (is (= :not-sender (:error r)))))

;; ---------------------------------------------------------------------------
;; sender-cancel
;; ---------------------------------------------------------------------------

(deftest sender-cancel-unilateral
  (let [w (world-with-one-escrow)
        r (lc/sender-cancel w 0 alice {:can-cancel? true :unilateral-cancel? true})]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))
    (is (= 0 (get-in (:world r) [:total-held usdc] 0)))))

(deftest sender-cancel-mutual-consent-first-party
  "Sender sets agree-to-cancel but recipient hasn't yet — no finalisation."
  (let [w (world-with-one-escrow)
        r (lc/sender-cancel w 0 alice nil)]
    (is (true? (:ok r)))
    (is (= :pending (t/escrow-state (:world r) 0)) "still pending")
    (is (= :agree-to-cancel (get-in (:world r) [:escrow-transfers 0 :sender-status])))))

(deftest sender-cancel-mutual-consent-both-agree
  "Sender and recipient both call cancel — finalises on second call."
  (let [w0   (world-with-one-escrow)
        ;; Recipient already agreed
        w1   (assoc-in w0 [:escrow-transfers 0 :recipient-status] :agree-to-cancel)
        r    (lc/sender-cancel w1 0 alice nil)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)) "finalises when both agree")))

(deftest sender-cancel-not-sender
  (let [r (lc/sender-cancel (world-with-one-escrow) 0 bob nil)]
    (is (false? (:ok r)))
    (is (= :not-sender (:error r)))))

(deftest sender-cancel-not-pending
  (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] :released)
        r (lc/sender-cancel w 0 alice nil)]
    (is (false? (:ok r)))
    (is (= :transfer-not-pending (:error r)))))

(deftest sender-cancel-strategy-blocks
  (let [r (lc/sender-cancel (world-with-one-escrow) 0 alice {:can-cancel? false :unilateral-cancel? false})]
    (is (false? (:ok r)))
    (is (= :not-authorized-to-cancel-yet (:error r)))))

(deftest sender-cancel-strategy-can-cancel-dominates
  "can-cancel? false dominates over unilateral-cancel? true."
  (let [r (lc/sender-cancel (world-with-one-escrow) 0 alice {:can-cancel? false :unilateral-cancel? true})]
    (is (false? (:ok r)))
    (is (= :not-authorized-to-cancel-yet (:error r)))))

(deftest sender-cancel-strategy-mutual-only
  "can-cancel? true + unilateral-cancel? false → mutual-consent path."
  (let [r (lc/sender-cancel (world-with-one-escrow) 0 alice {:can-cancel? true :unilateral-cancel? false})]
    (is (true? (:ok r)))
    (is (= :pending (t/escrow-state (:world r) 0)) "still pending")
    (is (= :agree-to-cancel (get-in (:world r) [:escrow-transfers 0 :sender-status])))))

;; ---------------------------------------------------------------------------
;; recipient-cancel
;; ---------------------------------------------------------------------------

(deftest recipient-cancel-unilateral
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 bob {:can-cancel? true :unilateral-cancel? true})]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest recipient-cancel-mutual-consent-first-party
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 bob nil)]
    (is (true? (:ok r)))
    (is (= :pending (t/escrow-state (:world r) 0)))
    (is (= :agree-to-cancel (get-in (:world r) [:escrow-transfers 0 :recipient-status])))))

(deftest recipient-cancel-mutual-consent-finalises
  (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :sender-status] :agree-to-cancel)
        r (lc/recipient-cancel w 0 bob nil)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest recipient-cancel-not-recipient
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 alice nil)]
    (is (false? (:ok r)))
    (is (= :not-recipient (:error r)))))

(deftest recipient-cancel-strategy-can-cancel-dominates
  "can-cancel? false dominates over unilateral-cancel? true."
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 bob {:can-cancel? false :unilateral-cancel? true})]
    (is (false? (:ok r)))
    (is (= :not-authorized-to-cancel-yet (:error r)))))

(deftest recipient-cancel-strategy-mutual-only
  "can-cancel? true + unilateral-cancel? false → mutual-consent path."
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 bob {:can-cancel? true :unilateral-cancel? false})]
    (is (true? (:ok r)))
    (is (= :pending (t/escrow-state (:world r) 0)) "still pending")
    (is (= :agree-to-cancel (get-in (:world r) [:escrow-transfers 0 :recipient-status])))))

(deftest recipient-cancel-strategy-blocks
  "Both flags false → not-authorized-to-cancel-yet."
  (let [r (lc/recipient-cancel (world-with-one-escrow) 0 bob {:can-cancel? false :unilateral-cancel? false})]
    (is (false? (:ok r)))
    (is (= :not-authorized-to-cancel-yet (:error r)))))

;; ---------------------------------------------------------------------------
;; auto-cancel-disputed-escrow
;; ---------------------------------------------------------------------------

(deftest auto-cancel-disputed-happy
  (let [w (-> (world-disputed)
              ;; block-time > dispute-ts + max-dispute-duration (1000+3600=4600)
              (time-ctx/with-temporal-context {:block-ts 5000}))
        r (lc/auto-cancel-disputed-escrow w 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))
    (is (nil? (get-in (:world r) [:dispute-timestamps 0]))
        "dispute timestamp cleared on auto-cancel")))

(deftest auto-cancel-disputed-not-disputed
  (let [r (lc/auto-cancel-disputed-escrow (world-with-one-escrow) 0)]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))))

(deftest auto-cancel-disputed-has-pending-settlement
  "CRIT-3: must not override a resolver's pending decision."
  (let [pending {:exists true :is-release true :appeal-deadline 9999 :resolution-hash nil}
        w       (-> (world-disputed)
                    (assoc :block-time 5000)
                    (assoc-in [:pending-settlements 0] pending))
        r       (lc/auto-cancel-disputed-escrow w 0)]
    (is (false? (:ok r)))
    (is (= :has-pending-settlement (:error r)))))

(deftest auto-cancel-disputed-timeout-not-exceeded
  (let [w (assoc (world-disputed) :block-time 2000)  ; 2000 < 1000+3600
        r (lc/auto-cancel-disputed-escrow w 0)]
    (is (false? (:ok r)))
    (is (= :dispute-timeout-not-exceeded (:error r)))))

;; ---------------------------------------------------------------------------
;; auto-cancel-disputed-on-auto-time
;; ---------------------------------------------------------------------------

(deftest auto-cancel-disputed-on-auto-time-happy
  (let [w (-> (world-disputed-with-auto-cancel-time 1000)
              (time-ctx/with-temporal-context {:block-ts 5000}))
        r (lc/auto-cancel-disputed-on-auto-time w 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))
    (is (nil? (get-in (:world r) [:dispute-timestamps 0]))
        "dispute timestamp cleared on auto-cancel")))

(deftest auto-cancel-disputed-on-auto-time-not-disputed
  (let [w (-> (world-with-one-escrow)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 1000))
        r (lc/auto-cancel-disputed-on-auto-time w 0)]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))))

(deftest auto-cancel-disputed-on-auto-time-has-pending-settlement
  (let [pending {:exists true :is-release true :appeal-deadline 9999 :resolution-hash nil}
        w       (-> (world-disputed-with-auto-cancel-time)
                    (assoc :block-time 5000)
                    (assoc-in [:pending-settlements 0] pending))
        r       (lc/auto-cancel-disputed-on-auto-time w 0)]
    (is (false? (:ok r)))
    (is (= :has-pending-settlement (:error r)))))

(deftest auto-cancel-disputed-on-auto-time-time-not-passed
  (let [w (-> (world-disputed-with-auto-cancel-time 3000)
              (assoc :block-time 2000))
        r (lc/auto-cancel-disputed-on-auto-time w 0)]
    (is (false? (:ok r)))
    (is (= :auto-cancel-time-not-passed (:error r)))))

(deftest auto-cancel-disputed-with-resolver-and-bonds
  "Regression: cancel-disputed-escrow-now must not sub-held the slash from
   :total-held.  The resolver's stake is tracked in :resolver-stakes, not
   :total-held (register-stake never calls add-held).  Previously the
   sub-held in slash-resolver-stake consumed bond funds from :total-held
   after finalize had already drained the principal, causing
   return-all-bonds-for-workflow to underflow."
  (let [resolver "0xResolver"
        snap     (snap-fix/escrow-snapshot
                  {:escrow-fee-bps            0
                   :default-auto-release-delay 0
                   :default-auto-cancel-delay  0
                   :max-dispute-duration      3600
                   :appeal-window-duration    0
                   :appeal-bond-protocol-fee-bps 0})
        sett     (t/make-escrow-settings
                  {:auto-cancel-time 2000
                   :custom-resolver  resolver})
        w0       (t/empty-world 1000)
        {:keys [world workflow-id]} (lc/create-escrow w0 alice usdc bob 2000 sett snap)
        ;; Register resolver with stake
        w2       (reg/register-stake world resolver 5000)
        ;; Raise dispute
        w3       (:world (lc/raise-dispute w2 0 alice))
        ;; Post appeal bonds (both parties)
        w4       (acct/post-appeal-bond w3 0 bob   snap usdc 500)
        w5       (acct/post-appeal-bond w4 0 alice snap usdc 500)
        ;; Advance time past auto-cancel-time (2000)
        w6       (time-ctx/with-temporal-context w5 {:block-ts 3000})
        r        (lc/auto-cancel-disputed-on-auto-time w6 0)]
    (is (true? (:ok r)) "auto-cancel should succeed")
    (let [w (:world r)]
      (is (= :refunded (t/escrow-state w 0)) "escrow refunded")
      ;; Resolver stake reduced by amount-after-fee (2000 afa with 0 fee)
      (is (= 3000 (reg/get-stake w resolver)) "resolver stake reduced by afa")
      ;; Bond balances cleared
      (is (zero? (get-in w [:bond-balances 0 bob]   0)) "bob bond returned")
      (is (zero? (get-in w [:bond-balances 0 alice]  0)) "alice bond returned")
      ;; total-held must be non-negative (no underflow)
      (is (not (neg? (get-in w [:total-held usdc] 0))) "total-held non-negative")
      ;; Slash distribution recorded
      (is (pos? (get-in w [:bond-distribution :insurance] 0))
          "slash distributed to insurance")
      (is (pos? (get-in w [:bond-distribution :protocol] 0))
          "slash distributed to protocol")
      (is (pos? (:retained-slash-reserves w 0))
          "slash distributed to retained reserves"))))

;; ---------------------------------------------------------------------------
;; auto-cancel-disputed — edge cases
;; ---------------------------------------------------------------------------

(deftest auto-cancel-disputed-no-resolver
  "auto-cancel-disputed succeeds when escrow has no resolver assigned.
   The resolver slash is skipped; escrow is refunded cleanly."
  (let [w (-> (world-disputed)
              (time-ctx/with-temporal-context {:block-ts 5000}))
        r (lc/auto-cancel-disputed-escrow w 0)]
    (is (true? (:ok r)) "auto-cancel should succeed without resolver")
    (let [w' (:world r)]
      (is (= :refunded (t/escrow-state w' 0)) "escrow refunded")
      (is (nil? (get-in w' [:dispute-timestamps 0]))
          "dispute timestamp cleared")
      (is (not (neg? (get-in w' [:total-held usdc] 0)))
          "total-held non-negative"))))

(deftest auto-cancel-disputed-on-auto-time-no-resolver
  "auto-cancel-disputed-on-auto-time succeeds when escrow has no resolver."
  (let [w (-> (world-disputed-with-auto-cancel-time)
              (time-ctx/with-temporal-context {:block-ts 5000}))
        r (lc/auto-cancel-disputed-on-auto-time w 0)]
    (is (true? (:ok r)) "auto-cancel should succeed without resolver")
    (let [w' (:world r)]
      (is (= :refunded (t/escrow-state w' 0)) "escrow refunded")
      (is (nil? (get-in w' [:dispute-timestamps 0]))
          "dispute timestamp cleared")
      (is (not (neg? (get-in w' [:total-held usdc] 0)))
          "total-held non-negative"))))

(deftest auto-cancel-disputed-on-auto-time-zero-time
  "auto-cancel-disputed-on-auto-time fails when auto-cancel-time is 0."
  (let [w (-> (world-disputed)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 0)
              (time-ctx/with-temporal-context {:block-ts 5000}))
        r (lc/auto-cancel-disputed-on-auto-time w 0)]
    (is (false? (:ok r)))
    (is (= :auto-cancel-time-not-passed (:error r)))))

(deftest auto-cancel-disputed-no-resolver-auto-time-zero
  "auto-cancel-disputed succeeds when auto-cancel-time is 0 (uses dispute-timeout)."
  (let [w (-> (world-disputed)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 0)
              (time-ctx/with-temporal-context {:block-ts 5000}))
        r (lc/auto-cancel-disputed-escrow w 0)]
    (is (true? (:ok r)) "standard timeout path still works")
    (let [w' (:world r)]
      (is (= :refunded (t/escrow-state w' 0)) "escrow refunded"))))

(deftest auto-cancel-disputed-with-fot-token
  "auto-cancel-disputed-escrow handles fee-on-transfer tokens correctly.
   The FoT fee is deducted from the gross amount before crediting the
   refund recipient, and :total-fot-fees tracks the withheld amount."
  (let [resolver "0xResolver"
        fot-bps  100  ; 1% FoT
        snap     (snap-fix/escrow-snapshot
                  {:escrow-fee-bps            0
                   :default-auto-release-delay 0
                   :default-auto-cancel-delay  0
                   :max-dispute-duration      3600
                   :appeal-window-duration    0
                   :appeal-bond-protocol-fee-bps 0})
        sett     (t/make-escrow-settings
                  {:custom-resolver resolver})
        w0       (-> (t/empty-world 1000)
                     (assoc-in [:token-fot-bps usdc] fot-bps))
        {:keys [world workflow-id]} (lc/create-escrow w0 alice usdc bob 2000 sett snap)
        w2       (reg/register-stake world resolver 5000)
        w3       (:world (lc/raise-dispute w2 0 alice))
        w4       (time-ctx/with-temporal-context w3 {:block-ts 5000})
        r        (lc/auto-cancel-disputed-escrow w4 0)]
    (is (true? (:ok r)) "auto-cancel with FoT token should succeed")
    (let [w' (:world r)]
      (is (= :refunded (t/escrow-state w' 0)) "escrow refunded")
      (is (pos? (get-in w' [:total-fot-fees usdc] 0))
          "FoT fee tracked in :total-fot-fees")
      (is (not (neg? (get-in w' [:total-held usdc] 0)))
          "total-held non-negative"))))

;; ---------------------------------------------------------------------------
;; Fork-finality: terminal mutation rejection
;;
;; These tests verify that every lifecycle action rejects terminal escrows at
;; the domain-function level, not only at the state-machine transition graph.
;; They complement the absorbing-state tests in state_machine_test.clj.
;; ---------------------------------------------------------------------------

(deftest terminal-escrow-rejects-release
  (testing "Terminal escrows reject release at lifecycle guard"
    (doseq [terminal t/terminal-states]
      (testing (str "from " terminal)
        (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] terminal)
              r (lc/release w 0 alice (fn [_ _ _] {:allowed? true}))]
          (is (false? (:ok r)))
          (is (= :transfer-not-pending (:error r))))))))

(deftest terminal-escrow-rejects-sender-cancel
  (testing "Terminal escrows reject sender-cancel at lifecycle guard"
    (doseq [terminal [:released :refunded :resolved]]
      (testing (str "from " terminal)
        (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] terminal)
              r (lc/sender-cancel w 0 alice nil)]
          (is (false? (:ok r)))
          (is (= :transfer-not-pending (:error r))))))))

(deftest terminal-escrow-rejects-recipient-cancel
  (testing "Terminal escrows reject recipient-cancel at lifecycle guard"
    (doseq [terminal [:released :refunded :resolved]]
      (testing (str "from " terminal)
        (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] terminal)
              r (lc/recipient-cancel w 0 bob nil)]
          (is (false? (:ok r)))
          (is (= :transfer-not-pending (:error r))))))))

(deftest terminal-escrow-rejects-raise-dispute
  (testing "Terminal escrows reject raise-dispute at lifecycle guard"
    (doseq [terminal [:released :refunded :resolved]]
      (testing (str "from " terminal)
        (let [w (assoc-in (world-with-one-escrow) [:escrow-transfers 0 :escrow-state] terminal)
              r (lc/raise-dispute w 0 alice)]
          (is (false? (:ok r)))
          (is (= :transfer-not-pending (:error r))))))))

(deftest terminal-escrow-rejects-execute-resolution
  (testing "Terminal escrows reject execute-resolution at lifecycle guard"
    (doseq [terminal [:released :refunded :resolved]]
      (testing (str "from " terminal)
        (let [w (-> (world-with-one-escrow)
                    (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                    (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
                    (assoc-in [:escrow-transfers 0 :dispute-resolver] "0xResolver")
                    (assoc-in [:escrow-transfers 0 :escrow-state] terminal))
              r (res/execute-resolution w 0 "0xResolver" true "0xhash" nil)]
          (is (false? (:ok r)))
          (is (= :transfer-not-in-dispute (:error r))))))))

(deftest register-stake-via-command-handler-does-not-increase-total-held
  "Regression: the apply-action \"register-stake\" command handler must
   not increase :total-held or :total-principal-deposited.
   Resolver stake is tracked separately in :resolver-stakes."
  (let [agent-index {0 {:address "0xRes"}}
        ctx         {:agent-index agent-index}
        world0      (t/empty-world 1000)
        event       {:params  {:resolver "0xRes" :amount 5000 :token "USDC"}
                     :action  "register-stake"
                     :agent   0}
        result      (sew/apply-action ctx world0 event)
        world1      (:world result)]
    (is (true? (:ok result)) "register-stake should succeed")
    (is (= 5000 (get-in world1 [:resolver-stakes "0xRes"]))
        "stake registered in :resolver-stakes")
    (is (= 0 (get-in world1 [:total-held :USDC] 0))
        ":total-held not increased by register-stake")
    (is (= 0 (get-in world1 [:total-principal-deposited :USDC] 0))
        ":total-principal-deposited not increased by register-stake")))

(deftest withdraw-stake-via-command-handler-does-not-decrease-total-held
  "Regression: the apply-action \"withdraw-stake\" command handler must
   not decrease :total-held for the principal portion (only for yield).
   Resolver stake is tracked separately in :resolver-stakes."
  (let [agent-index {0 {:address "0xRes"}}
        ctx         {:agent-index agent-index}
        world0      (t/empty-world 1000)
        reg-event   {:params  {:resolver "0xRes" :amount 5000 :token "USDC"}
                     :action  "register-stake"
                     :agent   0}
        world1      (:world (sew/apply-action ctx world0 reg-event))
        initial-held   (get-in world1 [:total-held :USDC] 0)
        initial-princ  (get-in world1 [:total-principal-deposited :USDC] 0)
        wdraw-event {:params  {:amount 2000 :token "USDC"}
                     :action  "withdraw-stake"
                     :agent   0}
        result      (sew/apply-action ctx world1 wdraw-event)
        world2      (:world result)]
    (is (true? (:ok result)) "withdraw-stake should succeed")
    (is (= 3000 (reg/get-stake world2 "0xRes"))
        "stake reduced in :resolver-stakes")
    (is (= initial-held (get-in world2 [:total-held :USDC] 0))
        ":total-held not decreased by withdraw-stake principal")
    (is (= initial-princ (get-in world2 [:total-principal-deposited :USDC] 0))
        ":total-principal-deposited unchanged by withdraw-stake")))
