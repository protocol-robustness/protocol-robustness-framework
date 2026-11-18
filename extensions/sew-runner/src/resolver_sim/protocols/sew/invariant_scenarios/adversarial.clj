(ns resolver-sim.protocols.sew.invariant-scenarios.adversarial
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer :all]
            [resolver-sim.protocols.sew.invariant-scenarios.forking-strategist :as fork]))

(def s24
  {:scenario-id        "s24-resolver-stake-depletion-cascade"
   :schema-version     "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents             [{:id "buyer0"   :address "0xbuyer0"   :strategy "honest"}
                        {:id "buyer1"   :address "0xbuyer1"   :strategy "honest"}
                        {:id "buyer2"   :address "0xbuyer2"   :strategy "honest"}
                        {:id "seller"   :address "0xseller"   :strategy "honest"}
                        {:id "resolver" :address "0xresolver" :role "resolver"}
                        {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params    stake-cascade
   :events
   [;; Resolver registers stake before any escrow is opened
    {:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 3000}}
    {:seq 1 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 2 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 3 :time 1000 :agent "buyer2" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 4 :time 1060 :agent "buyer0" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 6 :time 1060 :agent "buyer2" :action "raise_dispute"
     :params {:workflow-id 2}}
    ;; Early attempt: 240 s elapsed, need 300 → rejected
    {:seq 7 :time 1300 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    ;; Timeout reached — full slash: resolver stake 3000 → 1000
    {:seq 8 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    ;; Partial slash: only 1000 remains; actual_slashed=1000, stake 1000 → 0
    {:seq 9 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 1}}
    ;; Zero-stake slash: resolver exhausted; actual_slashed=0, stake stays 0
    {:seq 10 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 2}}]})

;; ---------------------------------------------------------------------------
;; S25 — Profit-Maximizer: fraud-slash lifecycle
;;
;; Adversarial actor: governance that proposes a speculative fraud slash to
;; extract value from a resolver, then tries to execute it after losing the
;; appeal.
;;
;; Sequence:
;;   1. Buyer creates escrow (8000 USDC, appeal-window=120).
;;   2. Buyer raises dispute; resolver submits a release decision
;;      → pending (deadline = 1120+120 = 1240).
;;   3. Governance proposes a fraud slash against the resolver (amount=500)
;;      → slash is :pending (appeal-deadline = 1130+120 = 1250).
;;   4. Resolver appeals the slash within the window.
;;      → slash status: :appealed.
;;   5. Governance resolves the appeal in the resolver's favour (upheld?=true)
;;      → slash status: :reversed.
;;   6. Governance attempts to execute the reversed slash
;;      → rejected (:slash-already-reversed).
;;   7. Keeper executes the original pending settlement after deadline.
;;      → escrow :released.
;;
;; Expected: PASS.
;;
;; Invariants exercised:
;;   slash-status-consistent? — slash transitions pending→appealed→reversed
;;   no-auto-fraud-execute?   — slash went through proper pending window
;;   appeal-bond-conserved?   — no negative bond amounts anywhere
;;   conservation-of-funds?   — 0 held + AFA released + 0 refunded = AFA deposited
;;   terminal-states-unchanged? — once released, stays released
;; ---------------------------------------------------------------------------

