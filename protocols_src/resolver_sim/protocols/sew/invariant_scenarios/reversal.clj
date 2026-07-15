(ns resolver-sim.protocols.sew.invariant-scenarios.reversal
  "Invariant scenarios for reversal slashing (Track 1/2) and challenge paths."
  (:require [resolver-sim.protocols.sew.invariant-scenarios.common :refer :all]))

(def ^:private reversal-slash-enabled
  (assoc kleros-appeal
         :reversal-slash-bps 2500
         ;; Track 2 executes through the common slash executor, whose epoch
         ;; cap must admit the configured per-offense reversal penalty.
         :slash-epoch-cap-bps 2500
         :challenge-bounty-bps 1000))

;; S101 — Track 1 reversal slash executes when bps > 0 (pre-v3 / enabled config)
(def s101
  {:scenario-id     "s101-reversal-slash-track1-enabled"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0"         :address "0xl0"     :role "resolver"}
                     {:id "l1"         :address "0xl1"     :role "resolver"}
                     {:id "challenger" :address "0xchall"  :strategy "honest"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params reversal-slash-enabled
   :events
   [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0"}}
    {:seq 5 :time 1130 :agent "challenger" :action "challenge_resolution"
     :params {:workflow-id 0}}
    {:seq 6 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1"}}
    {:seq 7 :time 1261 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S102 — challenge_resolution records challenger for reversal bounty
(def s102
  {:scenario-id     "s102-reversal-challenge-bounty"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0"         :address "0xl0"     :role "resolver"}
                     {:id "l1"         :address "0xl1"     :role "resolver"}
                     {:id "watchdog"   :address "0xwatch"  :strategy "honest"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params reversal-slash-enabled
   :events
   [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 8000}}
    {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 8000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0"}}
    {:seq 5 :time 1130 :agent "watchdog" :action "challenge_resolution"
     :params {:workflow-id 0}}
    {:seq 6 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1"}}
    {:seq 7 :time 1261 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S103 — L2 fork produces distinct level-scoped reversal slash ids
(def s103
  {:scenario-id     "s103-l2-reversal-slash-ids"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0resolver" :address "0xl0"     :role "resolver"}
                     {:id "l1resolver" :address "0xl1"     :role "resolver"}
                     {:id "l2resolver" :address "0xl2"     :role "resolver"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params reversal-slash-enabled
   :events
   [{:seq 0 :time 1000 :agent "l0resolver" :action "register_stake" :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "l1resolver" :action "register_stake" :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "l2resolver" :action "register_stake" :params {:amount 10000}}
    {:seq 3 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 6000}}
    {:seq 4 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 5 :time 1120 :agent "l0resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
    {:seq 6 :time 1130 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 7 :time 1190 :agent "l1resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1hash"}}
    {:seq 8 :time 1200 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 9 :time 1260 :agent "l2resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl2hash"}}
    {:seq 10 :time 1325 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S104 — Track 1 executed reversal slash cannot be appealed
(def s104
  {:scenario-id     "s104-executed-reversal-slash-not-appealable"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0"         :address "0xl0"     :role "resolver"}
                     {:id "l1"         :address "0xl1"     :role "resolver"}
                     {:id "challenger" :address "0xchall"  :strategy "honest"}]
   :protocol-params reversal-slash-enabled
   :expected-fail? true
   :events
   [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 8000}}
    {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 8000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0"}}
    {:seq 5 :time 1130 :agent "challenger" :action "challenge_resolution"
     :params {:workflow-id 0}}
    {:seq 6 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1"}}
    {:seq 7 :time 1201 :agent "l0" :action "appeal_slash"
     :params {:workflow-id 0
                   :slash-id {:slash-ref {:workflow-id 0 :kind :reversal :level 0}}}}]})

;; S105 — escalate_dispute records challenger (same reversal settlement as challenge)
(def s105
  {:scenario-id     "s105-escalate-challenger-on-reversal"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"   :address "0xseller" :strategy "honest"}
                     {:id "l0"       :address "0xl0"     :role "resolver"}
                     {:id "l1"       :address "0xl1"     :role "resolver"}
                     {:id "keeper"   :address "0xkeeper" :role "keeper"}]
   :protocol-params reversal-slash-enabled
   :events
   [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 8000}}
    {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 8000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0"}}
    {:seq 5 :time 1130 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 6 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1"}}
    {:seq 7 :time 1261 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S106 — Track 2: new evidence → pending reversal slash → appeal upheld → :reversed
;; Verifies a reversal slash can itself be reversed before stake execution (L0 stake intact).
(def s106
  {:scenario-id     "s106-reversal-track2-evidence-appeal"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0"         :address "0xl0"     :role "resolver"}
                     {:id "l1"         :address "0xl1"     :role "resolver"}
                     {:id "governance" :address "0xgov"    :role "governance"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params reversal-slash-enabled
   :events
   [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 8000}}
    {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 8000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0"}}
    {:seq 5 :time 1130 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 6 :time 1140 :agent "seller" :action "submit_evidence"
     :params {:workflow-id 0 :evidence-hash "0xnew-evidence"}}
    {:seq 7 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1"}}
    {:seq 8 :time 1201 :agent "governance" :action "appeal_slash"
     :params {:workflow-id 0
              :slash-id {:slash-ref {:workflow-id 0 :kind :reversal :level 0}}}}
    {:seq 9 :time 1202 :agent "governance" :action "resolve_appeal"
     :params {:workflow-id 0
              :slash-id {:slash-ref {:workflow-id 0 :kind :reversal :level 0}}
              :upheld? true}}
    {:seq 10 :time 1261 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S107 — Track 2 reversal slash: appeal rejected, slash executes after deadline
(def s107
  {:scenario-id     "s107-reversal-track2-appeal-rejected-executes"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0"         :address "0xl0"     :role "resolver"}
                     {:id "l1"         :address "0xl1"     :role "resolver"}
                     {:id "governance" :address "0xgov"    :role "governance"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   ;; The slash and settlement become executable in adjacent replay steps.
   ;; Permit that intentional intermediate state; the following keeper event
   ;; must consume the settlement.
   :protocol-params reversal-slash-enabled
   :expected-failures {"s107-reversal-track2-appeal-rejected-executes"
                       #{:no-stale-automatable-escrows}}
   :events
   [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 8000}}
    {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 8000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 4000}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xl0"}}
    {:seq 5 :time 1130 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}
    {:seq 6 :time 1140 :agent "seller" :action "submit_evidence"
     :params {:workflow-id 0 :evidence-hash "0xnew-evidence"}}
    {:seq 7 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xl1"}}
    {:seq 8 :time 1201 :agent "governance" :action "appeal_slash"
     :params {:workflow-id 0
              :slash-id {:slash-ref {:workflow-id 0 :kind :reversal :level 0}}}}
    {:seq 9 :time 1202 :agent "governance" :action "resolve_appeal"
     :params {:workflow-id 0
              :slash-id {:slash-ref {:workflow-id 0 :kind :reversal :level 0}}
              :upheld? false}}
    {:seq 10 :time 1261 :agent "governance" :action "execute_fraud_slash"
     :params {:workflow-id 0
              :slash-id {:slash-ref {:workflow-id 0 :kind :reversal :level 0}}}}
    {:seq 11 :time 1262 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

;; S43 (orphan recovery) — Kleros-module reversal slash (ported from standalone JSON)
;; Functionally equivalent to S101, retains golden fixture at s43-dr3-kleros-reversal-slash.
(def s43-kleros-reversal-slash
  {:scenario-id     "s43-dr3-kleros-reversal-slash"
   :schema-version  "1.0"
   :scenario-author scenario-author
   :initial-block-time 1000
   :agents          [{:id "buyer"      :address "0xbuyer"  :strategy "honest"}
                     {:id "seller"     :address "0xseller" :strategy "honest"}
                     {:id "l0"         :address "0xl0"     :role "resolver"}
                     {:id "l1"         :address "0xl1"     :role "resolver"}
                     {:id "challenger" :address "0xchall"  :strategy "honest"}
                     {:id "keeper"     :address "0xkeeper" :role "keeper"}]
   :protocol-params reversal-slash-enabled
   :events
   [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 10000}}
    {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 10000}}
    {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0xseller" :amount 5000}}
    {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
    {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
     :params {:workflow-id 0 :is-release true :resolution-hash "0xhash-l0"}}
    {:seq 5 :time 1130 :agent "challenger" :action "challenge_resolution"
     :params {:workflow-id 0}}
    {:seq 6 :time 1200 :agent "l1" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false :resolution-hash "0xhash-l1"}}
    {:seq 7 :time 1261 :agent "keeper" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})
