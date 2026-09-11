(ns resolver-sim.protocols.sew.invariant-scenarios.gaps
  "S43–S48 equivalence-gap scenarios ported from fixture traces.

   Fills numbering holes between S42 and the extended suite (S49+).
   S45 flash-loan and S46 reorg-idempotence remain in adversarial.clj."
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer :all]))

;; ---------------------------------------------------------------------------
;; S43 — Unauthorized resolver rejected, authorized recovery
;; ---------------------------------------------------------------------------

(def s43
  {:scenario-id     "s43-auth-rejected-then-authorized-recovery"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"       :address "0xbuyer"       :strategy "honest"}
                     {:id "seller"      :address "0xseller"      :strategy "honest"}
                     {:id "badresolver" :address "0xbadresolver" :strategy "malicious" :role "resolver"}
                     {:id "resolver"    :address "0xresolver"    :role "resolver"}]
   :protocol-params dr3
   :notes "Unauthorized resolver attempt must reject; authorized resolver then succeeds."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3000 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "badresolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xfake"}}
    {:seq 3 :time 1180 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xauth"}}]})

;; ---------------------------------------------------------------------------
;; S44 — Escalation tier mismatch rejected
;; ---------------------------------------------------------------------------

(def s44
  {:scenario-id     "s44-escalation-tier-mismatch-rejected"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"       :address "0xbuyer"       :strategy "honest"}
                     {:id "seller"      :address "0xseller"      :strategy "honest"}
                     {:id "l0resolver"  :address "0xl0"          :role "resolver"}
                     {:id "l1resolver"  :address "0xl1"          :role "resolver"}
                     {:id "badresolver" :address "0xbadresolver" :strategy "malicious" :role "resolver"}
                     {:id "keeper"      :address "0xkeeper"      :role "keeper"}]
   :protocol-params kleros-appeal
   :notes "Wrong-tier resolver rejected after escalation; L1 authorized resolver succeeds."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    {:seq 3 :time 1130 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1140 :agent "badresolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xwrongtier"}}
    {:seq 5 :time 1200 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    {:seq 6 :time 1260 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S45-stale — Stale module snapshot rejects legacy resolver
;; (Distinct from s45-flash-loan-stake-inflation in adversarial.clj)
;; ---------------------------------------------------------------------------

(def s45-stale-module-snapshot
  {:scenario-id     "s45-stale-module-snapshot-rejects-legacy-resolver"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"           :address "0xbuyer"       :strategy "honest"}
                     {:id "seller"          :address "0xseller"      :strategy "honest"}
                     {:id "legacyresolver"  :address "0xlegacy"      :role "resolver"}
                     {:id "newresolver"     :address "0xnewresolver" :role "resolver"}]
   :protocol-params (assoc dr3-module :resolution-module "0xmodule-v2")
   :notes "Legacy resolver rejected after module snapshot change; new resolver succeeds."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 3500 :custom-resolver "0xnewresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "legacyresolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xlegacy"}}
    {:seq 3 :time 1180 :agent "newresolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xnew"}}]})

;; ---------------------------------------------------------------------------
;; S47a / S47b — Appeal window boundary pair
;; ---------------------------------------------------------------------------

(def s47a
  {:scenario-id     "s47a-appeal-window-last-second-settlement"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal-60
   :notes "Settlement exactly at appeal-window boundary (inclusive) succeeds."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2500 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xok"}}
    {:seq 3 :time 1180 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def s47b
  {:scenario-id     "s47b-appeal-window-plus-one-rejected"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "resolver" :address "0xresolver" :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params appeal-60
   :allow-open-disputes? true
   :notes "Settlement one second after appeal-window boundary must reject; dispute remains open."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 2500 :custom-resolver "0xresolver"}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xok"}}
    {:seq 3 :time 1181 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S48 — Max escalation exact boundary accepted
;; ---------------------------------------------------------------------------

(def s48
  {:scenario-id     "s48-max-escalation-exact-boundary"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal
   :notes "Escalation to configured max level accepted; L1 resolution and settlement succeed."
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
    {:seq 5 :time 1250 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; ---------------------------------------------------------------------------
;; S46a / S46b — Settlement vs escalation window edge (counterfactual pair)
;; Distinct from s46-reorg-idempotence in adversarial.clj
;; ---------------------------------------------------------------------------

(def s46a
  {:scenario-id     "s46a-settlement-before-escalation-window-edge"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal
   :notes "Settlement at window edge before escalation; post-settlement escalation rejected."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    {:seq 3 :time 1180 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 4 :time 1181 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}]})

(def s46b
  {:scenario-id     "s46b-escalation-before-settlement-window-edge"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params kleros-appeal
   :notes "Escalation lands before settlement window edge; L1 path differs from s46a."
   :events
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    {:seq 3 :time 1179 :agent "buyer" :action "escalate_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 1180 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    {:seq 5 :time 1240 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl1hash"}}
    {:seq 6 :time 1300 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})
