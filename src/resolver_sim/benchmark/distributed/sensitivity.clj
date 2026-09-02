(ns resolver-sim.benchmark.distributed.sensitivity
  "Canonical sensitivity bases for fixed benchmark execution chunks."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "benchmark-execution-sensitivity.v1")
(def ^:private domain-tag "PRF_BENCHMARK_EXECUTION_SENSITIVITY_V1")

(def default-basis
  {:sensitivity/schema schema
   :sensitivity/class :unclassified
   :sensitivity/assessment :not-yet-assessed})

(defn root
  "Derive a deterministic sensitivity commitment from an authoritative basis."
  [basis]
  (hash-ref/sha256-ref (hc/domain-hash domain-tag basis)))

(defn default-root []
  (root default-basis))
