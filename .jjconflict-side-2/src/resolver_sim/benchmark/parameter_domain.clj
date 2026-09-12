(ns resolver-sim.benchmark.parameter-domain
  "parameter-domain.v1

   Content-addressed artifact for benchmark parameter domains.
   Declares the parameter space, bounds, types, and default values
   that a benchmark execution samples from.

   Relationship: (:benchmark/parameter-domain-root registry-entry)
                 = (:parameter/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "parameter-domain.v1")
