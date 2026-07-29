(ns resolver-sim.benchmark.fixed-regression-case
  "fixed-regression-case.v1

   Content-addressed artifact for fixed regression test cases.
   Provides a stable set of curated cases that must produce
   known outcomes, used as regression guards against protocol changes.

   Relationship: (:benchmark/fixed-regression-case-root registry-entry)
                 = (:fixed-regression-case/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "fixed-regression-case.v1")
