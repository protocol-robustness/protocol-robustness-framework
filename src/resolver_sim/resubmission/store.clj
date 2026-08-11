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
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.transaction.protocol :as protocol]))

(deftype ResubmissionChainStore [family-id disposition-public-hex receipt-public-hex state-atom]
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
   lifecycle events and the receipt key verifies canonical admissions."
  ([family-id] (new-resubmission-store family-id nil nil))
  ([family-id disposition-public-hex]
   (new-resubmission-store family-id disposition-public-hex nil))
  ([family-id disposition-public-hex receipt-public-hex]
   (ResubmissionChainStore. family-id disposition-public-hex receipt-public-hex (atom {}))))

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
