(ns resolver-sim.benchmark.evidence-contract
  "evidence-contract.v1

   Content-addressed artifact for benchmark evidence contracts.
   Defines the evidence schema, chaining rules, and verification
   requirements for evidence produced during benchmark execution.

   Relationship: (:benchmark/evidence-contract-root registry-entry)
                 = (:evidence-contract/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "evidence-contract.v1")
