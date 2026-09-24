(ns resolver-sim.research.execution-cell-runner
  "Pure local execution of one rooted research cell."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.distributed-scheduler :as distributed]
            [resolver-sim.research.execution-cell :as cell]
            [resolver-sim.research.scheduler-harness :as harness]))

(def ^:const schema "research-execution-cell-result.v1")
(defn result-root [r]
  (hash-ref/sha256-ref (hc/domain-hash :research-execution-cell-result-v1
                                       (dissoc r :research-execution-cell-result/root))))
(defn validate-result [r]
  (let [errors (cond-> []
                 (not= schema (:artifact/schema r)) (conj :research-execution-cell-result/unsupported-schema)
                 (not (hash-ref/valid-sha256-ref? (:research-execution-cell/root r))) (conj :research-execution-cell-result/invalid-cell-root)
                 (not (hash-ref/valid-sha256-ref? (:scheduler-run/root r))) (conj :research-execution-cell-result/invalid-run-root)
                 (not= :complete (:cell-result/status r)) (conj :research-execution-cell-result/invalid-status)
                 (not= (:research-execution-cell-result/root r) (try (result-root r) (catch Exception _ nil))) (conj :research-execution-cell-result/root-mismatch))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn result-matches-cell?
  "True only when a successful result is evidence for this exact cell.
   Equal computational output bytes cannot substitute across workload,
   policy, or distributed-profile provenance."
  [execution-cell result]
  (and (:valid? (validate-result result))
       (= (:research-execution-cell/root execution-cell)
          (:research-execution-cell/root result))
       (= (:workload/root execution-cell) (:workload/root result))
       (= (:policy-subject/root execution-cell) (:policy-subject/root result))
       (= (:distributed-experiment-profile/root execution-cell)
          (:distributed-experiment-profile/root result))
       (= (get-in execution-cell [:commit/predicate :expected/root])
          (:expected/root result) (:committed/root result))))
(defn run-cell [scope execution-cell]
  (when-not (cell/valid-cell? execution-cell)
    (throw (ex-info "Invalid execution cell" (cell/validate-cell execution-cell))))
  (let [subject (:policy-subject/root execution-cell)
        policy (get-in scope [:policy-by-subject-root subject])
        workload (get-in scope [:workload-by-root (:workload/root execution-cell)])]
    (when-not (and ((set harness/policies) policy)
                   (= subject (harness/policy-subject-root policy false))
                   workload (= (:workload/root execution-cell) (:workload/root workload)))
      (throw (ex-info "Execution scope does not resolve the declared cell" {:cell execution-cell})))
    (let [run (harness/run-policy workload policy false)
          expected (get-in execution-cell [:commit/predicate :expected/root])]
      (when-not (= expected (:expected/root run) (:committed/root run))
        (throw (ex-info "Cell commit predicate failed" {:cell execution-cell :run run})))
      (let [result {:artifact/schema schema
                    :research-execution-cell/root (:research-execution-cell/root execution-cell)
                    :scheduler-run/root (:scheduler-run/root run)
                    :policy-subject/root subject :workload/root (:workload/root execution-cell)
                    :committed/root (:committed/root run) :expected/root expected
                    :cell-result/status :complete :metrics (:metrics run)
                    :speculation/accounting (:speculation/accounting run)}
            rooted (assoc result :research-execution-cell-result/root (result-root result))]
        (when-not (:valid? (validate-result rooted))
          (throw (ex-info "Invalid execution cell result" (validate-result rooted))))
        rooted))))

(defn run-distributed-cell
  "Execute one cell under rooted distributed profile and policy semantics.

   Returns an envelope because a terminal distributed failure is canonical
   evidence but is not a successful cell result."
  [scope execution-manifest-root execution-cell]
  (when-not (cell/valid-cell? execution-cell)
    (throw (ex-info "Invalid execution cell" (cell/validate-cell execution-cell))))
  (let [policy-root (:policy-subject/root execution-cell)
        profile-root (:distributed-experiment-profile/root execution-cell)
        workload (get-in scope [:workload-by-root (:workload/root execution-cell)])
        policy (get-in scope [:distributed-policy-by-root policy-root])
        profile (get-in scope [:distributed-profile-by-root profile-root])
        observed (or (:observed-distributed-profile-root scope) profile-root)]
    (when-not (and workload policy profile profile-root)
      (throw (ex-info "Distributed execution scope does not resolve the declared cell"
                      {:research-execution-cell/root
                       (:research-execution-cell/root execution-cell)})))
    (let [run (distributed/run
               workload profile policy observed
               {:research-execution-manifest/root execution-manifest-root
                :research-execution-cell/root
                (:research-execution-cell/root execution-cell)})]
      (if (= :failed (:execution/status run))
        {:failure-receipts (:failure-receipts run) :run run}
        (let [expected (get-in execution-cell [:commit/predicate :expected/root])]
          (when-not (= expected (:expected/root run) (:committed/root run))
            (throw (ex-info "Distributed cell commit predicate failed"
                            {:cell execution-cell :run run})))
          (let [result {:artifact/schema schema
                        :research-execution-cell/root
                        (:research-execution-cell/root execution-cell)
                        :scheduler-run/root (:distributed-scheduler-run/root run)
                        :policy-subject/root policy-root
                        :workload/root (:workload/root execution-cell)
                        :distributed-experiment-profile/root profile-root
                        :committed/root (:committed/root run)
                        :expected/root expected
                        :cell-result/status :complete
                        :metrics (:metrics run)
                        :execution/attempt-roots
                        (mapv :distributed-execution-attempt/root (:execution/attempts run))
                        :execution/failure-receipt-roots
                        (mapv :distributed-execution-failure-receipt/root
                              (:failure-receipts run))
                        :execution/placement-projection-roots
                        (mapv :distributed-placement-projection/root
                              (:placement/projections run))
                        ;; Rooted forensic artifacts are retained in the cell
                        ;; result so reconstruction never depends on transient
                        ;; executor logs. The root vectors above provide the
                        ;; compact evidence-DAG edges.
                        :execution/attempts (:execution/attempts run)
                        :execution/placement-projections (:placement/projections run)
                        :execution/failure-receipts (:failure-receipts run)}
                rooted (assoc result :research-execution-cell-result/root
                              (result-root result))]
            {:result rooted :failure-receipts (:failure-receipts run) :run run}))))))
