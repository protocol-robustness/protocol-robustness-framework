(ns resolver-sim.accounting.held-adjustment
  "Protocol-independent value projections for held custody adjustments.
   World mutation, artifact hashing, and authorization consumption remain
   protocol adapter responsibilities."
  (:require [resolver-sim.assurance.parameter-attribution :as pa]))

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
