(ns resolver-sim.protocols.sew.invariant-scenarios.baseline
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer :all]))

(def s01
  {:scenario-id     "s01-baseline-happy-path"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S02 — DR3 dispute → release
;; ---------------------------------------------------------------------------

(def s02
  {:scenario-id     "s02-dr3-dispute-release"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S03 — DR3 dispute → refund
;; ---------------------------------------------------------------------------

(def s03
  {:scenario-id     "s03-dr3-dispute-refund"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S04 — dispute timeout → auto-cancel
;; Dispute raised at t=1060; max-dispute-duration=300 → eligible at t≥1360.
;; ---------------------------------------------------------------------------

(def s04
  {:scenario-id     "s04-dispute-timeout-autocancel"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
   :protocol-params timeout
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Early attempt → rejected (240 s elapsed, need 300)
    {:seq 2 :time 1300 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    ;; After timeout (1360 − 1060 = 300 ≥ 300) → ok
    {:seq 3 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S05 — pending settlement → execute
;; Resolution at t=1120; appeal-deadline = 1120+120 = 1240.
;; ---------------------------------------------------------------------------

(def s05
  {:scenario-id     "s05-pending-settlement-execute"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "executor" :address "0xexecutor" :role "keeper"}]
   :protocol-params appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver votes; creates pending-settlement (deadline = 1120+120 = 1240)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; Early → rejected (appeal window still open)
    {:seq 3 :time 1180 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; At deadline → ok
    {:seq 4 :time 1240 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S06 — mutual cancel
;; ---------------------------------------------------------------------------

(def s06
  {:scenario-id     "s06-mutual-cancel"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1500}}
    {:seq 1 :time 1060 :agent "buyer" :action "sender_cancel"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S07 — unauthorised resolver rejected; authorised resolver succeeds
;; ---------------------------------------------------------------------------

(def s07
  {:scenario-id     "s07-unauthorized-resolver-rejected"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"       :address "0xbuyer"       :strategy "honest"}
                     {:id "seller"      :address "0xseller"      :strategy "honest"}
                     {:id "badresolver" :address "0xbadresolver" :strategy "malicious" :role "resolver"}
                     {:id "resolver"    :address "0xresolver"    :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Wrong resolver → rejected
    {:seq 2 :time 1120 :agent "badresolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xfake"}}
    ;; Authorised resolver → ok
    {:seq 3 :time 1180 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xauth"}}]})

;; ---------------------------------------------------------------------------
;; S08 — state machine attack gauntlet
;; Every invalid transition is attempted; all are rejected; no violations fire.
;; ---------------------------------------------------------------------------

(def s08
  {:scenario-id     "s08-state-machine-attack-gauntlet"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :custom-resolver "0xresolver"}}
    ;; Execute-resolution before dispute → rejected
    {:seq 1 :time 1060 :agent "buyer" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xh"}}
    ;; Raise dispute → ok
    {:seq 2 :time 1120 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Release while disputed → rejected
    {:seq 3 :time 1180 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    ;; Double-dispute → rejected
    {:seq 4 :time 1240 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Authorised resolver resolves → ok (no appeal window → finalized immediately)
    {:seq 5 :time 1300 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xauth"}}
    ;; Resolve on terminal state → rejected
    {:seq 6 :time 1360 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xdup"}}
    ;; Dispute on terminal state → rejected
    {:seq 7 :time 1420 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Release on terminal state → rejected
    {:seq 8 :time 1480 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S09 — multi-escrow solvency (3 concurrent escrows, mixed outcomes)
;; wf0: direct release; wf1/wf2: disputed and resolved.
;; ---------------------------------------------------------------------------

(def s09
  {:scenario-id     "s09-multi-escrow-solvency"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer0"   :address "0xbuyer0"   :strategy "honest"}
                     {:id "buyer1"   :address "0xbuyer1"   :strategy "honest"}
                     {:id "buyer2"   :address "0xbuyer2"   :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer0" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1000}}
    {:seq 1 :time 1000 :agent "buyer1" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1000 :agent "buyer2" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000
              :custom-resolver "0xresolver"}}
    {:seq 3 :time 1060 :agent "buyer0" :action "release"
     :params {:workflow-id 0}}
    {:seq 4 :time 1060 :agent "buyer1" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 5 :time 1060 :agent "buyer2" :action "raise_dispute"
     :params {:workflow-id 2}}
    {:seq 6 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xh1"}}
    {:seq 7 :time 1180 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 2 :is-release true :resolution-hash "0xh2"}}]})

;; ---------------------------------------------------------------------------
;; S10 — double-finalize rejected
;; wf0: release × 3 (2 rejected); wf1: dispute + resolve × 2 (1 rejected).
;; ---------------------------------------------------------------------------

(def s10
  {:scenario-id     "s10-double-finalize-rejected"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "seller2"  :address "0xseller2"  :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 500}}
    ;; resolver acts as buyer for wf1 (creates to seller2, sets itself as resolver)
    {:seq 1 :time 1000 :agent "resolver" :action "create_escrow"
     :params {:token "USDC" :to "0xseller2" :amount 500
              :custom-resolver "0xresolver"}}
    {:seq 2 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    {:seq 3 :time 1060 :agent "resolver" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 4 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xok"}}
    ;; Double-release → rejected (wf0 is :released terminal)
    {:seq 5 :time 1180 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    {:seq 6 :time 1240 :agent "buyer" :action "release"
     :params {:workflow-id 0}}
    ;; Double-resolve → rejected (wf1 is :released terminal)
    {:seq 7 :time 1300 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 1 :is-release true :resolution-hash "0xdup"}}]})

;; ---------------------------------------------------------------------------
;; S11 — zero fee edge case
;; ---------------------------------------------------------------------------

(def s11
  {:scenario-id     "s11-zero-fee-edge-case"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params {:resolver-fee-bps 0 :appeal-window-duration 0
                     :max-dispute-duration 2592000}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S12 — governance snapshot isolation
;; Two independent sessions with different fee_bps must not cross-contaminate.
;; Defined as two scenarios; the runner reports them jointly as S12.
;; ---------------------------------------------------------------------------

(def s12a
  {:scenario-id     "s12a-snapshot-isolation-fee-zero"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 0}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 10000}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

(def s12b
  {:scenario-id     "s12b-snapshot-isolation-fee-500"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params {:resolver-fee-bps 500}
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 10000}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S13 — pending settlement → refund
;; Same as S05 but resolver votes is-release=false.
;; ---------------------------------------------------------------------------

(def s13
  {:scenario-id     "s13-pending-settlement-refund"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "executor" :address "0xexecutor" :role "keeper"}]
   :protocol-params appeal
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000
              :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Refund vote; creates pending-settlement (deadline = 1120+120 = 1240)
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash"}}
    ;; Early → rejected
    {:seq 3 :time 1180 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; At deadline → ok (finalizes as :refunded)
    {:seq 4 :time 1240 :agent "executor" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S14 — DR3 module (Priority 2): authorised resolver succeeds
;; No custom-resolver; authority falls through to the resolution module.
;; ---------------------------------------------------------------------------

(def s14
  {:scenario-id     "s14-dr3-module-authorized"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}]
   :protocol-params dr3-module
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S15 — DR3 module: unauthorised resolver rejected; authorised succeeds
;; ---------------------------------------------------------------------------

(def s15
  {:scenario-id     "s15-dr3-module-unauthorized-rejected"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"       :address "0xbuyer"       :strategy "honest"}
                     {:id "seller"      :address "0xseller"      :strategy "honest"}
                     {:id "badresolver" :address "0xbadresolver" :strategy "malicious" :role "resolver"}
                     {:id "resolver"    :address "0xresolver"    :role "resolver"}]
   :protocol-params dr3-module
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Module rejects 0xbadresolver → revert
    {:seq 2 :time 1120 :agent "badresolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xfake"}}
    ;; Module authorizes 0xresolver → ok
    {:seq 3 :time 1180 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xauth"}}]})

;; ---------------------------------------------------------------------------
;; S16 — IEO base: create + release (no resolver, zero fee)
;; ---------------------------------------------------------------------------

(def s16
  {:scenario-id     "s16-ieo-create-release"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params ieo
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2000}}
    {:seq 1 :time 1060 :agent "buyer" :action "release"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S17 — IEO: dispute with no resolver → auto-cancel on timeout
;; Dispute at t=1060; max-dispute-duration=300 → eligible at t≥1360.
;; ---------------------------------------------------------------------------

(def s17
  {:scenario-id     "s17-ieo-dispute-no-resolver-timeout"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "keeper" :address "0xkeeper" :role "resolver"}]
   :protocol-params ieo-timeout
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 1000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Early → rejected
    {:seq 2 :time 1300 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    ;; Timeout elapsed → ok
    {:seq 3 :time 1360 :agent "keeper" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S18 — DR3 Kleros: L0 resolver resolves at level 0
;; ---------------------------------------------------------------------------

(def s18
  {:scenario-id     "s18-dr3-kleros-l0-resolves"
   :title           "DR3 Kleros: L0 Resolver Resolves"
   :threat-tags     ["appeal-escalation"]
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}]
   :protocol-params kleros
   :events
   [(kleros-create-escrow-event 0 1000)
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Kleros module authorizes 0xl0 at level 0 → finalized immediately
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}]})

;; ---------------------------------------------------------------------------
;; S19 — DR3 Kleros: preemptive escalation rejected; L0 resolves
;; No appeal window → no pending settlement → escalation reverts before l0 acts.
;; The escalation attempt is rejected (:no-resolution-to-appeal); l0 then resolves.
;; ---------------------------------------------------------------------------

(def s19
  {:scenario-id     "s19-dr3-kleros-escalation-rejected-l0-resolves"
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :strict-expected-errors? true
   :expected-errors [{:seq 2 :action "escalate_dispute" :error :no-resolution-to-appeal}
                     {:seq 4 :action "execute_resolution" :error :transfer-not-in-dispute}]
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}]
   :protocol-params kleros
   :events
   [(kleros-create-escrow-event 0 1000)
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Escalation before any resolution → rejected (:no-resolution-to-appeal)
    {:seq 2 :time 1060 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolver resolves immediately (no appeal window)
    {:seq 3 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
    ;; L1 tries after L0 resolved → rejected (:transfer-not-in-dispute)
    ;; State check now fires before auth; escrow is :released, so any caller
    ;; receives the same informative error regardless of resolver identity.
    {:seq 4 :time 1180 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}]})

;; ---------------------------------------------------------------------------
;; S20 — DR3 Kleros: max-escalation guard
;; All escalation attempts rejected (no pending settlement in no-appeal-window mode).
;; L2 resolver also rejected (level 0 authorizes 0xl0, not 0xl2).
;; Dispute unresolved at end — no invariant violations.
;; ---------------------------------------------------------------------------

(def s20
  {:scenario-id          "s20-dr3-kleros-max-escalation-guard"
   :title                "DR3 Kleros: Max Escalation Guard"
   :threat-tags          ["appeal-escalation"]
   :schema-version       "1.0"
   :scenario-author "@grifma"
   :allow-open-disputes? true ; intentionally tests guard logic; dispute is never resolved
   :initial-block-time   1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l2resolver" :address "0xl2"     :role "resolver"}]
   :protocol-params kleros
   :events
   [(kleros-create-escrow-event 0 1000)
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Three escalation attempts, all rejected (:no-resolution-to-appeal)
    {:seq 2 :time 1060 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    {:seq 3 :time 1120 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1180 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L2 tries to resolve at level 0 → rejected (module authorizes 0xl0 not 0xl2)
    {:seq 5 :time 1240 :agent "l2resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl2hash"}}]})

;; ---------------------------------------------------------------------------
;; S21 — DR3 Kleros: pending settlement cleared on escalation
;; Requires appeal window so a pending settlement exists for escalation to clear.
;; Sequence: L0 resolves → pending; buyer escalates (clears pending, level→1);
;;           L1 resolves → pending; execute_pending_settlement after deadline.
;; ---------------------------------------------------------------------------

(def s21
  {:scenario-id     "s21-dr3-kleros-pending-cleared-on-escalation"
   :title           "DR3 Kleros: Pending Cleared On Escalation"
   :threat-tags     ["appeal-escalation"]
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "resolver"}]
   :protocol-params kleros-appeal
   :events
   [(kleros-create-escrow-event 0 1000)
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolves → creates pending (deadline = 1120+60 = 1180)
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Buyer escalates before deadline → clears pending, level→1, new-resolver=0xl1
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L1 resolves → new pending (deadline = 1190+60 = 1250)
    {:seq 4 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    ;; Keeper executes after deadline → ok
    {:seq 5 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S22 — REGRESSION: transition-to-disputed clears counterparty agree-to-cancel
;; Bug: raise_dispute was not clearing recipient's :agree-to-cancel status.
;; Fix: transition-to-disputed now resets counterparty status to :none.
;; Expected: PASS (no invariant violation with the fix applied).
;; ---------------------------------------------------------------------------

(def s22
  {:scenario-id          "s22-status-leak-agree-cancel-over-dispute"
   :schema-version       "1.0"
   :scenario-author "@grifma"
   :allow-open-disputes? true ; intentionally stops after raising the dispute (regression test for status clearing)
   :initial-block-time   1000
   :agents          [{:id "buyer"  :address "0xbuyer"  :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}]
   :protocol-params dr3
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    ;; Seller agrees to cancel (sets recipient :agree-to-cancel, state stays :pending)
    {:seq 1 :time 1000 :agent "seller" :action "recipient_cancel"
     :params {:workflow-id 0}}
    ;; Buyer raises dispute — must clear seller's :agree-to-cancel status
    {:seq 2 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S23 — REGRESSION: preemptive escalation rejected (no pending settlement)
;; Bug: escalate_dispute had no pending-settlement guard → buyer could skip levels.
;; Fix: escalation now requires a pending settlement (it is an appeal, not a skip).
;; Sequence: buyer disputes; seller tries to escalate (both attempts rejected);
;;           L0 resolver resolves immediately.
;; Expected: PASS (both seller escalations rejected without violation).
;; ---------------------------------------------------------------------------

(def s23
  {:scenario-id     "s23-preemptive-escalation-blocked"
   :title           "DR3 Kleros: Preemptive Escalation Blocked"
   :threat-tags     ["escalation-abuse"]
   :schema-version  "1.0"
   :scenario-author "@grifma"
   :strict-expected-errors? true
   :expected-errors [{:seq 2 :action "escalate_dispute" :error :no-resolution-to-appeal}
                     {:seq 4 :action "escalate_dispute" :error :transfer-not-in-dispute}]
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}]
   :protocol-params kleros
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Seller attempts preemptive escalation → rejected (:no-resolution-to-appeal)
    {:seq 2 :time 1060 :agent "seller" :action "escalate_dispute"
     :params {:workflow-id 0}}
    ;; L0 resolver resolves immediately (no appeal window)
    {:seq 3 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    ;; Seller tries again on terminal state → rejected (:transfer-not-in-dispute)
    {:seq 4 :time 1180 :agent "seller" :action "escalate_dispute"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S24 — resolver stake depletion cascade
;;
;; One resolver (stake=3000) is assigned to three concurrent escrows (AFA=2000 each).
;; All three disputes timeout. Each auto-cancel slashes the resolver's remaining stake:
;;   wf0: slashes 2000 → stake 3000→1000  (full slash)
;;   wf1: slashes 2000 → stake 1000→0     (partial: only 1000 available)
;;   wf2: slashes 2000 → stake 0→0        (zero: nothing left to slash)
;;
;; Exercises:
;;   - held-custody-reconciles? at each step (strict = on total-held vs live-sum)
;;   - conservation-of-funds? after all three auto-cancels
;;     (0 held + 0 released + 6000 refunded = 6000 deposited)
;;   - held-non-negative? / sub-held assert survives all three finalizations
;;   - slash-resolver-stake saturation: min(stake, slash) never goes negative
;; ---------------------------------------------------------------------------

