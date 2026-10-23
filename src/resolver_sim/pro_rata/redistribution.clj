(ns resolver-sim.pro-rata.redistribution
  "PRF runtime adapter for the pinned portable cap-redistribution allocator.

   This namespace binds claimant execution budgets, progress reporting and
   test-only callbacks. Allocation and redistribution semantics live in
   prf-pro-rata-allocation."
  (:require [prf.pro-rata.redistribution :as redistribution]
            [resolver-sim.execution.realization :as realization]
            [resolver-sim.pro-rata.engine :as engine]
            [resolver-sim.pro-rata.progress :as progress]))

(def ^:dynamic *redistribution-claimant-hook*
  "Test/runtime-only hook invoked during detached active-set claimant fact
   determination. Never read by canonical allocation/evidence code."
  nil)

(def ^:dynamic *redistribution-claimant-determination-hook*
  "Runtime-only test instrumentation invoked before an active-set redistribution
   round determines claimant-local facts. It is excluded from canonical
   allocation requests, decisions, evidence, and package projections."
  nil)

(defn- allocate-pro-rata-with-redistribution*
  "Delegate active-set semantics to the package using PRF runtime adapters."
  [request]
  (redistribution/allocate
   request
   {:effective-claimant-parallelism engine/effective-claimant-parallelism
    :ordered-detached-mapv engine/ordered-detached-mapv
    :report-progress! progress/report-pro-rata-progress!
    :claimant-hook (fn [item]
                     (when *redistribution-claimant-hook*
                       (*redistribution-claimant-hook* item)))
    :claimant-determination-hook
    (fn [round]
      (when *redistribution-claimant-determination-hook*
        (*redistribution-claimant-determination-hook* round)))}))

(defn allocate-pro-rata-with-redistribution [request]
  (let [state (realization/open)]
    (binding [realization/*claimant-execution-realization* state]
      (let [result (allocate-pro-rata-with-redistribution* request)]
        (realization/complete! state)
        result))))
