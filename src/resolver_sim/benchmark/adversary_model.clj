(ns resolver-sim.benchmark.adversary-model
  "adversary-model.v1

   Content-addressed artifact for benchmark adversary models.
   Specifies adversarial capabilities, strategies, and behavioral
   constraints for stress-testing protocol robustness.

   Relationship: (:benchmark/adversary-model-root registry-entry)
                 = (:adversary/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "adversary-model.v1")
