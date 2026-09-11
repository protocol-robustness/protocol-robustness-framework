(ns resolver-sim.protocols.sew.invariants.accounting
  (:require [resolver-sim.protocols.sew.types :as t]))

(defn- sum-tokens [tokens-map]
  (apply + (conj (vals tokens-map) 0)))

(defn accounting-consistent?
  "Checks that Total_Deposited == Total_Released + Total_Withdrawn + Total_Pending_Refunds."
  [world]
  (let [transfers (:escrow-transfers world {})
        released  (:total-released world {})
        refunded  (:total-refunded world {})
        pending   (filter #(:exists (val %)) (:pending-settlements world))

        deposited (reduce (fn [acc [_ et]]
                            (if (and (:token et) (:amount et))
                              (update acc (:token et) (fnil + 0) (:amount et))
                              acc))
                          {} transfers)
        released-sum (apply + (vals released))
        refunded-sum (apply + (vals refunded))
        pending-sum  (reduce (fn [acc [wf _]]
                               (let [et (get transfers wf)]
                                 (if (and (:token et) (:amount-after-fee et))
                                   (update acc (:token et) (fnil + 0) (:amount-after-fee et))
                                   acc)))
                             {} pending)]
    (= deposited (merge-with + (zipmap (keys released) (repeat released-sum)) (zipmap (keys refunded) (repeat refunded-sum)) pending-sum))))
