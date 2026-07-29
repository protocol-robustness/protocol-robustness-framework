(ns resolver-sim.protocols.sew.invariant-scenarios.extended
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer :all]))

(def s49
  {:scenario-id     "s49-appeal-deadline-boundary"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "resolver"}]
   :protocol-params kleros-appeal
   :notes "Validate appeal window boundary enforcement. L0 resolves at t=1120 (deadline=1180), buyer escalates at t=1150 (within 60s window) successfully. L1 then resolves, and settlement executes after deadline."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolves → creates pending (deadline = 1120+60 = 1180)
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates at t=1150 (within 60s appeal window) → accepted
    {:seq 3 :time 1150 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 resolves → new pending (deadline = 1210+60 = 1270)
    {:seq 4 :time 1210 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    ;; Keeper executes settlement after deadline
    {:seq 5 :time 1270 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S50 — D1 False assertion unchallenged (monitoring assumption validation)
;; Resolver makes clearly wrong decision (refunds when release is correct).
;; No one challenges or appeals → bad resolution finalizes.
;; Purpose: Validate that protocol DETECTS if monitoring assumption fails.
;; ---------------------------------------------------------------------------

(def s50
  {:scenario-id     "s50-false-assertion-unchallenged"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver" :strategy "malicious"}]
   :protocol-params appeal
   :expected-fail? false
   :notes "This is NOT a protocol failure; it demonstrates the monitoring assumption: if no watchdog challenges a malicious resolution, the bad outcome settles. The scenario outcome is 'pass' iff all invariants hold even under malicious settlement."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    ;; Buyer then raises dispute claiming refund (lies)
    {:seq 2 :time 1080 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Malicious resolver sides with buyer (refund), even though release already occurred
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}
    ;; Appeal window closes; no one escalates
    {:seq 4 :time 1240 :agent "buyer" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S51 — D7 Same-block challenge/finalize race
;; Appeal window just closing; two txs in same block.
;; One finalizes, one escalates → deterministic ordering.
;; ---------------------------------------------------------------------------

(def s51
  {:scenario-id     "s51-same-block-challenge-finalize-race"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "executor" :address "0xexecutor" :role "keeper"}
                     {:id "appealer" :address "0xappealer" :role "keeper"}]
   :protocol-params appeal
   :notes "Same-block ordering: executor and appealer both act at t=1240 (deadline). Deterministic ordering ensures one succeeds and other fails."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver decides at t=1120 → appeal deadline = 1240
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; At t=1240, two transactions in same block:
    ;; First: finalize (succeeds, locks settlement)
    {:seq 3 :time 1240 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Second: escalate (fails, appeal window closed and finalized)
    {:seq 4 :time 1240 :agent "appealer" :action "escalate_dispute"
     :params {:workflow-id 0}}]})

(def s51-inverse
  {:scenario-id     "s51-inverse-same-block-escalate-then-finalize"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "executor" :address "0xexecutor" :role "keeper"}
                     {:id "appealer" :address "0xappealer" :role "keeper"}]
   :protocol-params appeal
   :notes "Same-block inverse ordering: appealer and executor both act at t=1240 (deadline). At exact deadline escalation should be expired and reject; settlement should still execute."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; At t=1240, inverse order:
    ;; First: escalate (fails, appeal window expired at exact deadline)
    {:seq 3 :time 1240 :agent "appealer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; Second: finalize (succeeds)
    {:seq 4 :time 1240 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s51c-deadline-matrix-execute-then-escalate
  {:scenario-id     "s51c-deadline-matrix-execute-then-escalate"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "executor" :address "0xexecutor" :role "keeper"}
                     {:id "appealer" :address "0xappealer" :role "keeper"}]
   :protocol-params appeal
   :strict-expected-errors? true
   :expected-errors [{:seq 3  :action "execute_pending_settlement" :error :appeal-window-not-expired}
                     {:seq 4  :action "escalate_dispute"           :error :not-participant}
                     {:seq 10 :action "escalate_dispute"           :error :transfer-not-in-dispute}
                     {:seq 15 :action "escalate_dispute"           :error :transfer-not-in-dispute}]
   :notes "Matrix branch execute->escalate. Three escrows exercise t=deadline-1, t=deadline, t=deadline+1 with same-block ordering. At deadline-1 execute rejects then escalate succeeds; at deadline and deadline+1 execute succeeds then escalate rejects on terminal state. A keeper settlement cleanup step is inserted between branches so no automatable stale work remains."
   :events
   [;; wf0: time = deadline-1 => execute rejects, escalate succeeds
    {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash0"}}
    {:seq 3 :time 1239 :agent "executor" :action "execute_pending_settlement" :params {:workflow-id 0}}
    {:seq 4 :time 1239 :agent "appealer" :action "escalate_dispute" :params {:workflow-id 0}}
    ;; wf1: time = deadline => execute succeeds, escalate rejects
    {:seq 5 :time 1840 :agent "executor" :action "execute_pending_settlement" :params {:workflow-id 0}}
    {:seq 6 :time 2000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 7 :time 2060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 1}}
    {:seq 8 :time 2120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhash1"}}
    {:seq 9 :time 2240 :agent "executor" :action "execute_pending_settlement" :params {:workflow-id 1}}
    {:seq 10 :time 2240 :agent "appealer" :action "escalate_dispute" :params {:workflow-id 1}}
    ;; wf2: time = deadline+1 => execute succeeds, escalate rejects
    {:seq 11 :time 3000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 12 :time 3060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 2}}
    {:seq 13 :time 3120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 2 :is-release true :resolution-hash "0xhash2"}}
    {:seq 14 :time 3241 :agent "executor" :action "execute_pending_settlement" :params {:workflow-id 2}}
    {:seq 15 :time 3241 :agent "appealer" :action "escalate_dispute" :params {:workflow-id 2}}]})

(def s51d-deadline-matrix-escalate-then-execute
  {:scenario-id     "s51d-deadline-matrix-escalate-then-execute"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "executor" :address "0xexecutor" :role "keeper"}
                     {:id "appealer" :address "0xappealer" :role "keeper"}]
   :protocol-params appeal
   :strict-expected-errors? true
   :expected-errors [{:seq 3  :action "escalate_dispute"           :error :not-participant}
                     {:seq 4  :action "execute_pending_settlement" :error :appeal-window-not-expired}
                     {:seq 9  :action "escalate_dispute"           :error :not-participant}
                     {:seq 14 :action "escalate_dispute"           :error :not-participant}]
   :notes "Matrix branch escalate->execute. Three escrows exercise t=deadline-1, t=deadline, t=deadline+1 with same-block ordering. At deadline-1 escalate succeeds and execute rejects due to no pending; at deadline and deadline+1 escalate expires and execute succeeds. A keeper settlement cleanup step is inserted between branches so no automatable stale work remains."
   :events
   [;; wf0: time = deadline-1 => escalate succeeds, execute rejects
    {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash0"}}
    {:seq 3 :time 1239 :agent "appealer" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1239 :agent "executor" :action "execute_pending_settlement" :params {:workflow-id 0}}
    ;; wf1: time = deadline => escalate rejects, execute succeeds
    {:seq 5 :time 1840 :agent "executor" :action "execute_pending_settlement" :params {:workflow-id 0}}
    {:seq 6 :time 2000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 7 :time 2060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 1}}
    {:seq 8 :time 2120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhash1"}}
    {:seq 9 :time 2240 :agent "appealer" :action "escalate_dispute" :params {:workflow-id 1}}
    {:seq 10 :time 2240 :agent "executor" :action "execute_pending_settlement" :params {:workflow-id 1}}
    ;; wf2: time = deadline+1 => escalate rejects, execute succeeds
    {:seq 11 :time 3000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 12 :time 3060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 2}}
    {:seq 13 :time 3120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 2 :is-release true :resolution-hash "0xhash2"}}
    {:seq 14 :time 3241 :agent "appealer" :action "escalate_dispute" :params {:workflow-id 2}}
    {:seq 15 :time 3241 :agent "executor" :action "execute_pending_settlement" :params {:workflow-id 2}}]})

