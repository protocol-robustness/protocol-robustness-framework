(ns resolver-sim.resubmission.postgres-admission-store-test
  "Integration tests for the PostgreSQL ResubmissionAdmissionStore adapter.

   Requires a running PostgreSQL instance. By default the tests connect to
   localhost:5433 with user/password postgres/postgres (the docker-compose
   service). Override with DATABASE_URL:

     export DATABASE_URL='jdbc:postgresql://db-host:5432/postgres?user=me&password=secret'

   Run with:
     bb test:integration:postgres
     clojure -M:test -e \"(require 'resolver-sim.resubmission.postgres-admission-store-test) (clojure.test/run-tests 'resolver-sim.resubmission.postgres-admission-store-test)\"
   "
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc.result-set :as rs]
            [resolver-sim.resubmission.admission :as admission]
            [resolver-sim.resubmission.admission-store :as store]
            [resolver-sim.resubmission.admission-workflow :as workflow]
            [resolver-sim.resubmission.postgres-admission-store :as pg]
            [resolver-sim.db.pool :as pool]
            [next.jdbc :as jdbc]))

(def ^:dynamic *ds* nil)

(defn- db-now-ms []
  (:clock_millis (jdbc/execute-one!
                  *ds* ["SELECT (extract(epoch FROM clock_timestamp())*1000)::bigint AS clock_millis"]
                  {:builder-fn rs/as-unqualified-maps})))

(defn force-expired!
  "Backdate one active reservation's lease into the past using the PostgreSQL
   clock, so the DB-authoritative clock considers it due. This mirrors how a
   lease lapses in production and lets the adapter materialize expiry via its
   authoritative store-time rather than the test-runner clock."
  [family-id reservation-id]
  (let [key (pr-str (admission/partition-key family-id))
        row (jdbc/execute-one!
             *ds* ["SELECT state_edn FROM prf_resubmission_admission_partition
                    WHERE partition_key = ?" key]
             {:builder-fn rs/as-unqualified-maps})
        state (edn/read-string (:state_edn row))
        now (java.time.Instant/ofEpochMilli (db-now-ms))
        reservation (get-in state [:admission/reservations reservation-id])]
    (when-not reservation
      (throw (ex-info "reservation not found" {:reservation-id reservation-id
                                               :family-id family-id})))
    (let [next (assoc-in state [:admission/reservations reservation-id]
                         (assoc reservation :reservation/expires-at
                                (str (.minusSeconds now 5))))]
      (jdbc/execute! *ds*
                     ["UPDATE prf_resubmission_admission_partition
                       SET state_edn = ? WHERE partition_key = ?"
                      (pr-str next) key]))))

(def family "sha256:FAMILY")
(defn r [c] (str "sha256:" (apply str (repeat 64 c))))

(defn request
  [s id candidate]
  {:concurrency/partition-key (:concurrency/partition-key s)
   :concurrency/snapshot-root (:concurrency/snapshot-root s)
   :concurrency/expected-state-version (:concurrency/expected-state-version s)
   :concurrency/idempotency-key id
   :reservation/candidate-root candidate
   :reservation/validation-root (r "v")
   :reservation/proposed-ordering-root (r "o")})

(defn validation-for [s candidate]
  {:profile-id :strict :profile-version 1
   :checks (mapv (fn [id] {:check/id id :valid? true
                           :validated-against/root (:concurrency/snapshot-root s)
                           :validated-against/version (:concurrency/expected-state-version s)
                           :validated-against/candidate-root candidate})
                 admission/required-check-order)})

(defn finalize-request [s reservation receipt]
  {:concurrency/partition-key (:concurrency/partition-key s)
   :concurrency/expected-state-version (:concurrency/expected-state-version s)
   :reservation/id (:reservation/id reservation)
   :concurrency/fence (:reservation/fence reservation)
   :reservation/candidate-root (:reservation/candidate-root reservation)
   :reservation/validation-root (:reservation/validation-root reservation)
   :reservation/proposed-ordering-root (:reservation/proposed-ordering-root reservation)
   :signing/payload-root (:signing/payload-root (admission/signing-payload reservation))
   :receipt/root receipt
   :authorization/evidence-root receipt})

(def default-db-url
  "jdbc:postgresql://localhost:5433/postgres?user=postgres&password=postgres")

(defn database-url []
  (or (System/getenv "DATABASE_URL")
      default-db-url))

(defn skip-if-no-db [f]
  (try
    (let [ds (pool/pool (database-url) {})]
      (jdbc/execute-one! ds ["SELECT 1"])
      (.close ds)
      (f))
    (catch Exception e
      (println "PostgreSQL integration tests skipped (" (.getMessage e) ")")
      (flush)
      (System/exit 0))))

;; ---------------------------------------------------------------------------
;; Fixture — per-namespace schema setup + teardown
;; ---------------------------------------------------------------------------

(def ^:dynamic *ds* nil)

(defn pg-fixture [f]
  (let [ds (pool/pool (database-url) {})]
    (pg/ensure-schema! ds)
    (binding [*ds* ds]
      (try
        (f)
        (finally
          (jdbc/execute! ds ["TRUNCATE TABLE prf_resubmission_admission_partition"])
          (.close ds))))))

(def ^:dynamic *ds* nil)

(use-fixtures :once skip-if-no-db)
(use-fixtures :each pg-fixture)

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest reservations-fence-idempotency-and-finalization
  (let [db (pg/postgres-store *ds*)
        s0 (store/snapshot! db family)
        reserved (store/reserve! db (request s0 "idem-a" (r "a")))
        reservation (:reservation reserved)
        duplicate (store/reserve! db (request s0 "idem-a" (r "a")))
        conflict (store/reserve! db (request s0 "idem-a" (r "b")))
        finalized (store/finalize! db (finalize-request s0 reservation (r "r")))
        retry (store/finalize! db (finalize-request s0 reservation (r "r")))]
    (is (= :reserved (:concurrency/outcome reserved)))
    (is (= 1 (:reservation/fence reservation)))
    (is (= :idempotent-replay (:concurrency/outcome duplicate)))
    (is (= :idempotency-conflict (:reason conflict)))
    (is (= :finalized (:concurrency/outcome finalized)))
    (is (= :idempotent-replay (:concurrency/outcome retry)))
    (is (= 1 (:family/version (store/snapshot! db family))))))

