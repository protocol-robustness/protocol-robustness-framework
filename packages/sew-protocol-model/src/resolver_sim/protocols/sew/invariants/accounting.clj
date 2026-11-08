(ns resolver-sim.protocols.sew.invariants.accounting
  "Simulation-only reconciliation of terminal Sew escrow accounting.")

(defn accounting-consistent?
  "Check per-token deposits against released, refunded, and pending settlement amounts."
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
        pending-sum  (reduce (fn [acc [wf _]]
                               (let [et (get transfers wf)]
                                 (if (and (:token et) (:amount-after-fee et))
                                   (update acc (:token et) (fnil + 0) (:amount-after-fee et))
                                   acc)))
                             {} pending)]
    (= deposited (merge-with + released refunded pending-sum))))
