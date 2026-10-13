(ns resolver-sim.benchmark.distributed.fixed-chunks
  "Pure canonical fixed-chunk derivation for frozen benchmark execution plans."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "benchmark-fixed-chunk-set.v1")
(def ^:private run-plan-domain "PRF_BENCHMARK_RUN_PLAN_V1")
(def ^:private execution-plan-domain "PRF_BENCHMARK_EXECUTION_PLAN_V1")

(def ^:private chunk-set-domain "PRF_BENCHMARK_FIXED_CHUNK_SET_V1")

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- require-positive! [value]
  (when-not (and (integer? value) (pos? value))
    (throw (ex-info "Fixed chunk size must be a positive integer" {:chunk-size value})))
  value)

(defn- require-root! [field value]
  (when-not (hash-ref/valid-sha256-ref? value)
    (throw (ex-info "Fixed chunk derivation requires a SHA-256 root"
                    {:field field :value value})))
  value)

(defn- canonical-plan-entry [entry]
  (select-keys entry [:execution/ordinal :execution/id :execution/descriptor :scenario/input-root]))

(defn input-root
  "Derive an observed or expected chunk input root from ordered execution rows."
  [entries]
  (root "PRF_BENCHMARK_FIXED_CHUNK_INPUT_V1"
        (mapv #(select-keys % [:execution/id :scenario/input-root]) entries)))

(defn- expected-input-root [entries]
  (input-root entries))

(defn work-root
  "Derive an observed or expected chunk work root from ordered execution rows."
  [entries]
  (root "PRF_BENCHMARK_FIXED_CHUNK_WORK_V1"
        (mapv #(select-keys % [:execution/ordinal :execution/id :execution/descriptor]) entries)))

(defn- expected-work-root [entries]
  (work-root entries))

(defn execution-plan-root [plan]
  (root execution-plan-domain (mapv canonical-plan-entry plan)))

(defn chunk-set-root
  "Reconstruct the canonical identity of a fixed chunk set from its committed
   derivation inputs and public chunk descriptors."
  [chunk-set]
  (root chunk-set-domain
        {:chunk-set/schema schema
         :run-plan/root (:run-plan/root chunk-set)
         :execution-plan/root (:execution-plan/root chunk-set)
         :chunk-size (:chunk-size chunk-set)
         :sensitivity/root (:sensitivity/root chunk-set)
         :executable-distribution/root (:executable-distribution/root chunk-set)
         :chunks (mapv #(dissoc % :chunk/work-items) (:chunks chunk-set))}))

(defn derive-fixed-chunk-set
  "Derive the complete, ordered fixed chunk descriptor set from a frozen plan.

   Every plan entry must have a canonical frozen input root. Sensitivity is an
   expected execution condition supplied by the canonical caller, never a
   worker preference."
  [plan {:keys [chunk-size sensitivity-root executable-distribution-root]}]
  (require-positive! chunk-size)
  (require-root! :sensitivity/root sensitivity-root)
  (require-root! :executable-distribution/root executable-distribution-root)
  (when-not (and (vector? plan) (seq plan))
    (throw (ex-info "Fixed chunk derivation requires a nonempty frozen plan" {})))
  (let [plan (mapv canonical-plan-entry plan)
        ids (mapv :execution/id plan)
        _ (when-not (and (every? string? ids) (= (count ids) (count (set ids))))
            (throw (ex-info "Frozen execution plan has invalid or duplicate execution IDs" {:ids ids})))
        _ (doseq [entry plan] (require-root! :scenario/input-root (:scenario/input-root entry)))
        run-plan-root (root run-plan-domain
                            (mapv #(select-keys % [:execution/id :execution/descriptor]) plan))
        execution-plan-root (execution-plan-root plan)
        chunks (mapv (fn [index entries]
                       (let [entries (vec entries)]
                         {:chunk/id (format "chunk-%04d" (inc index))
                          :chunk/expected-input-root (expected-input-root entries)
                          :chunk/expected-work-root (expected-work-root entries)
                          :chunk/expected-sensitivity-root sensitivity-root
                          :chunk/expected-executable-distribution-root executable-distribution-root
                          :chunk/execution-ids (mapv :execution/id entries)
                          :chunk/work-items entries}))
                     (range) (partition-all chunk-size plan))
        chunk-set {:chunk-set/schema schema
                   :run-plan/root run-plan-root
                   :execution-plan/root execution-plan-root
                   :chunk-size chunk-size
                   :sensitivity/root sensitivity-root
                   :executable-distribution/root executable-distribution-root
                   :chunks chunks}]
    (assoc chunk-set :chunk-set/root (chunk-set-root chunk-set))))
