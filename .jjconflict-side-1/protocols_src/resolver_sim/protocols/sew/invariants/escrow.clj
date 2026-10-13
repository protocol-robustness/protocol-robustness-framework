(ns resolver-sim.protocols.sew.invariants.escrow
  "Escrow-related invariant predicates for the Sew contract model."
  (:require [resolver-sim.protocols.sew.state-machine :as sm]))

(defn escrow-state-in-graph?
  "True when every stored :escrow-state is a node in `sm/allowed-transitions`.

   Defense-in-depth for worlds constructed outside the transition graph."
  [world]
  (let [valid (set (keys sm/allowed-transitions))
        violations
        (for [[wf et] (:escrow-transfers world {})
              :let  [state (:escrow-state et)]
              :when (not (contains? valid state))]
          {:workflow-id wf :escrow-state state :allowed valid})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn cancellation-mutex?
  "True when any escrow in :resolved, :released, or :refunded status
   rejects any cancellation attempt."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (and (contains? #{:resolved :released :refunded} (:escrow-state et))
                         (or (not= :none (:sender-status et))
                             (not= :none (:recipient-status et))))]
          {:workflow-id wf :state (:escrow-state et)})]
    {:holds? (empty? violations) :violations (vec violations)}))
