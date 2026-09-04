(ns resolver-sim.benchmark.distributed.executable-invocation
  "Portable, rooted authorization for one closed executable invocation.

   This descriptor contains no callbacks, Vars, executors, futures, paths, or
   worker-local handles. A worker resolves it into local execution machinery
   only after independently resolving and verifying the required distribution."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "benchmark-executable-invocation.v1")
(def ^:private domain-tag "PRF_BENCHMARK_EXECUTABLE_INVOCATION_V1")
(def ^:private content-fields
  #{:executable-invocation/schema
    :execution-plan/root
    :executable-distribution/root
    :chunk/id
    :execution-ids
    :input/root
    :work/root
    :sensitivity/root})
(def required-fields (conj content-fields :executable-invocation/root))

(defn- root? [value]
  (hash-ref/valid-sha256-ref? value))

(defn invocation-body [invocation]
  (let [body (select-keys invocation content-fields)]
    (when-not (and (= content-fields (set (keys (dissoc invocation
                                                        :executable-invocation/root))))
                   (= schema (:executable-invocation/schema invocation))
                   (root? (:execution-plan/root invocation))
                   (root? (:executable-distribution/root invocation))
                   (string? (:chunk/id invocation))
                   (seq (:execution-ids invocation))
                   (vector? (:execution-ids invocation))
                   (= (count (:execution-ids invocation))
                      (count (set (:execution-ids invocation))))
                   (every? string? (:execution-ids invocation))
                   (root? (:input/root invocation))
                   (root? (:work/root invocation))
                   (root? (:sensitivity/root invocation)))
      (throw (ex-info "Invalid executable invocation descriptor"
                      {:reason :invalid-executable-invocation
                       :invocation invocation})))
    body))

(defn invocation-root [invocation]
  (hash-ref/sha256-ref
   (hc/domain-hash domain-tag (invocation-body invocation))))

(defn build-invocation [invocation]
  (let [body (invocation-body (assoc (dissoc invocation
                                             :executable-invocation/root)
                                     :executable-invocation/schema schema))]
    (assoc body :executable-invocation/root (invocation-root body))))

(defn verify-invocation [invocation]
  (and (= required-fields (set (keys invocation)))
       (= (:executable-invocation/root invocation)
          (invocation-root invocation))))
