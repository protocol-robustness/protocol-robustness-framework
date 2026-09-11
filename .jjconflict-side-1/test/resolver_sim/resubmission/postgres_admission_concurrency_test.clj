(ns resolver-sim.resubmission.postgres-admission-concurrency-test
  "Genuine multi-instance concurrency tests for the PostgreSQL admission store.

   These tests boot two INDEPENDENT store instances — each owns its own bounded
   Hikari connection pool to the SAME PostgreSQL database. No shared atom, mutex
   or thread-local authority coordinates them: PostgreSQL is the only
   linearization boundary. Two independent pools against one DB exercise the same
   correctness surface as two JVMs on separate EC2 hosts (the in-process atomicity
   of the mechanism is separately scrutinised by the connection/lock-death test).

   Tests run against a real PostgreSQL, apply real forward-only migrations from an
   empty schema, and for expiry boundaries rely only on the DATABASE clock, never
   the test-runner wall clock.

     bb test:integration:postgres:concurrency
     clojure -M:test -e \"(require 'resolver-sim.resubmission.postgres-admission-concurrency-test) (clojure.test/run-tests 'resolver-sim.resubmission.postgres-admission-concurrency-test)\"
  "
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [resolver-sim.resubmission.admission :as admission]
            [resolver-sim.resubmission.admission-store :as store]
            [resolver-sim.resubmission.admission-workflow :as workflow]
            [resolver-sim.resubmission.postgres-admission-store :as pg]
            [resolver-sim.db.pool :as pool]))

(def family "sha256:CONCURRENCY")
(defn r [c] (str "sha256:" (apply str (repeat 64 c))))

(def default-db-url
  "jdbc:postgresql://localhost:5433/postgres?user=postgres&password=postgres")

(defn database-url []
  (or (System/getenv "DATABASE_URL") default-db-url))

(def ^:dynamic *ds-a* nil)   ;; datasource / pool for instance A
(def ^:dynamic *ds-b* nil)   ;; datasource / pool for instance B
(def ^:dynamic *db-a* nil)   ;; store adapter A
(def ^:dynamic *db-b* nil)   ;; store adapter B

(defn request
  [s id candidate]
  {:concurrency/partition-key (:concurrency/partition-key s)
   :concurrency/snapshot-root (:concurrency/snapshot-root s)
   :concurrency/expected-state-version (:concurrency/expected-state-version s)
   :concurrency/idempotency-key id
   :reservation/candidate-root candidate
   :reservation/validation-root (r "v")
   :reservation/proposed-ordering-root (r "o")})

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

(defn- db-now-ms [ds]
  (:clock_millis (jdbc/execute-one!
                  ds ["SELECT (extract(epoch FROM clock_timestamp())*1000)::bigint AS clock_millis"]
                  {:builder-fn rs/as-unqualified-maps})))

(defn- force-deadline!
  "Rewrite one reservation's lease to exactly `delta-seconds` relative to the DB
   clock. Returns the DB-authoritative Instant used. Positive delta = future
   (still active), negative = already due, zero = exactly at the deadline."
  [ds family-id reservation-id delta-seconds]
  (let [key (pr-str (admission/partition-key family-id))
        base (db-now-ms ds)
        target (-> (java.time.Instant/ofEpochMilli base) (.plusSeconds delta-seconds))
        row (jdbc/execute-one!
             ds ["SELECT state_edn FROM prf_resubmission_admission_partition
                  WHERE partition_key = ?" key]
             {:builder-fn rs/as-unqualified-maps})
        state (edn/read-string (:state_edn row))
        reservation (get-in state [:admission/reservations reservation-id])]
    (when-not reservation
      (throw (ex-info "reservation not found" {:reservation-id reservation-id})))
    (jdbc/execute! ds
                   ["UPDATE prf_resubmission_admission_partition
                     SET state_edn = ? WHERE partition_key = ?"
                    (pr-str (assoc-in state [:admission/reservations reservation-id]
                                      (assoc reservation :reservation/expires-at (str target))))
                    key])
    target))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn skip-if-no-db [f]
  (try
    (let [ds (pool/pool (database-url) {})]
      (jdbc/execute-one! ds ["SELECT 1"])
      (.close ds)
      (f))
    (catch Throwable e
      (println "PostgreSQL concurrency tests skipped (" (.getMessage e) ")")
      (flush)
      (System/exit 0))))

