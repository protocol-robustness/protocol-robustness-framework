(ns resolver-sim.cancellation.statement-boundary
  "Fail-closed STATEMENT boundary for cancellation operations.

   NAMING AND ASSURANCE CORRECTION (review follow-up): the artifact this
   boundary accepts is an EXECUTED-STATEMENT-shaped `cancellation-operation.v1`
   record (:execution :status :applied is mandatory for completeness,
   operation.clj:30). Acceptance here is therefore classification as an
   OPERATION/STATEMENT, never as a \"canonical action\", and never as advance
   authorization of execution.

   Timeline context (traced): in the current repository nothing constructs
   these statements or signs party commands outside tests; admission
   (cancellation.admission/admit) is RETROSPECTIVE — it verifies an already
   applied statement. A prospective model (signed intent → authorized
   admission → verified transition → applied statement → receipt) is proposed
   architecture, not implemented code. See
   docs/cancellation/CANCELLATION_ARTIFACT_MAP.md §Timeline.

   MOTIVATION: Semantic Composition V1's generic :composition-sequence encoder
   validly roots ordered descriptive-facet vectors (purpose
   :canonical-cancellation/action), including reordered/duplicated variants.
   Generic validity must never be consumed as cancellation-action validity.
   This namespace is the second stage between generic composition validation
   and any future cancellation-action admission:

     generic composition validation   (composition/v1.clj)
         ↓
     THIS statement boundary          (shape classification only)
         ↓
     retrospective verification       (cancellation.admission/admit)

   GUARD ONLY — defines no schema, hash domain, registry entry, or version.
   Reachability: no production consumer currently feeds composition-shaped
   maps into a cancellation-action role (verified by inventory); this guard is
   therefore a TESTED DEFENSIVE UTILITY at the layer where such consumption
   would first appear."
  (:require [resolver-sim.cancellation.operation :as operation]))

(defn- composition-shaped?
  "True when a candidate carries generic semantic-composition markers."
  [m]
  (boolean (or (contains? m :composition/family)
               (contains? m :composition/purpose)
               (contains? m :composition/dimensions))))

(def non-claims
  "What structural acceptance does NOT assert. Carried verbatim on every
   accepted verdict so the assurance vocabulary cannot drift.

   :no-state-change-verified — an applied-statement input DOES ASSERT
   (:execution :status :applied) that something was applied; this boundary
   never verifies that assertion."
  #{:no-execution-verified :no-authority-verified :no-admissibility-claimed
    :no-transition-verified :no-state-change-verified})

(def ^:private allowed-statement-keys
  "Exact closed envelope of a cancellation-operation.v1 statement. The
   operation validator (operation.clj) checks field presence and reference
   syntax but does NOT reject unknown fields; THIS boundary adds that closure.
   Nested shapes (:target/:request/:policy/:evaluation/:authorization/
   :execution) are validated semantically by admission stages, not here."
  #{:operation/schema :operation/purpose :event/id :protocol/id
    :target :request :policy :evaluation :preconditions/root
    :authorization :execution :operation/root})

(defn statement-verdict
  "Decide whether `candidate` may be consumed AS a cancellation-operation.v1
   statement. Returns a boundary verdict map; never throws on candidate shape.

   Accepted candidates are EXACTLY complete cancellation-operation.v1
   statements under a CLOSED envelope: schema version must match and unknown
   top-level fields are rejected (:boundary/unknown-statement-fields) — the
   operation validator alone does not provide that closure. Admission-style
   verification — reference resolution, authority, recomputation — belongs
   exclusively to cancellation.admission and is NOT performed here."
  [candidate]
  (cond
    (not (map? candidate))
    {:boundary/admitted? false
     :boundary/reason :boundary/unrecognized-shape
     :boundary/details {:candidate-type (type candidate)}}

    (composition-shaped? candidate)
    {:boundary/admitted? false
     :boundary/reason :boundary/descriptive-composition-not-operation-statement
     :boundary/details
     {:composition/family (:composition/family candidate)
      :composition/purpose (or (:composition/purpose candidate)
                               (some-> candidate :composition/dimensions :purpose))
      :note "a valid ordered composition over descriptive fields is still not a cancellation operation statement"}}

    (not= operation/schema-version (:operation/schema candidate))
    {:boundary/admitted? false
     :boundary/reason :boundary/unrecognized-shape
     :boundary/details {:observed-schema (:operation/schema candidate)
                        :required-schema operation/schema-version}}

    (seq (remove allowed-statement-keys (keys candidate)))
    {:boundary/admitted? false
     :boundary/reason :boundary/unknown-statement-fields
     :boundary/details {:unknown (vec (sort (remove allowed-statement-keys (keys candidate))))
                        :allowed (vec (sort allowed-statement-keys))}}

    (not (operation/operation-complete? candidate))
    {:boundary/admitted? false
     :boundary/reason :boundary/statement-incomplete
     :boundary/details {:missing-fields (operation/missing-operation-fields candidate)
                        :invalid-references (operation/invalid-operation-references candidate)}}

    :else
    {:boundary/admitted? true
     :boundary/classification :operation-statement
     :boundary/via operation/schema-version
     :boundary/statement-root (:operation/root candidate)
     :assurance :structural-shape-only
     :non-claims non-claims}))

(defn statement-structurally-accepted?
  "True iff the verdict accepted the candidate as an operation STATEMENT with
   structural-only assurance. Deliberately NOT named `admitted` — protocol
   admission is cancellation.admission/admit, and :no-admissibility-claimed
   holds on every verdict this namespace produces."
  [verdict] (true? (:boundary/admitted? verdict)))
