(ns prf.extensions.held-custody.legacy-add-held
  "Compatibility reader for the frozen legacy force-auth-add-held artifacts.

   Reads and CLASSIFIES legacy v1/v2 members under their original contracts and
   projects them into the in-memory held-mutation representation. It never
   rewrites an old artifact under its original hash and never emits new legacy
   artifacts. It is a compatibility module, not a canonical builder.

   Assurance classifications:
     :legacy-direction-unbound    — v1: direction/scope is not independently
                                    committed (no committed scope map), so no
                                    direction/scope assurance can be claimed.
     :legacy-direction-bound      — v2: direction is committed via the body and
                                    the :authorization/scope-projection and the
                                    v2 validator cross-checks them, so the
                                    direction cannot be changed while preserving
                                    a valid artifact. The :held/action string is
                                    committed but NOT action↔direction bound, so
                                    this is direction-bound only.
     :action-and-direction-bound  — the new force-auth-held-custody-mutation
                                    artifact (action and direction both bound).

   BOUNDARY GUARD — This namespace MUST NOT import or depend on:
     - resolver-sim.protocols.sew
     - any form under protocols_src/
     - resolver-sim.evidence.force-authorisation (deleted legacy core domain)

   Historical member verification is extension-owned: the frozen v1/v2
   validators live in prf.extensions.held-custody.legacy-validate, the
   extension's permanent historical-read contract."
  (:require [resolver-sim.assurance.force-authorisation :as fa]
            [prf.extensions.held-custody.mutation :as mutation]
            [prf.extensions.held-custody.legacy-validate :as lv]))

(defn classify-legacy-add-held
  "Classify a legacy (or new) held-custody mutation artifact:
     :legacy-direction-unbound    v1 force-auth-add-held
     :legacy-direction-bound      v2 force-auth-add-held (direction committed)
     :action-and-direction-bound  new force-auth-held-custody-mutation
     :not-force-auth-add-held     anything else / invalid under its own contract"
  [artifact]
  (cond
    (not (map? artifact)) :not-force-auth-add-held
    (= mutation/schema-version (:schema-version artifact)) :action-and-direction-bound
    (= lv/add-held-v2-schema-version (:schema-version artifact))
    (if (lv/valid-force-auth-add-held-v2? artifact)
      :legacy-direction-bound
      :legacy-direction-unbound)
    (= lv/add-held-schema-version (:schema-version artifact))
    (if (lv/valid-force-auth-add-held? artifact)
      :legacy-direction-unbound
      :not-force-auth-add-held)
    :else :not-force-auth-add-held))

(defn validate-legacy-add-held
  "Validate a legacy force-auth-add-held artifact under its ORIGINAL contract
   (v1 or v2). Returns {:valid? :schema-version :classification}."
  [artifact]
  (let [classification (classify-legacy-add-held artifact)]
    {:valid? (contains? #{:legacy-direction-unbound :legacy-direction-bound}
                        classification)
     :schema-version (:schema-version artifact)
     :classification classification}))

(defn- projection-from-fields
  "Best-effort in-memory scope projection derived from the artifact's own
   fields. For v1 this is NOT committed (the committed :authorization/scope-hash
   may disagree with a projection rebuilt from member fields); for v2 the
   committed :authorization/scope-projection is used verbatim."
  [artifact]
  (assoc (fa/normalize-force-authorisation-scope
          {:authorization/id (:authorization/id artifact)
           :authorization/type (:authorization/type artifact)
           :held/direction (:held/direction artifact)
           :token (:held/token artifact)
           :amount (:held/amount artifact)
           :held/account (:held/account artifact)
           :owner/address (:owner/address artifact)
           :held/reason (:held/reason artifact)
           :held/workflow-id (:held/workflow-id artifact)})
          :operation :held-custody-mutation))

(defn project-legacy-add-held
  "Project a legacy force-auth-add-held artifact into the in-memory held-mutation
   representation (canonical keyword action, :mutation/id, :owner/address,
   committed scope projection when available). Returns a separate data map; the
   original artifact and hash are preserved and never rewritten.

   For v1 the precise action and the scope binding are NOT independently
   committed — the projection marks :legacy/action-bound? and
   :legacy/scope-committed? accordingly and derives a conservative action from
   direction only as an in-memory convenience."
  [artifact]
  (let [direction (some-> (:held/direction artifact) name keyword)
        action (:held/action artifact)
        classification (classify-legacy-add-held artifact)]
    {:mutation/id (:held/adjustment-id artifact)
     :held/action (if (some? action)
                    (keyword action)
                    (if (= :out direction) :sub-held :add-held))
     :legacy/action-bound? (some? action)
     :held/direction direction
     :held/amount (:held/amount artifact)
     :held/token (:held/token artifact)
     :held/account (:held/account artifact)
     :owner/address (:owner/address artifact)
     :authorization/id (:authorization/id artifact)
     :authorization/type (:authorization/type artifact)
     :authorization-scope/projection
     (or (:authorization/scope-projection artifact)
         (projection-from-fields artifact))
     :authorization-scope/projection-hash
     (or (:authorization/scope-hash artifact)
         (:authorization-scope/projection-hash artifact))
     :legacy/classification classification
     :legacy/scope-committed? (= :legacy-direction-bound classification)}))

(defn legacy-total-is-gross-flow-warning
  "Structured interpretation of a legacy summary :total-amount as gross flow.
   Emit this ONLY when interpreting/presenting the legacy field; the new
   aggregate never warns merely because both directions are present."
  [gross-inflow gross-outflow]
  {:reason :legacy-total-is-gross-flow
   :field :total-amount
   :gross-inflow gross-inflow
   :gross-outflow gross-outflow
   :gross-flow (+ gross-inflow gross-outflow)
   :net-change (- gross-inflow gross-outflow)})