(def s25
  {:scenario-id     "s25-profit-maximizer-slash-lifecycle"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"     :strategy "honest"}
                     {:id "seller"     :address "0xseller"    :strategy "honest"}
                     {:id "resolver"   :address "0xresolver"  :role "resolver"}
                     {:id "governance" :address "0xgov"       :role "governance"}
                     {:id "keeper"     :address "0xkeeper"    :role "keeper"}]
   :protocol-params appeal ; appeal-window=120s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits decision → pending (deadline = 1120+120 = 1240)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes a fraud slash (profit-maximizer trying to penalise resolver)
    ;; slash appeal-deadline = 1130 + 120 (appeal-window-duration from snapshot) = 1250
    {:seq 3 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; Resolver appeals the slash within the window
    {:seq 4 :time 1140 :agent "resolver" :action "appeal_slash"
     :params {:workflow-id 0}}
    ;; Governance resolves the appeal: resolver wins (upheld?=true → slash :reversed)
    {:seq 5 :time 1160 :agent "governance" :action "resolve_appeal"
     :params {:workflow-id 0 :upheld? true}}
    ;; Governance tries to execute the reversed slash → rejected (:slash-already-reversed)
    {:seq 6 :time 1200 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}
    ;; Keeper executes the pending settlement after its deadline (1240)
    {:seq 7 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S26 — Forking-Strategist: L0→L1 decision reversal
;;
;; Adversarial actor: buyer who escalates strategically after losing at L0,
;; gambling that a different L1 resolver will fork to the opposite outcome.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros appeal-window=60s).
;;   2. Buyer raises dispute.
;;   3. L0 resolver rules :release (in favour of seller).
;;      → pending (deadline = 1120+60 = 1180).
;;   4. Buyer (forking strategist) escalates before the deadline, posting a
;;      challenge bond (100 USDC default) → level 0→1, new-resolver=0xl1.
;;   5. L1 resolver rules :refund (opposite of L0 = the "fork").
;;      → new pending (deadline = 1190+60 = 1250).
;;   6. Keeper executes the pending settlement after the L1 deadline.
;;      → escrow :refunded.
;;
;; Expected: PASS — the protocol handles a cross-level decision fork correctly.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?       — level advances 0→1, never goes back
;;   finalization-accounting-correct?  — L1 refund recorded despite L0 release vote
;;   conservation-of-funds?            — 0 held + 0 released + AFA refunded = AFA deposited
;;   token-tax-reconciliation?         — delta-held matches delta-refunded exactly
;;   fee-cap-holds?                    — escrow-fee + challenge-bond ≤ original amount
;;   terminal-states-unchanged?        — once :refunded, stays :refunded
;; ---------------------------------------------------------------------------

(def s26
  (fork/enrich-scenario
   {:scenario-id        "s26-forking-strategist-l1-reversal"
    :schema-version     "1.0"
    :scenario-author    "@grifma"
    :initial-block-time 1000
    :agents             [{:id "buyer"      :address "0xbuyer"  :strategy "forking-strategist"}
                         {:id "seller"     :address "0xseller" :strategy "honest"}
                         {:id "l0resolver" :address "0xl0"     :role "resolver"}
                         {:id "l1resolver" :address "0xl1"     :role "resolver"}
                         {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 6000}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
      :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
     {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release false :resolution-hash "0xl1hash"}}
     {:seq 5 :time 1255 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}]}
   {:scenario-title   "L1 reversal after L0 release"
    :scenario-purpose "Verify escalation opens a new branch and L1 can fork the L0 outcome to refund."
    :description      "Buyer escalates after L0 release; L1 rules refund; pending settlement finalizes without corrupting prior hashes."
    :threat-model     {:actor "buyer" :strategy "forking-strategist"
                       :goal  "Reverse an unfavourable L0 release via L1 escalation."}
    :expected-outcome {:workflow-0 "refund-after-l1-reversal"}
    :notes            "Falsifies if L1 refund mutates L0 audit trail incorrectly, appeal window is bypassed, or funds leak on reversal."
    :theory           (fork/theory-block
                       {:claim-id :claims/forking-strategist-l1-reversal
                        :claim    "L1 escalation may reverse L0 without breaking accounting or monotonic dispute level."})
    :expectations     (fork/single-wf-expectations
                       {:final-state    :refunded
                        :claim-address  "0xbuyer"
                        :claim-amount   fork/escrow-afa-6000
                        :dispute-level  1
                        :step-terminal  [{:seq 3 :path ["dispute-levels" 0] :equals 1}]})}))

;; ---------------------------------------------------------------------------
;; S27 — Forking-Strategist: full 3-level ladder, fork at L2
;;
;; Adversarial actor: buyer who keeps escalating even after L1 confirms L0's
;; decision, betting that a different L2 resolver will finally rule in their
;; favour.  The fork materialises at L2.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release (buyer loses).  Pending deadline = 1120+60 = 1180.
;;   4. Buyer escalates before deadline → level 0→1, new-resolver=0xl1.
;;   5. L1 also rules :release (no fork yet).  Pending deadline = 1190+60 = 1250.
;;   6. Buyer escalates again before deadline → level 1→2, new-resolver=0xl2.
;;   7. L2 rules :refund (the fork finally arrives).  Pending deadline = 1260+60 = 1320.
;;   8. Keeper executes after L2 deadline → escrow :refunded.
;;
;; Expected: PASS — the protocol handles a full 3-level decision reversal.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?      — level advances 0→1→2, never reverses
;;   conservation-of-funds?           — 0 held + 0 released + AFA refunded = AFA deposited
;;   fee-cap-holds?                   — fee + two challenge bonds ≤ original amount
;;   token-tax-reconciliation?        — delta-held matches delta-refunded
;;   terminal-states-unchanged?       — once :refunded, stays :refunded
;; ---------------------------------------------------------------------------

(def s27
  (fork/enrich-scenario
   {:scenario-id        "s27-forking-strategist-l2-fork"
    :schema-version     "1.0"
    :scenario-author    "@grifma"
    :initial-block-time 1000
    :agents             [{:id "buyer"      :address "0xbuyer"  :strategy "forking-strategist"}
                         {:id "seller"     :address "0xseller" :strategy "honest"}
                         {:id "l0resolver" :address "0xl0"     :role "resolver"}
                         {:id "l1resolver" :address "0xl1"     :role "resolver"}
                         {:id "l2resolver" :address "0xl2"     :role "resolver"}
                         {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 6000}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
      :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
     {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
     {:seq 5 :time 1200 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 6 :time 1260 :agent "l2resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release false :resolution-hash "0xl2hash"}}
     {:seq 7 :time 1325 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}]}
   {:scenario-title   "L2 fork after confirming L0 and L1"
    :scenario-purpose "Verify full three-level ladder: fork materializes only at L2."
    :description      "Buyer escalates through L0 and L1 (both release); L2 refunds — distinct resolution hashes per level."
    :threat-model     {:actor "buyer" :strategy "forking-strategist"
                       :goal  "Keep escalating until a higher tier reverses the outcome."}
    :expected-outcome {:workflow-0 "refund-after-l2-reversal"}
    :theory           (fork/theory-block
                       {:claim-id :claims/forking-strategist-l2-fork
                        :claim    "Dispute level advances monotonically 0→1→2; L2 may fork without rewriting L0/L1 hashes."})
    :expectations     (fork/single-wf-expectations
                       {:final-state    :refunded
                        :claim-address  "0xbuyer"
                        :claim-amount   fork/escrow-afa-6000
                        :dispute-level  2
                        :step-terminal  [{:seq 5 :path ["dispute-levels" 0] :equals 2}]})}))

;; ---------------------------------------------------------------------------
;; S28 — Forking-Strategist: late escalation rejected, L0 decision stands
;;
;; Adversarial actor: buyer who waits too long before attempting to escalate.
;; The L0 appeal window expires before they act; the escalation is rejected
;; and the L0 pending settlement executes normally.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release.  Pending deadline = 1120+60 = 1180.
;;   4. Buyer attempts escalation at t=1185 (5 s after deadline) → rejected.
;;   5. Keeper executes the still-live L0 pending settlement at t=1190.
;;      → escrow :released.
;;
;; Expected: PASS — the appeal-window guard correctly rejects the late escalation;
;; no invariant fires; L0 outcome stands.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?    — level never advances (stays at 0)
;;   terminal-states-unchanged?     — once :released via L0, stays :released
;;   conservation-of-funds?         — 0 held + AFA released + 0 refunded = AFA deposited
;;   no-stale-automatable-escrows?  — no automatable work remains after settlement
;; ---------------------------------------------------------------------------

(def s28
  (fork/enrich-scenario
   {:scenario-id        "s28-forking-strategist-late-escalation-rejected"
    :schema-version     "1.0"
    :scenario-author    "@grifma"
    :initial-block-time 1000
    :agents             [{:id "buyer"      :address "0xbuyer"  :strategy "forking-strategist"}
                         {:id "seller"     :address "0xseller" :strategy "honest"}
                         {:id "l0resolver" :address "0xl0"     :role "resolver"}
                         {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 6000}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
      :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
     {:seq 3 :time 1185 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 4 :time 1190 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}]}
   {:scenario-title            "Late escalation rejected; L0 release stands"
    :scenario-purpose          "Verify appeal-window guard rejects post-deadline escalation."
    :description               "Escalation after L0 appeal window expires is rejected; L0 pending settlement still executes."
    :threat-model              {:actor "buyer" :strategy "forking-strategist"
                                :goal  "Escalate after the appeal window to overturn L0."}
    :expected-outcome          {:workflow-0 "release-after-l0-finalization"}
    :strict-expected-errors?   true
    :expected-errors           [{:seq 3 :action "escalate_dispute" :error :appeal-window-expired}]
    :theory                    (fork/theory-block
                                {:claim-id :claims/forking-strategist-late-escalation-rejected
                                 :claim    "Post-deadline escalation must not advance dispute level or block L0 settlement."})
    :expectations              (fork/single-wf-expectations
                                {:final-state   :released
                                 :claim-address "0xseller"
                                 :claim-amount  fork/escrow-afa-6000
                                 :step-terminal [{:seq 4 :path ["live-states" 0] :equals :released}]})}))

;; ---------------------------------------------------------------------------
;; S29 — Forking-Strategist: seller escalates after losing L0 refund
;;
;; Role reversal: it is the seller (not the buyer) who acts as the forking
;; strategist.  L0 rules refund (buyer wins); seller escalates hoping L1
;; will fork to release.  L1 does.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :refund (buyer wins; seller loses).  Pending deadline = 1120+60 = 1180.
;;   4. Seller (forking strategist) escalates before deadline.
;;      → level 0→1, new-resolver=0xl1.
;;   5. L1 forks to :release (seller wins).  Pending deadline = 1190+60 = 1250.
;;   6. Keeper executes after L1 deadline → escrow :released.
;;
;; Expected: PASS — protocol correctly handles a seller-initiated escalation
;; that reverses a buyer-favourable L0 outcome.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?       — level advances 0→1, never reverses
;;   finalization-accounting-correct?  — release recorded despite prior refund vote
;;   conservation-of-funds?            — 0 held + AFA released + 0 refunded = AFA deposited
;;   fee-cap-holds?                    — fee + seller's challenge bond ≤ 6000
;;   terminal-states-unchanged?        — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s29
  (fork/enrich-scenario
   {:scenario-id        "s29-forking-strategist-seller-escalates"
    :schema-version     "1.0"
    :scenario-author    "@grifma"
    :initial-block-time 1000
    :agents             [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                         {:id "seller"     :address "0xseller" :strategy "forking-strategist"}
                         {:id "l0resolver" :address "0xl0"     :role "resolver"}
                         {:id "l1resolver" :address "0xl1"     :role "resolver"}
                         {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 6000}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
      :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release false :resolution-hash "0xl0hash"}}
     {:seq 3 :time 1130 :agent "seller" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
     {:seq 5 :time 1255 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}]}
   {:scenario-title   "Seller-initiated L1 fork to release"
    :scenario-purpose "Verify non-buyer participant may escalate and fork an L0 refund to release."
    :description      "L0 refunds buyer; seller escalates; L1 releases to seller — participant role does not break escalation rules."
    :threat-model     {:actor "seller" :strategy "forking-strategist"
                       :goal  "Reverse L0 refund via L1 release."}
    :expected-outcome {:workflow-0 "release-after-l1-reversal"}
    :theory           (fork/theory-block
                       {:claim-id :claims/forking-strategist-seller-escalates
                        :claim    "Seller escalation may fork L0 refund to L1 release with correct bonds and hashes."})
    :expectations     (fork/single-wf-expectations
                       {:final-state    :released
                        :claim-address  "0xseller"
                        :claim-amount   fork/escrow-afa-6000
                        :dispute-level  1})}))

;; ---------------------------------------------------------------------------
;; S30 — Forking-Strategist: double-loss, fork never materialises
;;
;; Adversarial actor: buyer who escalates to L1 expecting a fork.  L1
;; confirms L0's decision (both rule release); the buyer loses their
;; challenge bond and the escrow settles at the original outcome.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release (buyer loses).  Pending deadline = 1120+60 = 1180.
;;   4. Buyer escalates before deadline (expensive gamble).
;;      → level 0→1, challenge bond posted, new-resolver=0xl1.
;;   5. L1 also rules :release (no fork — L1 agrees with L0).
;;      Pending deadline = 1190+60 = 1250.
;;   6. Appeal window expires without further escalation.
;;   7. Keeper executes the L1 pending settlement → escrow :released.
;;      Buyer forfeits their challenge bond for nothing.
;;
;; Expected: PASS — no invariant fires; buyer's failed gamble is captured
;; in bond accounting; final outcome is identical to L0 (release).
;;
;; Invariants exercised:
;;   escalation-level-monotonic?      — level advances 0→1 only
;;   conservation-of-funds?           — 0 held + AFA released + 0 refunded = AFA deposited
;;   fee-cap-holds?                   — fee + forfeited challenge bond ≤ 6000
;;   token-tax-reconciliation?        — delta-held == delta-released (no unexplained leak)
;;   terminal-states-unchanged?       — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s30
  (fork/enrich-scenario
   {:scenario-id        "s30-forking-strategist-double-loss"
    :schema-version     "1.0"
    :scenario-author    "@grifma"
    :initial-block-time 1000
    :agents             [{:id "buyer"      :address "0xbuyer"  :strategy "forking-strategist"}
                         {:id "seller"     :address "0xseller" :strategy "honest"}
                         {:id "l0resolver" :address "0xl0"     :role "resolver"}
                         {:id "l1resolver" :address "0xl1"     :role "resolver"}
                         {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 6000}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
      :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
     {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
     {:seq 5 :time 1255 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}]}
   {:scenario-title   "Double loss: L1 confirms L0 release"
    :scenario-purpose "Verify failed fork gamble: L1 agrees with L0; buyer loses bond; outcome stays release."
    :description      "Buyer escalates but L1 also releases — no reversal; challenge bond forfeited without invariant break."
    :threat-model     {:actor "buyer" :strategy "forking-strategist"
                       :goal  "Force L1 reversal; instead L1 confirms L0."}
    :expected-outcome {:workflow-0 "release-after-l1-confirmation"}
    :theory           (fork/theory-block
                       {:claim-id :claims/forking-strategist-double-loss
                        :claim    "When L1 confirms L0, final settlement remains release and accounting stays conserved."})
    :expectations     (fork/single-wf-expectations
                       {:final-state    :released
                        :claim-address  "0xseller"
                        :claim-amount   fork/escrow-afa-6000
                        :dispute-level  1})}))

;; ---------------------------------------------------------------------------
;; S31 — Forking-Strategist: all three levels confirm — no fork ever materialises
;;
;; Adversarial actor: buyer who keeps escalating even after two consecutive
;; confirming rulings (L0 and L1 both release), then tries a third escalation
;; at the maximum level — which is rejected because the protocol caps escalation
;; at level 2.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release.  Pending deadline = 1120+60 = 1180.
;;   4. Buyer escalates → level 0→1, new-resolver=0xl1.
;;   5. L1 also rules :release (no fork).  Pending deadline = 1190+60 = 1250.
;;   6. Buyer escalates again → level 1→2, new-resolver=0xl2.
;;   7. L2 also rules :release (still no fork).  Pending deadline = 1260+60 = 1320.
;;   8. Buyer attempts a third escalation → rejected (:escalation-not-allowed,
;;      final-round? = true, max-dispute-level = 2).
;;   9. Keeper executes the L2 pending settlement → escrow :released.
;;      Buyer has lost two challenge bonds for nothing.
;;
;; Expected: PASS — max-level guard fires correctly; two bonds are accounted for
;; without any invariant violation.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?   — level advances 0→1→2, then stays at 2
;;   fee-cap-holds?                — escrow-fee + 2 challenge bonds ≤ 6000
;;   conservation-of-funds?        — 0 held + AFA released + 0 refunded = AFA deposited
;;   token-tax-reconciliation?     — delta-held == delta-released exactly
;;   terminal-states-unchanged?    — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s31
  (fork/enrich-scenario
   {:scenario-id        "s31-forking-strategist-all-levels-confirm"
    :schema-version     "1.0"
    :scenario-author    "@grifma"
    :initial-block-time 1000
    :agents             [{:id "buyer"      :address "0xbuyer"  :strategy "forking-strategist"}
                         {:id "seller"     :address "0xseller" :strategy "honest"}
                         {:id "l0resolver" :address "0xl0"     :role "resolver"}
                         {:id "l1resolver" :address "0xl1"     :role "resolver"}
                         {:id "l2resolver" :address "0xl2"     :role "resolver"}
                         {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 6000}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
      :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
     {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
     {:seq 5 :time 1200 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 6 :time 1260 :agent "l2resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl2hash"}}
     {:seq 7 :time 1270 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 8 :time 1325 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}]}
   {:scenario-title            "Max-level guard rejects third escalation"
    :scenario-purpose          "Verify escalation cap at level 2; all levels may confirm release without fork."
    :description               "L0/L1/L2 all release; third escalation rejected; two bonds lost; final release at L2."
    :threat-model              {:actor "buyer" :strategy "forking-strategist"
                                :goal  "Escalate past max level after unanimous release votes."}
    :expected-outcome          {:workflow-0 "release-after-l2-confirmation"}
    :strict-expected-errors?   true
    :expected-errors           [{:seq 7 :action "escalate_dispute" :error :transfer-not-in-dispute}
                                {:seq 8 :action "execute_pending_settlement" :error :no-pending-settlement}]
    :theory                    (fork/theory-block
                                {:claim-id :claims/forking-strategist-all-levels-confirm
                                 :claim    "Third escalation after max level must reject without corrupting L2 pending settlement."})
    :expectations              (fork/single-wf-expectations
                                {:final-state    :released
                                 :claim-address  "0xseller"
                                 :claim-amount   fork/escrow-afa-6000
                                 :dispute-level  2})}))

