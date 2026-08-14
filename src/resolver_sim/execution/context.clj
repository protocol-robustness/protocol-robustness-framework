(ns resolver-sim.execution.context
  "Runtime-only execution controls. Values here are never canonical scenario
   inputs, execution identities, evidence, or package content."
  (:require [resolver-sim.economics.payoffs :as payoffs]))

(def ^:dynamic *context* nil)

(defn validate-context
  "Validate noncanonical controls before scenario execution begins."
  [context]
  (let [context (or context {})
        parallelism (or (:execution/claimant-parallelism context) 1)
        threshold (or (:execution/claimant-parallel-threshold context)
                      payoffs/*pro-rata-parallel-threshold*)]
    (when-not (and (integer? parallelism) (pos? parallelism))
      (throw (ex-info "Claimant parallelism must be a positive integer"
                      {:execution/claimant-parallelism parallelism})))
    (when-not (and (integer? threshold) (pos? threshold))
      (throw (ex-info "Claimant parallel threshold must be a positive integer"
                      {:execution/claimant-parallel-threshold threshold})))
    {:execution/claimant-parallelism parallelism
     :execution/claimant-parallel-threshold threshold}))

(defn claimant-options
  "Snapshot runtime settings lexically at an operation boundary. Child claimant
   executors therefore do not need dynamic-binding conveyance."
  []
  (validate-context *context*))

(defmacro with-claimant-options
  "Bind the allocator controls only around an operation's allocation call.
   The values are resolved before any child executor is created."
  [& body]
  `(let [{parallelism# :execution/claimant-parallelism
          threshold# :execution/claimant-parallel-threshold} (claimant-options)]
     (binding [payoffs/*pro-rata-parallelism* parallelism#
               payoffs/*pro-rata-parallel-threshold* threshold#]
       ~@body)))
