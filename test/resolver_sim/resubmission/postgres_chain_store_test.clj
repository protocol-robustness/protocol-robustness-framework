(ns resolver-sim.resubmission.postgres-chain-store-test
  "PostgreSQL integration tests for durable P1B resubmission transaction storage.
   Requires DATABASE_URL or the local compose PostgreSQL on port 5433."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [resolver-sim.resubmission.receipt-daemon :as daemon]
            [resolver-sim.resubmission.receipt-worker :as worker]
            [resolver-sim.db.pool :as pool]
            [resolver-sim.resubmission.postgres-chain-store :as pg]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.receipt-test]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.support.ed25519 :as ed]
            [resolver-sim.transaction.protocol :as protocol])
  (:import [java.sql SQLException]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:dynamic *ds* nil)
(def family "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(defn database-url [] (or (System/getenv "DATABASE_URL")
                          "jdbc:postgresql://localhost:5433/postgres?user=postgres&password=postgres"))

(defn skip-if-no-db [f]
  (try
    (let [ds (pool/pool (database-url) {})]
      (jdbc/execute-one! ds ["SELECT 1"])
      (.close ds)
      (f))
    (catch Exception e
      (println "PostgreSQL chain-store integration tests skipped (" (.getMessage e) ")")
      (flush)
      (System/exit 0))))

(defn pg-fixture [f]
  (let [ds (pool/pool (database-url) {})]
    (pg/ensure-schema! ds)
    (binding [*ds* ds]
      (try (f)
           (finally
             (jdbc/execute! ds ["TRUNCATE TABLE prf_resubmission_receipt_obligation, prf_resubmission_committed_transaction, prf_resubmission_chain_partition"])
             (.close ds))))))

(use-fixtures :once skip-if-no-db)
(use-fixtures :each pg-fixture)

