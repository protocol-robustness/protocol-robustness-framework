(ns resolver-sim.resubmission.postgres-admission-store
  "PostgreSQL implementation of ResubmissionAdmissionStore.

   One row stores one family partition's complete pure admission state; the row
   is the authoritative serialization point. Every mutation locks that row in a
   SERIALIZABLE transaction, runs the same pure transition as the in-memory
   reference adapter, and replaces the state blob (and the structured
   fence/version authority columns) before commit. This makes family
   head/version/finalization publication one database transaction and makes the
   database the network-wide linearization boundary — NOT any JVM mutex.

   Authoritative time: each operation obtains exactly ONE PostgreSQL time value
   inside the authoritative transaction and reuses that exact value throughout
   legacy normalization, lazy expiry, transition evaluation and CAS/write. It
   never reads the host wall clock, and it never calls `now()`/`clock_timestamp()`
   separately per step.

   Restart-safe fencing: deployments claiming restart-safe fences must use a
   PostgreSQL recovery model that never rolls back this table's monotonic fence
   state (see docs/operations/postgres-admission.md for the RDS/Multi-AZ recovery
   contract)."
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [resolver-sim.resubmission.admission :as admission]
            [resolver-sim.resubmission.admission-store :as store]))

(def partition-table "prf_resubmission_admission_partition")

