(ns resolver-sim.benchmark.execution-identity
  "Stable identity for benchmark-contained executions."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.io.input-source :as input-source]))

(def execution-profile "deterministic-replay.v1")

(defn descriptor [scenario-source scenario repetition-index]
  (let [protocol (or (:protocol scenario) "sew-v1")]
    {:execution/version 1
     :scenario/id (or (:scenario/id scenario) (:scenario-id scenario)
                      (:id scenario) (:input/display-name scenario-source))
     :input/content-hash (str "sha256:" (input-source/sha256 scenario-source))
     :parameter-set-hash (str "sha256:" (canonical/domain-hash
                                         "BENCHMARK_EXECUTION_PARAMETERS_V1"
                                         (or (:params scenario) {})))
     :protocol-config-hash (str "sha256:" (canonical/domain-hash
                                           "BENCHMARK_EXECUTION_PROTOCOL_CONFIG_V1"
                                           {:protocol protocol
                                            :flags (if (= protocol "yield-v1")
                                                     {:yield-dt-validation? true
                                                      :metrics-profile :yield-provider}
                                                     {})}))
     :seed (:seed scenario)
     :repetition-index repetition-index
     :execution-profile execution-profile}))

(defn execution-id [descriptor]
  (str "sha256:" (canonical/domain-hash
                  "BENCHMARK_EXECUTION_DESCRIPTOR_V1"
                  descriptor)))

(defn directory-name [ordinal descriptor]
  (let [hash (subs (execution-id descriptor) (count "sha256:"))]
    (format "exec-%04d-%s" ordinal (subs hash 0 16))))