;; ---------------------------------------------------------------------------
;; S32 — Forking-Strategist: fork lands at L1; keeper attempts settlement too
;;       early; appeal window expires without L2 escalation; retry succeeds
;;
;; Two-phase outcome: buyer's fork attempt succeeds (L1 reverses L0), but the
;; keeper tries to execute the pending settlement while the L1 appeal window is
;; still open (rejected :appeal-window-not-expired).  Buyer chooses not to
;; escalate to L2.  After the window closes, the keeper retries and the fork
;; (refund) is finalised.
;;
;; Sequence:
;;   1. Buyer creates escrow (6000 USDC, kleros-appeal window=60 s).
;;   2. Buyer raises dispute.
;;   3. L0 rules :release.  Pending deadline = 1120+60 = 1180.
;;   4. Buyer escalates → level 0→1, new-resolver=0xl1.
;;   5. L1 rules :refund (the fork).  Pending deadline = 1190+60 = 1250.
;;   6. Keeper tries execute_pending_settlement at t=1200 (<1250)
;;      → rejected (:appeal-window-not-expired).
;;   7. Appeal window expires; buyer does not escalate to L2.
;;   8. Keeper retries at t=1255 → escrow :refunded.
;;
;; Expected: PASS — the premature settlement attempt is cleanly rejected without
;; corrupting state; the L1 fork is finalised correctly on retry.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?       — level advances 0→1, stays at 1
;;   finalization-accounting-correct?  — refund recorded despite L0 release vote
;;   conservation-of-funds?            — 0 held + 0 released + AFA refunded = AFA deposited
;;   no-stale-automatable-escrows?     — no automatable work remains after settlement
;;   terminal-states-unchanged?        — once :refunded, stays :refunded
;; ---------------------------------------------------------------------------

