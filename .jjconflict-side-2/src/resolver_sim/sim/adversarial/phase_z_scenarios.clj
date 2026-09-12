(ns resolver-sim.sim.adversarial.phase-z-scenarios
  "Phase Z adversarial scenarios mapped to the contract-model replay format.

   Phase Z tests legitimacy / participation collapse using concrete EVM-like
   action sequences that stress the contract layer. Each scenario is a valid
   input to replay-with-protocol and produces a scored trace map (persistence
   via resolver-sim.io.phase-z-persist).

   NOTE: Timeout-based actions must respect per-workflow dispute timestamps:
   auto_cancel_disputed becomes eligible at (raise_dispute_time + max-dispute-duration)
   for each workflow."
  (:require [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.sim.trace-score :as ts]))

(defn- default-protocol []
  (preg/get-protocol preg/default-protocol-id))

;; ---------------------------------------------------------------------------
;; Shared protocol params (maps to vault's 90-day maxDisputeDuration)
;; ---------------------------------------------------------------------------

(def ^:private params-dr3
  {:resolver-fee-bps       100
   :appeal-window-duration 0
   :max-dispute-duration   7776000    ;; 90 days in seconds
   :resolution-module      "0x0000000000000000000000000000000000001234"})

;; ---------------------------------------------------------------------------
;; Scenario definitions
;; ---------------------------------------------------------------------------

(def scenario-z1-resolver-absent
  "TEST 2/4: resolver absent after market shock.
   Buyer creates, raises dispute. Resolver never acts.
   After 90 days the keeper (buyer) auto-cancels.
   Liveness failure score: +5."
  {:scenario-id        "phase-z-1-resolver-absent"
   :schema-version     "1.0"
   :initial-block-time 1000
   :seed               421
   :agents
   [{:id "buyer" :address "0x0000000000000000000000000000000000001001" :strategy "honest"}
    {:id "seller" :address "0x0000000000000000000000000000000000001002" :strategy "honest"}
    {:id "resolver" :address "0x0000000000000000000000000000000000001234" :role "resolver"}]
   :protocol-params params-dr3
   :events
   [{:seq 0 :time 1001 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 100000}}
    {:seq 1 :time 2000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver absent — no execute_resolution step.
    ;; After max-dispute-duration the dispute auto-cancels.
    {:seq 2 :time 7778001 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}]})

(def scenario-z3-false-positive-slash
  "TEST 3: Scam wave / false positive.
   Resolver (acting as attacker) cancels an escrow that should be released.
   Attacker-type resolver exploits dispute resolution.
   Score: attack-success +1 × 1."
  {:scenario-id        "phase-z-3-false-positive"
   :schema-version     "1.0"
   :initial-block-time 1000
   :seed               422
   :agents
   [{:id "buyer" :address "0x0000000000000000000000000000000000001001" :strategy "honest"}
    {:id "seller" :address "0x0000000000000000000000000000000000001002" :strategy "honest"}
    {:id "resolver" :address "0x0000000000000000000000000000000000001234" :strategy "malicious" :role "resolver"}]
   :protocol-params params-dr3
   :events
   [{:seq 0 :time 1001 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 100000}}
    {:seq 1 :time 2000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver cancels (false positive: should have released)
    {:seq 2 :time 3000 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false}}
    {:seq 3 :time 175801 :agent "buyer" :action "execute_pending_settlement"
     :params {:workflow-id 0}}]})

(def scenario-z4-combined-shock
  "TEST 4: Combined shock — two escrows dispute, resolver resolves one wrong,
   second auto-cancels (resolver exits mid-stream).
   Combines false positive + liveness failure.
   Score: attack-success +1, liveness-fail +5."
  {:scenario-id        "phase-z-4-combined-shock"
   :schema-version     "1.0"
   :initial-block-time 1000
   :seed               423
   :agents
   [{:id "buyer" :address "0x0000000000000000000000000000000000001001" :strategy "honest"}
    {:id "seller" :address "0x0000000000000000000000000000000000001002" :strategy "honest"}
    {:id "resolver" :address "0x0000000000000000000000000000000000001234" :strategy "malicious" :role "resolver"}]
   :protocol-params params-dr3
   :events
   ;; Escrow A
   [{:seq 0 :time 1001 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 100000}}
    {:seq 1 :time 2000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    ;; Resolver acts on escrow A (false positive cancel)
    {:seq 2 :time 3000 :agent "resolver" :action "execute_resolution"
     :params {:workflow-id 0 :is-release false}}
    {:seq 3 :time 175801 :agent "buyer" :action "execute_pending_settlement"
     :params {:workflow-id 0}}
    ;; Escrow B — resolver exits; times out
    {:seq 4 :time 175900 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 80000}}
    {:seq 5 :time 176000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 1}}
    ;; Resolver absent for wf1; auto-cancel after 90 days from dispute raise
    {:seq 6 :time 7954001 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id 1}}]})

(def scenario-z5-cascade-liveness
  "TEST 5: Cascading failures — three concurrent disputes, resolver resolves none.
   All three auto-cancel. Maximum liveness failure score: +5."
  {:scenario-id        "phase-z-5-cascade-liveness"
   :schema-version     "1.0"
   :initial-block-time 1000
   :seed               424
   :agents
   [{:id "buyer" :address "0x0000000000000000000000000000000000001001" :strategy "honest"}
    {:id "seller" :address "0x0000000000000000000000000000000000001002" :strategy "honest"}
    {:id "resolver" :address "0x0000000000000000000000000000000000001234" :strategy "honest"}]
   :protocol-params params-dr3
   :events
   ;; All three escrows created
   [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 100000}}
    {:seq 1 :time 1010 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 50000}}
    {:seq 2 :time 1020 :agent "buyer" :action "create_escrow"
     :params {:token "USDC" :to "0x0000000000000000000000000000000000001002" :amount 75000}}
    ;; All three disputed (staggered by 10s)
    {:seq 3 :time 2000 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 0}}
    {:seq 4 :time 2010 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 1}}
    {:seq 5 :time 2020 :agent "buyer" :action "raise_dispute"
     :params {:workflow-id 2}}
    ;; Auto-cancel eligibility is per-workflow (raise_dispute_time + max-dispute-duration):
    ;; wf0: 2000 + 7776000 = 7778000
    ;; wf1: 2010 + 7776000 = 7778010
    ;; wf2: 2020 + 7776000 = 7778020
    {:seq 6 :time 7778001 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id 0}}
    {:seq 7 :time 7778011 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id 1}}
    {:seq 8 :time 7778021 :agent "buyer" :action "auto_cancel_disputed"
     :params {:workflow-id 2}}]})

(def all-scenarios
  "All Phase Z adversarial scenarios, in ascending severity order."
  [scenario-z1-resolver-absent
   scenario-z3-false-positive-slash
   scenario-z4-combined-shock
   scenario-z5-cascade-liveness])

;; ---------------------------------------------------------------------------
;; Scoring (pure)
;; ---------------------------------------------------------------------------

(defn run-and-score
  "Run a Phase Z scenario through replay-with-protocol and score the result.
   Returns a map with :scenario, :result, :scored-result."
  [scenario]
  (let [result (replay/replay-with-protocol (default-protocol) scenario)
        scored-result (ts/score-result result)]
    {:scenario scenario
     :result result
     :scored-result scored-result
     :categories (ts/score-category scored-result)}))

(defn run-all
  "Run all Phase Z scenarios and return scored results, sorted by score descending."
  []
  (->> all-scenarios
       (mapv run-and-score)
       (sort-by #(- (get-in % [:scored-result :trace-score] 0)))))