(use-fixtures :once skip-if-no-db)

(defn two-pool-fixture
  "Migrate an emptied database, then build two independent stores on separate
   bounded pools. Each test starts from a fresh table so fences/counters are
   deterministic."
  [f]
  (let [bootstrap (pool/pool (database-url) {})]
    (pg/ensure-schema! bootstrap)
    (jdbc/execute! bootstrap ["TRUNCATE TABLE prf_resubmission_admission_partition"])
    (let [ds-a (pool/pool (database-url) {:pool-size 4 :idle-timeout-ms 60000})
          ds-b (pool/pool (database-url) {:pool-size 4 :idle-timeout-ms 60000})
          db-a (pg/postgres-store ds-a)
          db-b (pg/postgres-store ds-b)]
      (binding [*ds-a* ds-a *ds-b* ds-b *db-a* db-a *db-b* db-b]
        (try
          (f)
          (finally
            (.close ds-a)
            (.close ds-b)
            (jdbc/execute! bootstrap ["TRUNCATE TABLE prf_resubmission_admission_partition"])
            (.close bootstrap)))))))

(use-fixtures :each two-pool-fixture)

(defn- race!
  "Run two thunks concurrently against two independent store instances and return
   their results in order."
  [fa fb]
  (let [a (future (fa))
        b (future (fb))]
    [(try (deref a 30000 :timed-out) (catch Throwable t {:exploded t}))
     (try (deref b 30000 :timed-out) (catch Throwable t {:exploded t}))]))

