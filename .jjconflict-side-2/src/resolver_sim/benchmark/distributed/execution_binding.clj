(ns resolver-sim.benchmark.distributed.execution-binding
  "Thin rooted binding between a frozen execution plan and executable material."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "benchmark-execution-binding.v1")
(def ^:private domain-tag "PRF_BENCHMARK_EXECUTION_BINDING_V1")
(def required-fields #{:execution-plan/root :executable-distribution/root})

(defn binding-body [binding]
  (let [plan-root (:execution-plan/root binding)
        distribution-root (:executable-distribution/root binding)]
    (when-not (and (hash-ref/valid-sha256-ref? plan-root)
                   (hash-ref/valid-sha256-ref? distribution-root))
      (throw (ex-info "Execution binding requires plan and distribution roots"
                      {:reason :invalid-execution-binding
                       :execution-plan/root plan-root
                       :executable-distribution/root distribution-root})))
    {:execution-binding/schema schema
     :execution-plan/root plan-root
     :executable-distribution/root distribution-root}))

(defn binding-root [binding]
  (hash-ref/sha256-ref (hc/domain-hash domain-tag (binding-body binding))))

(defn build-binding [binding]
  (assoc (binding-body binding) :execution-binding/root (binding-root binding)))

(defn verify-binding [binding]
  (and (= schema (:execution-binding/schema binding))
       (hash-ref/valid-sha256-ref? (:execution-binding/root binding))
       (= (:execution-binding/root binding) (binding-root binding))))
