(ns resolver-sim.protocols.sew.invariants.settlement
  "Settlement-related invariant predicates for the Sew contract model."
  (:require [resolver-sim.protocols.sew.types :as t]))

(defn pending-settlement-consistency?
  "True when every workflow-id with an existing pending-settlement has
   escrow-state == :disputed."
  [world]
  (let [violations
        (for [[wf pending] (:pending-settlements world)
              :when (:exists pending)
              :let  [state (t/escrow-state world wf)]
              :when (not= :disputed state)]
          {:workflow-id wf :state state})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
