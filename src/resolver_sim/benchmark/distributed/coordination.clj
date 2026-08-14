(ns resolver-sim.benchmark.distributed.coordination
  "PostgreSQL operational control plane for fixed distributed benchmark chunks.

   This namespace intentionally has no canonical reduction or publication code.
   A :completed database row is only a fenced detached-result acceptance; the
   coordinator must still retrieve, verify, reconcile, reduce, close work, and
   publish through the established benchmark lifecycle."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private builder {:builder-fn rs/as-unqualified-maps})

(defn- nonblank! [name value]
  (when-not (and (string? value) (seq value))
    (throw (ex-info "Distributed benchmark coordination requires a nonblank string"
                    {:reason :invalid-coordination-request :field name :value value})))
  value)

(defn- positive! [name value]
  (when-not (and (integer? value) (pos? value))
    (throw (ex-info "Distributed benchmark coordination requires a positive integer"
                    {:reason :invalid-coordination-request :field name :value value})))
  value)

(defn register-run!
  "Register one coordinator-owned run and its already frozen fixed chunks.
   Chunk assignment is operational, but each row is strictly bound to this
   execution-plan root and its exact expected work-set root."
  [ds {:keys [run-id run-plan-root execution-plan-root chunks]}]
  (nonblank! :run-id run-id)
  (nonblank! :run-plan-root run-plan-root)
  (nonblank! :execution-plan-root execution-plan-root)
  (when-not (seq chunks)
    (throw (ex-info "Distributed execution run requires at least one fixed chunk"
                    {:reason :empty-distributed-execution-plan})))
  (jdbc/with-transaction [tx ds]
    (jdbc/execute-one! tx
                       ["INSERT INTO benchmark_execution_run
                         (run_id, run_plan_root, execution_plan_root)
                         VALUES (?, ?, ?)
                         ON CONFLICT (run_id) DO NOTHING"
                        run-id run-plan-root execution-plan-root]
                       builder)
    (let [stored (jdbc/execute-one! tx
                                    ["SELECT run_plan_root, execution_plan_root
                                      FROM benchmark_execution_run WHERE run_id = ? FOR UPDATE" run-id]
                                    builder)]
      (when-not (= [run-plan-root execution-plan-root]
                   [(:run_plan_root stored) (:execution_plan_root stored)])
        (throw (ex-info "Distributed run ID is already bound to another plan"
                        {:reason :distributed-run-plan-conflict :run-id run-id}))))
    (doseq [{:keys [chunk-id expected-work-root]} chunks]
      (nonblank! :chunk-id chunk-id)
      (nonblank! :expected-work-root expected-work-root)
      (jdbc/execute-one! tx
                         ["INSERT INTO benchmark_execution_chunk
                           (run_id, chunk_id, expected_work_root)
                           VALUES (?, ?, ?)
                           ON CONFLICT (run_id, chunk_id) DO NOTHING"
                          run-id chunk-id expected-work-root]
                         builder)
      (let [stored (jdbc/execute-one! tx
                                      ["SELECT expected_work_root FROM benchmark_execution_chunk
                                        WHERE run_id = ? AND chunk_id = ? FOR UPDATE" run-id chunk-id]
                                      builder)]
        (when-not (= expected-work-root (:expected_work_root stored))
          (throw (ex-info "Distributed chunk ID is already bound to another work set"
                          {:reason :distributed-chunk-work-conflict
                           :run-id run-id :chunk-id chunk-id})))))
    {:run-id run-id :execution-plan-root execution-plan-root
     :chunk-count (count chunks)}))

(defn claim-chunk!
  "Claim one pending or DB-expired fixed chunk. PostgreSQL `clock_timestamp()`
   is read once in this authoritative transaction. A lease is current exactly
   when `now < lease_expires_at`; equality is expired and reclaimable."
  [ds {:keys [run-id worker-id lease-ms]}]
  (nonblank! :run-id run-id)
  (nonblank! :worker-id worker-id)
  (positive! :lease-ms lease-ms)
  (jdbc/with-transaction [tx ds]
    (let [row (jdbc/execute-one!
               tx
               ["WITH db_clock AS (SELECT clock_timestamp() AS now),
                       candidate AS (
                         SELECT c.run_id, c.chunk_id
                         FROM benchmark_execution_chunk c, db_clock
                         WHERE c.run_id = ?
                           AND (c.status = 'pending'
                             OR (c.status = 'leased' AND c.lease_expires_at <= db_clock.now))
                         ORDER BY c.chunk_id
                         FOR UPDATE SKIP LOCKED
                         LIMIT 1)
                  UPDATE benchmark_execution_chunk c
                  SET status = 'leased', worker_id = ?, fence = c.fence + 1,
                      attempt = c.attempt + 1,
                      lease_expires_at = (SELECT now FROM db_clock)
                                         + (? * INTERVAL '1 millisecond')
                  FROM candidate
                  WHERE c.run_id = candidate.run_id AND c.chunk_id = candidate.chunk_id
                  RETURNING c.*, (SELECT execution_plan_root FROM benchmark_execution_run
                                  WHERE run_id = c.run_id) AS execution_plan_root"
                run-id worker-id lease-ms]
               builder)]
      (when row
        {:outcome :leased
         :run-id (:run_id row) :chunk-id (:chunk_id row)
         :execution-plan-root (:execution_plan_root row)
         :expected-work-root (:expected_work_root row)
         :worker-id (:worker_id row) :fence (:fence row) :attempt (:attempt row)
         :lease-expires-at (:lease_expires_at row)}))))

(defn complete-chunk!
  "Accept a detached result only under the current DB-authoritative lease and
   fence. An existing completion is idempotent only when the complete accepted
   identity tuple matches; a different locator is allowed only as a locator for
   the same roots/provenance commitment."
  [ds {:keys [run-id execution-plan-root chunk-id expected-work-root worker-id fence
              result-root result-manifest-root result-ref sensitivity-root sensitivity-level]}]
  (doseq [[k v] [[:run-id run-id] [:execution-plan-root execution-plan-root]
                 [:chunk-id chunk-id] [:expected-work-root expected-work-root]
                 [:worker-id worker-id] [:result-root result-root]
                 [:result-manifest-root result-manifest-root] [:result-ref result-ref]
                 [:sensitivity-root sensitivity-root]]]
    (nonblank! k v))
  (positive! :fence fence)
  (jdbc/with-transaction [tx ds]
    (let [row (jdbc/execute-one! tx
                                 ["SELECT c.*, r.execution_plan_root, clock_timestamp() AS db_now
                                   FROM benchmark_execution_chunk c
                                   JOIN benchmark_execution_run r ON r.run_id = c.run_id
                                   WHERE c.run_id = ? AND c.chunk_id = ? FOR UPDATE"
                                  run-id chunk-id]
                                 builder)]
      (cond
        (nil? row) {:outcome :rejected :reason :unknown-chunk}
        (not= execution-plan-root (:execution_plan_root row))
        {:outcome :rejected :reason :execution-plan-root-mismatch}
        (not= expected-work-root (:expected_work_root row))
        {:outcome :rejected :reason :expected-work-root-mismatch}
        (= "completed" (:status row))
        (if-not (= (long fence) (long (:fence row)))
          {:outcome :rejected :reason :stale-fence :current-fence (:fence row)}
          (let [same? (= [execution-plan-root chunk-id expected-work-root result-root result-manifest-root sensitivity-root]
                         [(:execution_plan_root row) (:chunk_id row) (:expected_work_root row)
                          (:result_root row) (:result_manifest_root row) (:sensitivity_root row)])]
            (if same?
              {:outcome :idempotent-completion :result-ref (:result_ref row)
               :result-root (:result_root row) :result-manifest-root (:result_manifest_root row)}
              {:outcome :rejected :reason :completed-result-conflict})))
        (not= "leased" (:status row)) {:outcome :rejected :reason :chunk-not-leased}
        (not= (long fence) (long (:fence row))) {:outcome :rejected :reason :stale-fence :current-fence (:fence row)}
        (not= worker-id (:worker_id row)) {:outcome :rejected :reason :lease-owner-mismatch}
        ;; Exact expiry boundary: now >= expires_at is expired.
        (not (neg? (compare (:db_now row) (:lease_expires_at row))))
        {:outcome :rejected :reason :lease-expired}
        :else
        (do
          (jdbc/execute-one! tx
                             ["UPDATE benchmark_execution_chunk
                               SET status = 'completed', result_root = ?, result_manifest_root = ?,
                                   result_ref = ?, sensitivity_root = ?, sensitivity_level = ?,
                                   worker_id = NULL, lease_expires_at = NULL
                               WHERE run_id = ? AND chunk_id = ?"
                              result-root result-manifest-root result-ref sensitivity-root
                              (some-> sensitivity-level name) run-id chunk-id]
                             builder)
          {:outcome :completed :run-id run-id :chunk-id chunk-id
           :result-root result-root :result-manifest-root result-manifest-root
           :result-ref result-ref :sensitivity-root sensitivity-root})))))

(defn resolve-chunk-completion!
  "Read authoritative accepted completion for restart/lost-response recovery.
   This does not publish or merge the detached result."
  [ds run-id chunk-id]
  (let [row (jdbc/execute-one! ds
                               ["SELECT c.*, r.execution_plan_root FROM benchmark_execution_chunk c
                                 JOIN benchmark_execution_run r ON r.run_id = c.run_id
                                 WHERE c.run_id = ? AND c.chunk_id = ?" run-id chunk-id]
                               builder)]
    (when (= "completed" (:status row))
      {:outcome :completed :run-id run-id :chunk-id chunk-id
       :execution-plan-root (:execution_plan_root row)
       :expected-work-root (:expected_work_root row)
       :result-root (:result_root row) :result-manifest-root (:result_manifest_root row)
       :result-ref (:result_ref row) :sensitivity-root (:sensitivity_root row)
       :sensitivity-level (some-> (:sensitivity_level row) keyword)})))
