(ns resolver-sim.protocols.sew.claimable-outcome
  "Observed escrow_yield delivery outcomes for claimable-classification.v2."
  (:require [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.yield.accounting :as yield-acct]))

(def manifest-shortfall-outcomes
  #{:may-be-partially-deferred :fully-deferred :fully-immediate :none})

(defn- yield-position
  "Resolve yield position from full or snapshot world."
  [world owner-id]
  (or (get-in world [:yield/positions owner-id])
      (get-in world [:yield-positions owner-id])))

(defn escrow-yield-shortfall-outcome
  "Classify escrow_yield pull-settlement outcome for workflow-id.

   Returns a keyword aligned with manifest `shortfall_outcome` strings when applicable."
  [world workflow-id]
  (let [wf        workflow-id
        owner     (t/escrow-yield-owner-id wf)
        pos       (yield-position world owner)
        sf        (:shortfall pos)
        deferred  (long (or (:deferred-amount sf) 0))
        yield-claims (get-in world [:claimable-v2 wf :settlement/yield] {})
        immediate-yield (reduce + 0 (vals (or yield-claims {})))]
    (cond
      (and (pos? deferred) (pos? immediate-yield))
      :may-be-partially-deferred

      (pos? deferred)
      :fully-deferred

      (pos? immediate-yield)
      :fully-immediate

      :else
      :none)))

(defn escrow-yield-outcome-detail
  "Forensic breakdown for projections and test manifests."
  [world workflow-id]
  (let [wf     workflow-id
        owner  (t/escrow-yield-owner-id wf)
        pos    (yield-position world owner)
        sf     (:shortfall pos)
        outcome (escrow-yield-shortfall-outcome world wf)]
    {:workflow-id wf
     :outcome outcome
     :manifest-label (when (manifest-shortfall-outcomes outcome)
                       (name outcome))
     :partial-yield-shortfall? (yield-acct/partial-yield-shortfall? pos sf)
     :immediate-yield-claimable (reduce + 0 (vals (get-in world [:claimable-v2 wf :settlement/yield] {})))
     :principal-claimable (reduce + 0 (vals (get-in world [:claimable-v2 wf :settlement/principal] {})))
     :deferred-amount (long (or (:deferred-amount sf) 0))
     :fulfilled-yield-amount (long (or (:fulfilled-amount sf) 0))}))

(defn outcomes-by-workflow
  [world]
  (let [positions (or (:yield/positions world) (:yield-positions world) {})]
    (into {}
          (for [[owner-id _] positions
                :let [wf (when (vector? owner-id) (second owner-id))]
                :when wf]
            [wf (escrow-yield-outcome-detail world wf)]))))
