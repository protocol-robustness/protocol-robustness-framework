;; # World Structure Workbench
;;
;; How does protocol state change—and how can we check that its different
;; representations still agree?
;;
;; This notebook follows one real escrow from creation to automatic refund. It
;; shows the protocol state before and after the transition, then progressively
;; reveals how that state is represented, derived, and checked.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.world-structure-workbench
  (:require [clojure.data.json :as json]
            [nextjournal.clerk :as clerk]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.projection :as sew-projection]))

;; ## Automatic cancellation: one escrow, one timed transition

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def sample-scenario
  (json/read-str (slurp "data/fixtures/traces/s-auto-cancel-time-via-keeper.trace.json")
                 :key-fn keyword))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def sample-replay (sew/replay-with-sew-protocol sample-scenario))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def replay-trace (vec (or (:trace sample-replay) [])))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def terminal-entry (last replay-trace))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def terminal-world (or (:world terminal-entry)
                        (sew-projection/terminal-world-from-result sample-replay)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def previous-entry (when (> (count replay-trace) 1)
                      (nth replay-trace (- (count replay-trace) 2))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def previous-world (:world previous-entry))

;; The facade is resolved once. A notebook-classpath problem is a workbench
;; health issue, not a protocol-state result, and is shown once below.
^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def facade
  (try
    {:status :available
     :catalogue (requiring-resolve 'resolver-sim.state/catalogue)
     :inspect-state (requiring-resolve 'resolver-sim.state/inspect-state)
     :diff-state (requiring-resolve 'resolver-sim.state/diff-state)
     :explain-transition (requiring-resolve 'resolver-sim.state/explain-transition)
     :representation-lineage (requiring-resolve 'resolver-sim.state/representation-lineage)}
    (catch Exception e
      {:status :unavailable :error (.getMessage e)})))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn facade-call [name & args]
  (if (= :available (:status facade))
    (try (apply (get facade name) args)
         (catch Exception e {:workbench/status :integration-failed
                             :workbench/function name
                             :workbench/message (.getMessage e)}))
    {:workbench/status :not-measured
     :workbench/reason :state-facade-unavailable}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn money [n] (str (or n 0) " USDC"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn escrow [world]
  (first (vals (:escrow-transfers world))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def before-escrow (escrow previous-world))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def after-escrow (escrow terminal-world))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def principal (or (:amount-after-fee before-escrow) 1970))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def before-time (or (get-in previous-entry [:time-after :block-ts])
                     (:block-time previous-world)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def after-time (or (get-in terminal-entry [:time-after :block-ts])
                    (:block-time terminal-world)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def custody-assurance
  (when terminal-world
    (:state/assurance (facade-call :inspect-state terminal-world {:state/model-id :sew/runtime
                                                                    :state/instance-kind :terminal
                                                                    :sequence (:seq terminal-entry)}))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "
Buyer deposits **1,970 USDC** → time reaches the cancellation boundary → a
keeper triggers automatic cancellation → the buyer becomes claimable for the
refunded amount.

> **Why it matters:** the keeper triggers the transition; the keeper does not
> become the owner or beneficiary of the refund.
")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Scenario" "Asset" "Principal" "Fee" "Trigger" "Time" "Outcome" "Replay"]
  :rows [["Automatic cancellation" "USDC" (money principal) "30 USDC" "Keeper"
          (str before-time " → " after-time) (name (:escrow-state after-escrow))
          (name (:outcome sample-replay))]]})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Buyer" "Escrow" "Buyer"]
  :rows [["deposit" (str (money principal) " held until t=" after-time) "refund claim"]]})

;; ## Workbench health

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(if (= :available (:status facade))
  (clerk/md "**Workbench health:** state facade available. Classification, authority, representation lineage, and registered assurance are measured by the workbench.")
  (clerk/md (str "### ⚠ State facade unavailable\n\n"
                 "Replay data remains visible, but facade-owned classification, authority, and representation-lineage views are **not measured**.\n\n"
                 "Technical reason: `" (:error facade) "`")))

;; ## 1. What just happened?

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "
**Why it matters:** execution success and structural assurance are different
results. A replay may complete while an independently recomputed representation
still exposes a mismatch.
")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Before" "Event" "After"]
  :rows [[(str "Escrow: " (name (:escrow-state before-escrow)) "\nHeld: "
              (money (get-in previous-world [:total-held :USDC])) "\nBuyer claim: 0")
          (str "keeper reaches auto-cancel boundary\nt=" after-time "\n"
               (:action terminal-entry))
          (str "Escrow: " (name (:escrow-state after-escrow)) "\nHeld: "
              (money (get-in terminal-world [:total-held :USDC])) "\nBuyer claim: "
              (money principal))]]})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Replay execution" "Protocol invariants" "Custody reconstruction"]
  :rows [[(if (= :pass (:outcome sample-replay)) "PASS" (name (:outcome sample-replay)))
          (if (:invariants-ok? terminal-entry) "PASS" "not measured")
          (or (some-> custody-assurance first :status name) "not measured")]]})

