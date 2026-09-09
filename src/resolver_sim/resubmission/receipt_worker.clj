(ns resolver-sim.resubmission.receipt-worker
  "P1A in-memory receipt issuer.

   This worker is recoverable from the current store contents, but the store is
   not restart durable. It never creates obligations and never consults current
   application or authority state."
  (:require [resolver-sim.resubmission.committed-transaction :as committed]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.issuance :as issuance]
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

(defn authenticate-candidate
  "Authenticate the retained pre-commit candidate body before it is reused for
   post-commit issuance. Candidate authority is conceptually distinct from the
   issued-receipt authority; current P1A obligations freeze the same historical
   configured key for both roles."
  [candidate committed-candidate-id candidate-public-key]
  (let [schema (or (:attempt-receipt/schema candidate) receipt/receipt-schema)
        v2? (= schema receipt/receipt-v2-schema)
        shape-valid? (if v2?
                       (receipt/valid-receipt-v2-shape? candidate)
                       (receipt/valid-receipt-shape? candidate))
        recomputed (if v2? (receipt/receipt-hash-v2 candidate)
                       (receipt/receipt-hash candidate))
        signature (receipt/verify-receipt-signature-dispatch candidate
                                                             candidate-public-key)]
    (cond
      (not shape-valid?) {:valid? false :reason :candidate-shape-invalid}
      (not= committed-candidate-id (:attempt-receipt/id candidate))
      {:valid? false :reason :candidate-id-command-mismatch}
      (not= committed-candidate-id recomputed)
      {:valid? false :reason :candidate-id-recomputation-mismatch}
      (not (:valid? signature))
      {:valid? false :reason :candidate-signature-invalid
       :signature-reason (:reason signature)}
      :else {:valid? true :reason :ok :schema schema})))

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
                command-input (get-in record [:transaction-record/command :transaction/input])
                retained-candidate (:candidate-attempt-receipt command-input)
                committed-candidate-id (or (:candidate-attempt-receipt-id command-input)
                                           (:attempt-receipt/id retained-candidate))
                candidate-check (authenticate-candidate
                                 retained-candidate
                                 committed-candidate-id
                                 (:receipt-obligation/receipt-authority-public-key o))]
            (if-not (:valid? candidate-check)
              {:status :invalid :reason (:reason candidate-check)}
              (let [candidate (receipt-from-commit record ordering)
                    chain-check (issuance/receipt-chain-join candidate ordering)]
                (if-not (:valid? chain-check)
                  {:status :invalid :reason (:reason chain-check)}
                  (let [signed (if (= receipt/receipt-v2-schema
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
                                   signed))))))))))))))

(defn issue-pending!
  "Issue every currently pending obligation in deterministic order. This is a
   convenience worker loop, not a correctness-critical process-local queue."
  [store private-key]
  (mapv #(reconstruct store
                      (get-in % [:receipt-obligation :receipt-obligation/id])
                      private-key)
        (store/pending-receipt-obligations store)))
