(ns resolver-sim.resubmission.store
  "In-memory implementation of resolver-sim.transaction.protocol/TransactionStore
   for a single resubmission family.

   The store owns ONLY:
     - loading the snapshot for the conflict key;
     - invoking the pure transition (resolver-sim.resubmission.transition);
     - atomically comparing the version (compare-and-set);
     - building and attaching the transaction-ordering evidence;
     - committing the returned state and evidence;
     - retrying after CAS contention.

   It does NOT own domain rules; those live in the pure transition.

   Transaction evidence (no hash cycle):
     - the transaction ordering commits the chain-state transition
       (state-before/state-after roots), excluding the attempt receipt artifact;
     - the ordering hash is stored as :transaction/last-hash on the committed
       state, but :transaction/last-hash is EXCLUDED from the state-root
       projection, so state-after-root is stable;
     - a signed attempt receipt commits the resulting
       :transaction-ordering/hash (receipt issuance is a later slice)."
  (:require [resolver-sim.resubmission.committed-transaction :as committed-transaction]
            [resolver-sim.resubmission.receipt-obligation :as receipt-obligation]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.transaction.protocol :as protocol]))

(deftype ResubmissionChainStore [family-id disposition-public-hex receipt-public-hex state-atom genesis authority-context]
  protocol/TransactionStore
  (transact!
    [_store _conflict-key expected-version transition-fn]
    (let [conflict-key [:resubmission-family family-id]]
      (loop []
        (let [current @state-atom
              entry (get current conflict-key
                         {:state (cond-> (transition/empty-state family-id disposition-public-hex)
                                   authority-context (assoc :chain/disposition-authority-context authority-context))
                          :version 0})
              {:keys [state version]} entry]
          (if (and (some? expected-version) (not= expected-version version))
            {:status :contention :reason :version-mismatch
             :observed-version version :expected-version expected-version}
            (let [result (transition-fn state)]
              (if-not (= :committed (:status result))
                result
                (let [state-before-root (transition/state-root state)
                      state-after-root (transition/state-root (:state result))
                      effects-root (transition/effects-root (:effects result))
                      ordering
                      (ordering/transaction-ordering
                       (merge (:ordering-input result)
                              {:transaction/commit-index
                               (:transaction/commit-index (:state result))
                               :transaction/previous-transaction-hash
                               (:transaction/last-hash state)
                               :transaction/state-before-root state-before-root
                               :transaction/state-after-root state-after-root
                               :transaction/effects-root effects-root}))
                      final-state (assoc (:state result)
                                         :transaction/last-hash
                                         (:transaction-ordering/hash ordering))
                      transaction-record
                      (committed-transaction/build-record
                       state (:committed-command result) ordering)
                      record-validation
                      (committed-transaction/validate-record transaction-record)
                      _ (when-not (:valid? record-validation)
                          (throw (ex-info "committed transaction record is invalid"
                                          {:type :transaction-record/invalid
                                           :errors (:errors record-validation)})))
                      ordering-hash (:transaction-ordering/hash ordering)
                      candidate-receipt (get-in (:committed-command result)
                                                [:transaction/input :candidate-attempt-receipt])
                      ;; Legacy/in-memory fixtures without receipt authority do
                      ;; not declare a post-commit receipt contract. They retain
                      ;; the pre-existing commit semantics and create no orphan
                      ;; obligation. Configured receipt-authority admissions do.
                      obligation (when (receipt-obligation/receipt-required?
                                        ordering candidate-receipt (.receipt-public-hex _store))
                                   (receipt-obligation/build ordering candidate-receipt
                                                             (.receipt-public-hex _store)))
                      _ (when (and obligation (not (receipt-obligation/valid? obligation)))
                          (throw (ex-info "receipt obligation is invalid"
                                          {:type :receipt-obligation/invalid
                                           :obligation obligation})))
                      obligation-id (:receipt-obligation/id obligation)
                      new-current (cond-> (-> current
                                              (assoc conflict-key
                                                     {:state final-state :version (inc version)})
                                              (assoc-in [:committed-transactions ordering-hash]
                                                        transaction-record))
                                    obligation
                                    (assoc-in [:receipt-obligations obligation-id]
                                              (receipt-obligation/pending-entry obligation)))]
                  (if (compare-and-set! state-atom current new-current)
                    (assoc result :transaction-ordering ordering)
                    (recur)))))))))))

(defn new-resubmission-store
  "Create an in-memory resubmission chain store serving one family.

   Public keys are trusted store configuration: the disposition key verifies
   lifecycle events and the receipt key verifies canonical admissions.

   The genesis field is nil for the 1-3-arity overloads below; it is set only
   when a store is realized from a canonical genesis via
   new-resubmission-store-from-genesis, or via chain/new-chain (which
   constructs a genesis for provenance without validation, as local runtime
   instantiation is a convenience path, not an authority path).

   This nil-genesis category is transitional. The destination invariant is that
   every resubmission chain store carries a declared genesis. A nil genesis
   simply means 'undeclared provenance' — it does not imply anything about
   governance authorization."
  ([family-id] (new-resubmission-store family-id nil nil))
  ([family-id disposition-public-hex]
   (new-resubmission-store family-id disposition-public-hex nil))
  ([family-id disposition-public-hex receipt-public-hex]
   (new-resubmission-store family-id disposition-public-hex receipt-public-hex nil))
  ([family-id disposition-public-hex receipt-public-hex genesis]
   (new-resubmission-store family-id disposition-public-hex receipt-public-hex genesis nil))
  ([family-id disposition-public-hex receipt-public-hex genesis authority-context]
   (ResubmissionChainStore. family-id disposition-public-hex receipt-public-hex
                            (atom {}) genesis authority-context)))