(deftest expired-reservation-retries-with-a-new-fence-but-keeps-its-binding
  (let [db (pg/postgres-store *ds*)
        s0 (store/snapshot! db family)
        first (:reservation (store/reserve! db (request s0 "idem-a" (r "a"))))
        _ (force-expired! family (:reservation/id first))
        _ (store/expire! db family (:reservation/id first) (:reservation/fence first))
        closed (store/reserve! db (request s0 "idem-a" (r "a")))
        retry (:reservation (store/reserve! db (request s0 "idem-a-retry" (r "a"))))
        substitution (store/reserve! db (request s0 "idem-a" (r "b")))]
    (is (= 1 (:reservation/fence first)))
    (is (= :idempotency-attempt-closed (:reason closed)))
    (is (= 2 (:reservation/fence retry)))
    (is (= :idempotency-conflict (:reason substitution)))))

(deftest stale-worker-cannot-finalize-after-fence-replacement
  (let [db (pg/postgres-store *ds*)
        s0 (store/snapshot! db family)
        a (:reservation (store/reserve! db (request s0 "idem-a" (r "a"))))
        _ (force-expired! family (:reservation/id a))
        _ (store/expire! db family (:reservation/id a) (:reservation/fence a))
        b (:reservation (store/reserve! db (request s0 "idem-b" (r "b"))))
        b-final (store/finalize! db (finalize-request s0 b (r "b")))
        stale (store/finalize! db (finalize-request s0 a (r "a")))]
    (is (= 1 (:reservation/fence a)))
    (is (= 2 (:reservation/fence b)))
    (is (= :finalized (:concurrency/outcome b-final)))
    (is (= :stale-fence (:reason stale)))
    (is (= (r "b") (:family/head (store/snapshot! db family))))))

(deftest workflow-reserves-signs-verifies-and-finalizes-without-signer-arbitration
  (let [db (pg/postgres-store *ds*)
        s (store/snapshot! db family)
        candidate (r "c")
        sign! (fn [payload] {:receipt/root (r "r")
                             :signing/payload-root (:signing/payload-root payload)})
        result (workflow/attempt! {:admission-store db :family-id family
                                   :candidate-root candidate :idempotency-key "idem-workflow"
                                   :proposed-ordering-root (r "o")
                                   :validation (validation-for s candidate)
                                   :sign! sign! :verify-signature! (constantly true)})]
    (is (= :finalized (:concurrency/outcome result)))
    (is (= 1 (:family/version (store/snapshot! db family))))
    (is (= (r "r") (:family/head (store/snapshot! db family))))))

(deftest authoritative-finalization-resolution-replays-a-lost-response
  (let [db (pg/postgres-store *ds*)
        s (store/snapshot! db family)
        reservation (:reservation (store/reserve! db (request s "idem-final" (r "a"))))
        final-request (finalize-request s reservation (r "r"))
        _ (store/finalize! db final-request)
        resolved (workflow/resolve-finalization! db family final-request)]
    (is (= :finalized (:concurrency/outcome resolved)))
    (is (= (r "r") (get-in resolved [:finalization :receipt/root])))
    (is (= 1 (:family/version (store/snapshot! db family))))))

(deftest abort-is-fenced-and-idempotent
  (let [db (pg/postgres-store *ds*)
        s (store/snapshot! db family)
        reservation (:reservation (store/reserve! db (request s "idem-abort" (r "a"))))
        first (store/abort! db family (:reservation/id reservation) (:reservation/fence reservation))
        retry (store/abort! db family (:reservation/id reservation) (:reservation/fence reservation))
        stale (store/abort! db family (:reservation/id reservation) 0)]
    (is (= :aborted (:concurrency/outcome first)))
    (is (= :idempotent-replay (:concurrency/outcome retry)))
    (is (= :stale-fence (:reason stale)))))

(deftest distinct-families-do-not-share-a-cas-partition
  (let [db (pg/postgres-store *ds*)
        families ["sha256:FA" "sha256:FB" "sha256:FC"]
        results (mapv (fn [f]
                        (future
                          (let [s (store/snapshot! db f)]
                            (store/reserve! db (request s (str "idem-" f) (r "c"))))))
                      families)]
    (doseq [result (mapv deref results)]
      (is (= :reserved (:concurrency/outcome result)))
      (is (= 1 (get-in result [:reservation :reservation/fence]))))))
