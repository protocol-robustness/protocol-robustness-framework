(ns resolver-sim.resubmission.receipt-obligation
  "Immutable post-commit receipt obligations and their P1A in-memory lifecycle.

   P1A is atomic within the current store CAS, but is not restart durable. An
   obligation freezes which receipt contract the committed transaction owes;
   pending/issued processing state is intentionally outside its identity."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.resubmission.receipt :as receipt]))

(def schema "resubmission-receipt-obligation.v1")
(def domain :prf-resubmission-receipt-obligation-v1)
(def immutable-fields
  #{:receipt-obligation/schema :receipt-obligation/transaction-ordering-hash
    :receipt-obligation/receipt-schema :receipt-obligation/subject-schema
    :receipt-obligation/attempt-subject-root
    :receipt-obligation/receipt-authority-public-key
    :receipt-obligation/receipt-authority-key-id})

(defn projection [obligation] (select-keys obligation immutable-fields))
(defn obligation-id [obligation]
  (ref/sha256-ref (hc/domain-hash domain (projection obligation))))

(defn qualifying-receipt-schema [candidate]
  (or (:attempt-receipt/schema candidate) receipt/receipt-schema))

(defn receipt-required?
  "True exactly for an admit-child committed under a configured receipt
   authority with a closed candidate receipt that declares its signing key.
   Legacy stores lacking that configured authority are explicitly
   non-qualifying; they are not silently treated as failed receipt issuance."
  [transaction-ordering candidate receipt-public-key]
  (and (= :prf.resubmission/admit-child (:transaction/action transaction-ordering))
       (map? candidate)
       (contains? #{receipt/receipt-schema receipt/receipt-v2-schema}
                  (qualifying-receipt-schema candidate))
       (string? receipt-public-key)
       (some? (get-in candidate [:attempt-receipt/validator :key/id]))))

(defn build
  "Build the immutable obligation for an already-committed admit-child ordering.
   The exact configured receipt-authority public key is frozen because current
   receipt admission verifies against that historical configuration key."
  [ordering candidate receipt-public-key]
  (let [receipt-schema (qualifying-receipt-schema candidate)
        v2? (= receipt-schema receipt/receipt-v2-schema)
        obligation (cond->
                    {:receipt-obligation/schema schema
                     :receipt-obligation/transaction-ordering-hash
                     (:transaction-ordering/hash ordering)
                     :receipt-obligation/receipt-schema receipt-schema
                     :receipt-obligation/receipt-authority-public-key receipt-public-key
                     :receipt-obligation/receipt-authority-key-id
                     (get-in candidate [:attempt-receipt/validator :key/id])
                     :receipt-obligation/subject-schema nil
                     :receipt-obligation/attempt-subject-root nil}
                     v2? (assoc :receipt-obligation/subject-schema "acceptance-attempt-subject.v1"
                                :receipt-obligation/attempt-subject-root
                                (:attempt-receipt/attempt-subject-root candidate)))]
    (assoc obligation :receipt-obligation/id (obligation-id obligation))))

(defn valid? [obligation]
  (let [v2? (= receipt/receipt-v2-schema (:receipt-obligation/receipt-schema obligation))]
    (and (map? obligation)
         (= (conj immutable-fields :receipt-obligation/id) (set (keys obligation)))
         (= schema (:receipt-obligation/schema obligation))
         (ref/valid-sha256-ref? (:receipt-obligation/transaction-ordering-hash obligation))
         (contains? #{receipt/receipt-schema receipt/receipt-v2-schema}
                    (:receipt-obligation/receipt-schema obligation))
         (string? (:receipt-obligation/receipt-authority-public-key obligation))
         (some? (:receipt-obligation/receipt-authority-key-id obligation))
         (if v2?
           (and (= "acceptance-attempt-subject.v1" (:receipt-obligation/subject-schema obligation))
                (ref/valid-sha256-ref? (:receipt-obligation/attempt-subject-root obligation)))
           (and (nil? (:receipt-obligation/subject-schema obligation))
                (nil? (:receipt-obligation/attempt-subject-root obligation))))
         (= (:receipt-obligation/id obligation) (obligation-id obligation)))))

(defn pending-entry [obligation] {:receipt-obligation obligation :receipt-obligation/status :pending})
(defn issued-entry [obligation signed-receipt]
  {:receipt-obligation obligation
   :receipt-obligation/status :issued
   :receipt-obligation/issued-receipt-root (:attempt-receipt/id signed-receipt)
   :receipt-obligation/issued-receipt signed-receipt})
(defn pending? [entry] (= :pending (:receipt-obligation/status entry)))
(defn issued? [entry] (= :issued (:receipt-obligation/status entry)))
