(ns resolver-sim.cancellation.action-boundary
  "Fail-closed consumer-side boundary for cancellation actions.

   MOTIVATION (clean-room finding): Semantic Composition V1's generic
   :composition-sequence encoder validly roots ordered descriptive-facet
   vectors such as

     {:purpose :canonical-cancellation/action
      :components [:operation/party-cancel :domain/sew :required-state/pending]}

   including reordered or duplicated variants. That generic validity must never
   be mistaken for cancellation-action validity by production consumers. This
   namespace is the required second stage between generic composition
   validation and cancellation-action admission:

     generic composition validation   (clean-room / V1 contract)
         ↓
     purpose-specific component/schema validation
         ↓
     cancellation action admission    (cancellation-operation.v1)

   THIS IS A GUARD, NOT A SCHEMA. It defines no new artifact format, hash
   domain, registry entry, or version. It admits exactly one shape — a complete
   `cancellation-operation.v1` statement — and rejects everything else with
   typed reasons. Descriptive composition shapes are refused with
   :boundary/descriptive-composition-not-action regardless of their internal
   validity as sequences."
  (:require [resolver-sim.cancellation.operation :as operation]))

(defn- composition-shaped?
  "True when a candidate carries generic semantic-composition markers."
  [m]
  (boolean (or (contains? m :composition/family)
               (contains? m :composition/purpose)
               (contains? m :composition/dimensions))))

(defn action-admit
  "Decide whether `candidate` may be consumed AS a cancellation action.
   Returns a boundary verdict map; never throws on candidate shape.

   Admitted candidates are exactly complete cancellation-operation.v1
   statements (delegating completeness/reference checks to
   resolver-sim.cancellation.operation). Note that operation completeness
   itself requires :authorization :kind :ordinary and :execution :status
   :applied, so any hypothetical dispute-remediation or timeout variant would
   NOT pass this boundary until its own schema exists."
  [candidate]
  (cond
    (not (map? candidate))
    {:boundary/admitted? false
     :boundary/reason :boundary/unrecognized-shape
     :boundary/details {:candidate-type (type candidate)}}

    (composition-shaped? candidate)
    {:boundary/admitted? false
     :boundary/reason :boundary/descriptive-composition-not-action
     :boundary/details
     {:composition/family (:composition/family candidate)
      :composition/purpose (or (:composition/purpose candidate)
                               (some-> candidate :composition/dimensions :purpose))
      :note "a valid ordered composition over descriptive fields is still not a cancellation action"}}

    (not= operation/schema-version (:operation/schema candidate))
    {:boundary/admitted? false
     :boundary/reason :boundary/unrecognized-shape
     :boundary/details {:observed-schema (:operation/schema candidate)
                        :required-schema operation/schema-version}}

    (not (operation/operation-complete? candidate))
    {:boundary/admitted? false
     :boundary/reason :boundary/operation-incomplete
     :boundary/details {:missing-fields (operation/missing-operation-fields candidate)
                        :invalid-references (operation/invalid-operation-references candidate)}}

    :else
    {:boundary/admitted? true
     :boundary/via operation/schema-version
     :boundary/operation-root (:operation/root candidate)}))

(defn action-admitted? [verdict] (true? (:boundary/admitted? verdict)))
