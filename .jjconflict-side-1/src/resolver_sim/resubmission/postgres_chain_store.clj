(ns resolver-sim.resubmission.postgres-chain-store
  "Durable PostgreSQL TransactionStore for one resubmission family.

   The family partition row is locked inside a SERIALIZABLE transaction. A
   successful transition publishes its chain state/version, replay record, and
   qualifying pending receipt obligation atomically; rejected transitions write
   none of those artifacts. Receipt issuance separately locks its obligation
   row, preserving the P1A issued/idempotent/conflict contract across processes
   and restarts."
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [resolver-sim.resubmission.committed-transaction :as committed-transaction]
            [resolver-sim.resubmission.receipt-obligation :as receipt-obligation]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.transaction.protocol :as protocol]))

(def ^:private partition-table "prf_resubmission_chain_partition")
(def ^:private transaction-table "prf_resubmission_committed_transaction")
(def ^:private obligation-table "prf_resubmission_receipt_obligation")
(def ^:private max-transaction-attempts 5)

(defn ensure-schema!
  "Explicitly apply forward-only PostgreSQL migrations; never use at load time."
  [datasource]
  ((requiring-resolve 'resolver-sim.db.migrate/migrate!) datasource)
  datasource)

(defn- row-opts [] {:builder-fn rs/as-unqualified-maps})
(defn- encode [value] (pr-str value))
(defn- decode [value] (edn/read-string value))

(defn- retryable-transaction-error? [throwable]
  (loop [cause throwable]
    (cond
      (nil? cause) false
      (and (instance? java.sql.SQLException cause)
           (contains? #{"40001" "40P01"}
                      (.getSQLState ^java.sql.SQLException cause))) true
      :else (recur (.getCause ^Throwable cause)))))

(defn- with-transaction-retry! [f]
  (loop [attempt 1]
    (let [result (try
                   {:value (f)}
                   (catch Throwable error {:error error}))]
      (if-let [error (:error result)]
        (if (and (< attempt max-transaction-attempts)
                 (retryable-transaction-error? error))
          (do (Thread/sleep (+ 5 (* 5 attempt)))
              (recur (inc attempt)))
          (throw error))
        (:value result)))))

(defn- empty-state [store]
  (cond-> (transition/empty-state (.family-id store) (.disposition-public-hex store))
    (.authority-context store)
    (assoc :chain/disposition-authority-context (.authority-context store))))

(defn- locked-partition! [tx store]
  (let [family-id (.family-id store)
        inserted (jdbc/execute-one!
                  tx [(str "INSERT INTO " partition-table " (family_id, state_edn, chain_version)
                           VALUES (?, ?, 0) ON CONFLICT (family_id) DO NOTHING RETURNING family_id")
                      family-id (encode (empty-state store))]
                  (row-opts))
        row (jdbc/execute-one!
             tx [(str "SELECT state_edn, chain_version FROM " partition-table
                      " WHERE family_id = ? FOR UPDATE") family-id]
             (row-opts))]
    {:state (decode (:state_edn row))
     :version (:chain_version row)
     :created? (some? inserted)}))

(defn- delete-new-partition! [tx store]
  (jdbc/execute! tx [(str "DELETE FROM " partition-table " WHERE family_id = ?")
                     (.family-id store)]))

(defn- update-partition! [tx store state version]
  (jdbc/execute! tx
                 [(str "UPDATE " partition-table
                       " SET state_edn = ?, chain_version = ? WHERE family_id = ?")
                  (encode state) version (.family-id store)]))

(defn- commit! [tx store state version result]
  (let [state-before-root (transition/state-root state)
        state-after-root (transition/state-root (:state result))
        effects-root (transition/effects-root (:effects result))
        transaction-ordering
        (ordering/transaction-ordering
         (merge (:ordering-input result)
                {:transaction/commit-index (:transaction/commit-index (:state result))
                 :transaction/previous-transaction-hash (:transaction/last-hash state)
                 :transaction/state-before-root state-before-root
                 :transaction/state-after-root state-after-root
                 :transaction/effects-root effects-root}))
        final-state (assoc (:state result) :transaction/last-hash
                           (:transaction-ordering/hash transaction-ordering))
        record (committed-transaction/build-record state (:committed-command result)
                                                   transaction-ordering)
        validation (committed-transaction/validate-record record)
        _ (when-not (:valid? validation)
            (throw (ex-info "committed transaction record is invalid"
                            {:type :transaction-record/invalid :errors (:errors validation)})))
        candidate (get-in (:committed-command result)
                          [:transaction/input :candidate-attempt-receipt])
        obligation (when (receipt-obligation/receipt-required?
                          transaction-ordering candidate (.receipt-public-hex store))
                     (receipt-obligation/build transaction-ordering candidate
                                               (.receipt-public-hex store)))
        _ (when (and obligation (not (receipt-obligation/valid? obligation)))
            (throw (ex-info "receipt obligation is invalid"
                            {:type :receipt-obligation/invalid :obligation obligation})))
        ordering-hash (:transaction-ordering/hash transaction-ordering)]
    (update-partition! tx store final-state (inc version))
    (jdbc/execute! tx
                   [(str "INSERT INTO " transaction-table
                         " (ordering_hash, family_id, record_edn) VALUES (?, ?, ?)")
                    ordering-hash (.family-id store) (encode record)])
    (when obligation
      (jdbc/execute! tx
                     [(str "INSERT INTO " obligation-table
                           " (obligation_id, family_id, transaction_ordering_hash, status, issued_receipt_root, entry_edn) VALUES (?, ?, ?, 'pending', NULL, ?)")
                      (:receipt-obligation/id obligation) (.family-id store)
                      (:transaction-ordering/hash transaction-ordering)
                      (encode (receipt-obligation/pending-entry obligation))]))
    (assoc result :transaction-ordering transaction-ordering)))

(deftype PostgresChainStore [datasource family-id disposition-public-hex receipt-public-hex genesis authority-context]
  protocol/TransactionStore
  (transact! [store _conflict-key expected-version transition-fn]
    (with-transaction-retry!
      #(jdbc/with-transaction [tx datasource {:isolation :serializable}]
         (let [{:keys [state version created?]} (locked-partition! tx store)]
           (if (and (some? expected-version) (not= expected-version version))
             (do
               (when created? (delete-new-partition! tx store))
               {:status :contention :reason :version-mismatch
                :observed-version version :expected-version expected-version})
             (let [result (transition-fn state)]
               (if (= :committed (:status result))
                 (commit! tx store state version result)
                 (do
                   (when created? (delete-new-partition! tx store))
                   result))))))))
  (transact! [store conflict-key transition-fn]
    (protocol/transact! store conflict-key nil transition-fn)))

(declare resolve-committed-transaction resolve-receipt-obligation
         pending-receipt-obligations mark-receipt-issued!)

(extend-type PostgresChainStore
  store/DurableReceiptStore
  (resolve-committed-transaction* [s ordering-hash]
    (resolve-committed-transaction s ordering-hash))
  (resolve-receipt-obligation* [s obligation-id]
    (resolve-receipt-obligation s obligation-id))
  (pending-receipt-obligations* [s]
    (pending-receipt-obligations s))
  (mark-receipt-issued!* [s obligation-id signed-receipt]
    (mark-receipt-issued! s obligation-id signed-receipt)))

(defn postgres-chain-store
  "Create a durable TransactionStore for one configured resubmission family.
   Call `ensure-schema!` from the dedicated migration job before use."
  ([datasource family-id]
   (postgres-chain-store datasource family-id nil nil nil nil))
  ([datasource family-id disposition-public-hex receipt-public-hex]
   (postgres-chain-store datasource family-id disposition-public-hex receipt-public-hex nil nil))
  ([datasource family-id disposition-public-hex receipt-public-hex declared-genesis authority-context]
   (PostgresChainStore. datasource family-id disposition-public-hex receipt-public-hex
                        declared-genesis authority-context)))

(defn state-of [store]
  (with-transaction-retry!
    #(jdbc/with-transaction [tx (.datasource store) {:isolation :serializable}]
       (let [{:keys [state created?]} (locked-partition! tx store)]
         (when created? (delete-new-partition! tx store))
         state))))

(defn resolve-committed-transaction [store ordering-hash]
  (some-> (jdbc/execute-one! (.datasource store)
                             [(str "SELECT record_edn FROM " transaction-table
                                   " WHERE family_id = ? AND ordering_hash = ?")
                              (.family-id store) ordering-hash]
                             (row-opts))
          :record_edn decode))

(defn resolve-receipt-obligation [store obligation-id]
  (some-> (jdbc/execute-one! (.datasource store)
                             [(str "SELECT entry_edn FROM " obligation-table
                                   " WHERE family_id = ? AND obligation_id = ?")
                              (.family-id store) obligation-id]
                             (row-opts))
          :entry_edn decode))

(defn pending-receipt-obligations [store]
  (mapv (comp decode :entry_edn)
        (jdbc/execute! (.datasource store)
                       [(str "SELECT entry_edn FROM " obligation-table
                             " WHERE family_id = ? AND status = 'pending' ORDER BY obligation_id")
                        (.family-id store)]
                       (row-opts))))

(defn mark-receipt-issued! [store obligation-id signed-receipt]
  (with-transaction-retry!
    #(jdbc/with-transaction [tx (.datasource store) {:isolation :serializable}]
       (let [row (jdbc/execute-one!
                  tx [(str "SELECT entry_edn FROM " obligation-table
                           " WHERE family_id = ? AND obligation_id = ? FOR UPDATE")
                      (.family-id store) obligation-id]
                  (row-opts))
             entry (some-> row :entry_edn decode)]
         (cond
           (nil? entry) {:status :receipt-obligation/not-found}
           (receipt-obligation/pending? entry)
           (let [issued (receipt-obligation/issued-entry (:receipt-obligation entry) signed-receipt)]
             (jdbc/execute! tx [(str "UPDATE " obligation-table
                                     " SET status = 'issued', issued_receipt_root = ?, entry_edn = ? WHERE obligation_id = ?")
                                (:attempt-receipt/id signed-receipt)
                                (encode issued) obligation-id])
             {:status :issued :entry issued})
           (receipt-obligation/issued? entry)
           (if (= (:receipt-obligation/issued-receipt-root entry)
                  (:attempt-receipt/id signed-receipt))
             {:status :idempotent :entry entry}
             {:status :receipt-obligation/conflict :entry entry})
           :else {:status :receipt-obligation/invalid-state :entry entry})))))

(defn genesis-of [store] [(.genesis store)])
(defn chain-head [store] (:chain/head (state-of store)))
(defn chain-version [store] (:chain/version (state-of store)))
(defn family-id-of [store] (.family-id store))
