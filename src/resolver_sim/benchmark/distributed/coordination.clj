(ns resolver-sim.benchmark.distributed.coordination
  "PostgreSQL operational control plane for immutable distributed benchmark chunks.

   A completed database row is only a fenced detached-result acceptance. The
   coordinator must still retrieve, verify, reconcile, reduce, close work, and
   publish through the established benchmark lifecycle."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.sensitivity.sentinel :as sentinel]))

(def ^:private builder {:builder-fn rs/as-unqualified-maps})
(def ^:private chunk-set-domain-tag "PRF_BENCHMARK_EXECUTION_CHUNK_SET_V1")
(def ^:private chunk-set-schema "benchmark-execution-chunk-set.v1")

(defn- nonblank! [name value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info "Distributed benchmark coordination requires a nonblank string"
                    {:reason :invalid-coordination-request :field name :value value})))
  value)

(defn- positive! [name value]
  (when-not (and (integer? value) (pos? value))
    (throw (ex-info "Distributed benchmark coordination requires a positive integer"
                    {:reason :invalid-coordination-request :field name :value value})))
  value)

(defn- sensitivity-level! [level]
  (when-not (contains? sentinel/level-set level)
    (throw (ex-info "Distributed benchmark completion has an invalid sensitivity level"
                    {:reason :invalid-sensitivity-level :sensitivity-level level})))
  level)

(defn- level->db [level]
  (subs (str level) 1))

(defn- db->level [level]
  (some-> level keyword))

(defn- canonical-chunks! [chunks]
  (when-not (seq chunks)
    (throw (ex-info "Distributed execution run requires at least one fixed chunk"
                    {:reason :empty-distributed-execution-plan})))
  (let [descriptors
        (mapv (fn [{:keys [chunk-id expected-input-root expected-work-root]}]
                (nonblank! :chunk-id chunk-id)
                (nonblank! :expected-input-root expected-input-root)
                (nonblank! :expected-work-root expected-work-root)
                {:chunk/id chunk-id
                 :chunk/expected-input-root expected-input-root
                 :chunk/expected-execution-root expected-work-root})
              chunks)
        ids (mapv :chunk/id descriptors)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "Distributed execution registration has duplicate chunk IDs"
                      {:reason :duplicate-distributed-chunk-id :chunk-ids ids})))
    (vec (sort-by :chunk/id descriptors))))

(defn chunk-set-root
  "Return the canonical SHA-256 reference for a complete, ordered chunk set."
  [chunks]
  (hash-ref/sha256-ref
   (hc/domain-hash chunk-set-domain-tag
                   {:chunk-set/schema chunk-set-schema
                    :chunk-set/chunks (canonical-chunks! chunks)})))

(defn- run-row-for-update! [tx run-id lock-mode]
  (jdbc/execute-one! tx
                     [(str "SELECT * FROM benchmark_execution_run WHERE run_id = ? " lock-mode) run-id]
                     builder))

