(ns resolver-sim.protocols.sew.financial.finality
  "Sew-specific classification of chain finality and financial finality.

   Chain finality = state permanence (blockchain consensus).
   Financial finality = obligation permanence (economic outcome stability).

   They are explicitly NOT the same. A transaction can be chain-final
   while the financial outcome is still challengeable, recoverable, or
   awaiting solvency proof.

   The classifier reads existing world/protocol-params/result data —
   it never modifies state. All functions are pure and side-effect-free.

   See also:
     resolver-sim.financial.taxonomies — general taxonomy definitions
     (chain phases, financial phases, ordinals) used by this namespace.

   This is a Sew reference implementation. Protocols with different
   world state shapes should implement their own classifiers using
   the same taxonomy vocabulary."
  (:require [clojure.string :as str]
            [resolver-sim.financial.taxonomies :as tax]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.time.context :as time-ctx]))

;; ── Chain finality ───────────────────────────────────────────────────────────
;; Taxonomy: resolver-sim.financial.taxonomies/chain-phases

(defn classify-chain-finality
  "Classify chain finality from world state.

   In the replay engine, we do not model chain reorganisations.
   All states are assumed chain-final by the time they appear in the
   world. This is explicit rather than implicit so that projections
   never confuse chain finality with financial finality.

   Returns:
     {:chain/phase     :final
      :chain/source    :assumed-by-replay
      :chain/block     nil
      :chain-final?    true}"
  [world]
  {:chain/phase  :final
   :chain/source :assumed-by-replay
   :chain/block  (time-ctx/block-ts world)
   :chain-final? true})

;; ── Financial finality ───────────────────────────────────────────────────────
;; Taxonomy: resolver-sim.financial.taxonomies/financial-phases

(defn index-pending-slashes
  [world]
  (group-by :workflow-id (vals (get world :pending-fraud-slashes {}))))

(defn- has-appeal-pending?
  [indexed-slashes wf]
  (some #(= :pending (:status %)) (get indexed-slashes wf)))

(defn- open-gates
  "Determine which financial-finality gates are still open for a workflow in the given world."
  [world workflow-id indexed-slashes]
  (let [state     (t/escrow-state world workflow-id)
        pending   (t/get-pending world workflow-id)
        owner-id  (t/escrow-yield-owner-id workflow-id)
        yield-pos (get-in world [:yield/positions owner-id])
        has-pos?  (some? yield-pos)
        has-unwinding? (= :unwinding (:status yield-pos))]
    (cond-> []

      ;; Gate: non-terminal state means outcome not yet determined
      (not (contains? t/terminal-states state))
      (conj :escrow-state)

       ;; Gate: unresolved pending settlement
      (and (= state :disputed) (:exists pending))
      (conj :pending-settlement)

       ;; Gate: appeal/challenge window still open
      (and (= state :disputed) (:exists pending)
           (< (time-ctx/block-ts world) (:appeal-deadline pending)))
      (conj :appeal-window)

       ;; Gate: yield position still unwinding (shortfall recovery)
      has-unwinding?
      (conj :yield-recovery)

       ;; Gate: slashing appeal still pending
      (has-appeal-pending? indexed-slashes workflow-id)
      (conj :slash-appeal))))

(defn classify-financial-finality
  "Classify financial finality for a specific workflow.

   Returns:
     {:financial/phase              keyword
      :financially-final?           boolean
      :can-change?                  boolean
      :open-gates                   [:appeal-window :yield-recovery ...]
      :reason                       string}"
  [world workflow-id]
  (let [state (t/escrow-state world workflow-id)
        gates (open-gates world workflow-id (index-pending-slashes world))]
    (cond
      ;; No escrow at this workflow-id — trivially provisional
      (nil? state)
      {:financial/phase       :provisional
       :financially-final?    false
       :can-change?           false
       :open-gates            []
       :reason                "no escrow at this workflow-id"}

      ;; Fully terminal with no open gates
      (and (contains? t/terminal-states state)
           (empty? gates))
      {:financial/phase       :financially-final
       :financially-final?    true
       :can-change?           false
       :open-gates            []
       :reason                "all gates closed; escrow terminal and no pending recoveries"}

      ;; Terminal state but still recoverable (yield/slashing not yet final)
      (and (contains? t/terminal-states state)
           (seq gates))
      {:financial/phase       (if (some #{:yield-recovery :slash-appeal} gates)
                                :recoverable
                                :finalizing)
       :financially-final?    false
       :can-change?           true
       :open-gates            gates
       :reason                (str "terminal escrow but gates still open: "
                                   (str/join ", " (map name gates)))}

      ;; Disputed with challenge/appeal windows open
      (= state :disputed)
      {:financial/phase       :challengeable
       :financially-final?    false
       :can-change?           true
       :open-gates            gates
       :reason                (if (seq gates)
                                (str "gates open: " (str/join ", " (map name gates)))
                                "disputed with no pending settlement")}

      ;; No resolution yet (pending escrow, or not yet disputed)
      :else
      {:financial/phase       :provisional
       :financially-final?    false
       :can-change?           true
       :open-gates            (vec gates)
       :reason                (str "escrow in " (name state) " state")})))

(defn combine-finality
  "Produce a single, plain classifier map for a workflow.

   This is the primary entry point for consumers. It returns both
   chain and financial finality in one map, with no ambiguous
   :final? or :settled? keys.

   Returns:
     {:chain {:phase :final :source :assumed-by-replay :chain-final? true}
      :financial {:phase :challengeable :financially-final? false
                  :can-change? true :open-gates [...] :reason \"...\"}}"
  [world workflow-id]
  {:chain     (classify-chain-finality world)
   :financial (classify-financial-finality world workflow-id)})