;; ## 2. What does the world look like now?

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "
**Why it matters:** a terminal world is a protocol value, not just an event
record. This summary starts with the human consequences before exposing its map
structure.
")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Layer" "Observed terminal state"]
  :rows [["Escrow" (str "REFUNDED · sender " (:from after-escrow) " · recipient " (:to after-escrow))]
         ["Custody" (str "currently held " (money (get-in terminal-world [:total-held :USDC]))
                          " · " (count (:held-adjustments terminal-world)) " adjustments")]
         ["Claims" (str "buyer claimable " (money principal))]
         ["Fees" (str "protocol fee " (money (get-in terminal-world [:total-fees :USDC]))) ]
         ["Time" (str "block time " after-time " · replay sequence " (:seq terminal-entry))]]})

;; ## 3. Where did the money go?

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "
```text
                         CANONICAL HISTORY
                         :held-adjustments
                                │
                   ┌────────────┴─────────────┐
                   ▼                          ▼
           LIVE MATERIALIZATION       INDEPENDENT REPLAY
           :total-held                custody reconstruction
           :held-ledger/index
           :held/positions
                   │                          │
                   └────────────┬─────────────┘
                                ▼
                          RECONCILIATION
```

The custody history is authoritative; balances and indexes are recomputable
views. PRF independently replays the history to detect drift.

**Why it matters:** a balance can look plausible while disagreeing with the
canonical adjustment history.
")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Check" "Status" "Meaning"]
  :rows (if (seq custody-assurance)
          (mapv (fn [check]
                  [(name (:assurance/id check)) (name (:status check))
                   (cond
                     (= :failed (:status check)) "Live materialization differs from independent replay."
                     (= :ok (:status check)) "Registered representations agree."
                     :else (name (:reason check)))])
                custody-assurance)
          [["custody reconstruction" "not measured" "No usable terminal world or state facade."]])})

;; ## 4. What exactly changed?

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "
At the cancellation boundary the escrow moved from pending to refunded, custody
left the escrow, and the buyer received a claimable refund. The full diff is
available below as proof material rather than the default explanation.

**Why it matters:** registered regions receive explicit authority labels;
unregistered paths remain visible but are not assigned invented semantics.
")

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def transition-explanation
  (when (and previous-world terminal-world)
    (facade-call :explain-transition {:before previous-world :after terminal-world
                                      :event terminal-entry
                                      :context {:state/model-id :sew/runtime
                                                :sequence (:seq terminal-entry)}})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Changed area" "Observed effect"]
  :rows [["Escrow lifecycle" (str (name (:escrow-state before-escrow)) " → " (name (:escrow-state after-escrow)))]
         ["Custody" (str (money (get-in previous-world [:total-held :USDC])) " → "
                          (money (get-in terminal-world [:total-held :USDC])))]
         ["Claims" (str "buyer claimable 0 → " (money principal))]
         ["Time" (str "t=" before-time " +" (- after-time before-time) "s → t=" after-time)]
         ["Custody history" "+1 canonical adjustment"]]})

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
(clerk/code {:event (select-keys terminal-entry [:seq :time :agent :action :params :result :invariants-ok?])
             :full-semantic-diff (:diff transition-explanation)
             :full-transition-explanation transition-explanation})

;; ## 5. How did time cause this transition?

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md (str "```text\nSIMULATED TIME\n\nt=" before-time "  ●──────── +" (- after-time before-time)
               "s ────────●  t=" after-time "\n"
               "Escrow pending                         Auto-cancel boundary\n"
               (money principal) " held                       Keeper triggers refund\n```\n\n"
               "Technical clock details are retained in the full transition record. "
               "Synthetic epoch instants are intentionally not foregrounded here."))

;; ## 6. Where did this world come from?

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Lineage position" "Sequence" "Time" "Meaning"]
  :rows [["Initial" "—" before-time "Escrow is live before the selected transition."]
         ["Selected trace transition" (:seq terminal-entry) after-time "Keeper triggers auto-cancel."]
         ["Terminal" (:seq terminal-entry) after-time "Refunded world retained by replay."]
         ["Counterfactual fork" "not retained by this fixture" "not measured"
          "Forks are branch-local checkpoints, never mainline terminal state."]]})

;; ## 7. Is every state-shaped value the same kind of thing?

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/md "
No. A runtime world has transition semantics. A checkpoint is a historical
instance of that world. A reconstruction, snapshot, export, or EVM comparison
view is computed from a source value and has a narrower contract.

**Why it matters:** useful projections must not silently replace the protocol
world that owns state semantics.
")

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/code (if terminal-world
              (facade-call :representation-lineage terminal-world {:state/model-id :sew/runtime})
              {:workbench/status :not-measured :workbench/reason :no-terminal-world}))

;; ## 8. What does this replay establish—and what doesn't it?

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Established here" "Not established merely by this notebook"]
  :rows [["This scenario replay completed.\nThe timed transition occurred.\nThe resulting state can be inspected.\nCustody representations can be compared."
          "External-chain execution.\nReal-world asset custody.\nSafety outside the modeled scenario.\nPortfolio risk or VaR."]]})

;; ## Reference — what can the state facade inspect?

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
(clerk/code (facade-call :catalogue))

;; ## Explore raw proof material

^{:nextjournal.clerk/visibility {:code :fold :result :hide}}
(clerk/code {:terminal-world terminal-world
             :trace replay-trace
             :replay-result (select-keys sample-replay [:outcome :events-processed :halt-reason])})