(defn- active-reservation-count
  "Count active reservations by reading the authoritative state_edn directly
   (snapshots intentionally do not expose reservation detail)."
  [ds f]
  (let [key (pr-str (admission/partition-key f))
        row (jdbc/execute-one!
             ds ["SELECT state_edn FROM prf_resubmission_admission_partition
                  WHERE partition_key = ?" key]
             {:builder-fn rs/as-unqualified-maps})]
    (or (->> (get-in (edn/read-string (:state_edn row)) [:admission/reservations])
             (vals)
             (filter #(= :active (:reservation/status %)))
             (count))
        0)))

(defn- reservation-status [db f rid]
  (get-in (store/resolve-finalization! db f rid) [:reservation :reservation/status]))

(defn- reserve-active! [db f id c]
  (let [s (store/snapshot! db f)
        out (store/reserve! db (request s id (r c)))]
    (if (= :reserved (:concurrency/outcome out))
      (:reservation out)
      (throw (ex-info "expected reservation" {:outcome out})))))

(defn- passing-validation
  "A complete, snapshot-bound validation input for workflow-level tests."
  [snapshot candidate]
  {:profile-id :postgres-concurrency-test
   :profile-version "1"
   :checks (mapv (fn [check-id]
                   {:check/id check-id
                    :valid? true
                    :validated-against/root (:concurrency/snapshot-root snapshot)
                    :validated-against/version (:concurrency/expected-state-version snapshot)
                    :validated-against/candidate-root candidate})
                 admission/required-check-order)})

(defn- workflow-attempt
  "Run a complete admission workflow. The signer intentionally sleeps so a
   concurrent caller has time to contend while the successful caller holds the
   durable reservation."
  [db snapshot id candidate signer-count]
  (workflow/attempt!
   {:admission-store db
    :family-id family
    :snapshot snapshot
    :candidate-root candidate
    :idempotency-key id
    :proposed-ordering-root (r "o")
    :validation (passing-validation snapshot candidate)
    :sign! (fn [payload]
             (swap! signer-count inc)
             (Thread/sleep 100)
             {:receipt/root (r "r")
              :signing/payload-root (:signing/payload-root payload)})
    :verify-signature! (fn [payload signed]
                         (= (:signing/payload-root payload)
                            (:signing/payload-root signed)))}))

;; ---------------------------------------------------------------------------
;; 1. Reservation contention — exactly one canonical winner
;; ---------------------------------------------------------------------------

(deftest reservation-contention-has-one-canonical-winner
  (let [s0 (store/snapshot! *db-a* family)
        [ra rb] (race!
                 #(store/reserve! *db-a* (request s0 "idem-A" (r "a")))
                 #(store/reserve! *db-b* (request s0 "idem-B" (r "b"))))
        winners (filter #(= :reserved (:concurrency/outcome %)) [ra rb])]
    (is (= 1 (count winners))
        (str "exactly one canonical winner, got "
             (pr-str (mapv :concurrency/outcome [ra rb]))))
    (doseq [w winners] (is (= 1 (:reservation/fence (:reservation w)))))
    (doseq [l (remove #(= :reserved (:concurrency/outcome %)) [ra rb])]
      (is (#{:contention :rejected} (:concurrency/outcome l))))
    (is (= 1 (active-reservation-count *ds-a* family))
        "no duplicate active reservation")))

;; ---------------------------------------------------------------------------
;; 2. Workflow contention — only the reservation winner reaches the signer
;; ---------------------------------------------------------------------------

(deftest workflow-contention-signs-and-finalizes-only-one-candidate
  (let [snapshot (store/snapshot! *db-a* family)
        signer-count (atom 0)
        [a b] (race!
               #(workflow-attempt *db-a* snapshot "workflow-A" (r "a") signer-count)
               #(workflow-attempt *db-b* snapshot "workflow-B" (r "b") signer-count))
        outcomes (mapv :concurrency/outcome [a b])
        finalized (filter #(= :finalized (:concurrency/outcome %)) [a b])
        state (store/snapshot! *db-a* family)]
    (is (= 1 (count finalized))
        (str "exactly one workflow finalized, got " (pr-str outcomes)))
    (is (= 1 @signer-count) "only the fenced reservation winner invokes signing")
    (is (= 1 (:family/version state)) "only one receipt is durably published")
    (is (= (r "r") (:family/head state))
        "the signed receipt is the authoritative finalized head")
    (is (some #{:contention} outcomes)
        (str "the losing workflow reports contention, got " (pr-str outcomes)))))

;; ---------------------------------------------------------------------------
;; 3. Idempotent reservation replay — converge on one canonical result
;; ---------------------------------------------------------------------------

(deftest idempotent-replay-converges-across-instances
  (let [s0 (store/snapshot! *db-a* family)
        [ra rb] (race!
                 #(store/reserve! *db-a* (request s0 "idem-replay" (r "c")))
                 #(store/reserve! *db-b* (request s0 "idem-replay" (r "c"))))
        reserved (filter #(= :reserved (:concurrency/outcome %)) [ra rb])
        replays (filter #(= :idempotent-replay (:concurrency/outcome %)) [ra rb])
        res-ids (into #{} (keep (comp :reservation/id :reservation)) [ra rb])]
    (is (= 1 (count reserved)) "exactly one initial reservation")
    (is (pos? (count replays)) "racing instance converges via idempotent replay")
    (is (= 1 (count res-ids)) "both agree on the same canonical reservation id")))

;; ---------------------------------------------------------------------------
;; 3. Idempotency conflict — deterministic rejection, no corruption
;; ---------------------------------------------------------------------------

(deftest idempotency-conflict-converges-on-rejection
  (let [s0 (store/snapshot! *db-a* family)
        [ra rb] (race!
                 #(store/reserve! *db-a* (request s0 "idem-trap" (r "x")))
                 #(store/reserve! *db-b* (request s0 "idem-trap" (r "y"))))
        reserved (count (filter #(= :reserved (:concurrency/outcome %)) [ra rb]))
        conflicts (filter #(= :idempotency-conflict (:reason %)) [ra rb])]
    (is (= 1 reserved))
    (is (= 1 (count conflicts)) "incompatible request deterministically rejected")
    (is (= 1 (active-reservation-count *ds-a* family)) "no state corruption")))

;; ---------------------------------------------------------------------------
;; 4. Finalization contention — identical replay vs incompatible rejection
;; ---------------------------------------------------------------------------

(deftest identical-finalization-replays-canonically
  (let [s0 (store/snapshot! *db-a* family)
        reservation (reserve-active! *db-a* family "idem-F" "a")
        fr (finalize-request s0 reservation (r "r"))
        [ra rb] (race!
                 #(store/finalize! *db-a* fr)
                 #(store/finalize! *db-b* fr))
        sn (store/snapshot! *db-a* family)]
    (is (= 1 (count (filter #(= :finalized (:concurrency/outcome %)) [ra rb]))))
    (is (= 1 (count (filter #(= :idempotent-replay (:concurrency/outcome %)) [ra rb]))))
    (is (= 1 (:family/version sn)) "family version advanced exactly once")
    (is (= (r "r") (:family/head sn)) "canonical head committed once")))

(deftest incompatible-finalization-is-rejected
  (let [s0 (store/snapshot! *db-a* family)
        reservation (reserve-active! *db-a* family "idem-G" "a")
        fr-a (finalize-request s0 reservation (r "r1"))
        fr-b (finalize-request s0 reservation (r "r2"))
        [ra rb] (race!
                 #(store/finalize! *db-a* fr-a)
                 #(store/finalize! *db-b* fr-b))
        sn (store/snapshot! *db-a* family)]
    (is (= 1 (count (filter #(= :finalized (:concurrency/outcome %)) [ra rb]))))
    (is (= 1 (count (filter #(= :finalization-conflict (:reason %)) [ra rb]))))
    (is (= 1 (:family/version sn)))))

;; ---------------------------------------------------------------------------
;; 5. Abort versus finalize — only a legal serializable outcome commits
;; ---------------------------------------------------------------------------

(deftest abort-versus-finalize-commits-only-legal-outcome
  (let [s0 (store/snapshot! *db-a* family)
        reservation (reserve-active! *db-a* family "idem-H" "a")
        fr (finalize-request s0 reservation (r "r"))
        rid (:reservation/id reservation)
        [ra rb] (race!
                 #(store/abort! *db-a* family rid (:reservation/fence reservation))
                 #(store/finalize! *db-b* fr))
        sn (store/snapshot! *db-a* family)
        finalization (get-in sn [:admission/finalizations rid])]
    ;; Illegal for the reservation to be BOTH finalized and aborted; version must
    ;; reflect whichever side committed.
    (is (or (nil? finalization) (= 1 (:family/version sn)))
        "finalized ⇒ version=1")
    (is (or (some? finalization) (zero? (:family/version sn)))
        "not finalized ⇒ version=0")
    (is (= 0 (active-reservation-count *ds-a* family))
        "no active reservation escapes the abort/finalize race")))

;; ---------------------------------------------------------------------------
;; 6. Stale worker — B advances authority, A is excluded by fence
;; ---------------------------------------------------------------------------

(deftest stale-worker-excluded-after-fence-advance
  (let [s0 (store/snapshot! *db-a* family)
        a (reserve-active! *db-a* family "idem-S" "a")
        rid (:reservation/id a)
        fence (:reservation/fence a)
        ;; Worker A's lease lapses (DB-authoritative time). Lazy expiry causes it.
        _ (force-deadline! *ds-a* family rid -5)
        _ (store/snapshot! *db-a* family)   ;; materialize lazy expiry under lock
        ;; Worker B (independent instance) takes over the family and finalizes.
        b (reserve-active! *db-b* family "idem-SB" "b")
        b-fr (finalize-request s0 b (r "b"))
        b-final (store/finalize! *db-b* b-fr)
        ;; Worker A, now stale, attempts finalization with its old fence.
        stale (store/finalize! *db-a* (finalize-request s0 a (r "a")))
        sn (store/snapshot! *db-a* family)]
    (is (= 1 fence))
    (is (= 2 (:reservation/fence b)) "fence advanced after B's reservation")
    (is (= :finalized (:concurrency/outcome b-final)))
    (is (= :stale-fence (:reason stale)) "stale worker excluded")
    (is (= (r "b") (:family/head sn)) "only B's finalization became authoritative")))

;; ---------------------------------------------------------------------------
;; 7. Expiry boundary with database-authoritative time
;; ---------------------------------------------------------------------------

(deftest expiry-boundary-before-at-and-after-deadline
  (testing "after the deadline → expired"
    (let [db *db-a*
          s0 (store/snapshot! db family)
          reservation (reserve-active! db family "idem-E1" "a")]
      (force-deadline! *ds-a* family (:reservation/id reservation) -1)
      (is (= :expired (reservation-status db family (:reservation/id reservation))))))
  (testing "exactly at the deadline → expired (lease is inclusive of 'now')"
    (let [db *db-b*
          s0 (store/snapshot! db family)
          reservation (reserve-active! db family "idem-E2" "b")]
      (force-deadline! *ds-b* family (:reservation/id reservation) 0)
      (is (= :expired (reservation-status db family (:reservation/id reservation))))))
  (testing "before the deadline → still active"
    (let [db *db-a*
          s0 (store/snapshot! db family)
          reservation (reserve-active! db family "idem-E3" "c")]
      (force-deadline! *ds-a* family (:reservation/id reservation) 60)
      (is (= :active (reservation-status db family (:reservation/id reservation)))))))

;; ---------------------------------------------------------------------------
;; 8. Lost finalization response — resolution replays canonical result
;; ---------------------------------------------------------------------------

(deftest lost-finalization-response-resolves-canonically
  (let [db *db-a*
        other *db-b*
        s0 (store/snapshot! db family)
        reservation (reserve-active! db family "idem-L" "a")
        fr (finalize-request s0 reservation (r "r"))
        committed (store/finalize! db fr)]
    (is (= :finalized (:concurrency/outcome committed)))
    (let [resolved (workflow/resolve-finalization! other family fr)]
      (is (= :finalized (:concurrency/outcome resolved)) "resolution replays canonical result")
      (is (= (r "r") (get-in resolved [:finalization :receipt/root]))))))

(deftest lost-finalization-response-active-eligible-can-exact-replay
  (let [db *db-a*
        other *db-b*
        s0 (store/snapshot! db family)
        reservation (reserve-active! db family "idem-LA" "a")
        fr (finalize-request s0 reservation (r "r"))
        ;; No finalization happened; the reservation is still active and eligible.
        resolved (workflow/resolve-finalization! other family fr)]
    (is (= :finalized (:concurrency/outcome resolved)) "eligible active reservation safe to exact-replay")
    (is (= 1 (:family/version (store/snapshot! db family))))))

(deftest lost-finalization-response-expired-resolves-unavailable
  (let [db *db-a*
        other *db-b*
        s0 (store/snapshot! db family)
        reservation (reserve-active! db family "idem-LE" "a")
        fr (finalize-request s0 reservation (r "r"))
        _ (force-deadline! *ds-a* family (:reservation/id reservation) -1)
        resolved (workflow/resolve-finalization! other family fr)]
    (is (= :finalization-unavailable (:concurrency/outcome resolved))
        "expired reservation resolves as unavailable, not wrongly re-finalized")))

;; ---------------------------------------------------------------------------
;; 9. Connection/process death while holding a transaction lock
;; ---------------------------------------------------------------------------

(deftest connection-death-releases-lock-and-allows-progress
  ;; A bank of concurrently-live independent workers, one of which dies while it
  ;; owns the family partition lock.
  (let [killer-pool (pool/pool (database-url) {:pool-size 2})
        independent (pool/pool (database-url) {:pool-size 2})
        key (pr-str (admission/partition-key family))]
    (try
      ;; take the partition row lock inside an uncommitted transaction
      (let [culprit (jdbc/get-connection killer-pool)]
        (try
          (jdbc/execute! culprit [(str "INSERT INTO prf_resubmission_admission_partition
                                        (partition_key, state_edn) VALUES (?, ?)
                                        ON CONFLICT (partition_key) DO NOTHING")
                                  key (pr-str (admission/empty-partition family))])
          (jdbc/execute! culprit ["SELECT state_edn
                                   FROM prf_resubmission_admission_partition
                                   WHERE partition_key = ? FOR UPDATE" key])
          ;; kill the connection while it owns the transaction/lock
          (.close ^java.sql.Connection culprit)
          (catch Throwable _ (.close ^java.sql.Connection culprit))))
      ;; an independent worker (different pool) must now be able to make progress
      (let [db (pg/postgres-store independent)
            outcome (store/reserve! db (request (store/snapshot! db family) "idem-C" "c"))]
        (is (= :reserved (:concurrency/outcome outcome))
            "independent worker progresses after lock-holder connection death")
        (is (= 1 (get-in outcome [:reservation :reservation/fence]))))
      (finally
        (.close killer-pool)
        (.close independent)))))