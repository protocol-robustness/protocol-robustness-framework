(ns resolver-sim.benchmark.claims-artifact
  "claims-artifact.v1

   Content-addressed artifact for benchmark claims.
   Declares the set of claims that a benchmark evaluation checks,
   including claim IDs, descriptions, and expected outcomes.

   Relationship: (:benchmark/claims-root registry-entry)
                 = (:claims/hash model)

   This is distinct from resolver-sim.benchmark.claims (claim evaluators).
   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "claims-artifact.v1")
