(ns resolver-sim.protocols.sew.invariant-scenarios.cancellation-extended
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer :all]))

(def s-extortion-unilateral-cancel
  "H3 test: Buyer has unilateral cancellation power via {:can-cancel? true
   :unilateral-cancel? true}. Buyer cancels unilaterally -> immediate refund.
   No extortion is possible because the buyer receives A-F (the same as mutual
   cancel or dispute refund) and cannot extract more."
  {:scenario-id     "s-extortion-unilateral-cancel"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 0
                     :max-dispute-duration 2592000
                     :cancellation-strategy {:can-cancel? true
                                             :unilateral-cancel? true}}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500}}
    {:seq 1 :time 1060 :agent "buyer" :action "sender_cancel"
     :params {:workflow-id 0}}]})

(def s-extortion-unilateral-cancel-dual
  "H3 variant: Both parties have unilateral cancel power.
   Seller cancels before buyer acts. Verifies that unilateral cancel is
   available to both parties with symmetric strategy. No party can extract
   more than A-F from any cancel path."
  {:scenario-id     "s-extortion-unilateral-cancel-dual"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 0
                     :max-dispute-duration 2592000
                     :cancellation-strategy {:can-cancel? true
                                             :unilateral-cancel? true}}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500}}
    ;; Seller cancels unilaterally — immediate refund to buyer
    {:seq 1 :time 1060 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}]})

(def s-same-timestamp-cancel-vs-dispute
  "Boundary test: sender_cancel and raise_dispute at the same timestamp.
   Verifies deterministic ordering when cancel and dispute actions collide.
   Cancel-first ordering: sender calls cancel (succeeds, sets senderStatus),
   then dispute is raised at the same timestamp.  Dispute ALSO succeeds
   because senderCancel does not change escrowState (stays PENDING).
   The dispute overrides the cancel status — senderStatus cleared by
   transitionToDisputed.  Dispute wins at same timestamp."
  {:scenario-id     "s-same-timestamp-cancel-vs-dispute"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 0
                     :max-dispute-duration 2592000}
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500}}
    ;; Cancel succeeds at t=1060 (set senderStatus = AGREE_TO_CANCEL, state stays PENDING)
    {:seq 1 :time 1060 :agent "buyer" :action "sender_cancel"
     :params {:workflow-id 0}}
    ;; Dispute at same timestamp — ALSO SUCCEEDS because state is still PENDING.
    ;; Dispute transitions PENDING → DISPUTED and clears senderStatus to NONE.
    {:seq 2 :time 1060 :agent "seller" :action "raise_dispute"
     :params {:workflow-id 0}}]})

