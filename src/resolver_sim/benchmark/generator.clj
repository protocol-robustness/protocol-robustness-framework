(ns resolver-sim.benchmark.generator
  "generator.v1

   Content-addressed artifact for benchmark case generators.
   Defines how individual test cases are produced from the parameter
   domain, including sampling strategies and seed schedules.

   Relationship: (:benchmark/generator-root registry-entry)
                 = (:generator/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "generator.v1")
