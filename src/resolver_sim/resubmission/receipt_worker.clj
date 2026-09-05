(ns resolver-sim.resubmission.receipt-worker
  "P1A in-memory receipt issuer.

   This worker is recoverable from the current store contents, but the store is(ns resolver-sim.resubmission.receipt-worker)
   not restart durable. It never creates obligations and never consults current
   application or authority state."
  (:require [resolver-sim.resubmission.committed-transaction :as committed]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.receipt-obligation :as obligation]
            [resolver-sim.resubmission.store :as store]))

(defn- receipt-from-commit
  [record ordering]
  (let [candidate (get-in record [:transaction-record/command
                                  :transaction/input
                                  :candidate-attempt-receipt])
        chain (merge (:attempt-receipt/chain candidate)
                     {:admission-status :admitted
                      :transaction-ordering-hash
                      (:transaction-ordering/hash ordering)})]
    (assoc (-> candidate
               (dissoc :attempt-receipt/id)
               (update :attempt-receipt/validator dissoc :signature)
               (assoc :attempt-receipt/chain chain))
           :attempt-receipt/schema
           (or (:attempt-receipt/schema candidate) receipt/receipt-schema))))

(defn reconstruct
  "Reconstruct and sign the receipt owed by an obligation. `private-key` is
   supplied by the issuer process; the resulting signature is verified against
   the exact public key frozen in the obligation."
  [store obligation-id private-key]
  (let [entry (store/resolve-receipt-obligation store obligation-id)
        o (:receipt-obligation entry)
        record (when o (store/resolve-committed-transaction
                        store (:receipt-obligation/transaction-ordering-hash o)))]
    (cond
      (nil? entry) {:status :not-found}
      (not (obligation/valid? o)) {:status :invalid-obligation}
      (obligation/issued? entry) {:status :idempotent
                                  :receipt (:receipt-obligation/issued-receipt entry)}
      (not (obligation/pending? entry)) {:status :invalid-state}
      (nil? record) {:status :unavailable :reason :committed-transaction-not-found}
      :else
      (let [validation (committed/validate-record record)]
        (if-not (:valid? validation)
          {:status :invalid :reason :committed-transaction-invalid
           :errors (:errors validation)}
          (let [ordering (:transaction-record/ordering record)
                candidate (receipt-from-commit record ordering)
                signed (if (= receipt/receipt-v2-schema
                              (:receipt-obligation/receipt-schema o))
                         (receipt/sign-receipt-v2 candidate private-key)
                         (receipt/sign-receipt candidate private-key))
                verification (receipt/verify-receipt-signature-dispatch
                              signed
                              (:receipt-obligation/receipt-authority-public-key o))]
            (if-not (:valid? verification)
              {:status :invalid :reason :issued-receipt-verification-failed
               :verification verification}
              (let [persisted (store/mark-receipt-issued! store obligation-id signed)]
                (assoc persisted :receipt
                       (or (get-in persisted [:entry :receipt-obligation/issued-receipt])
                           signed))))))))))

(defn issue-pending!
  "Issue every currently pending obligation in deterministic order. This is a
   convenience worker loop, not a correctness-critical process-local queue."
  [store private-key]
  (mapv #(reconstruct store
                      (get-in % [:receipt-obligation :receipt-obligation/id])
                      private-key)
        (store/pending-receipt-obligations store)))
