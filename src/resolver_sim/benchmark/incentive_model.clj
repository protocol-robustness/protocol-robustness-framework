(ns resolver-sim.benchmark.incentive-model
  "incentive-model.v1

   Content-addressed artifact for benchmark incentive models.
   Describes reward, penalty, and payoff structures that govern
   participant behavior within a research benchmark.

   Relationship: (:benchmark/incentive-model-root registry-entry)
                 = (:incentive/hash model)

   Artifact not yet implemented — placeholder for schema version tracking
   and content-root integration."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "incentive-model.v1")
