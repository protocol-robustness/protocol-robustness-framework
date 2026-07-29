(ns resolver-sim.benchmark.evaluation-policy
  "evaluation-policy.v1

   Content-addressed artifact for benchmark evaluation policies.
   Specifies how claim outcomes, evidence, and confidence are
   evaluated and aggregated into a final benchmark report.

   Relationship: (:benchmark/evaluation-policy-root registry-entry)
                 = (:evaluation-policy/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "evaluation-policy.v1")
