(ns resolver-sim.accounting.held-adjustment
  "Protocol-independent value projections for held custody adjustments.
   World mutation, artifact hashing, and authorization consumption remain
   protocol adapter responsibilities."
  (:require [resolver-sim.assurance.parameter-attribution :as pa]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const scope-keys
  [:authorization/id :authorization/type
   :held/direction :token :amount :held/account :held/position-id :owner/address
   :held/reason :held/workflow-id])

(def ^:const record-keys
  [:held-adjustment/id :held/direction :token :amount :held/before :held/after
   :held/account :held/position-id :owner/address :held/reason :held/action
   :held/workflow-id :parameter/context :parameter/address :authorization/provenance])

(defn reserved-adjustment-keys-present
  "Reserved provenance keys are top-level opts only; they must never arrive
   through a metadata carrier such as :extra."
  [extra]
  (pa/reserved-parameter-attribution-keys extra))

(defn parameter-attribution-error
  [adjustment]
  (pa/parameter-attribution-error adjustment))

(defn valid-held-adjustment?
  [adjustment]
  (and (map? adjustment)
       (nil? (parameter-attribution-error adjustment))))

(defn held-adjustment-error
  [adjustment]
  (or (parameter-attribution-error adjustment)
      (when-not (map? adjustment) :invalid-held-adjustment)))

(defn project-held-adjustment-scope
  "Project the exact authorisable custody scope. Optional parameter attribution
   is included only when both fields are valid, preserving legacy preimages
   when it is absent. Position-id is included because it is a custody-location
   boundary of the authorized operation."
  [adjustment]
  (when-let [reason (held-adjustment-error adjustment)]
    (throw (ex-info "cannot project invalid held adjustment scope"
                    {:type :invalid-held-adjustment
                     :reason reason
                     :adjustment adjustment})))
  (let [base (select-keys adjustment scope-keys)
        attribution (pa/project-parameter-attribution adjustment)]
    (merge base attribution)))

(defn build-held-adjustment
  "Build the canonical held-adjustment record projection from already-derived
   fields. It never assigns IDs, mutates state, or hashes artifacts."
  [fields]
  (when-let [reason (held-adjustment-error fields)]
    (throw (ex-info "cannot build invalid held adjustment"
                    {:type :invalid-held-adjustment
                     :reason reason
                     :adjustment fields})))
  (merge fields (pa/project-parameter-attribution fields)))

;; ---------------------------------------------------------------------------
;; Settlement-scoped custody attribution (P2 / L4b)
;;
;; A withdrawal settlement moves custody through one or more held adjustments.
;; Every adjustment attributable to a settlement carries the settlement's
;; canonical identity (:held-adjustment/settlement-root); the settlement commits
;; :settlement/held-adjustment-set-root over its attributed adjustment set.  A
;; verifier then proves the attribution is a bijection: the claimed set exists,
;; binds exactly this settlement, and is complete (no attributable adjustment
;; lies outside it), with the net custody delta equal to the settled amount.
;; ---------------------------------------------------------------------------

(defn settlement-identity
  "Canonical settlement identity for custody-adjustment attribution.  Binds
   workflow, token, direction, settled amount, and recipient, so every
   held-adjustment attributable to one settlement shares one root and cannot be
   confused with another settlement's adjustments.  Emitted as
   :held-adjustment/settlement-root on attributable adjustments and as
   :settlement/root on the settlement artifact."
  [{:keys [workflow-id token direction filled recipient]}]
  (hc/hash-with-intent {:hash/intent :projection-artifact}
                       {:kind :sew/settlement
                        :workflow-id workflow-id
                        :token token
                        :direction direction
                        :filled (long filled)
                        :recipient recipient}))

(defn settlement-held-adjustment-set-root
  "Content-addressed root over the set of held-adjustments attributed to one
   settlement, ordered by :held-adjustment/id.  The verifier recomputes this
   from the observed attributable adjustments and compares it to the committed
   :settlement/held-adjustment-set-root, which proves the claimed set is exactly
   the observed set (existence + completeness, i.e. a bijection)."
  [adjustments]
  (hc/hash-with-intent {:hash/intent :projection-artifact}
                       {:kind :sew/settlement-attribution
                        :adjustments
                        (mapv (fn [a]
                                {:id (:held-adjustment/id a)
                                 :amount (long (:amount a))
                                 :direction (:held/direction a)})
                              (sort-by :held-adjustment/id adjustments))}))
