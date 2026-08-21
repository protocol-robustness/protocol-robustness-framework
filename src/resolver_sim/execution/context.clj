(ns resolver-sim.execution.context
  "Runtime-only execution controls. Values here are never canonical scenario
   inputs, execution identities, evidence, or package content."
  (:require [resolver-sim.economics.payoffs :as payoffs]))

(def ^:dynamic *context* nil)

(defn validate-context
  "Validate noncanonical controls before scenario execution begins."
  [context]
  (let [context (or context {})
        outer-parallelism (some-> context :execution/outer-parallelism)
        parallelism (or (:execution/claimant-parallelism context) 1)
        threshold (or (:execution/claimant-parallel-threshold context)
                      payoffs/*pro-rata-parallel-threshold*)
        quiescence-timeout-seconds (:execution/quiescence-timeout-seconds context)]
    (when (some? outer-parallelism)
      (when-not (and (integer? outer-parallelism) (pos? outer-parallelism))
        (throw (ex-info "Outer parallelism must be a positive integer"
                        {:execution/outer-parallelism outer-parallelism}))))
    (when-not (and (integer? parallelism) (pos? parallelism))
      (throw (ex-info "Claimant parallelism must be a positive integer"
                      {:execution/claimant-parallelism parallelism})))
    (when-not (and (integer? threshold) (pos? threshold))
      (throw (ex-info "Claimant parallel threshold must be a positive integer"
                      {:execution/claimant-parallel-threshold threshold})))
    (when (some? quiescence-timeout-seconds)
      (when-not (and (integer? quiescence-timeout-seconds)
                     (pos? quiescence-timeout-seconds))
        (throw (ex-info "Claimant quiescence timeout must be a positive integer"
                        {:execution/quiescence-timeout-seconds quiescence-timeout-seconds}))))
    (cond-> {:execution/outer-parallelism outer-parallelism
             :execution/claimant-parallelism parallelism
             :execution/claimant-parallel-threshold threshold}
      (some? quiescence-timeout-seconds)
      (assoc :execution/quiescence-timeout-seconds quiescence-timeout-seconds))))

(defn claimant-options
  "Snapshot runtime settings lexically at an operation boundary."
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