(def s32
  (fork/enrich-scenario
   {:scenario-id        "s32-forking-strategist-premature-settlement-rejected"
    :schema-version     "1.0"
    :scenario-author    "@grifma"
    :initial-block-time 1000
    :agents             [{:id "buyer"      :address "0xbuyer"  :strategy "forking-strategist"}
                         {:id "seller"     :address "0xseller" :strategy "honest"}
                         {:id "l0resolver" :address "0xl0"     :role "resolver"}
                         {:id "l1resolver" :address "0xl1"     :role "resolver"}
                         {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 6000}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
      :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
     {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
      :params {:workflow-id 0}}
     {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release false :resolution-hash "0xl1hash"}}
     {:seq 5 :time 1200 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}
     {:seq 6 :time 1255 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}]}
   {:scenario-title            "Premature settlement rejected; L1 fork finalizes"
    :scenario-purpose          "Verify keeper cannot execute before L1 appeal window expires."
    :description               "Early execute_pending_settlement rejected; retry after window closes refunds buyer."
    :threat-model              {:actor "buyer" :strategy "forking-strategist"
                                :goal  "Finalize L1 refund before appeal window allows."}
    :expected-outcome          {:workflow-0 "refund-after-l1-reversal"}
    :strict-expected-errors?   true
    :expected-errors           [{:seq 5 :action "execute_pending_settlement" :error :appeal-window-not-expired}]
    :theory                    (fork/theory-block
                                {:claim-id :claims/forking-strategist-premature-settlement-rejected
                                 :claim    "Premature settlement must reject without corrupting pending state; post-window refund succeeds."})
    :expectations              (fork/single-wf-expectations
                                {:final-state    :refunded
                                 :claim-address  "0xbuyer"
                                 :claim-amount   fork/escrow-afa-6000
                                 :dispute-level  1
                                 :step-terminal  [{:seq 5 :path ["live-states" 0] :equals :disputed}]})}))

;; ---------------------------------------------------------------------------
;; S33 — Forking-Strategist: two concurrent disputes — fork on wf0, no
;;       escalation on wf1 — tests per-escrow state isolation
;;
;; Two independent escrows opened simultaneously.  buyer0 plays the forking
;; strategist on wf0 (L0 releases → buyer escalates → L1 refunds).  buyer1
;; accepts the L0 outcome on wf1 (no escalation, pending settles normally).
;; The two escrows travel completely different resolution paths and must not
;; contaminate each other's state or accounting.
;;
;; Sequence:
;;   1.  buyer0 creates wf0 (6000 USDC) and buyer1 creates wf1 (4000 USDC).
;;   2.  Both raise disputes.
;;   3.  L0 rules :release on both.
;;       wf0 pending deadline = 1120+60 = 1180.
;;       wf1 pending deadline = 1120+60 = 1180.
;;   4.  buyer0 escalates wf0 before its deadline → level 0→1.
;;       wf1 is not escalated; its pending settlement remains live.
;;   5.  Keeper executes wf1 pending at t=1185 (after wf1 deadline) → wf1 :released.
;;   6.  L1 rules :refund on wf0 (the fork).  wf0 pending deadline = 1190+60 = 1250.
;;   7.  Keeper executes wf0 pending at t=1255 → wf0 :refunded.
;;
;; Expected: PASS — two entirely different outcomes on co-existing escrows
;; without any cross-contamination of held amounts, dispute levels, or
;; settlement state.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?   — wf0 level 0→1; wf1 level never changes
;;   held-custody-reconciles?               — two escrows tracked independently at each step
;;   conservation-of-funds?        — (wf0 AFA refunded) + (wf1 AFA released) = total deposited AFA
;;   fee-cap-holds?                — separate fee-cap checks for each escrow
;;   terminal-states-unchanged?    — both escrows stay in their terminal state
;; ---------------------------------------------------------------------------

(def s33
  {:scenario-id        "s33-forking-strategist-two-escrow-fork-isolation"
   :schema-version     "1.0"
   :scenario-author    "@grifma"
   :scenario-title     "Fork isolation across two disputed escrows"
   :scenario-family    "forking-strategist"
   :scenario-purpose   "Verify that escalation of one workflow creates an isolated dispute branch and does not affect settlement of another workflow sharing the same seller and L0 resolver."
   :description        "Two escrows share seller and L0 resolver. One dispute is escalated after L0 resolution while the other settles independently. Proves escalation forks only the selected workflow."
   :purpose            :adversarial-robustness
   :threat-tags        ["fork-isolation" "state-isolation" "appeal-escalation"]
   :security-properties [:workflow-isolation
                         :settlement-isolation
                         :appeal-window-isolation
                         :resolution-hash-isolation
                         :claimable-balance-isolation]
   :threat-model       {:actor    "buyer0"
                        :strategy "forking-strategist"
                        :goal     "Cause settlement or escalation state from one escrow to contaminate another escrow."}
   :expected-outcome   {:workflow-0 "refund-after-l1-reversal"
                        :workflow-1 "release-after-l0-finalization"}
   :notes              "Falsifies if: workflow-1 cannot settle after its appeal window because workflow-0 escalated; workflow-1 L0 release is overwritten by workflow-0 L1 refund; claimables mix across workflows; resolution hashes alias; appeal or dispute-level state leaks between workflows."
   :theory             {:claim-id     :claims/workflow-dispute-isolation-shared-resolver
                        :claim        "Escalating workflow 0 must not alter, block, cancel, delay, overwrite, or contaminate the pending settlement state of workflow 1."
                        :assumptions  [:appeal-window-60s :shared-l0-resolver :independent-escrow-state]
                        :falsifies-if [{:metric :funds-lost :op :> :value 0}
                                       {:metric :double-settlements :op :> :value 0}]}
   :expectations       {:terminal [{:path ["live-states" 0] :equals :refunded}
                                   {:path ["live-states" 1] :equals :released}
                                   {:path ["claimable" 0 "0xbuyer0"] :equals 5910}
                                   {:path ["claimable" 1 "0xseller"] :equals 3940}
                                   {:path ["dispute-levels" 0] :equals 1}]
                        :step-terminal [{:seq 7 :path ["live-states" 1] :equals :released}
                                        {:seq 6 :path ["live-states" 1] :equals :disputed}
                                        {:seq 6 :path ["dispute-levels" 0] :equals 1}]}
   :initial-block-time 1000
   :agents             [{:id "buyer0"     :address "0xbuyer0" :strategy "forking-strategist"}
                        {:id "buyer1"     :address "0xbuyer1" :strategy "honest"}
                        {:id "seller"     :address "0xseller" :strategy "honest"}
                        {:id "l0resolver" :address "0xl0"     :role "resolver"}
                        {:id "l1resolver" :address "0xl1"     :role "resolver"}
                        {:id "l2resolver" :address "0xl2"     :role "resolver"}
                        {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params    kleros-appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}
     :save-id-as "wf0"}
    {:seq 1 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000}
     :save-id-as "wf1"}
    {:seq 2 :time 1060 :agent "buyer0" :action "raise_dispute"
     :params {:workflow-id "wf0"}}
    {:seq 3 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id "wf1"}}
    {:seq 4 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id "wf0" :is-release true :resolution-hash "0xl0-wf0-hash"}}
    {:seq 5 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id "wf1" :is-release true :resolution-hash "0xl0-wf1-hash"}}
    {:seq 6 :time 1130 :agent "buyer0" :action "escalate_dispute"
     :params {:workflow-id "wf0"}}
    {:seq 7 :time 1185 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id "wf1"}}
    {:seq 8 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id "wf0" :is-release false :resolution-hash "0xl1-wf0-hash"}}
    {:seq 9 :time 1255 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id "wf0"}}]})

