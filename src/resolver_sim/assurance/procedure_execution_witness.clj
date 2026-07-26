(ns resolver-sim.assurance.procedure-execution-witness
  "Immutable procedure-execution-witness.v1 schema and builder.
   Builder runs during the benchmark pipeline and produces a
   content-addressed witness artifact binding a trust-sequence
   definition root to evidence chain links.
   
   Verification is in resolver-sim.assurance.witness-verifier."
  (:require [resolver-sim.hash.canonical :as hc]))

(def schema-version 1)

(defn build-witness
  "Build an immutable execution witness.
   
   Required:
     :id               — unique instance id (string)
     :definition-root  — root of the trust-sequence-definition
     :initial-input-root — content hash of initial world state
     :step-bindings    — ordered vector of {:step/id <kw>
                          :evidence <evidence-map>}
     :result-root      — content hash of final result after all steps
   
   Each evidence map must contain :world/before-hash, :world/after-hash,
   :evidence/hash, and :evidence/chain-seq."
  [{:keys [id definition-root initial-input-root step-bindings result-root]}]
  (let [steps (mapv (fn [{:keys [step/id evidence]}]
                      {:step/id id
                       :step/input-root (:world/before-hash evidence)
                       :step/output-root (:world/after-hash evidence)
                       :step/evidence-content-hash (:evidence/hash evidence)
                       :step/evidence-chain-seq (:evidence/chain-seq evidence)})
                    step-bindings)
        base {:procedure-execution-witness/schema-version schema-version
              :procedure-execution-witness/id id
              :procedure-execution-witness/definition-root definition-root
              :procedure-execution-witness/initial-input-root initial-input-root
              :procedure-execution-witness/steps steps
              :procedure-execution-witness/result-root result-root}
        root (hc/hash-with-intent {:hash/intent :procedure-execution-witness} base)]
    (assoc base :procedure-execution-witness/root root)))