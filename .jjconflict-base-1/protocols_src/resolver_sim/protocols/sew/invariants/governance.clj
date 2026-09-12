(ns resolver-sim.protocols.sew.invariants.governance
  "Governance and module-snapshot invariants for the Sew contract model.")

(defn module-snapshot-immutable?
  "True when :module-snapshots for existing escrows are unchanged across a step.

   New escrows may gain a snapshot; in-flight escrows must keep the snapshot taken
   at create_escrow (active-escrow-module-snapshot-immutable)."
  [world-before world-after]
  (let [existing (set (keys (:escrow-transfers world-before {})))
        violations
        (for [wf existing
              :let [before (get-in world-before [:module-snapshots wf])
                    after  (get-in world-after [:module-snapshots wf])]
              :when (not= before after)]
          {:workflow-id wf :before before :after after})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))
