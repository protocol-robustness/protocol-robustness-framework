(ns resolver-sim.protocols.sew.invariants.bond
  "Bond-related invariant predicates for the Sew contract model.")

(defn bond-slash-bounded?
  "True when :bond-slashed[wf] <= sum of original bonds posted for that workflow.
   Uses :bond-balances + :bond-slashed as the accounting split."
  [world]
  (let [violations
        (for [[wf slashed] (:bond-slashed world)
              :let  [remaining (reduce + 0 (vals (get (:bond-balances world) wf {})))
                     ;; Original posted = remaining + slashed
                     original  (+ remaining slashed)]
              :when (> slashed original)]
          {:workflow-id wf :slashed slashed :original original})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