(defn- stored-chunks [tx run-id]
  (mapv (fn [row]
          {:chunk/id (:chunk_id row)
           :chunk/expected-input-root (:expected_input_root row)
           :chunk/expected-execution-root (:expected_work_root row)})
        (jdbc/execute! tx
                       ["SELECT chunk_id, expected_input_root, expected_work_root
                         FROM benchmark_execution_chunk
                         WHERE run_id = ? ORDER BY chunk_id" run-id]
                       builder)))

(defn- registration-conflict! [run-id]
  (throw (ex-info "Distributed run ID is already bound to another fixed work set"
                  {:reason :distributed-run-plan-conflict :run-id run-id})))

(defn register-run!
  "Atomically register an immutable, complete fixed chunk set, or verify an
   exact retry. Chunk descriptors require stable input and execution roots."
  [ds {:keys [run-id run-plan-root execution-plan-root chunks]}]
  (nonblank! :run-id run-id)
  (nonblank! :run-plan-root run-plan-root)
  (nonblank! :execution-plan-root execution-plan-root)
  (let [chunks (canonical-chunks! chunks)
        root (chunk-set-root chunks)
        expected-count (count chunks)]
    (jdbc/with-transaction [tx ds]
      (jdbc/execute-one! tx
                         ["INSERT INTO benchmark_execution_run
                           (run_id, run_plan_root, execution_plan_root)
                           VALUES (?, ?, ?)
                           ON CONFLICT (run_id) DO NOTHING"
                          run-id run-plan-root execution-plan-root]
                         builder)
      (let [run (run-row-for-update! tx run-id "FOR UPDATE")]
        (when-not (= [run-plan-root execution-plan-root]
                     [(:run_plan_root run) (:execution_plan_root run)])
          (registration-conflict! run-id))
        (if (:registration_complete run)
          (when-not (and (= root (:chunk_set_root run))
                         (= expected-count (:expected_chunk_count run))
                         (= chunks (stored-chunks tx run-id)))
            (registration-conflict! run-id))
          (do
            (doseq [{:chunk/keys [id expected-input-root expected-execution-root]} chunks]
              (jdbc/execute-one! tx
                                 ["INSERT INTO benchmark_execution_chunk
                                   (run_id, chunk_id, expected_input_root, expected_work_root)
                                   VALUES (?, ?, ?, ?)"
                                  run-id id expected-input-root expected-execution-root]
                                 builder))
            (jdbc/execute-one! tx
                               ["UPDATE benchmark_execution_run
                                 SET chunk_set_root = ?, expected_chunk_count = ?,
                                     registration_complete = TRUE
                                 WHERE run_id = ?"
                                root expected-count run-id]
                               builder)))
        {:run-id run-id :execution-plan-root execution-plan-root
         :chunk-set-root root :chunk-count (:expected_chunk_count
                                            (run-row-for-update! tx run-id "FOR UPDATE"))}))))

(defn claim-chunk!
  "Claim one pending or DB-expired chunk only while its run is dispatching.
   The shared run lock serializes terminal transitions without serializing
   claims for independent chunks."
  [ds {:keys [run-id worker-id lease-ms]}]
  (nonblank! :run-id run-id)
  (nonblank! :worker-id worker-id)
  (positive! :lease-ms lease-ms)
  (jdbc/with-transaction [tx ds]
    (let [run (run-row-for-update! tx run-id "FOR SHARE")]
      (when (= "dispatching" (:coordination_status run))
        (when-let [row (jdbc/execute-one!
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
          {:outcome :leased
           :run-id (:run_id row) :chunk-id (:chunk_id row)
           :execution-plan-root (:execution_plan_root row)
           :expected-work-root (:expected_work_root row)
           :worker-id (:worker_id row) :fence (:fence row) :attempt (:attempt row)
           :lease-expires-at (:lease_expires_at row)})))))

(defn complete-chunk!
  "Accept a detached result only under the current run state, lease, and fence.
   Exact accepted-result replays are idempotent; sensitivity level is accepted
   provenance and therefore part of that immutable identity."
  [ds {:keys [run-id execution-plan-root chunk-id expected-work-root worker-id fence
              result-root result-manifest-root result-ref sensitivity-root sensitivity-level]}]
  (doseq [[k v] [[:run-id run-id] [:execution-plan-root execution-plan-root]
                 [:chunk-id chunk-id] [:expected-work-root expected-work-root]
                 [:worker-id worker-id] [:result-root result-root]
                 [:result-manifest-root result-manifest-root] [:result-ref result-ref]
                 [:sensitivity-root sensitivity-root]]]
    (nonblank! k v))
  (positive! :fence fence)
  (sensitivity-level! sensitivity-level)
  (jdbc/with-transaction [tx ds]
    (let [run (run-row-for-update! tx run-id "FOR SHARE")
          row (jdbc/execute-one! tx
                                 ["SELECT c.*, r.execution_plan_root, clock_timestamp() AS db_now
                                   FROM benchmark_execution_chunk c
                                   JOIN benchmark_execution_run r ON r.run_id = c.run_id
                                   WHERE c.run_id = ? AND c.chunk_id = ? FOR UPDATE"
                                  run-id chunk-id]
                                 builder)]
      (cond
        (nil? row) {:outcome :rejected :reason :unknown-chunk}
        (= "failed" (:coordination_status run)) {:outcome :rejected :reason :run-failed}
        (not= execution-plan-root (:execution_plan_root row))
        {:outcome :rejected :reason :execution-plan-root-mismatch}
        (not= expected-work-root (:expected_work_root row))
        {:outcome :rejected :reason :expected-work-root-mismatch}
        (= "completed" (:status row))
        (if-not (= (long fence) (long (:fence row)))
          {:outcome :rejected :reason :stale-fence :current-fence (:fence row)}
          (let [same? (= [execution-plan-root chunk-id expected-work-root result-root
                          result-manifest-root sensitivity-root (level->db sensitivity-level)]
                         [(:execution_plan_root row) (:chunk_id row) (:expected_work_root row)
                          (:result_root row) (:result_manifest_root row) (:sensitivity_root row)
                          (:sensitivity_level row)])]
            (if same?
              {:outcome :idempotent-completion :result-ref (:result_ref row)
               :result-root (:result_root row) :result-manifest-root (:result_manifest_root row)}
              {:outcome :rejected :reason :completed-result-conflict})))
        (not= "dispatching" (:coordination_status run))
        {:outcome :rejected :reason :run-not-runnable}
        (not= "leased" (:status row)) {:outcome :rejected :reason :chunk-not-leased}
        (not= (long fence) (long (:fence row)))
        {:outcome :rejected :reason :stale-fence :current-fence (:fence row)}
        (not= worker-id (:worker_id row)) {:outcome :rejected :reason :lease-owner-mismatch}
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
                              (level->db sensitivity-level) run-id chunk-id]
                             builder)
          {:outcome :completed :run-id run-id :chunk-id chunk-id
           :result-root result-root :result-manifest-root result-manifest-root
           :result-ref result-ref :sensitivity-root sensitivity-root})))))

