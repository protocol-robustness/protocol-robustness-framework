(ns resolver-sim.resubmission.receipt-worker-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.resubmission.issuance :as issuance]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.receipt-worker :as worker]
            [resolver-sim.support.ed25519 :as ed]
            [resolver-sim.transaction.ordering :as ordering]))

(def root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ordering
  (ordering/transaction-ordering
   {:transaction/action :prf.resubmission/admit-child
    :transaction/scope :resubmission-family
    :transaction/conflict-key [:resubmission-family "family"]
    :transaction/commit-index 1
    :transaction/state-before-root root
    :transaction/state-after-root root
    :transaction/effects-root root
    :transaction/input {:family-id "family"
                        :sequence 1
                        :parent-receipt-hash nil}}))

(defn candidate [schema]
  {:attempt-receipt/schema schema
   :attempt-receipt/submitted-bundle-root root
   :attempt-receipt/outcome :rejected
   :attempt-receipt/finality :final
   :attempt-receipt/resubmission-eligibility :eligible
   :attempt-receipt/lifecycle-status :active
   :attempt-receipt/roots {:research-subject {:root/schema "r" :status :verified :hash root}
                           :execution-context {:root/schema "e" :status :verified :hash root}
                           :results {:root/schema "x" :status :verified :hash root}
                           :submission-basis {:root/schema "s" :status :verified :hash root}}
   :attempt-receipt/validator {:policy/hash root :key/id "k"}})

(defn- joined-candidate []
  (issuance/receipt-candidate
   (candidate receipt/receipt-schema)
   {:admission-status :admitted
    :family-id "family"
    :sequence 1
    :parent-receipt-hash nil
    :transaction-ordering-hash (:transaction-ordering/hash ordering)}))

(deftest receipt-chain-join-rejects-mismatched-commit-metadata
  (let [candidate (joined-candidate)]
    (is (:valid? (issuance/receipt-chain-join candidate ordering)))
    (is (= :family-inconsistent
           (:reason (issuance/receipt-chain-join
                     (assoc-in candidate [:attempt-receipt/chain :family-id] "other")
                     ordering))))
    (is (= :sequence-inconsistent
           (:reason (issuance/receipt-chain-join
                     (assoc-in candidate [:attempt-receipt/chain :sequence] 2)
                     ordering))))
    (is (= :parent-inconsistent
           (:reason (issuance/receipt-chain-join
                     (assoc-in candidate [:attempt-receipt/chain :parent-receipt-hash] root)
                     ordering))))))

(deftest authenticates-signed-v1-and-rejects-tampering
  (let [kp (ed/keypair :candidate-auth)
        signed (receipt/sign-receipt (candidate receipt/receipt-schema) (:private-key kp))
        id (:attempt-receipt/id signed)]
    (is (:valid? (worker/authenticate-candidate signed id (:public-hex kp))))
    (is (= :candidate-id-recomputation-mismatch
           (:reason (worker/authenticate-candidate (assoc signed :attempt-receipt/outcome :accepted)
                                                   id (:public-hex kp)))))
    (is (= :candidate-id-command-mismatch
           (:reason (worker/authenticate-candidate signed root (:public-hex kp)))))
    (is (= :candidate-signature-invalid
           (:reason (worker/authenticate-candidate
                     (assoc-in signed [:attempt-receipt/validator :signature :signature] "00")
                     id (:public-hex kp)))))
    (is (= :candidate-signature-invalid
           (:reason (worker/authenticate-candidate signed id (:public-hex (ed/keypair :wrong))))))))

(deftest authenticates-signed-v2-and-rejects-invalid-shape
  (let [kp (ed/keypair :candidate-auth-v2)
        signed (receipt/sign-receipt-v2
                (assoc (candidate receipt/receipt-v2-schema)
                       :attempt-receipt/attempt-subject-root root)
                (:private-key kp))
        id (:attempt-receipt/id signed)]
    (is (:valid? (worker/authenticate-candidate signed id (:public-hex kp))))
    (is (= :candidate-shape-invalid
           (:reason (worker/authenticate-candidate
                     (dissoc signed :attempt-receipt/attempt-subject-root)
                     id (:public-hex kp)))))))
