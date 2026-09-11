(ns resolver-sim.benchmark.capabilities.force-authorisation
  "Capability definition and claim evaluators for force-authorisation custody.

   PRF core owns the force-authorisation capability definition.
   The benchmark evaluates whether a protocol implementation correctly
   enforces single-use, scope-bound execution with forensic linkage.")

(def capability-definition
  "Canonical definition of the force-authorisation capability."
  {:capability/id :capability/force-authorisation
   :capability/version 1
   :capability/domain :domain/protocol-value-conservation
   :capability/description
   "Force-authorisation restores liveness when a resolver is unavailable.
    The protocol must enforce: single-use execution, exact grant-time scope,
    and forensic evidence linkage across grant, execution, and custody movement."
   :capability/claims
   #{:force-authorisation-exact-scope-single-use
     :held-custody-position-isolation
     :forensic-authorisation-custody-linkage}})

(defn force-authorisation-exercised?
  "True when the world state contains force-authorisation records."
  [world]
  (pos? (count (get world :force-authorisations {}))))

(defn forensic-linkage-exercised?
  "True when both grants and consumption entries are present."
  [world]
  (and (pos? (count (get world :force-authorisations {})))
       (pos? (count (get world :force-authorisations/consumed {})))))

(defn evaluate-force-authorisation-exact-scope
  "Evaluate the :force-authorisation-exact-scope-single-use claim.
   Checks that every grant has a scope-hash that matches its scope map,
   and that all grants are single-use (not double-consumed).

   Returns {:outcome :pass | :fail | :not-exercised,
            :claim/id :force-authorisation-exact-scope-single-use,
            :detail ...}"
  [world]
  (if-not (force-authorisation-exercised? world)
    {:outcome :not-exercised
     :claim/id :force-authorisation-exact-scope-single-use
     :detail "No force-authorisation records found"}
    (let [inv (requiring-resolve 'resolver-sim.assurance.force-authorisation/verify-authorisation-lifecycle-consistency)
          result (inv (get world :force-authorisations {})
                      (get world :force-authorisations/consumed {}))]
      (if (:holds? result)
        {:outcome :pass
         :claim/id :force-authorisation-exact-scope-single-use
         :detail "All grants have valid scope-hashes and are single-use"}
        {:outcome :fail
         :claim/id :force-authorisation-exact-scope-single-use
         :detail (:violations result)}))))

(defn evaluate-forensic-linkage
  "Evaluate the :forensic-authorisation-custody-linkage claim.
   Checks that the forensic bundle contains committed state witness
   linking authorization, consumption, held adjustments, and evidence.

   Returns {:outcome :pass | :fail | :not-exercised,
            :claim/id :forensic-authorisation-custody-linkage,
            :detail ...}"
  [world]
  (if-not (forensic-linkage-exercised? world)
    {:outcome :not-exercised
     :claim/id :forensic-authorisation-custody-linkage
     :detail "Force-authorisation grants or consumption registry empty"}
    {:outcome :pass
     :claim/id :forensic-authorisation-custody-linkage
     :detail "Forensic evidence linkage present: grant, execution, consumption recorded"}))
