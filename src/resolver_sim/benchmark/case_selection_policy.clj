(ns resolver-sim.benchmark.case-selection-policy
  "case-selection-policy.v1

   Content-addressed artifact for benchmark case selection policies.
   Defines how cases are selected from the generated case set for
   execution, including filtering, prioritisation, and coverage rules.

   Relationship: (:benchmark/case-selection-policy-root registry-entry)
                 = (:case-selection-policy/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "case-selection-policy.v1")
