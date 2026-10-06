(ns resolver-sim.research.accounting-implementation-comparison
  "Execution-bound comparison of two independently identified native
   accounting implementations. Semantic results remain in the accounting
   namespace; this artifact is assurance/provenance, not an accounting result."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(def schema "research-accounting-implementation-comparison.v1")

(defn- root [body]
  (reference/sha256-ref
   (canonical/domain-hash :research-accounting-implementation-comparison-v1 body)))

(defn compare-implementations
  "Derive an execution-bound comparison of two validated implementations of
   the same accounting partition. Inputs are executions, never caller-supplied
   expected values or roots. Incompatible semantic/dependency domains remain
   :not-comparable and carry no arithmetic delta. This is implementation
   agreement evidence, not proof that either implementation or accounting
   source is economically correct."
  [left-work left-result right-work right-result]
  (native/validate-execution left-work left-result)
  (native/validate-execution right-work right-result)
  (let [left-request (:accounting/request left-work)
        right-request (:accounting/request right-work)
        left-unit (:accounting/work-unit left-work)
        right-unit (:accounting/work-unit right-work)
        left-impl (:work/implementation left-work)
        right-impl (:work/implementation right-work)
        left-partial (:accounting-partial left-result)
        right-partial (:accounting-partial right-result)
        reasons (cond-> []
                  (not= (:accounting-request/root left-request)
                        (:accounting-request/root right-request))
                  (conj :different-accounting-request)
                  (not= (:accounting-work-unit/root left-unit)
                        (:accounting-work-unit/root right-unit))
                  (conj :different-accounting-partition)
                  (not= (:resolved-research-dependency-set/root
                         (:work/resolved-dependencies left-work))
                        (:resolved-research-dependency-set/root
                         (:work/resolved-dependencies right-work)))
                  (conj :different-dependency-closure)
                  (not= (:research-execution-environment/root
                         (:work/execution-environment left-work))
                        (:research-execution-environment/root
                         (:work/execution-environment right-work)))
                  (conj :different-execution-environment)
                  (not= (:accounting-reduction-contract/root left-request)
                        (:accounting-reduction-contract/root right-request))
                  (conj :different-reduction-contract))
        comparable? (empty? reasons)
        left-values (:accounting/values left-partial)
        right-values (:accounting/values right-partial)
        status (cond (not comparable?) :not-comparable
                     (= left-values right-values) :equal
                     :else :different)
        witness (when (= :different status)
                  (into (sorted-map)
                        (for [category (sort (into (set (keys left-values))
                                                   (keys right-values)))
                              :when (not= (get left-values category)
                                          (get right-values category))]
                          [category {:left (get left-values category)
                                     :right (get right-values category)}])))
        body {:artifact/schema schema
              :comparison/left-work-root (:research-native-accounting-work/root left-work)
              :comparison/right-work-root (:research-native-accounting-work/root right-work)
              :comparison/left-execution-root (native/execution-root left-result)
              :comparison/right-execution-root (native/execution-root right-result)
              :comparison/left-assurance-root
              (get-in left-result [:run-assurance-evidence
                                   :research-run-assurance-evidence/root])
              :comparison/right-assurance-root
              (get-in right-result [:run-assurance-evidence
                                   :research-run-assurance-evidence/root])
              :comparison/left-semantic-partial-root
              (accounting/partial-semantic-root left-partial)
              :comparison/right-semantic-partial-root
              (accounting/partial-semantic-root right-partial)
              :comparison/left-request-root (:accounting-request/root left-request)
              :comparison/right-request-root (:accounting-request/root right-request)
              :comparison/left-work-unit-root (:accounting-work-unit/root left-unit)
              :comparison/right-work-unit-root (:accounting-work-unit/root right-unit)
              :comparison/left-implementation-root
              (:research-implementation-identity/root left-impl)
              :comparison/right-implementation-root
              (:research-implementation-identity/root right-impl)
              :comparison/left-dependency-set-root
              (get-in left-work [:work/resolved-dependencies
                                 :resolved-research-dependency-set/root])
              :comparison/right-dependency-set-root
              (get-in right-work [:work/resolved-dependencies
                                 :resolved-research-dependency-set/root])
              :comparison/left-environment-root
              (get-in left-work [:work/execution-environment
                                 :research-execution-environment/root])
              :comparison/right-environment-root
              (get-in right-work [:work/execution-environment
                                 :research-execution-environment/root])
              :comparison/status status
              :comparison/not-comparable-reasons reasons
              :comparison/values (when comparable?
                                   {:left left-values :right right-values})
              :comparison/mismatch-witness witness
              :comparison/implementation-distinct?
              (not= (:research-implementation-identity/root left-impl)
                    (:research-implementation-identity/root right-impl))
              :comparison/assurance-classification
              (if (= (:research-implementation-identity/root left-impl)
                     (:research-implementation-identity/root right-impl))
                :same-implementation-reexecution
                :artifact-level-independent-implementation)
              :comparison/meaning :implementation-result-equivalence-not-correctness-proof}]
    (assoc body :research-accounting-implementation-comparison/root (root body))))

(defn valid-comparison?
  [left-work left-result right-work right-result comparison]
  (= comparison
     (try (compare-implementations left-work left-result right-work right-result)
          (catch Exception _ nil))))

(defn evidence-node
  "Attach the comparison to the existing generic forensic graph, causally
   linked to both validated execution branches."
  [left-work left-result right-work right-result comparison]
  (when-not (valid-comparison? left-work left-result right-work right-result comparison)
    (throw (ex-info "Invalid accounting implementation comparison" {})))
  (let [left-nodes (native/evidence-nodes left-work left-result)
        right-nodes (native/evidence-nodes right-work right-result)]
    (forensic/build-forensic-evidence-node
     {:subject {:subject/kind :accounting/implementation-comparison
                :subject/root (:research-accounting-implementation-comparison/root comparison)}
      :role :derived :visibility :restricted
      :parent-nodes [(-> left-nodes last :node-hash)
                     (-> right-nodes last :node-hash)]
      :causal-parent-roots [(:comparison/left-execution-root comparison)
                            (:comparison/right-execution-root comparison)
                            (:comparison/left-assurance-root comparison)
                            (:comparison/right-assurance-root comparison)]
      :consequence? true})))