(defn candidate [key]
  (receipt/sign-receipt
   (assoc (#'resolver-sim.resubmission.receipt-test/v1-candidate)
          :attempt-receipt/chain {:admission-status :admitted :family-id family
                                  :sequence 1 :parent-receipt-hash nil})
   (:private-key key)))

(defn command [candidate suffix]
  {:transaction/action :prf.resubmission/admit-child
   :transaction/input {:parent-receipt-hash nil
                       :candidate-attempt-receipt candidate
                       :candidate-attempt-receipt-id (:attempt-receipt/id candidate)
                       :idempotency-key (str "pg-chain-idempotency-" suffix)
                       :content-key (str "pg-chain-content-" suffix)
                       :sequence 1}})

(defn store [key]
  (pg/postgres-chain-store *ds* family nil (:public-hex key)))

(defn commit! [s cmd]
  (protocol/transact! s nil nil #(transition/apply-action % cmd)))

(deftest commit-atomically-publishes-state-replay-record-and-obligation
  (let [key (ed/keypair :postgres-chain-store)
        s (store key)
        result (commit! s (command (candidate key) "atomic"))
        ordering (:transaction-ordering result)
        obligation (first (pg/pending-receipt-obligations s))]
    (is (= :committed (:status result)))
    (is (= 1 (pg/chain-version s)))
    (is (= (:transaction-ordering/hash ordering) (:transaction/last-hash (pg/state-of s))))
    (is (= (:transaction-ordering/hash ordering)
           (get-in (pg/resolve-committed-transaction s (:transaction-ordering/hash ordering))
                   [:transaction-record/ordering :transaction-ordering/hash])))
    (is (= (:transaction-ordering/hash ordering)
           (get-in obligation [:receipt-obligation :receipt-obligation/transaction-ordering-hash])))))

(deftest rejected-transition-creates-no-durable-artifacts
  (let [key (ed/keypair :postgres-chain-store-rejected)
        s (store key)
        result (commit! s {:transaction/action :prf.resubmission/admit-child :transaction/input {}})]
    (is (= :rejected (:status result)))
    (is (= 0 (pg/chain-version s)))
    (is (nil? (pg/chain-head s)))
    (is (empty? (pg/pending-receipt-obligations s)))
    (is (= 0 (:count (jdbc/execute-one! *ds* ["SELECT count(*) AS count FROM prf_resubmission_committed_transaction"]))))))

(deftest restart-instance-resolves-committed-transaction-and-pending-obligation
  (let [key (ed/keypair :postgres-chain-store-restart)
        first-store (store key)
        result (commit! first-store (command (candidate key) "restart"))
        ordering-hash (get-in result [:transaction-ordering :transaction-ordering/hash])
        second-store (store key)
        obligation (first (pg/pending-receipt-obligations second-store))]
    (is (= (pg/state-of first-store) (pg/state-of second-store)))
    (is (some? (pg/resolve-committed-transaction second-store ordering-hash)))
    (is (= 1 (count (pg/pending-receipt-obligations second-store))))
    (is (= obligation (pg/resolve-receipt-obligation second-store
                                                     (get-in obligation [:receipt-obligation :receipt-obligation/id]))))))

(deftest issued-receipts-persist-and-preserve-idempotency-and-conflict
  (let [key (ed/keypair :postgres-chain-store-issued)
        first-store (store key)
        _ (commit! first-store (command (candidate key) "issued"))
        obligation-id (get-in (first (pg/pending-receipt-obligations first-store))
                              [:receipt-obligation :receipt-obligation/id])
        signed (candidate key)
        issued (pg/mark-receipt-issued! first-store obligation-id signed)
        second-store (store key)
        idempotent (pg/mark-receipt-issued! second-store obligation-id signed)
        conflict (pg/mark-receipt-issued! second-store obligation-id (assoc signed :attempt-receipt/id "sha256:different"))]
    (is (= :issued (:status issued)))
    (is (= :issued (:receipt-obligation/status (pg/resolve-receipt-obligation second-store obligation-id))))
    (is (= :idempotent (:status idempotent)))
    (is (= :receipt-obligation/conflict (:status conflict)))
    (is (empty? (pg/pending-receipt-obligations second-store)))))

(deftest duplicate-ordering-anchor-is-rejected-by-database
  (let [key (ed/keypair :postgres-chain-store-unique)
        s (store key)
        result (commit! s (command (candidate key) "unique"))
        obligation (first (pg/pending-receipt-obligations s))
        ordering-hash (get-in obligation [:receipt-obligation :receipt-obligation/transaction-ordering-hash])
        duplicate-id "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
    (is (= :committed (:status result)))
    (is (thrown? java.sql.SQLException
                 (jdbc/execute! *ds* ["INSERT INTO prf_resubmission_receipt_obligation (obligation_id, family_id, transaction_ordering_hash, status, issued_receipt_root, entry_edn) VALUES (?, ?, ?, 'pending', NULL, ?)"
                                      duplicate-id family ordering-hash (pr-str (:entry_edn (jdbc/execute-one! *ds* ["SELECT entry_edn FROM prf_resubmission_receipt_obligation WHERE obligation_id = ?" (:receipt-obligation/id (:receipt-obligation obligation))])))])))
    (is (= 1 (:count (jdbc/execute-one! *ds* ["SELECT count(*) AS count FROM prf_resubmission_receipt_obligation"]))))))

(deftest concurrent-commits-retry-and-preserve-one-valid-chain
  (let [key (ed/keypair :postgres-chain-store-conflict)
        dsa (pool/pool (database-url) {})
        dsb (pool/pool (database-url) {})
        a (pg/postgres-chain-store dsa family nil (:public-hex key))
        b (pg/postgres-chain-store dsb family nil (:public-hex key))
        results (try
                  (mapv deref [(future (commit! a (command (candidate key) "conflict-a")))
                               (future (commit! b (command (candidate key) "conflict-b")))])
                  (finally (.close dsa) (.close dsb)))]
    (is (every? #(contains? #{:committed :rejected} (:status %)) results))
    (is (= 1 (pg/chain-version (store key))))
    (is (= 1 (:count (jdbc/execute-one! *ds* ["SELECT count(*) AS count FROM prf_resubmission_receipt_obligation"]))))
    (is (= 1 (:count (jdbc/execute-one! *ds* ["SELECT count(*) AS count FROM prf_resubmission_committed_transaction"]))))))

(deftest concurrent-first-family-initialization-is-safe
  (let [key (ed/keypair :postgres-chain-store-init)
        family-id (str family "-init")
        dsa (pool/pool (database-url) {})
        dsb (pool/pool (database-url) {})
        a (pg/postgres-chain-store dsa family-id nil (:public-hex key))
        b (pg/postgres-chain-store dsb family-id nil (:public-hex key))
        ready (CountDownLatch. 2)
        start (CountDownLatch. 1)
        run (fn [s]
              (future
                (.countDown ready)
                (.await start 10 TimeUnit/SECONDS)
                (commit! s (command (candidate key) (str "init-" (if (= s a) "a" "b"))))))
        fa (run a)
        fb (run b)]
    (try
      (is (.await ready 10 TimeUnit/SECONDS))
      (.countDown start)
      (let [states [(deref fa 120 ::timeout) (deref fb 120 ::timeout)]]
        (is (every? #(not= ::timeout %) states))
        (is (= 1 (:count (jdbc/execute-one! *ds*
                                            ["SELECT count(*) AS count FROM prf_resubmission_chain_partition WHERE family_id = ?"
                                             family-id]))))
        (is (= 1 (:count (jdbc/execute-one! *ds*
                                            ["SELECT count(*) AS count FROM prf_resubmission_committed_transaction WHERE family_id = ?"
                                             family-id]))))
        (is (= 1 (:count (jdbc/execute-one! *ds*
                                            ["SELECT count(*) AS count FROM prf_resubmission_receipt_obligation WHERE family_id = ?"
                                             family-id]))))
        (is (every? #(contains? #{:committed :rejected} (:status %)) states)))
      (finally
        (.close dsa)
        (.close dsb)))))

(defn sql-state [throwable]
  (loop [cause throwable]
    (cond
      (nil? cause) nil
      (instance? SQLException cause) (.getSQLState ^SQLException cause)
      :else (recur (.getCause ^Throwable cause)))))

(deftest serializable-conflict-retries-store-operation-live
  (let [key (ed/keypair :postgres-chain-store-serialization)
        family-id (str family "-serialization")
        setup (store key)
        _ (jdbc/execute! *ds*
                         ["INSERT INTO prf_resubmission_chain_partition (family_id, state_edn, chain_version) VALUES (?, ?, 0)"
                          family-id (pr-str (transition/empty-state family-id (:public-hex key)))])
        dsa (pool/pool (database-url) {})
        dsb (pool/pool (database-url) {})
        ready (CountDownLatch. 2)
        start (CountDownLatch. 1)
        update-tx (fn [ds]
                    (future
                      (try
                        (jdbc/with-transaction [tx ds {:isolation :serializable}]
                          (jdbc/execute-one! tx
                                             ["SELECT family_id FROM prf_resubmission_chain_partition WHERE family_id = ?"
                                              family-id])
                          (.countDown ready)
                          (.await start 10 TimeUnit/SECONDS)
                          (jdbc/execute! tx
                                         ["UPDATE prf_resubmission_chain_partition SET chain_version = chain_version WHERE family_id = ?"
                                          family-id]))
                        {:status :committed}
                        (catch Throwable e
                          {:status :error :sqlstate (sql-state e)}))))
        fa (update-tx dsa)
        fb (update-tx dsb)]
    (try
      (is (.await ready 10 TimeUnit/SECONDS))
      (.countDown start)
      (let [raw-results [(deref fa 120 ::timeout) (deref fb 120 ::timeout)]
            store-result (commit! setup (command (candidate key) "serialization"))
            states (keep :sqlstate raw-results)]
        (is (every? #(not= ::timeout %) raw-results))
        (is (not= ::timeout store-result))
        (is (= :committed (:status store-result)))
        (is (= 1 (pg/chain-version setup)))
        (is (= 1 (count (pg/pending-receipt-obligations setup))))
        (println "live serializable SQLSTATEs:" states)
        (is (every? #(or (= :committed (:status %))
                         (= "40001" (:sqlstate %))) raw-results)))
      (finally
        (.close dsa)
        (.close dsb)))))

(deftest fresh-daemon-startup-issues-and-fresh-store-resolves-receipt
  (let [key (ed/keypair :postgres-chain-store-daemon-restart)
        a (store key)
        committed (commit! a (command (candidate key) "daemon-restart"))
        obligation (first (pg/pending-receipt-obligations a))
        obligation-id (get-in obligation [:receipt-obligation :receipt-obligation/id])
        b (store key)
        summary (daemon/run-once! b (:private-key key))
        c (store key)
        entry (pg/resolve-receipt-obligation c obligation-id)
        issued (:receipt-obligation/issued-receipt entry)]
    (is (= :committed (:status committed)))
    (is (= 1 (:attempted summary)))
    (is (= {:success 1} (:counts summary)))
    (is (= :issued (:receipt-obligation/status entry)))
    (is (some? issued))
    (is (:valid? (receipt/verify-receipt-signature-dispatch issued (:public-hex key))))))

(deftest concurrent-issuers-linearize-to-issued-and-idempotent
  (let [key (ed/keypair :postgres-chain-store-concurrent)
        a (store key)
        _ (commit! a (command (candidate key) "concurrent"))
        obligation-id (get-in (first (pg/pending-receipt-obligations a))
                              [:receipt-obligation :receipt-obligation/id])
        signed (candidate key)
        b (store key)
        results (mapv deref [(future (pg/mark-receipt-issued! a obligation-id signed))
                             (future (pg/mark-receipt-issued! b obligation-id signed))])]
    (is (= #{:issued :idempotent} (set (map :status results))))
    (is (= :issued (:receipt-obligation/status (pg/resolve-receipt-obligation a obligation-id))))))
