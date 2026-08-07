(ns resolver-sim.composition.plan
  "The compiled composition plan: a content-addressed artifact produced by the
   composition compiler. Execution consumes ONLY a compiled plan (or proves an
   equivalent plan was compiled); a raw requested combination is never executed
   directly.

   The plan binds the source combination root, exact capability descriptor
   roots, exact composition-contract roots, canonical node order and edges,
   graph input/output contracts, effect merge semantics, verification
   contract, and compiler identity/version."
  (:require [resolver-sim.hash.canonical :as hc]))

(def plan-schema-version 1)

(def plan-domain-tag
  "COMPOSITION_PLAN_V1")

(defn plan-projection
  "Committed fields of a compiled plan (excludes the self-referential
   :plan/root)."
  [plan]
  (select-keys plan
               [:plan/schema-version
                :plan/combination-root
                :plan/compiler-id
                :plan/compiler-version
                :plan/nodes
                :plan/edges
                :plan/adapters
                :plan/addresses
                :plan/input-contract
                :plan/output-contract
                :plan/effect-merge-strategy
                :plan/verification]))

(defn plan-root
  "Content-addressed root of a compiled plan.

   The plan projection may embed normalized contracts (set-valued roles/modes),
   so the committed value is projected to canonical-safe form (sets → sorted
   vectors) before hashing."
  [plan]
  (hc/domain-hash plan-domain-tag
                  (hc/project-committable-content (plan-projection plan))))

(defn build-plan
  "Assemble a compiled plan from its committed fields and attach :plan/root."
  [{:keys [combination-root compiler-id compiler-version
           nodes edges adapters addresses input-contract output-contract
           effect-merge-strategy verification]}]
  (let [base {:plan/schema-version plan-schema-version
              :plan/combination-root combination-root
              :plan/compiler-id compiler-id
              :plan/compiler-version compiler-version
              :plan/nodes (vec nodes)
              :plan/edges (vec edges)
              :plan/adapters (vec adapters)
              :plan/addresses (or addresses {})
              :plan/input-contract input-contract
              :plan/output-contract output-contract
              :plan/effect-merge-strategy effect-merge-strategy
              :plan/verification verification}]
    (assoc base :plan/root (plan-root base))))