;; ---------------------------------------------------------------------------
;; S34 — Profit-Maximizer: unchallenged slash (resolver forfeits)
;;
;; The simplest profit-extraction path: governance proposes a fraud slash,
;; the resolver chooses not to contest it, the appeal window closes, and
;; governance executes.  No appeal is ever filed.
;;
;; Sequence:
;;   1. Buyer creates escrow (8000 USDC, appeal-window=120 s).
;;   2. Buyer raises dispute; resolver submits release decision.
;;      Pending deadline = 1120+120 = 1240.
;;   3. Governance proposes a fraud slash (amount=500).
;;      Slash appeal-deadline = 1130+120 = 1250.
;;   4. Resolver does not appeal.
;;   5. Keeper executes the pending settlement first → escrow :released.
;;   6. Governance executes the slash at t=1255 (>1250) → slash :executed;
;;      resolver stake is debited by min(stake, 500).
;;
;; Expected: PASS — the unchallenged slash lifecycle completes without
;; any invariant violation.  slash and escrow settlement are independent.
;;
;; Invariants exercised:
;;   slash-status-consistent?     — slash pending→executed (no intermediate states)
;;   no-auto-fraud-execute?        — execute happens after explicit timelock
;;   conservation-of-funds?        — AFA released; slash operates on resolver-stakes
;;   terminal-states-unchanged?    — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s34
  {:scenario-id     "s34-profit-maximizer-unchallenged-slash"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"     :address "0xseller"   :strategy "honest"}
                     {:id "resolver"   :address "0xresolver" :role "resolver"}
                     {:id "governance" :address "0xgov"      :role "governance"}
                     {:id "keeper"     :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal ; appeal-window=120 s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits decision → pending (deadline = 1120+120 = 1240)
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes slash. Slash appeal-deadline = 1130+120 = 1250.
    {:seq 4 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; [Resolver does not appeal — forfeits.]
    ;; Settle escrow first (deadline 1240 has passed)
    {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Governance executes after appeal-deadline → slash :executed
    {:seq 6 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S35 — Profit-Maximizer: governance wins the appeal
;;
;; Resolver contests the slash (appeals), but governance rejects the appeal
;; (upheld?=false → slash reverts to :pending).  Governance then executes
;; the confirmed slash after the original appeal-deadline.
;;
;; Sequence:
;;   1. Buyer creates escrow (8000 USDC, appeal-window=120 s).
;;   2. Buyer raises dispute; resolver submits release decision.
;;      Pending deadline = 1120+120 = 1240.
;;   3. Governance proposes slash (amount=500).
;;      Slash appeal-deadline = 1130+120 = 1250.
;;   4. Resolver appeals within the window → slash :appealed.
;;   5. Governance resolves appeal with upheld?=false (appeal rejected).
;;      → slash status reverts to :pending (same appeal-deadline = 1250).
;;   6. Keeper settles the escrow first → :released.
;;   7. Governance executes at t=1255 (>1250) → slash :executed.
;;
;; Expected: PASS — full appeal cycle where governance prevails; resolver
;; cannot replay the appeal; slash executes on the original timelock.
;;
;; Invariants exercised:
;;   slash-status-consistent?     — pending→appealed→pending→executed
;;   appeal-bond-conserved?       — bond amounts stay non-negative throughout
;;   conservation-of-funds?       — AFA released; slash operates on resolver-stakes
;;   terminal-states-unchanged?   — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s35
  {:scenario-id     "s35-profit-maximizer-governance-wins-appeal"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"     :address "0xseller"   :strategy "honest"}
                     {:id "resolver"   :address "0xresolver" :role "resolver"}
                     {:id "governance" :address "0xgov"      :role "governance"}
                     {:id "keeper"     :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal ; appeal-window=120 s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits decision → pending (deadline = 1120+120 = 1240)
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes slash. Slash appeal-deadline = 1130+120 = 1250.
    {:seq 4 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; Resolver contests the slash within the window → :appealed
    {:seq 5 :time 1140 :agent "resolver" :action "appeal_slash"
     :params {:workflow-id 0}}
    ;; Governance rejects the appeal (upheld?=false) → slash returns to :pending
    {:seq 6 :time 1160 :agent "governance" :action "resolve_appeal"
     :params {:workflow-id 0 :upheld? false}}
    ;; Keeper settles the escrow first (pending deadline 1240 has passed)
    {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Governance executes after original appeal-deadline (1255 > 1250) → :executed
    {:seq 8 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S36 — Profit-Maximizer: pre-window execute rejected; retry succeeds
;;
;; Governance attempts to execute the slash before the appeal-deadline (the
;; timelock) has expired.  The attempt is rejected with :timelock-not-expired.
;; After the window closes, governance retries and the slash executes.
;;
;; Sequence:
;;   1. Buyer creates escrow (8000 USDC, appeal-window=120 s).
;;   2. Buyer raises dispute; resolver submits release decision.
;;      Pending deadline = 1120+120 = 1240.
;;   3. Governance proposes slash (amount=500).
;;      Slash appeal-deadline = 1130+120 = 1250.
;;   4. Governance immediately tries to execute at t=1135 (<1250)
;;      → rejected (:timelock-not-expired).
;;   5. Slash appeal-deadline passes; no appeal is filed.
;;   6. Keeper settles the escrow first → :released.
;;   7. Governance retries execute at t=1255 → slash :executed.
;;
;; Expected: PASS — pre-window execution is cleanly rejected; state is
;; unchanged; the retry after the deadline succeeds.
;;
;; Invariants exercised:
;;   slash-status-consistent?     — slash stays :pending throughout (never aborted)
;;   no-auto-fraud-execute?        — slash requires explicit post-timelock call
;;   conservation-of-funds?        — AFA released; slash on resolver-stakes
;;   terminal-states-unchanged?    — once :released, stays :released
;; ---------------------------------------------------------------------------

(def s36
  {:scenario-id     "s36-profit-maximizer-pre-window-execute-rejected"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"     :address "0xseller"   :strategy "honest"}
                     {:id "resolver"   :address "0xresolver" :role "resolver"}
                     {:id "governance" :address "0xgov"      :role "governance"}
                     {:id "keeper"     :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal ; appeal-window=120 s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver submits decision → pending (deadline = 1120+120 = 1240)
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes slash. Slash appeal-deadline = 1130+120 = 1250.
    {:seq 4 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; Governance attempts early execution (1135 < 1250) → rejected :timelock-not-expired
    {:seq 5 :time 1135 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}
    ;; [Resolver does not appeal.  Appeal window passes.]
    ;; Keeper settles escrow first (pending deadline 1240 has passed)
    {:seq 6 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Governance retries after deadline → slash :executed
    {:seq 7 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S37 — Profit-Maximizer: two resolvers slashed simultaneously, split outcomes
;;
;; Governance targets two resolvers in parallel.  Resolver-0 (on wf0) appeals
;; and wins (slash reversed).  Resolver-1 (on wf1) forfeits (no appeal).
;; After the window closes, governance attempts to execute both slashes:
;; wf0 is rejected (:slash-already-reversed); wf1 executes.  Both escrows
;; settle independently.
;;
;; Sequence:
;;   1. Two escrows created with separate custom resolvers.
;;   2. Both disputes raised; each resolver submits a release decision.
;;      Both pending deadlines = 1120+120 = 1240.
;;   3. Governance proposes slashes on both resolvers simultaneously.
;;      Both slash appeal-deadlines = 1130+120 = 1250.
;;   4. Resolver-0 appeals → wf0 slash :appealed.
;;      Resolver-1 does NOT appeal (forfeits).
;;   5. Governance resolves wf0 appeal with upheld?=true → wf0 slash :reversed.
;;   6. Settle both escrows after their pending deadlines.
;;   7. After appeal-deadline:
;;      - Governance tries to execute wf0 slash → rejected (:slash-already-reversed).
;;      - Governance executes wf1 slash → :executed.
;;      (slashes execute on released escrows, avoiding freeze-on-active-dispute violations).
;;
;; Expected: PASS — slash operations on wf0 and wf1 are fully isolated;
;; reversal on wf0 does not affect wf1; both escrows settle correctly.
;;
;; Invariants exercised:
;;   slash-status-consistent?    — wf0: pending→appealed→reversed; wf1: pending→executed
;;   held-custody-reconciles?             — two escrows tracked independently
;;   conservation-of-funds?      — (wf0 AFA + wf1 AFA) released = total deposited AFA
;;   appeal-bond-conserved?      — wf0 bond intact after reversal; wf1 has no bond
;;   terminal-states-unchanged?  — both escrows stay :released
;; ---------------------------------------------------------------------------

(def s37
  {:scenario-id     "s37-profit-maximizer-two-resolver-split-outcomes"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer0"      :address "0xbuyer0"    :strategy "honest"}
                     {:id "buyer1"      :address "0xbuyer1"    :strategy "honest"}
                     {:id "seller"      :address "0xseller"    :strategy "honest"}
                     {:id "resolver0"   :address "0xresolver0" :role "resolver"}
                     {:id "resolver1"   :address "0xresolver1" :role "resolver"}
                     {:id "governance"  :address "0xgov"       :role "governance"}
                     {:id "keeper"      :address "0xkeeper"    :role "keeper"}]
   :protocol-params appeal ; appeal-window=120 s, fee-bps=150
   :events
   [{:seq 0 :time 1000 :agent "resolver0" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "resolver1" :action "register_stake"
     :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 8000
              :custom-resolver "0xresolver0"}}
    {:seq 3 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000
              :custom-resolver "0xresolver1"}}
    {:seq 4 :time 1060 :agent "buyer0" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id 1}}
    ;; wf0: resolver0 submits at t=1120 → pending deadline = 1120+120 = 1240.
    ;; wf1: resolver1 submits at t=1125 → pending deadline = 1125+120 = 1245.
    ;; Staggering the deadlines prevents both from being simultaneously stale
    ;; when the keeper runs, satisfying the no-stale-automatable-escrows invariant.
    {:seq 6 :time 1120 :agent "resolver0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xr0hash"}}
    {:seq 7 :time 1125 :agent "resolver1" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xr1hash"}}
    ;; Governance proposes slashes on both. Slash deadlines = 1130+120 = 1250.
    {:seq 8 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver0" :amount 500}}
    {:seq 9 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 1 :resolver-addr "0xresolver1" :amount 300}}
    ;; Resolver-0 appeals within the window → wf0 slash :appealed
    {:seq 10 :time 1140 :agent "resolver0" :action "appeal_slash"
     :params {:workflow-id 0}}
    ;; [Resolver-1 does NOT appeal (forfeits).]
    ;; Governance resolves wf0 appeal in resolver-0's favour → wf0 slash :reversed
    {:seq 11 :time 1160 :agent "governance" :action "resolve_appeal"
     :params {:workflow-id 0 :upheld? true}}
    ;; Settle wf0 once its pending deadline has passed.
    {:seq 12 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Settle wf1 after its own pending deadline has passed.
    {:seq 13 :time 1246 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}
    ;; After slash deadlines have passed:
    ;; Governance tries to execute wf0 slash → rejected (:slash-already-reversed)
    {:seq 14 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}
    ;; Governance executes wf1 slash (forfeited, status :pending) → :executed
    {:seq 15 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 1}}]})

;; ---------------------------------------------------------------------------
;; S38 — DR3 resolver bond 80/20 mix invariant holds
;;
;; A resolver registers a valid bond mix (80% stable, 20% Sew) and completes
;; a full dispute lifecycle.  The resolver-bond-mix-valid? invariant is checked
;; after every step and must hold throughout.
;;
;; Invariants exercised:
;;   resolver-bond-mix-valid?  — 8000 stable + 2000 Sew = exactly 80/20
;;   held-custody-reconciles?           — full dispute with appeal window
;;   conservation-of-funds?   — AFA released; resolver stake intact
;; ---------------------------------------------------------------------------

(def s38
  {:scenario-id     "s38-dr3-bond-mix-valid"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "resolver" :action "register_resolver_bond"
     :params {:stable 8000 :sew 2000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S39 — DR3 senior coverage delegation at capacity
;;
;; A senior resolver registers with coverage-max=10000.  A junior delegates
;; in two tranches (8000 + 2000) reaching exactly the maximum.  The
;; senior-coverage-not-exceeded? invariant must hold throughout (reserved
;; equals but never exceeds the maximum).
;;
;; Invariants exercised:
;;   senior-coverage-not-exceeded?  — reserved grows to exactly coverage-max
;; ---------------------------------------------------------------------------

(def s39
  {:scenario-id     "s39-dr3-senior-coverage-delegation"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "senior" :address "0xsenior" :role "resolver"}
                     {:id "junior" :address "0xjunior" :role "resolver"}]
   :protocol-params dr3
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "senior" :action "register_senior_bond"
     :params {:coverage-max 10000}}
    {:seq 1 :time 1000 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :coverage 8000}}
    {:seq 2 :time 1001 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :coverage 2000}}]})

;; ---------------------------------------------------------------------------
;; S40 — DR3 freeze recorded after fraud slash (no-assignment after freeze)
;;
;; After a fraud slash is executed, the resolver is frozen until
;; block-time + 259200 (72 h).  No new escrow is assigned to the frozen
;; resolver so resolver-not-frozen-on-assign? holds throughout.  The scenario
;; verifies the freeze is correctly recorded and that the invariant passes when
;; the protocol correctly avoids assigning new work to the frozen resolver.
;;
;; Invariants exercised:
;;   resolver-not-frozen-on-assign?  — frozen resolver has no :disputed escrows
;;   slash-status-consistent?        — slash transitions pending → executed
;;   no-auto-fraud-execute?          — slash required explicit post-deadline call
;; ---------------------------------------------------------------------------

(def s40
  {:scenario-id     "s40-dr3-freeze-post-slash"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"     :address "0xseller"   :strategy "honest"}
                     {:id "resolver"   :address "0xresolver" :role "resolver"}
                     {:id "governance" :address "0xgov"      :role "governance"}
                     {:id "keeper"     :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Governance proposes slash. Slash appeal-deadline = 1130+120 = 1250.
    {:seq 4 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xresolver" :amount 500}}
    ;; Settle the escrow first (pending deadline = 1120+120 = 1240 ≤ 1241).
    ;; Once settled, the escrow is no longer :disputed, so executing the slash
    ;; and freezing the resolver does NOT trigger resolver-not-frozen-on-assign?.
    {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; After appeal window (1250): execute slash → resolver frozen until 1255+259200.
    ;; No :disputed escrows remain, so resolver-not-frozen-on-assign? holds.
    {:seq 6 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S41 — DR3 reversal slash disabled (reversal-slash-bps = 0)
;;
;; A challenge escalates a dispute from L0 to L1.  The L1 resolver issues the
;; opposite decision (is-release=false vs L0's is-release=true), triggering
;; the reversal path.  With reversal-slash-bps=0 (v3 default) no slash amount
;; is created, so reversal-slash-disabled? holds throughout.
;;
;; Invariants exercised:
;;   reversal-slash-disabled?    — no reversal slash entry has amount > 0
;;   escalation-level-monotonic? — level grows 0 → 1 after challenge
;;   held-custody-reconciles?             — full lifecycle through L1 decision + settlement
;; ---------------------------------------------------------------------------

(def s41
  {:scenario-id     "s41-dr3-reversal-slash-disabled"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"     :address "0xseller"  :strategy "honest"}
                     {:id "l0"         :address "0xl0"      :role "resolver"}
                     {:id "l1"         :address "0xl1"      :role "resolver"}
                     {:id "challenger" :address "0xchall"   :strategy "honest"}
                     {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
   :protocol-params kleros-appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolves with release → pending settlement, deadline = 1120+60 = 1180
    {:seq 2 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash-l0"}}
    ;; Challenger disputes within the window (1130 < 1180) → level 0→1, new resolver = 0xl1
    {:seq 3 :time 1130 :agent "challenger" :action "challenge_resolution"
     :params {:workflow-id 0}}
    ;; L1 resolves with refund (opposite of L0) → reversal path fires with slash-bps=0
    {:seq 4 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash-l1"}}
    ;; Keeper settles after L1 pending deadline (1200+60=1260)
    {:seq 5 :time 1261 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S42 — Resolver-Buyer Bribery Loop (Economic Collusion Stress)
;;
;; Adversarial interaction: A buyer colludes with a resolver. The buyer pays a
;; bribe (simulated as side-payment, outcome: refund) to the resolver to rule 
;; against the seller. The seller then challenges the biased ruling at L1.
;;
;; Sequence:
;;   1. Buyer creates escrow (10000 USDC, kleros-appeal window=60s).
;;   2. Buyer raises dispute (to trigger the bribe loop).
;;   3. L0 resolver (colluder) rules :refund (buyer wins, seller loses).
;;      → pending (deadline = 1120+60 = 1180).
;;   4. Seller (victim) challenges the biased ruling at t=1150.
;;      → challenge bond posted, level 0→1, new-resolver=0xl1.
;;   5. L1 resolver (honest) rules :release (reverses the bribe).
;;      → new pending (deadline = 1200+60 = 1260).
;;   6. Keeper executes after L1 deadline → escrow :released.
;;
;; Expected: PASS — the protocol's L1 challenge mechanism successfully 
;; neutralizes the bribery attempt. The buyer (colluder) loses the escrow 
;; amount they tried to steal via bribe, plus any side-costs.
;;
;; Invariants exercised:
;;   escalation-level-monotonic?    — level advances 0→1
;;   finalization-accounting-correct? — release recorded despite L0 bribe
;;   conservation-of-funds?         — funds are correctly attributed after reversal
;; ---------------------------------------------------------------------------

(def s42
  {:scenario-id     "s42-resolver-buyer-bribery-loop"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "malicious"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver" :strategy "malicious"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 10000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 rules refund (biased, part of bribe loop). Deadline = 1120+60 = 1180.
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xbribe-hash" :adversarial? true}}
    ;; Seller challenges the bias. Level 0→1, new-resolver=0xl1.
    {:seq 3 :time 1150 :agent "seller" :action "challenge_resolution"
     :params {:workflow-id 0}}
    ;; L1 rules release (honest reversal). Deadline = 1200+60 = 1260.
    {:seq 4 :time 1200 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhonest-hash"}}
    ;; Keeper executes after L1 deadline → :released
    {:seq 5 :time 1265 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S45 — Flash-Loan Stake Inflation
;;
;; Adversarial interaction: A resolver temporarily inflates their stake to gain
;; assignment for a high-volume escrow, then immediately withdraws the stake.
;; This bypasses the 'Skin in the Game' requirement for the remainder of the
;; dispute lifecycle.
;;
;; Sequence:
;;   1. Resolver registers a massive stake (100,000 USDC - simulated flash loan).
;;   2. Buyer creates a large escrow (50,000 USDC) assigned to this resolver.
;;      → Assigned because stake (100k) > amount (50k).
;;   3. Resolver immediately withdraws the stake (100,000 USDC).
;;      → Withdrawal successful (vulnerability: no active-assignment lock).
;;   4. Resolver is now assigned to a 50k escrow with 0 stake.
;;   5. Buyer raises dispute. Resolver has no capital at risk if they timeout.
;;
;; Expected: PASS (demonstrates the vulnerability).
;; Invariants exercised:
;;   held-custody-reconciles?           — total-held tracks the withdrawal correctly.
;;   conservation-of-funds?    — funds accounted for after the simulation.
;; ---------------------------------------------------------------------------

(def s45
  {:scenario-id     "s45-flash-loan-stake-inflation"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "resolver" :address "0xadv"    :role "resolver" :strategy "malicious"}
                     {:id "buyer"    :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"   :address "0xseller" :strategy "honest"}]
   :protocol-params (assoc dr3 :resolver-bond-bps 1000) ; 10% bond required
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 100000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 50000
              :custom-resolver "0xadv"}}
    ;; Adversary withdraws stake immediately after assignment
    {:seq 2 :time 1001 :agent "resolver" :action "withdraw_stake"
     :params {:amount 100000}}
    ;; Escrow is now live but resolver has 0 stake
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}]})

    ;; ---------------------------------------------------------------------------
    ;; S46 — Reorg Idempotence
    ;;
    ;; Validates that duplicate (replayed) events from divergent reorg branches
    ;; are handled gracefully (idempotence).
    ;; ---------------------------------------------------------------------------

(def s46
  {:scenario-id     "s46-reorg-idempotence"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xbranchA"}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xbranchA"}}
    {:seq 4 :time 1180 :agent "buyer" :action "challenge_resolution"
     :params {:workflow-id 0}}
    {:seq 5 :time 1181 :agent "buyer" :action "challenge_resolution"
     :params {:workflow-id 0}}]})

    ;; ---------------------------------------------------------------------------
    ;; S66 — Cooldown Boundary Reorg
    ;;
    ;; Validates that the Layer A Sybil-mitigation cooldown (1 day) is correctly
    ;; enforced across reorgs where timestamps might shift across the boundary.
    ;; ---------------------------------------------------------------------------

(def s66
  {:scenario-id     "s66-cooldown-boundary-reorg"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; First escalation (0→1) at T=1130
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; Second escalation attempt at T=80000 (rejected: < 86400 cooldown)
    {:seq 4 :time 80000 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; Third escalation attempt at T=90000 (succeeds: > 86400 cooldown since T=1130)
    {:seq 5 :time 90000 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}]})

    ;; ---------------------------------------------------------------------------
    ;; S67 — Reentrancy Callback
    ;;
    ;; Validates that the reentrancy guard on withdraw-escrow correctly blocks
    ;; reentrant calls. The execute_reentrant_withdraw action sets the
    ;; reentrancy guard BEFORE dispatching the callback. If the callback
    ;; attempts to withdraw_escrow, it is rejected with :reentrancy-guard-violated
    ;; and the world state is unchanged (no funds lost).
    ;; ---------------------------------------------------------------------------

(def s67
  {:scenario-id     "s67-reentrancy-callback"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "attacker" :address "0xattacker" :strategy "malicious"}
                     {:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :expected-errors [{:seq 2 :action "execute_reentrant_withdraw" :error :reentrancy-guard-violated}]
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xattacker" :amount 10000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "attacker" :action "execute_reentrant_withdraw"
      :params {:workflow-id 0
               :callback {:agent "attacker" :action "withdraw_escrow" :params {:workflow-id 0}}}}]})

;; ---------------------------------------------------------------------------
;; S108 — Senior coverage consumed by slash
;;
;; A senior resolver registers with coverage-max=10000. A junior resolver
;; registers stake, delegates 5000 coverage to the senior, handles an escrow,
;; and is slashed for 3000 (within coverage). The coverage is consumed from
;; the senior's reserved-coverage, and the junior's stake is untouched.
;;
;; Invariants exercised:
;;   senior-coverage-not-exceeded?  — reserved-coverage decreases, not increases
;;   slash-distribution-consistent? — full 3000 distributed to buckets
;; ---------------------------------------------------------------------------

(def s108
  {:scenario-id     "s108-coverage-consumed-by-slash"
   :schema-version  "1.0"
   :scenario-author "@kilo-research"
   :initial-block-time 1000
   :agents          [{:id "senior"     :address "0xsenior" :role "resolver"}
                     {:id "junior"     :address "0xjunior" :role "resolver"}
                     {:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "governance" :address "0xgov"    :role "governance"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params (assoc appeal :resolver-bond-bps 0 :governance-mode :full
                           :slash-epoch-cap-bps 10000
                           :max-slash-per-offense-bps 10000)
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "senior" :action "register_senior_bond"
     :params {:coverage-max 10000}}
    {:seq 1 :time 1000 :agent "junior" :action "register_stake"
     :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :resolver-addr "0xjunior" :coverage 5000}}
    {:seq 3 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xjunior"}}
    {:seq 4 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1120 :agent "junior" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Propose fraud slash on junior (3000, within 5000 coverage).
    ;; Slash appeal deadline = 1130 + 120 = 1250.
    {:seq 6 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xjunior" :amount 3000}}
    ;; Settle escrow first (pending deadline = 1120+120 = 1240).
    {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Execute slash after appeal window (1250).
    {:seq 8 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S109 — Senior coverage exhausted by slash
;;
;; Same setup as S108, but the slash amount (8000) exceeds the reserved
;; coverage (5000). The senior's full coverage is consumed, and the
;; remaining 3000 is taken from the junior's stake.
;;
;; Invariants exercised:
;;   senior-coverage-not-exceeded?  — coverage fully consumed, still bonded
;;   slash-distribution-consistent? — full 8000 distributed to buckets
;; ---------------------------------------------------------------------------

(def s109
  {:scenario-id     "s109-coverage-exhausted-by-slash"
   :schema-version  "1.0"
   :scenario-author "@kilo-research"
   :initial-block-time 1000
   :agents          [{:id "senior"     :address "0xsenior" :role "resolver"}
                     {:id "junior"     :address "0xjunior" :role "resolver"}
                     {:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "governance" :address "0xgov"    :role "governance"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params (assoc appeal :resolver-bond-bps 0 :governance-mode :full
                           :slash-epoch-cap-bps 10000
                           :max-slash-per-offense-bps 10000)
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "senior" :action "register_senior_bond"
     :params {:coverage-max 10000}}
    {:seq 1 :time 1000 :agent "junior" :action "register_stake"
     :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :resolver-addr "0xjunior" :coverage 5000}}
    {:seq 3 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xjunior"}}
    {:seq 4 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1120 :agent "junior" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Propose fraud slash on junior (8000, exceeds 5000 coverage).
    {:seq 6 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xjunior" :amount 8000}}
    ;; Settle escrow first (pending deadline = 1120+120 = 1240).
    {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Execute slash after appeal window (1250).
    {:seq 8 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S110 — Multi-junior delegation to single senior
;;
;; Two junior resolvers delegate to one senior, then each is slashed
;; independently. Coverage is consumed from the shared senior pool.
;;
;; Invariants exercised:
;;   senior-coverage-not-exceeded? — reserved never exceeds coverage-max
;;   slash-distribution-consistent? — each slash credited to buckets
;; ---------------------------------------------------------------------------

(def s110
  {:scenario-id     "s110-multi-junior-coverage"
   :schema-version  "1.0"
   :scenario-author "@kilo-research"
   :initial-block-time 1000
   :agents          [{:id "senior"     :address "0xsenior" :role "resolver"}
                     {:id "juniorA"    :address "0xjuniorA" :role "resolver"}
                     {:id "juniorB"    :address "0xjuniorB" :role "resolver"}
                     {:id "buyer"      :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"     :address "0xseller"  :strategy "honest"}
                     {:id "governance" :address "0xgov"     :role "governance"}
                     {:id "keeper"     :address "0xkeeper"  :role "keeper"}]
   :protocol-params (assoc appeal :resolver-bond-bps 0 :governance-mode :full
                           :slash-epoch-cap-bps 10000
                           :max-slash-per-offense-bps 10000)
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "senior" :action "register_senior_bond"
     :params {:coverage-max 10000}}
    {:seq 1 :time 1000 :agent "juniorA" :action "register_stake"
     :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "juniorB" :action "register_stake"
     :params {:amount 10000}}
    {:seq 3 :time 1000 :agent "juniorA" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :resolver-addr "0xjuniorA" :coverage 4000}}
    {:seq 4 :time 1001 :agent "juniorB" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :resolver-addr "0xjuniorB" :coverage 4000}}
    ;; Escrow 0: Junior-A handles, gets slashed for 3000 (within 4000 coverage).
    {:seq 5 :time 1100 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xjuniorA"}}
    {:seq 6 :time 1160 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 7 :time 1220 :agent "juniorA" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 8 :time 1230 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xjuniorA" :amount 3000}}
    {:seq 9 :time 1341 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 10 :time 1355 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}
    ;; Escrow 1: Junior-B handles, gets slashed for 2000 (within 4000 coverage).
    {:seq 11 :time 1400 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xjuniorB"}}
    {:seq 12 :time 1460 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 13 :time 1520 :agent "juniorB" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhash"}}
    {:seq 14 :time 1530 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 1 :resolver-addr "0xjuniorB" :amount 2000}}
    {:seq 15 :time 1641 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}
    {:seq 16 :time 1655 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 1}}]})

;; ---------------------------------------------------------------------------
;; S111 — Gradual coverage exhaustion over multiple slashes
;;
;; A single junior delegating 5000 coverage is slashed three times.
;; The first two slashes (2000 each) are fully covered by the senior pool.
;; The third slash (2000) partially exhausts the remaining 1000 coverage
;; and the remaining 1000 comes from the junior's own stake.
;;
;; Invariants exercised:
;;   senior-coverage-not-exceeded? — coverage never exceeds max
;;   slash-distribution-consistent? — each slash accounted
;; ---------------------------------------------------------------------------

(def s111
  {:scenario-id     "s111-gradual-coverage-exhaustion"
   :schema-version  "1.0"
   :scenario-author "@kilo-research"
   :initial-block-time 1000
   :agents          [{:id "senior"     :address "0xsenior" :role "resolver"}
                     {:id "junior"     :address "0xjunior" :role "resolver"}
                     {:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "governance" :address "0xgov"    :role "governance"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params (assoc appeal :resolver-bond-bps 0 :governance-mode :full
                           :slash-epoch-cap-bps 10000
                           :max-slash-per-offense-bps 10000)
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "senior" :action "register_senior_bond"
     :params {:coverage-max 5000}}
    {:seq 1 :time 1000 :agent "junior" :action "register_stake"
     :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :resolver-addr "0xjunior" :coverage 5000}}
    ;; Slash 1: escrow 0, slash 2000 (within coverage).
    {:seq 3 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xjunior"}}
    {:seq 4 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1120 :agent "junior" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 6 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xjunior" :amount 2000}}
    {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 8 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}
    ;; Slash 2: escrow 1, slash 2000 (still within coverage).
    {:seq 9 :time 1300 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xjunior"}}
    {:seq 10 :time 1360 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 11 :time 1420 :agent "junior" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhash"}}
    {:seq 12 :time 1430 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 1 :resolver-addr "0xjunior" :amount 2000}}
    {:seq 13 :time 1541 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}
    {:seq 14 :time 1555 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 1}}
    ;; Slash 3: escrow 2, slash 2000 (exceeds remaining 1000 coverage).
    {:seq 15 :time 1600 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xjunior"}}
    {:seq 16 :time 1660 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 2}}
    {:seq 17 :time 1720 :agent "junior" :action "execute_resolution"
     :params {:workflow-id 2 :is-release true :resolution-hash "0xhash"}}
    {:seq 18 :time 1730 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 2 :resolver-addr "0xjunior" :amount 2000}}
    {:seq 19 :time 1841 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 2}}
    {:seq 20 :time 1855 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 2}}]})

;; ---------------------------------------------------------------------------
;; S112 — Senior-coverage-exceeded error path
;;
;; Attempt to delegate more coverage than the senior's coverage-max.
;; Expected revert: :senior-coverage-exceeded.
;;
;; Invariants exercised:
;;   senior-coverage-not-exceeded? — always holds (delegation rejected)
;; ---------------------------------------------------------------------------

(def s112
  {:scenario-id     "s112-coverage-exceeded-error"
   :schema-version  "1.0"
   :scenario-author "@kilo-research"
   :initial-block-time 1000
   :agents          [{:id "senior" :address "0xsenior" :role "resolver"}
                     {:id "junior" :address "0xjunior" :role "resolver"}]
   :protocol-params (assoc dr3 :governance-mode :full)
   :expected-revert? true
   :events
   [{:seq 0 :time 1000 :agent "senior" :action "register_senior_bond"
     :params {:coverage-max 5000}}
    {:seq 1 :time 1000 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :resolver-addr "0xjunior" :coverage 5000}}
    {:seq 2 :time 1001 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :resolver-addr "0xjunior" :coverage 1000}}]})

;; ---------------------------------------------------------------------------
;; S113 — Insurance-cut-bps governance override
;;
;; Protocol params override the default slash distribution split to
;; 80/10/10 (insurance/protocol/retained) instead of 50/30/20.
;; A slash event occurs and the distribution is verified.
;;
;; Invariants exercised:
;;   slash-distribution-consistent? — custom split holds
;; ---------------------------------------------------------------------------

(def s113
  {:scenario-id     "s113-insurance-bps-override"
   :schema-version  "1.0"
   :scenario-author "@kilo-research"
   :initial-block-time 1000
   :agents          [{:id "senior"     :address "0xsenior" :role "resolver"}
                     {:id "junior"     :address "0xjunior" :role "resolver"}
                     {:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "governance" :address "0xgov"    :role "governance"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params (assoc appeal :resolver-bond-bps 0 :governance-mode :full
                           :slash-epoch-cap-bps 10000
                           :max-slash-per-offense-bps 10000
                           :insurance-cut-bps 8000
                           :protocol-retained-bps 1000)
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "senior" :action "register_senior_bond"
     :params {:coverage-max 10000}}
    {:seq 1 :time 1000 :agent "junior" :action "register_stake"
     :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "junior" :action "delegate_to_senior"
     :params {:senior-addr "0xsenior" :resolver-addr "0xjunior" :coverage 5000}}
    {:seq 3 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xjunior"}}
    {:seq 4 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1120 :agent "junior" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 6 :time 1130 :agent "governance" :action "propose_fraud_slash"
     :params {:workflow-id 0 :resolver-addr "0xjunior" :amount 2000}}
    {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 8 :time 1255 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0}}]})