(defn ensure-schema!
  "Idempotently apply forward-only migrations for the admission store.

   This is deliberately EXPLICIT and never a load-time side effect. In
   production it is invoked by a single migration job, never by arbitrary
   application instances on startup (see resolver-sim.db.migrate and the
   Phase 8 deployment contract in docs/operations/postgres-admission.md)."
  [datasource]
  (let [migrate-ns (requiring-resolve 'resolver-sim.db.migrate/migrate!)]
    (migrate-ns datasource))
  datasource)

(def ^:private max-transaction-attempts 5)

(defn- retryable-transaction-error?
  "PostgreSQL requires retry of the complete SERIALIZABLE transaction for
   serialization failures and deadlocks. Walk causes because JDBC wrappers may
   wrap the SQLException."
  [throwable]
  (loop [cause throwable]
    (cond
      (nil? cause) false
      (and (instance? java.sql.SQLException cause)
           (contains? #{"40001" "40P01"}
                      (.getSQLState ^java.sql.SQLException cause))) true
      :else (recur (.getCause ^Throwable cause)))))

(defn- with-transaction-retry!
  [f]
  (loop [attempt 1]
    (let [result (try
                   {:value (f)}
                   (catch Throwable e {:error e}))]
      (if-let [error (:error result)]
        (if (and (< attempt max-transaction-attempts)
                 (retryable-transaction-error? error))
          (do (Thread/sleep (+ 5 (* 5 attempt)))
              (recur (inc attempt)))
          (throw error))
        (:value result)))))

(defn- row-opts [] {:builder-fn rs/as-unqualified-maps})
(defn- encode [state] (pr-str state))
(defn- decode [s] (edn/read-string s))
(defn- partition-id [family-id] (pr-str (admission/partition-key family-id)))

(defn- authoritative-now!
  "Obtain ONE authoritative PostgreSQL time value inside the current (locked)
   transaction. Uses `clock_timestamp()` so lazy-expiry/deadline decisions
   reflect committed wall-clock progression at this transaction, captured once."
  [tx]
  (java.time.Instant/ofEpochMilli
   (:clock_millis (jdbc/execute-one!
                   tx ["SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint AS clock_millis"]
                   (row-opts)))))

(defn- locked-state!
  [tx family-id]
  (let [key (partition-id family-id)
        insert-sql (str "INSERT INTO " partition-table
                        " (partition_key, state_edn) VALUES (?, ?)
                         ON CONFLICT (partition_key) DO NOTHING")
        select-sql (str "SELECT state_edn, concurrency_fence, family_version FROM "
                        partition-table " WHERE partition_key = ? FOR UPDATE")]
    ;; Insert first makes a missing partition lockable without a read/create
    ;; race. The row is then locked for all state reads and mutations.
    (jdbc/execute! tx [insert-sql key (encode (admission/empty-partition family-id))])
    (let [row (jdbc/execute-one!
               tx [select-sql key]
               (row-opts))]
      {:key key
       :row row
       :state (admission/normalize-state (decode (:state_edn row)))})))

(defn- persist-state!
  [tx key next-state]
  ;; Write the canonical payload plus the structured authority columns used for
  ;; DB-enforced/exposed fence & version.
  (jdbc/execute! tx
                 [(str "UPDATE " partition-table
                       " SET state_edn = ?, concurrency_fence = ?,
                           family_version = ?
                       WHERE partition_key = ?")
                  (encode next-state)
                  (:concurrency/fence next-state)
                  (:family/version next-state)
                  key]))

(defn- transact-state!
  [datasource family-id transition & args]
  (with-transaction-retry!
    #(jdbc/with-transaction [tx datasource {:isolation :serializable}]
       (let [{:keys [key state]} (locked-state! tx family-id)
             now (authoritative-now! tx)
             state (admission/terminalize-expired state now)
             args (cond
                    (= transition admission/reserve-transition)
                    [(assoc (first args)
                            :reservation/issued-at (str now)
                            :reservation/expires-at (str (.plusSeconds now admission/reservation-lease-seconds)))]
                    (= transition admission/expire-transition)
                    (conj (vec args) now)
                    :else args)
             outcome (apply transition state args)
             next-state (:state outcome)]
         (when-not (= state next-state)
           (persist-state! tx key next-state))
         (dissoc outcome :state)))))

(defn- read-state!
  [datasource family-id]
  ;; A read is also serialized against a concurrent family mutation so recovery
  ;; sees one authoritative reservation/finalization view. Lazy expiry is a
  ;; terminal authority transition and therefore must be persisted here too.
  (with-transaction-retry!
    #(jdbc/with-transaction [tx datasource {:isolation :serializable}]
       (let [{:keys [key state]} (locked-state! tx family-id)
             now (authoritative-now! tx)
             next-state (admission/terminalize-expired state now)]
         (when-not (= state next-state)
           (persist-state! tx key next-state))
         next-state))))

(deftype PostgresAdmissionStore [datasource]
  store/ResubmissionAdmissionStore
  (snapshot! [_ family-id]
    (admission/snapshot (read-state! datasource family-id)))
  (reserve! [_ request]
    (let [key (:concurrency/partition-key request)]
      (if-not (admission/valid-partition-key? key)
        {:concurrency/outcome :rejected :reason :partition-mismatch}
        (transact-state! datasource (second key) admission/reserve-transition request))))
  (finalize! [_ request]
    (let [key (:concurrency/partition-key request)]
      (if-not (admission/valid-partition-key? key)
        {:concurrency/outcome :rejected :reason :partition-mismatch}
        (transact-state! datasource (second key) admission/finalize-transition request))))
  (abort! [_ family-id reservation-id fence]
    (transact-state! datasource family-id admission/withdraw-transition
                     reservation-id fence :aborted))
  (expire! [_ family-id reservation-id fence]
    (transact-state! datasource family-id admission/expire-transition
                     reservation-id fence))
  (compact! [_ family-id]
    (transact-state! datasource family-id admission/compact-transition))
  (resolve-finalization! [_ family-id reservation-id]
    (let [state (read-state! datasource family-id)
          reservation (get-in state [:admission/reservations reservation-id])
          finalization (get-in state [:admission/finalizations reservation-id])]
      {:concurrency/partition-key (:concurrency/partition-key state)
       :concurrency/fence (:concurrency/fence state)
       :family/version (:family/version state)
       :reservation reservation
       :finalization finalization}))
  (concurrency-capabilities [_]
    {:concurrency/adapter :postgresql
     :concurrency/per-family-cas? true
     :concurrency/cross-family-parallel? true
     :concurrency/durable? true
     :concurrency/multi-process-linearizable? true
     :concurrency/restart-safe-fences? :deployment-recovery-model-required}))

(defn postgres-store
  "Create an adapter over a configured JDBC datasource (e.g. from
   resolver-sim.db.pool/pool). Call `ensure-schema!`/the migration job first."
  [datasource]
  (PostgresAdmissionStore. datasource))