(ns resolver-sim.cancellation.ordinary-planner
  "Pure resolver for the protocol-party-cancellation mode. It walks named role
   artifacts and recomputes preconditions, evaluation, and derived effects."
  (:require [resolver-sim.cancellation.party-preconditions :as party]
            [resolver-sim.cancellation.semantic :as semantic]))

(defn plan [{:keys [operation snapshot policy principal]}]
  (let [party-key (get-in operation [:request :party])
        preconditions (party/preconditions snapshot party-key principal)
        preconditions (assoc preconditions :preconditions/root (party/preconditions-root preconditions))
        errors (:preconditions/errors preconditions)
        allowed? (and (empty? errors) (:policy/can-cancel? policy))
        final? (or (:policy/unilateral-cancel? policy) (party/other-party-agreed? snapshot party-key))
        effects (when allowed? {:effects/schema semantic/derived-effects-schema
                                :effects/kind (if final? :refund-sender :record-party-agreement)
                                :effects/by party-key})
        effects (when effects (assoc effects :effects/root (semantic/derived-effects-root effects)))
        evaluation {:evaluation/schema semantic/evaluation-schema
                    :operation/root (:operation/root operation) :snapshot/root (:snapshot/root snapshot)
                    :policy/root (:policy/root policy)
                    :decision/classification (if effects :authorized :forbidden)
                    :decision/reasons (vec errors) :derived-effects/root (:effects/root effects)}]
    {:preconditions preconditions
     :evaluation (assoc evaluation :evaluation/root (semantic/evaluation-root evaluation))
     :derived-effects effects}))
