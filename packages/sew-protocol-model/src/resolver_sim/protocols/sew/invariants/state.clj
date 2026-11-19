(ns resolver-sim.protocols.sew.invariants.state
  "State-related invariant predicates for the Sew contract model."
  (:require [clojure.set :as set]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.types :as t]))

(defn terminal-states-unchanged?
  "True when every escrow that was terminal in world-before is still terminal
   in world-after, and has the same state."
  [world-before world-after]
  (let [violations
        (for [[wf et-before] (:escrow-transfers world-before)
              :when (contains? t/terminal-states (:escrow-state et-before))
              :let  [et-after (get-in world-after [:escrow-transfers wf])]
              :when (not= (:escrow-state et-before) (:escrow-state et-after))]
          {:workflow-id wf
           :before      (:escrow-state et-before)
           :after       (:escrow-state et-after)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn terminal-escrow-accounting-unchanged?
  "True when every escrow that was terminal in world-before still has the same
   terminal accounting fields in world-after.

   Finality is not only about state labels.  An escrow that stays :released
   should also retain its :amount-after-fee, :from, :to, and :token unchanged.
   A mutation that modifies terminal accounting without changing the state label
   would violate this invariant."
  [world-before world-after]
  (let [core-fields #{:amount-after-fee :from :to :token :escrow-state}
        violations
        (for [[wf et-before] (:escrow-transfers world-before)
              :when (contains? t/terminal-states (:escrow-state et-before))
              :let  [et-after (get-in world-after [:escrow-transfers wf])
                     changed  (when et-after
                                (vec (for [k core-fields
                                           :when (not= (get et-before k)
                                                       (get et-after k))]
                                       {:field k
                                        :before (get et-before k)
                                        :after  (get et-after k)})))]
              :when (and et-after (seq changed))]
          {:workflow-id wf
           :changes     changed})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn escrow-state-transition-valid?
  "True when every changed :escrow-state follows `allowed-transitions`.

   Rejects circular / backward edges (e.g. :disputed → :pending, :released → :disputed)
   on any successful step."
  [world-before world-after]
  (let [wfs (set/union (set (keys (:escrow-transfers world-before {})))
                       (set (keys (:escrow-transfers world-after {}))))
        violations
        (for [wf wfs
              :let [before (get-in world-before [:escrow-transfers wf :escrow-state])
                    after  (get-in world-after [:escrow-transfers wf :escrow-state])]
              :when (and (some? before) (some? after) (not= before after)
                         (not (sm/valid-transition? before after)))]
          {:workflow-id wf :from before :to after})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
