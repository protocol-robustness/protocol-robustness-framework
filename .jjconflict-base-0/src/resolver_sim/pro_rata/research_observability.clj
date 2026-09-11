(ns resolver-sim.pro-rata.research-observability
  "Project-owned exact-integer observations derived from verified allocations."
  (:require [resolver-sim.benchmark.research-observation-projection :as observation-projection]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.allocation :as allocation]))

(defn- exact-non-negative? [value]
  (and (integer? value) (not (neg? value))))

(defn derive-observations [result]
  (when-not (allocation/allocation-hash-valid? result)
    (throw (ex-info "Cannot observe an allocation with an invalid hash"
                    {:reason :pro-rata/allocation-hash-invalid})))
  (let [rows (:rows result)
        observations {:pro-rata/allocated-total
                      {:observation/value (bigint (:allocated-total result))
                       :observation/domain {:kind :integer :unit :pro-rata-units}}
                      :pro-rata/unallocated-residual
                      {:observation/value (bigint (:unallocated-residual result))
                       :observation/domain {:kind :integer :unit :pro-rata-units}}}]
    (when-not (and (exact-non-negative? (:allocated-total result))
                   (exact-non-negative? (:unallocated-residual result))
                   (every? #(exact-non-negative? (or (:unmet %) 0N)) rows))
      (throw (ex-info "Allocation observations must be exact non-negative integers"
                      {:reason :pro-rata/invalid-observation-values})))
    (observation-projection/build-projection observations)))

(defn provenance-root [allocation-hash observation-root]
  (hash-ref/sha256-ref
   (hc/domain-hash :pro-rata-research-observation
                   {:allocation/hash allocation-hash
                    :research-observation-projection/root observation-root})))

(defn bind-observations [result projection]
  (let [observed (derive-observations result)]
    (when-not (= projection observed)
      (throw (ex-info "Research observation projection does not match allocation"
                      {:reason :pro-rata/observation-projection-mismatch
                       :expected/root (:research-observation-projection/root observed)
                       :observed/root (:research-observation-projection/root projection)})))
    {:artifact/schema "pro-rata-research-observation-binding.v1"
     :allocation/hash (:allocation/hash result)
     :research-observation-projection/root (:research-observation-projection/root projection)
     :binding/root (provenance-root (:allocation/hash result)
                                    (:research-observation-projection/root projection))}))