(defn new-resubmission-store-from-genesis
  "Canonical validated genesis realization path.

   Requires a structurally and cryptographically self-consistent
   resubmission-chain-genesis.v1: validates strict closed-shape (fail-closed),
   verifies that chain-id matches its derivation from the family identity basis,
   and verifies that initial-state/root matches the computed empty-state root.
   The genesis artifact is stored on the instance for provenance.

   NOTE: validation establishes well-formedness, internal consistency, and
   canonical rooting. It does NOT yet establish governance authorization.
   Authorized genesis binding is a future stage (see design §15)."
  ([genesis] (new-resubmission-store-from-genesis genesis nil))
  ([genesis authority-context]
   (let [v (genesis/validate-resubmission-chain-genesis genesis)]
     (when-not (:valid? v)
       (throw (ex-info "invalid resubmission-chain-genesis.v1"
                       {:type :genesis/invalid
                        :schema genesis/resubmission-chain-genesis-schema
                        :errors (:errors v)}))))
   (let [cfg (:configuration genesis)
         family-id (:family/id genesis)
         disp-k (:disposition-authority/public-key cfg)
         recv-k (:receipt-authority/public-key cfg)]
     (new-resubmission-store family-id disp-k recv-k genesis authority-context))))

(defn genesis-of
  "Return the genesis artifact declared on the store, or nil for stores created
   without a genesis (legacy constructors).

   CONVEYED MEANING — declaration/provenance only:

   A non-nil return means the store carries a declared canonical genesis
   (well-formed, internally consistent, canonically rooted).

   It does NOT imply governance authorization. Authority should be evidenced
   by a separate verifiable artifact (e.g., a future
   resubmission-chain-genesis-authorization.v1 binding the genesis root to a
   governance decision). Do not infer authorization from the mere presence of
   a genesis."
  [store]
  (.genesis store))

(defn state-of
  "The current committed state for the store's family (or empty-state)."
  [store]
  (let [{:keys [state]}
        (get @(.state-atom store) [:resubmission-family (.family-id store)]
             {:state (cond-> (transition/empty-state (.family-id store)
                                                     (.disposition-public-hex store))
                       (.authority-context store)
                       (assoc :chain/disposition-authority-context (.authority-context store)))})]
    state))

(defprotocol DurableReceiptStore
  (resolve-committed-transaction* [store ordering-hash])
  (resolve-receipt-obligation* [store obligation-id])
  (pending-receipt-obligations* [store])
  (mark-receipt-issued!* [store obligation-id signed-receipt]))

(defn resolve-committed-transaction
  "Resolve the store-owned replay record for an atomically committed ordering.
   The transaction-ordering hash is the sole lookup identity; journal entries
   are stored in the outer CAS envelope and are never part of protocol state."
  [store ordering-hash]
  (if (satisfies? DurableReceiptStore store)
    (resolve-committed-transaction* store ordering-hash)
    (get-in @(.state-atom store) [:committed-transactions ordering-hash])))

(defn resolve-receipt-obligation
  "Resolve the immutable receipt obligation and its P1A processing state.
   This is retained only in the current in-memory store; it is not restart
   durable until a P1B backend implements the same atomic semantics."
  [store obligation-id]
  (if (satisfies? DurableReceiptStore store)
    (resolve-receipt-obligation* store obligation-id)
    (get-in @(.state-atom store) [:receipt-obligations obligation-id])))

(defn pending-receipt-obligations
  "Return pending P1A receipt obligations in deterministic obligation-ID order."
  [store]
  (if (satisfies? DurableReceiptStore store)
    (pending-receipt-obligations* store)
    (->> (get @(.state-atom store) :receipt-obligations {})
         vals
         (filter receipt-obligation/pending?)
         (sort-by #(get-in % [:receipt-obligation :receipt-obligation/id]))
         vec)))

(defn mark-receipt-issued!
  "Conditionally discharge a pending obligation. Returns the stored issued
   entry on idempotent equivalence, or :receipt-obligation/conflict when an
   already-issued receipt differs."
  [store obligation-id signed-receipt]
  (if (satisfies? DurableReceiptStore store)
    (mark-receipt-issued!* store obligation-id signed-receipt)
    (loop []
      (let [current @(.state-atom store)
            entry (get-in current [:receipt-obligations obligation-id])]
        (cond
          (nil? entry) {:status :receipt-obligation/not-found}
          (receipt-obligation/pending? entry)
          (let [issued (receipt-obligation/issued-entry (:receipt-obligation entry) signed-receipt)
                next-state (assoc-in current [:receipt-obligations obligation-id] issued)]
            (if (compare-and-set! (.state-atom store) current next-state)
              {:status :issued :entry issued}
              (recur)))
          (receipt-obligation/issued? entry)
          (if (= (:receipt-obligation/issued-receipt-root entry)
                 (:attempt-receipt/id signed-receipt))
            {:status :idempotent :entry entry}
            {:status :receipt-obligation/conflict :entry entry})
          :else {:status :receipt-obligation/invalid-state :entry entry})))))

(defn chain-head
  "The current chain head receipt hash (nil before the first attempt)."
  [store]
  (:chain/head (state-of store)))

(defn chain-version
  [store]
  (:chain/version (state-of store)))

(defn family-id-of
  [store]
  (.family-id store))