(def s-same-timestamp-dispute-vs-cancel
  "Boundary test: raise_dispute and sender_cancel at the same timestamp.
   Dispute-first ordering: dispute is raised first (succeeds), then cancel
   is attempted (rejected because state is DISPUTED — expected revert)."
  {:scenario-id     "s-same-timestamp-dispute-vs-cancel"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 0
                     :max-dispute-duration 2592000}
   :allow-open-disputes? true
   :expected-errors [{:seq 2 :action "sender_cancel" :error :transfer-not-pending}]
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500}}
    ;; Dispute succeeds at t=1060
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     ;; Cancel at same timestamp — expected revert (state is DISPUTED)
     {:seq 2 :time 1060 :agent "buyer" :action "sender_cancel"
      :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; GAP FILL: auto-cancel-time via automate-timed-actions (S9.1)
;;
;; No existing scenario tests the auto-cancel-due? pathway through
;; automate-timed-actions for PENDING escrows.  S04/S17/S60/S94 test
;; auto_cancel_disputed (maxDisputeDuration for DISPUTED state), but the
;; auto-cancel-time field is a separate mechanism: it fires only when the
;; escrow is still PENDING and the absolute timestamp is reached.
;;
;; This scenario creates an escrow with auto-cancel-time=2000, then the
;; keeper calls automate-timed-actions at t=2000.  The escrow is PENDING
;; with no auto-release-time, so the auto-cancel branch fires, refunding
;; the sender.
;; ---------------------------------------------------------------------------

(def s-auto-cancel-time-via-keeper
  "Fundamental gap fill: auto-cancel-time via automate-timed-actions.
   Creates a PENDING escrow with auto-cancel-time set.  At deadline,
   automate-timed-actions fires the auto-cancel branch and refunds.
   No existing scenario tests this pathway."
  {:scenario-id     "s-auto-cancel-time-via-keeper"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :auto-cancel-time 2000}}
    ;; Keeper runs timed actions at deadline → auto-cancel fires
    {:seq 1 :time 2000 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; GAP FILL: auto-cancel-time boundary (S9.2)
;;
;; Tests the deadline-expired? (>=) boundary for auto-cancel-time.
;; At t-1 (1999) the deadline has not yet arrived → automate-timed-actions
;; returns no action.  At t (2000) the deadline is exactly met → auto-cancel
;; fires.
;; ---------------------------------------------------------------------------

(def s-auto-cancel-time-boundary
  "Boundary test: auto-cancel-time at t-1 (no action) vs t (auto-cancel).
   Validates that deadline-expired? uses >= semantics."
  {:scenario-id     "s-auto-cancel-time-boundary"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :auto-cancel-time 2000}}
    ;; Before deadline → no action (escrow stays PENDING)
    {:seq 1 :time 1999 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}
    ;; At deadline → auto-cancel fires (escrow becomes REFUNDED)
    {:seq 2 :time 2000 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; GAP FILL: same-timestamp auto-cancel vs dispute (S11)
;;
;; No scenario tests automate_timed_actions and raise_dispute at the same
;; block time.  Two orderings produce different deterministic outcomes:
;;
;;   auto-cancel-first:  auto-cancel fires (PENDING → REFUNDED), dispute
;;                       reverts (not PENDING).
;;   dispute-first:      dispute succeeds (PENDING → DISPUTED), then
;;                       auto-cancel-due-on-disputed? fires → REFUNDED.
;;
;; These scenarios verify that the seq-ordering produces correct behavior
;; regardless of the race condition.
;; ---------------------------------------------------------------------------

(def s-same-timestamp-auto-cancel-vs-dispute
  "Boundary test: automate_timed_actions and raise_dispute at same timestamp.
   Auto-cancel-first ordering: keeper fires auto-cancel (succeeds), then
   dispute is raised (reverts — escrow is no longer PENDING)."
  {:scenario-id     "s-same-timestamp-auto-cancel-vs-dispute"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params dr3
   :expected-failures {:reverts #{2}}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500
              :auto-cancel-time 1060}}
    ;; Auto-cancel fires first at t=1060 → PENDING, auto-cancel-time passed, escrow refunded
    {:seq 1 :time 1060 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}
    ;; Dispute at same timestamp — expected revert (state is REFUNDED, not PENDING)
    {:seq 2 :time 1060 :agent "seller" :action "raise_dispute"
     :params {:workflow-id 0}}]})

(def s-same-timestamp-dispute-vs-auto-cancel
  "Boundary test: raise_dispute and automate_timed_actions at same timestamp.
   Dispute-first ordering: dispute is raised first (succeeds), then
   auto-cancel fires via auto-cancel-due-on-disputed? → refunded.
   No expected failures — both succeed (the fix closes the griefing)."
  {:scenario-id     "s-same-timestamp-dispute-vs-auto-cancel"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500
              :auto-cancel-time 1060}}
    ;; Dispute succeeds at t=1060 (PENDING → DISPUTED)
    {:seq 1 :time 1060 :agent "seller" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Auto-cancel at same timestamp — fires auto-cancel-disputed-auto-time (the fix!)
    ;; Both actions succeed: dispute briefly sets state to DISPUTED, then
    ;; auto-cancel-due-on-disputed? fires and refunds the buyer immediately.
    {:seq 2 :time 1060 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; KNOWN LIMITATION: auto-cancel-time vs multi-level escalations (S11)
;;
;; auto-cancel-due-on-disputed? does not check dispute-levels.  After
;; escalation clears the pending settlement, there is a vulnerable window
;; where the auto-cancel-time griefing protection would fire, potentially
;; overriding an active multi-level dispute.
;;
;; Similarly, disputeRaisedTimestamp is never reset on escalation, so
;; max-dispute-duration is monotonic from the ORIGINAL dispute raise, not
;; the current escalation level.  Per-round timers in the DRM (resolveBy)
;; are independent from the protocol-level timeout.
;;
;; This is a known architectural limitation.  A design that reset
;; disputeRaisedTimestamp on each escalation (or checked escalation level
;; in the timeout predicates) would eliminate the concern.  For now, the
;; pending-settlement guard provides partial protection.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; GAP FILL: auto-cancel-time orphaned by dispute — CLOSED BY FIX (S9.3 → S10)
;;
;; Attack vector: a dispute raised before auto-cancel-time previously
;; orphaned the deadline because auto-cancel-due? only fires for PENDING.
;; The escrow was forced into the longer max-dispute-duration path.
;;
;; FIX (Session 10): Added auto-cancel-due-on-disputed? (ported to Solidity
;; in Session 10 as ACTION_AUTO_CANCEL_DISPUTED).  When a DISPUTED escrow has
;; auto-cancel-time set and passed, the dispute is automatically cancelled
;; — refunding the sender and closing the griefing window.
;;
;; The scenario now shows that at t=1500 the keeper fires auto-cancel-on-
;; disputed, refunding the buyer immediately (no need to wait for max-
;; dispute-duration at t=1800).
;; ---------------------------------------------------------------------------

(def s-auto-cancel-time-orphaned-by-dispute
  "Griefing vector CLOSED: dispute raised before auto-cancel-time no
   longer orphans the deadline.  automate-timed-actions fires auto-cancel-
   on-disputed at t=1500, refunding the buyer immediately.  Solidity shadow: SettlementOps.sol."
  {:scenario-id     "s-auto-cancel-time-orphaned-by-dispute"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params timeout
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :auto-cancel-time 1500}}
    ;; Dispute raised BEFORE auto-cancel-time (1200 < 1500)
    {:seq 1 :time 1200 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; FIX: auto-cancel-time arrives, dispute is auto-cancelled → refunded
    ;; No longer orphaned!  The new auto-cancel-due-on-disputed? fires
    ;; and calls auto-cancel-disputed-on-auto-time.
    {:seq 2 :time 1500 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; GAP FILL: cancel-strategy {:can-cancel? true, :unilateral-cancel? false}
;;
;; Mutual-only strategy: sender can initiate cancel but it requires
;; recipient agreement.  No immediate refund — falls through to mutual
;; consent path.  Tests that :unilateral-cancel? false correctly defers
;; to the mutual-consent code path.
;; ---------------------------------------------------------------------------

(def s-cancel-strategy-mutual-only
  {:scenario-id     "s-cancel-strategy-mutual-only"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 0
                     :max-dispute-duration 2592000
                     :cancellation-strategy {:can-cancel? true
                                             :unilateral-cancel? false}}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500}}
    ;; Mutual-only: sender cancel sets status (not immediate refund)
    {:seq 1 :time 1060 :agent "buyer" :action "sender_cancel"
     :params {:workflow-id 0}}
    ;; Recipient also cancels → both agreed → refunded
    {:seq 2 :time 1120 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; GAP FILL: cancel-strategy {:can-cancel? false, :unilateral-cancel? true}
;;
;; Verifies that :can-cancel? dominates :unilateral-cancel?.  Even when
;; unilateral-cancel? is true, if can-cancel? is false the cancel must
;; be rejected.  Without this test a refactor could accidentally honor
;; :unilateral-cancel? without checking :can-cancel?.
;; ---------------------------------------------------------------------------

(def s-cancel-strategy-can-cancel-dominates
  {:scenario-id     "s-cancel-strategy-can-cancel-dominates"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 0
                     :max-dispute-duration 2592000
                     :cancellation-strategy {:can-cancel? false
                                             :unilateral-cancel? true}}
   :expected-errors [{:seq 1 :action "sender_cancel" :error :not-authorized-to-cancel-yet}]
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500}}
    ;; can-cancel? false dominates — reject even though unilateral-cancel? true
    {:seq 1 :time 1060 :agent "buyer" :action "sender_cancel"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; GAP FILL: sender-cancel after auto-cancel-time deadline (before keeper)
;;
;; Creates an escrow with auto-cancel-time set and a unilateral cancel
;; strategy.  The sender cancels manually at the deadline (before the
;; keeper fires).  Then the keeper calls automate-timed-actions and gets
;; no action (escrow already refunded).
;; ---------------------------------------------------------------------------

(def s-sender-cancel-after-auto-cancel-deadline
  {:scenario-id     "s-sender-cancel-after-auto-cancel-deadline"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 0
                     :max-dispute-duration 2592000
                     :cancellation-strategy {:can-cancel? true
                                             :unilateral-cancel? true}}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :auto-cancel-time 2000}}
    ;; Sender manually cancels at the deadline → immediate refund (unilateral)
    {:seq 1 :time 2000 :agent "buyer" :action "sender_cancel"
     :params {:workflow-id 0}}
    ;; Keeper fires after manual cancel → no action (escrow already refunded)
    {:seq 2 :time 2100 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; GAP FILL: auto-cancel-due-on-disputed? with time-not-passed
;;
;; Disputed escrow with auto-cancel-time set to a future time.  At t=1300
;; the dispute-timeout has not yet exceeded max-dispute-duration (5000s)
;; and auto-cancel-time (2000) has not yet passed.  automate-timed-actions
;; returns no action.  At t=2000 auto-cancel-time arrives and the griefing
;; protection fires (auto-cancel-due-on-disputed?).
;; ---------------------------------------------------------------------------

(def s-auto-cancel-due-on-disputed-time-not-passed
  {:scenario-id     "s-auto-cancel-due-on-disputed-time-not-passed"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 0
                     :max-dispute-duration 5000}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :auto-cancel-time 2000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; At t=1300: auto-cancel-time (2000) not yet passed, timeout not exceeded
    {:seq 2 :time 1300 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}
    ;; At t=2000: auto-cancel-time passed → auto-cancel-disputed-auto-time fires
    {:seq 3 :time 2000 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; GAP FILL: auto-cancel-due-on-disputed? with pending settlement blocking
;;
;; Disputed escrow with auto-cancel-time passed, but a pending settlement
;; (not yet executable) blocks the auto-cancel griefing protection.
;; At t=1300 the pending settlement's appeal deadline (1400) has not passed,
;; and the dispute-timeout has not exceeded max-dispute-duration (5000).
;; At t=1500 the settlement becomes executable and Priority 1 fires.
;; ---------------------------------------------------------------------------

(def s-auto-cancel-due-on-disputed-pending-settlement
  {:scenario-id     "s-auto-cancel-due-on-disputed-pending-settlement"
   :schema-version  "1.0"
   :scenario-author "@research"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params {:resolver-fee-bps 150
                     :appeal-window-duration 300
                     :max-dispute-duration 5000}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :custom-resolver "0xresolver"
              :auto-cancel-time 1200}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits resolution → pending settlement (appeal deadline = 1100+300 = 1400)
    {:seq 2 :time 1100 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; At t=1300: auto-cancel-time passed but pending settlement blocks auto-cancel
    ;; (pending not executable yet — deadline 1400, timeout not exceeded — 240<5000)
    {:seq 3 :time 1300 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}
    ;; At t=1500: pending-settlement-executable? true → execute settlement
    {:seq 4 :time 1500 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}]})
