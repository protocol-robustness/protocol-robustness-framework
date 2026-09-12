(ns resolver-sim.benchmark.falsifier
  "falsifier.v1

   Content-addressed artifact for benchmark falsifiers.
   Defines strategies for generating counterexamples or failure
   cases that attempt to disprove protocol claims.

   Relationship: (:benchmark/falsifier-root registry-entry)
                 = (:falsifier/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "falsifier.v1")
