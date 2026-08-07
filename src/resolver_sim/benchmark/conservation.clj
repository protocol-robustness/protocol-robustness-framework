(ns resolver-sim.benchmark.conservation
  "Pure projection of canonical execution-level conservation invariant results."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]))

(def invariant-id :conservation-of-funds)
(def schema-version "benchmark-conservation.v1")

(defn aggregate-status [expected observed]
  (let [expected (set expected)
        observed-ids (set (map :execution_id observed))
        missing (set/difference expected observed-ids)
        statuses (map :status observed)]
    (cond
      (and (empty? expected) (empty? observed)) :not-exercised
      (seq missing) :incomplete
      (some #{:incomplete} statuses) :incomplete
      (some #{:fail} statuses) :fail
      (every? #{:pass} statuses) :pass
      :else :incomplete)))

(defn project
  "Create a conservation artifact from normalized execution entries. Each entry
   must identify the execution, its summary reference/hash, and the canonical
   invariant status. No conclusion or benchmark policy is evaluated here."
  [{:keys [benchmark-id run-id expected-execution-ids executions required?]
    :or {required? true}}]
  (let [expected (vec (sort expected-execution-ids))
        executions (mapv #(select-keys % [:execution_id :result_ref :result_sha256 :invariant_id :status]) executions)
        counts (frequencies (map :status executions))
        status (aggregate-status expected executions)]
    {:schema_version schema-version
     :benchmark_id benchmark-id
     :run_id run-id
     :authority {:kind "invariant" :namespace "sew" :id "conservation-of-funds" :version "v1"}
     :applicability {:policy "all-required-executions" :required required? :expected_execution_ids expected}
     :status (name status)
     :summary {:expected (count expected) :evaluated (count executions)
               :passed (get counts :pass 0) :failed (get counts :fail 0)
               :not_exercised (get counts :not-exercised 0)
               :missing (count (set/difference (set expected) (set (map :execution_id executions))))}
     :executions executions
     :reconciliation nil}))

(defn final-ref [artifact]
  (hash-ref/sha256-ref (canonical/domain-hash "BENCHMARK_CONSERVATION_V1" artifact)))