(defn mark-run-execution-complete!
  "Mark a dispatching run execution-complete only when every frozen chunk is
   accepted as completed. Canonical publication remains outside this operation."
  [ds run-id]
  (nonblank! :run-id run-id)
  (jdbc/with-transaction [tx ds]
    (let [run (run-row-for-update! tx run-id "FOR UPDATE")
          counts (jdbc/execute-one! tx
                                    ["SELECT count(*) AS total,
                                             count(*) FILTER (WHERE status = 'completed') AS completed
                                      FROM benchmark_execution_chunk WHERE run_id = ?" run-id]
                                    builder)]
      (cond
        (nil? run) {:outcome :rejected :reason :unknown-run}
        (= "execution-complete" (:coordination_status run)) {:outcome :idempotent-execution-complete}
        (not= "dispatching" (:coordination_status run)) {:outcome :rejected :reason :run-not-runnable}
        (not (and (= (:expected_chunk_count run) (:total counts))
                  (= (:total counts) (:completed counts))))
        {:outcome :rejected :reason :incomplete-run
         :expected-chunk-count (:expected_chunk_count run)
         :completed-chunk-count (:completed counts)}
        :else
        (do (jdbc/execute-one! tx
                               ["UPDATE benchmark_execution_run
                                 SET coordination_status = 'execution-complete' WHERE run_id = ?" run-id]
                               builder)
            {:outcome :execution-complete :run-id run-id
             :chunk-count (:expected_chunk_count run)})))))

(defn fail-run!
  "Atomically terminally fail a dispatching run. Existing leases cannot be
   completed afterwards because completion checks the run state under a shared
   run lock."
  [ds run-id]
  (nonblank! :run-id run-id)
  (jdbc/with-transaction [tx ds]
    (let [run (run-row-for-update! tx run-id "FOR UPDATE")]
      (cond
        (nil? run) {:outcome :rejected :reason :unknown-run}
        (= "failed" (:coordination_status run)) {:outcome :idempotent-failure}
        (not= "dispatching" (:coordination_status run)) {:outcome :rejected :reason :run-not-runnable}
        :else
        (do (jdbc/execute-one! tx
                               ["UPDATE benchmark_execution_run
                                 SET coordination_status = 'failed' WHERE run_id = ?" run-id]
                               builder)
            {:outcome :failed :run-id run-id})))))

(defn resolve-chunk-completion!
  "Read an authoritative accepted completion for restart/lost-response recovery.
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
       :sensitivity-level (db->level (:sensitivity_level row))})))