;; ---------------------------------------------------------------------------
;; S52 — I4 Yield accrued during dispute
;; Escrow earning yield while disputed; yield should be distributed per outcome.
;; ---------------------------------------------------------------------------

(def s52
  {:scenario-id     "s52-yield-accrued-during-dispute"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params appeal
   :notes "Escrow principal: 5000 USDC. Yield accrues at 0.1%/block. During dispute (1060–1120 = 60 blocks), yield ≈ 3 USDC. On release, seller receives principal + yield."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"
              :yield-rate 0.001}}
    ;; Yield accrues from t=1000 → t=1060 (60 blocks, 0.06% yield → ~3 USDC)
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; More yield accrues during dispute (t=1060 → t=1120)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Execute settlement
    {:seq 3 :time 1240 :agent "buyer" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Seller withdraws: principal (5000) + yield (≥3)
    {:seq 4 :time 1250 :agent "seller" :action "withdraw_escrow"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S53 — I2 Reentrant withdrawal guard
;; Buyer and seller attempt to withdraw the same escrow simultaneously
;; Expected: Only one succeeds; second is rejected (already-withdrawn or similar)
;; Validates: Atomicity of settlement ledger; no double-payout
;; ---------------------------------------------------------------------------

(def s53
  {:scenario-id     "s53-reentrant-withdrawal-guard"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal
   :notes "Settlement ledger isolation: buyer and seller attempt withdrawal at same time. One succeeds; second is rejected to prevent ledger duplication."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver decides → pending (deadline = 1120+120 = 1240)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Keeper executes settlement after deadline
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Buyer withdraws (succeeds)
    {:seq 4 :time 1250 :agent "buyer" :action "withdraw_escrow"
     :params {:workflow-id 0}}
    ;; Seller attempts withdrawal at same time (rejected—already withdrawn)
    {:seq 5 :time 1250 :agent "seller" :action "withdraw_escrow"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S54 — I6 Multi-claim ledger isolation
;; Two parallel escrows settled by same parties; verify claims stay isolated
;; Expected: Each party gets correct escrow amounts; no ledger bleed
;; Validates: Claim isolation; per-escrow accounting
;; ---------------------------------------------------------------------------

(def s54
  {:scenario-id     "s54-multi-claim-ledger-isolation"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer1"    :address "0xbuyer1"   :strategy "honest"}
                     {:id "buyer2"    :address "0xbuyer2"   :strategy "honest"}
                     {:id "seller"    :address "0xseller"   :strategy "honest"}
                     {:id "resolver"  :address "0xresolver" :role "resolver"}
                     {:id "keeper"    :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal
   :notes "Two independent escrows (wf0=5000, wf1=3000). First disputed and settled; second released. Tests that ledger isolation holds."
   :events
   [{:seq 0 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1000 :agent "buyer2" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1020 :agent "buyer2" :action "release"
     :params {:workflow-id 1}}
    {:seq 3 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 5 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s55
  {:scenario-id     "s55-resolver-unavailable-timeout-fallback"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver becomes paused during dispute. Tests fallback to timeout if resolver unavailable. Dispute should auto-cancel at timeout."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1100 :agent "keeper" :action "set_paused"
     :params {:paused? true}}
    {:seq 3 :time 2100 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})

(def s56
  {:scenario-id     "s56-resolver-diversity"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"     :address "0xbuyer"     :strategy "honest"}
                     {:id "seller"    :address "0xseller"    :strategy "honest"}
                     {:id "resolver1" :address "0xresolver1" :role "resolver"}
                     {:id "keeper"    :address "0xkeeper"    :role "keeper"}]
   :protocol-params appeal
   :notes "Tests standard dispute resolution flow with no special governance actions. Baseline for resolver authority tests."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver1"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s57
  {:scenario-id     "s57-corruption-cost-vs-profit"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"     :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"    :address "0xseller"   :strategy "honest"}
                     {:id "attacker"  :address "0xattacker" :strategy "profit-maximizer"}
                     {:id "resolver"  :address "0xresolver" :role "resolver"}
                     {:id "keeper"    :address "0xkeeper"   :role "keeper"}]
   :protocol-params dr3
   :notes "Profit-maximizer corrupts resolution to capture full escrow value. Tests cost-of-corruption analysis. Attacker uses external bribe (epsilon)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xattacker" :amount 10000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 4 :time 1250 :agent "attacker" :action "withdraw_escrow"
     :params {:workflow-id 0}}]})

(def s58
  {:scenario-id     "s58-watchdog-valid-challenge"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog challenges a bad resolution. Tests challenge_resolution and bounty allocation. Validator accepts challenge."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xbad_hash"}}
    {:seq 3 :time 1130 :agent "watchdog" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xchallenging_evidence"}}
    {:seq 4 :time 1200 :agent "keeper" :action "resolve_appeal"
     :params {:workflow-id 0 :appeal-winner "watchdog"}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s59
  {:scenario-id     "s59-watchdog-false-challenge-loss"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "watchdog" :address "0xwatchdog" :strategy "profit-maximizer"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog falsely challenges a valid resolution. Tests that false challenger loses bond. Validator rejects challenge."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xcorrect_hash"}}
    {:seq 3 :time 1130 :agent "watchdog" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xfalse_evidence"}}
    {:seq 4 :time 1200 :agent "keeper" :action "resolve_appeal"
     :params {:workflow-id 0 :appeal-winner "resolver"}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s60
  {:scenario-id     "s60-resolver-abstention-timeout-griefing"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :strategy "profit-maximizer"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver abstains from resolving (doesn't call execute_resolution). Dispute times out and auto-cancels. Tests resolver timeout penalties."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 2100 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})

(def s61
  {:scenario-id     "s61-fee-on-transfer-token-handling"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Tests settlement with a fee-on-transfer token (e.g., USDT variants). Ensures ledger accounts for transfer fees."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDT_FEE" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s62
  {:scenario-id     "s62-multi-appeal-escalation-chain"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params kleros
   :notes "Tests multi-level appeal chain: resolution → challenge → escalation. Tests Kleros-style jurisdiction transitions."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}
    {:seq 3 :time 1130 :agent "watchdog" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xchallenging_evidence"}}
    {:seq 4 :time 1140 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; Concurrent cross-token disputes (S62 *-under-dispute-load): overlapping disputes across
;; escrows/tokens/appeal depths — not high-volume resolver flooding (see capacity variant).

(def s62-cross-token-isolation-under-dispute-load
  {:scenario-id "s62-cross-token-isolation-under-dispute-load"
   :schema-version "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents [{:id "buyer1" :address "0xbuyer1" :strategy "honest"}
            {:id "seller1" :address "0xseller1" :strategy "honest"}
            {:id "buyer2" :address "0xbuyer2" :strategy "honest"}
            {:id "seller2" :address "0xseller2" :strategy "honest"}
            {:id "buyer3" :address "0xbuyer3" :strategy "honest"}
            {:id "seller3" :address "0xseller3" :strategy "honest"}
            {:id "resolver" :address "0xresolver" :role "resolver"}
            {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
            {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros
   :notes "Concurrent disputes across USDC, DAI, and USDT_FEE with interleaved resolution and settlement. Verifies ledger isolation and resolver routing — not DRM maxConcurrentDisputes saturation."
   :events
   [;; create three escrows on different tokens
    {:seq 0 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller1" :amount 2000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1002 :agent "buyer2" :action "create_escrow"
     :params {:token "DAI" :to "0xseller2" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 2 :time 1004 :agent "buyer3" :action "create_escrow"
     :params {:token "USDT_FEE" :to "0xseller3" :amount 2500 :custom-resolver "0xresolver"}}

    ;; raise disputes concurrently
    {:seq 3 :time 1060 :agent "buyer1" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1061 :agent "buyer2" :action "raise_dispute" :params {:workflow-id 1}}
    {:seq 5 :time 1062 :agent "buyer3" :action "raise_dispute" :params {:workflow-id 2}}

    ;; resolver processes resolutions in interleaved order
    {:seq 6 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhash1"}}
    {:seq 7 :time 1122 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash0"}}

    ;; resolve wf2, then settle all three workflows
    {:seq 8 :time 1130 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 2 :is-release true :resolution-hash "0xhash2"}}
    {:seq 9 :time 1300 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 1}}
    {:seq 10 :time 1310 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}
    {:seq 11 :time 1320 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 2}}]})

(def s62-cross-token-fee-on-transfer-under-dispute-load
  {:scenario-id "s62-cross-token-fee-on-transfer-under-dispute-load"
   :schema-version "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents [{:id "buyerA" :address "0xbuyerA" :strategy "honest"}
            {:id "sellerA" :address "0xsellerA" :strategy "honest"}
            {:id "buyerB" :address "0xbuyerB" :strategy "honest"}
            {:id "sellerB" :address "0xsellerB" :strategy "honest"}
            {:id "resolver" :address "0xresolver" :role "resolver"}
            {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
            {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros
   :notes "Concurrent disputes on USDT_FEE (with challenge/escalation) and USDC. Verifies fee-on-transfer accounting stays escrow-local while another workflow settles."
   :events
   [;; create two escrows, one fee-on-transfer token and one normal token
    {:seq 0 :time 1000 :agent "buyerA" :action "create_escrow"
     :params {:token "USDT_FEE" :to "0xsellerA" :amount 5000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1005 :agent "buyerB" :action "create_escrow"
     :params {:token "USDC" :to "0xsellerB" :amount 4000 :custom-resolver "0xresolver"}}

    ;; both raise disputes in quick succession
    {:seq 2 :time 1060 :agent "buyerA" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 3 :time 1061 :agent "buyerB" :action "raise_dispute" :params {:workflow-id 1}}

    ;; resolver resolves wf0 (appeal chain), then wf1; challenge + escalate on wf0
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhashA"}}
    {:seq 5 :time 1130 :agent "watchdog" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xchallA"}}
    {:seq 6 :time 1140 :agent "buyerA" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 7 :time 1150 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhashB"}}
    {:seq 8 :time 1300 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 1}}
    {:seq 9 :time 1310 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}]})

(def s62-cross-token-parallel-appeal-depths-under-dispute-load
  {:scenario-id "s62-cross-token-parallel-appeal-depths-under-dispute-load"
   :schema-version "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents [{:id "buyerX" :address "0xbuyerX" :strategy "honest"}
            {:id "sellerX" :address "0xsellerX" :strategy "honest"}
            {:id "buyerY" :address "0xbuyerY" :strategy "honest"}
            {:id "sellerY" :address "0xsellerY" :strategy "honest"}
            {:id "resolver-l0" :address "0xl0" :role "resolver"}
            {:id "resolver-l1" :address "0xl1" :role "resolver"}
            {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
            {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros
   :notes "Parallel disputes with shallow (wf1) vs deep (wf0 challenge→escalate→L1) appeal paths on USDC and DAI. Verifies escalation/jurisdiction state stays escrow-local."
   :events
   [;; create two escrows (L0 resolver)
    {:seq 0 :time 1000 :agent "buyerX" :action "create_escrow" :params {:token "USDC" :to "0xsellerX" :amount 6000 :custom-resolver "0xl0"}}
    {:seq 1 :time 1002 :agent "buyerY" :action "create_escrow" :params {:token "DAI" :to "0xsellerY" :amount 3500 :custom-resolver "0xl0"}}

    ;; both raise disputes
    {:seq 2 :time 1060 :agent "buyerX" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 3 :time 1061 :agent "buyerY" :action "raise_dispute" :params {:workflow-id 1}}

    ;; wfY resolves quickly; wfX goes through challenge + escalation to L1
    {:seq 4 :time 1120 :agent "resolver-l0" :action "execute_resolution" :params {:workflow-id 1 :is-release true :resolution-hash "0xhashY"}}
    {:seq 5 :time 1125 :agent "resolver-l0" :action "execute_resolution" :params {:workflow-id 0 :is-release false :resolution-hash "0xhashX0"}}
    {:seq 6 :time 1130 :agent "watchdog" :action "challenge_resolution" :params {:workflow-id 0 :evidence-hash "0xchallX"}}
    {:seq 7 :time 1140 :agent "buyerX" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 8 :time 1200 :agent "resolver-l1" :action "execute_resolution" :params {:workflow-id 0 :is-release false :resolution-hash "0xhashX1"}}
    {:seq 9 :time 1300 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 1}}
    {:seq 10 :time 1310 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}]})

(def s62-resolver-capacity-concurrent-dispute-load
  {:scenario-id     "s62-resolver-capacity-concurrent-dispute-load"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer0"    :address "0xbuyer0"    :strategy "honest"}
                     {:id "buyer1"    :address "0xbuyer1"    :strategy "honest"}
                     {:id "buyer2"    :address "0xbuyer2"    :strategy "honest"}
                     {:id "seller1"   :address "0xseller1"   :strategy "honest"}
                     {:id "seller2"   :address "0xseller2"   :strategy "honest"}
                     {:id "seller3"   :address "0xseller3"   :strategy "honest"}
                     {:id "resolver"  :address "0xresolver"  :role "resolver"}
                     {:id "keeper"    :address "0xkeeper"    :role "keeper"}]
   :protocol-params (assoc dr3 :resolver-bond-bps 0)
   :notes "DRM maxConcurrentDisputes=2: third concurrent raise_dispute rejected; slot freed after wf0 settlement allows wf2 dispute."
   :expected-errors [{:seq 6 :action "raise_dispute" :error :resolver-capacity-exceeded}]
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "set_resolver_capacity"
     :params {:max-concurrent 2}}
    {:seq 1 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller1" :amount 2000 :custom-resolver "0xresolver"}}
    {:seq 2 :time 1010 :agent "buyer1" :action "create_escrow"
     :params {:token "DAI" :to "0xseller2" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 3 :time 1020 :agent "buyer2" :action "create_escrow"
     :params {:token "USDC" :to "0xseller3" :amount 2500 :custom-resolver "0xresolver"}}
    {:seq 4 :time 1060 :agent "buyer0" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 5 :time 1070 :agent "buyer1" :action "raise_dispute" :params {:workflow-id 1}}
    {:seq 6 :time 1080 :agent "buyer2" :action "raise_dispute" :params {:workflow-id 2}}
    {:seq 7 :time 1140 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xh0"}}
    {:seq 8 :time 1200 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 0}}
    {:seq 9 :time 1210 :agent "buyer2" :action "raise_dispute" :params {:workflow-id 2}}
    {:seq 10 :time 1270 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release false :resolution-hash "0xh1"}}
    {:seq 11 :time 1280 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 2 :is-release true :resolution-hash "0xh2"}}
    {:seq 12 :time 1320 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 1}}
    {:seq 13 :time 1330 :agent "keeper" :action "execute_pending_settlement" :params {:workflow-id 2}}]})

(def s63
  {:scenario-id     "s63-frivolous-appeal-slashing"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "profit-maximizer"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Buyer appeals a correct resolution (frivolous appeal). Tests that frivolous appeals lose bond. Models UX fairness / access-to-justice constraint."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xcorrect_hash"}}
    {:seq 3 :time 1130 :agent "buyer" :action "appeal_slash"
     :params {:workflow-id 0 :appeal-reason "frivolous"}}
    {:seq 4 :time 1300 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s64
  {:scenario-id     "s64-minimal-bond-edge-case"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params dr3
   :notes "Escrow with minimal allowed bond. Tests bond constraints don't break resolution. Models F1: bond sensitivity."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 100
              :custom-resolver "0xresolver" :bond-amount 10}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s65
  {:scenario-id     "s65-appeal-after-settlement-rejected"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Buyer attempts to appeal after pending settlement has already executed. Tests that post-settlement appeals are rejected. Models B3: deadline edge cases."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 4 :time 1250 :agent "buyer" :action "appeal_slash"
     :params {:workflow-id 0 :appeal-reason "late"}}]})

(def s68
  {:scenario-id     "s68-double-settlement-guard"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Execute pending settlement twice on same escrow. Tests idempotence and double-settlement guard. Second attempt should be rejected."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s69
  {:scenario-id     "s69-stale-dispute-cleanup"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Dispute created then left unresolved beyond appeal window. Tests stale dispute cleanup and state recovery."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 2000 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s70
  {:scenario-id     "s70-large-escrow-resolution"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params dr3
   :notes "Large-value escrow with significant fees. Tests settlement accuracy at scale and fee precision."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1000000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s71
  {:scenario-id     "s71-zero-fee-settlement"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Escrow with zero fee. Tests settlement with no fee extraction. Edge case for financial calculations."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver" :fee-amount 0}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s72
  {:scenario-id     "s72-challenge-during-appeal-window"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog challenges during open appeal window. Tests that challenges within deadline are accepted."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}
    {:seq 3 :time 1200 :agent "watchdog" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xchallenging_evidence"}}
    {:seq 4 :time 1250 :agent "keeper" :action "resolve_appeal"
     :params {:workflow-id 0 :appeal-winner "watchdog"}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s73
  {:scenario-id     "s73-challenge-after-appeal-window-closed"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog attempts to challenge after appeal window has closed. Challenge should be rejected (E3)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 4 :time 1260 :agent "watchdog" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xchallenging_evidence"}}]})

(def s74
  {:scenario-id     "s74-multi-escrow-parallel-disputes"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller1"  :address "0xseller1"  :strategy "honest"}
                     {:id "seller2"  :address "0xseller2"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Two sequential escrows, both with disputes and resolutions. Tests resolver handling of multiple workflows. First escrow fully settled before second escrow disputed."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller1" :amount 2000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash0"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 4 :time 1300 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller2" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 5 :time 1360 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 6 :time 1420 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhash1"}}
    {:seq 7 :time 1560 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}]})

(def s75
  {:scenario-id     "s75-receiver-cancels-after-dispute"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :expected-errors [{:seq 2 :action "recipient_cancel" :error :transfer-not-pending}]
   :notes "After dispute raised, receiver attempts to cancel escrow. Tests authorization: can receiver override dispute?"
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1080 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 4 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s76
  {:scenario-id     "s76-sender-cancel-during-appeal"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :expected-errors [{:seq 3 :action "sender_cancel" :error :transfer-not-pending}]
   :notes "Sender attempts to cancel during appeal window. Tests that cancel is blocked during appeal period."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1200 :agent "buyer" :action "sender_cancel"
     :params {:workflow-id 0}}
    {:seq 4 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s77
  {:scenario-id     "s77-escalation-rejected-wrong-layer"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params ieo
   :notes "Escalate in a protocol (IEO) that doesn't support escalation. Tests layer enforcement (E4)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s78
  {:scenario-id     "s78-many-appeals-eventually-rejects"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "profit-maximizer"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params kleros
   :notes "Buyer appeals multiple times, each time losing. Tests that repeated frivolous appeals accumulate losses."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1140 :agent "buyer" :action "appeal_slash"
     :params {:workflow-id 0 :appeal-reason "wrong-outcome"}}
    {:seq 4 :time 1250 :agent "buyer" :action "appeal_slash"
     :params {:workflow-id 0 :appeal-reason "unfair"}}
    {:seq 5 :time 1300 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s79
  {:scenario-id     "s79-partial-resolution-release"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver issues partial release (not full refund/full release). Tests settlement flexibility."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 10000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xpartial" :amount-released 5000}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s80
  {:scenario-id     "s80-disputed-escrow-expiry-settlement"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params dr3
   :notes "Escrow with expiry during dispute. Tests settlement after escrow-level deadline."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver" :expiry-time 2000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1500 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s81
  {:scenario-id     "s81-appeal-deadline-boundary-before"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :allow-open-entities? true
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Boundary test: execute settlement 1 second before appeal deadline (should reject)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1239 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s82
  {:scenario-id     "s82-appeal-deadline-boundary-exact"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Boundary test: execute settlement exactly at appeal deadline (should succeed)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1240 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s83
  {:scenario-id     "s83-appeal-deadline-boundary-after"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Boundary test: execute settlement 1 second after appeal deadline (should succeed)."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1241 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s84
  {:scenario-id     "s84-false-assertion-unchallenged"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Honest resolver accepts a dispute resolution without challenge. Nobody disputes the resolution. Tests assumption: honest majority prevents wrong resolutions."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash_accepted"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s85
  {:scenario-id     "s85-watchdog-challenges-resolution"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "watchdog" :address "0xwatchdog" :strategy "honest"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Watchdog observes resolution and challenges it with evidence. Tests watchdog challenge flow."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xwrong_call"}}
    {:seq 3 :time 1150 :agent "watchdog" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xcorrect_evidence"}}
    {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s86
  {:scenario-id     "s86-reentrant-withdrawal-guard"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Tests that settlement guards against reentrant calls. Escrow reaches terminal state atomically."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 4 :time 1260 :agent "seller" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s87
  {:scenario-id     "s87-cross-escrow-isolation"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller1"  :address "0xseller1"  :strategy "honest"}
                     {:id "seller2"  :address "0xseller2"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Two escrows: one disputed, one released. Ensures settlement of disputed escrow doesn't affect un-disputed."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller1" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1020 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller2" :amount 2000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1100 :agent "seller2" :action "recipient_release"
     :params {:workflow-id 1}}
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 5 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s88
  {:scenario-id     "s88-resolution-with-conflicting-evidence"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Dispute with multiple evidence entries. Resolver picks one resolution. Tests evidence handling in resolution."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1090 :agent "seller" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xseller_evidence"}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xresolver_finds_buyer_right"}}
    {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s89
  {:scenario-id     "s89-dispute-resolution-with-zero-appeal-window"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params kleros
   :notes "Resolution in kleros mode (zero appeal window). Settlement executes immediately. Tests fast-path resolution."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver" :resolution-module "0xkleros-proxy"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1121 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s90
  {:scenario-id     "s90-resolver-capacity-stress"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller1"  :address "0xseller1"  :strategy "honest"}
                     {:id "seller2"  :address "0xseller2"  :strategy "honest"}
                     {:id "seller3"  :address "0xseller3"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver handles 3 large escrows in dispute simultaneously. Tests capacity under load."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller1" :amount 50000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1010 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller2" :amount 40000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1020 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller3" :amount 30000
              :custom-resolver "0xresolver"}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1070 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 5 :time 1080 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 2}}
    {:seq 6 :time 1140 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash0"}}
    {:seq 7 :time 1145 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xhash1"}}
    {:seq 8 :time 1150 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 2 :is-release true :resolution-hash "0xhash2"}}
    {:seq 9 :time 1260 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 10 :time 1265 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}
    {:seq 11 :time 1270 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 2}}]})

(def s91
  {:scenario-id     "s91-governance-snapshot-dispute-state"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}
                     {:id "gov"      :address "0xgov"      :role "governance"}]
   :protocol-params appeal
   :notes "Dispute created, then governance changes fee params. Dispute outcome unaffected by governance change. Tests snapshot isolation."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1080 :agent "gov" :action "set-fee-bps"
     :params {:new-fee-bps 500}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S92: Settlement with zero-amount escrow

(def s92
  {:scenario-id     "s92-settlement-zero-amount-edge"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Escrow with 1 USDC (minimum). Settlement of tiny amount. Tests ledger precision."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S93: Multiple appeals with refund resolution

(def s93
  {:scenario-id     "s93-multiple-appeals-with-refund"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolution is refund (not release). Followed by appeals and appeals. Tests refund path with appeals."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xrefund_hash"}}
    {:seq 3 :time 1150 :agent "seller" :action "resolve_appeal"
     :params {:workflow-id 0 :appeal-winner "resolver"}}
    {:seq 4 :time 1200 :agent "buyer" :action "appeal_slash"
     :params {:workflow-id 0 :appeal-reason "wrong"}}
    {:seq 5 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S94: Dispute with automatic timeout (no resolver action)

(def s94
  {:scenario-id     "s94-dispute-timeout-auto-refund"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params timeout
   :notes "Dispute raised but resolver never acts. Dispute timeout passes (300s). Auto-cancel at t=1360 succeeds. Tests resolver inaction fallback."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})

;; S95: Dispute with conflicting resolution challenge

(def s95
  {:scenario-id     "s95-resolution-challenge-and-counter"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "adversarial"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Resolver releases, seller challenges, multiple counter-evidence. Tests evidence complexity."
   :adversary? true
   :adversary/type :evidence-manipulator
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xresolver_decision"}}
    {:seq 3 :time 1150 :agent "seller" :action "challenge_resolution"
     :params {:workflow-id 0 :evidence-hash "0xseller_contra_evidence_1"}}
    {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S96: Multi-token escrow scenario

(def s96
  {:scenario-id     "s96-multi-token-dispute"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Two escrows same token. Both disputed separately. Tests multi-escrow settlement isolation."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1010 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1070 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 4 :time 1130 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash0"}}
    {:seq 5 :time 1140 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release false :resolution-hash "0xhash1"}}
    {:seq 6 :time 1251 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 7 :time 1261 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 1}}]})

;; S97: Appeal after settlement boundary

 (def s97
   {:scenario-id     "s97-appeal-after-settlement-attempt"
    :schema-version  "1.0"
   :scenario-author "@grifma"
    :initial-block-time 1000
    :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
    :protocol-params appeal
    :notes "Settlement executed, escrow terminal. Subsequent actions rejected. Tests settlement finality."
    :expected-errors [{:seq 4 :action "submit_evidence" :error :transfer-not-in-dispute}]
    :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
     {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
     {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
     {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
     {:seq 4 :time 1320 :agent "seller" :action "submit_evidence"
     :params {:workflow-id 0 :evidence-hash "0xpost-settlement"}}]})

;; S98: Receiver initiates cancel after auto-cancel deadline

(def s98
  {:scenario-id     "s98-receiver-cancel-after-auto-cancel"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params dr3
   :notes "Receiver (seller) calls recipient_cancel on a PENDING escrow with no cancellation strategy configured and no auto-cancel-time set. Tests mutual-consent cancel path (non-strategic). Note: the name is a misnomer — no auto-cancel deadline is involved; the scenario tests plain recipient_cancel without strategy."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1200 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}]})

;; S99: Large escrow with multiple fee tiers

(def s99
  {:scenario-id     "s99-large-escrow-fee-impact"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Very large escrow (1M USDC). Fee impact on settlement. Tests ledger arithmetic at scale."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1000000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    {:seq 3 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S100: Receiver denies, then dispute is resolved as release

(def s100
  {:scenario-id     "s100-deny-then-resolver-releases"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"  :role "keeper"}]
   :protocol-params appeal
   :notes "Receiver denies receipt, then dispute resolves as release anyway. Tests denial override."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1050 :agent "seller" :action "recipient_deny"
     :params {:workflow-id 0}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
     {:seq 4 :time 1250 :agent "keeper" :action "execute_pending_settlement"
      :params {:workflow-id 0}}]})

(def s114
  {:scenario-id        "s114-resolution-module-mutation"
   :schema-version     "1.0"
   :scenario-author    "@opencode"
   :initial-block-time 1000
   :agents             [{:id "buyer"   :address "0xbuyer"   :strategy "honest"}
                        {:id "seller"  :address "0xseller"  :strategy "honest"}
                        {:id "resolver" :address "0xresolver" :role "resolver"}
                        {:id "governance" :address "0xgov" :role "governance"}]
   :protocol-params    dr3
   :notes "Governance changes the resolution-module param while a dispute is in-flight. The existing dispute's module snapshot is immutable, so it resolves normally. New escrows would use the new module."
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 5000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1100 :agent "governance" :action "set_resolution_module"
     :params {:resolution-module "0xnew-module"}}
     {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
      :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

(def s115
  {:scenario-id        "s115-resolution-refused-timeout"
   :schema-version     "1.0"
   :scenario-author    "@opencode"
   :initial-block-time 1000
   :agents             [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                        {:id "seller"   :address "0xseller"   :strategy "honest"}
                        {:id "resolver" :address "0xresolver" :role "resolver"}
                        {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params    timeout
   :notes "Kleros ruling 0 (refuse to arbitrate). The escrow stays disputed until max-dispute-duration (300s) elapses, then the keeper auto-cancels via auto-cancel-refused-resolution."
   :allow-open-disputes? true
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 5000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :resolution-outcome "cannot-resolve" :resolution-hash "0xhash"}}
    {:seq 4 :time 1361 :agent "keeper" :action "automate_timed_actions"
     :params {:workflow-id 0}}]})

(def s116
  {:scenario-id        "s116-resolution-refused-then-resolved"
   :schema-version     "1.0"
   :scenario-author    "@opencode"
   :initial-block-time 1000
   :agents             [{:id "buyer"      :address "0xbuyer"      :strategy "honest"}
                        {:id "seller"     :address "0xseller"     :strategy "honest"}
                        {:id "resolver"  :address "0xresolver"  :role "resolver"}
                        {:id "governance" :address "0xgov"       :role "governance"}]
   :protocol-params    timeout
   :notes "Kleros ruling 0 (refuse to arbitrate). Before the timeout expires, governance rotates the resolver (who can then release). Tests the recovery path where a refused dispute gets back on track."
   :events
   [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
     :params {:amount 5000}}
    {:seq 1 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :resolution-outcome "cannot-resolve" :resolution-hash "0xhash"}}
    {:seq 4 :time 1200 :agent "governance" :action "rotate_dispute_resolver"
     :params {:workflow-id 0 :new-resolver "0xresolver"}}
    {:seq 5 :time 1300 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xrelease"}}]})

;; ---------------------------------------------------------------------------

