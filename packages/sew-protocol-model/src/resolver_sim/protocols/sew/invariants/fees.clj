(ns resolver-sim.protocols.sew.invariants.fees
  "Fee-related invariant predicates for the Sew contract model.")

(defn fees-non-negative?
  "True when all total-fees values are >= 0 (they should never go negative)."
  [world]
  (let [violations (for [[token amount] (:total-fees world)
                         :when (neg? amount)]
                     {:token token :amount amount})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn fee-increased-or-equal?
  "True when every total-fees entry in world' is >= the corresponding entry
   in world-before. Used to assert monotonicity across a single operation."
  [world-before world-after]
  (let [violations (for [[token before-amt] (:total-fees world-before)
                         :let [after-amt (get (:total-fees world-after) token 0)]
                         :when (< after-amt before-amt)]
                     {:token token :before before-amt :after after-amt})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
