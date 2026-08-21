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
  (:require [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.transaction.protocol :as protocol]))

(deftype ResubmissionChainStore [family-id disposition-public-hex receipt-public-hex state-atom genesis]
  protocol/TransactionStore
  (transact!
    [_store _conflict-key expected-version transition-fn]
    (let [conflict-key [:resubmission-family family-id]]
      (loop []
        (let [current @state-atom
              entry (get current conflict-key
                         {:state (transition/empty-state family-id disposition-public-hex) :version 0})
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
                      new-current (assoc current conflict-key
                                         {:state final-state :version (inc version)})]
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
   (ResubmissionChainStore. family-id disposition-public-hex receipt-public-hex
                            (atom {}) genesis)))

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
  [genesis]
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
    (new-resubmission-store family-id disp-k recv-k genesis)))

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
             {:state (transition/empty-state (.family-id store)
                                             (.disposition-public-hex store))})]
    state))

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
