(ns resolver-sim.resubmission.receipt-obligation-test
    (:require [clojure.test :refer [deftest is testing]]
              [resolver-sim.resubmission.receipt :as receipt]
              [resolver-sim.resubmission.receipt-obligation :as obligation]))

(def ordering
  {:transaction/action :prf.resubmission/admit-child
   :transaction-ordering/hash
   "sha256:1111111111111111111111111111111111111111111111111111111111111111"})

(def v1-candidate
  {:attempt-receipt/schema receipt/receipt-schema
   :attempt-receipt/validator {:key/id "receipt-key-1"}})

(def v2-candidate
  {:attempt-receipt/schema receipt/receipt-v2-schema
   :attempt-receipt/validator {:key/id "receipt-key-1"}
   :attempt-receipt/attempt-subject-root
   "sha256:2222222222222222222222222222222222222222222222222222222222222222"})

(def public-key "0123456789abcdef")

(deftest qualifying-rule-is-explicit
  (testing "configured admit-child with a supported keyed receipt qualifies"
    (is (obligation/receipt-required? ordering v1-candidate public-key)))
  (testing "missing authority is explicitly non-qualifying legacy behavior"
    (is (not (obligation/receipt-required? ordering v1-candidate nil))))
  (testing "non-admission actions do not owe post-commit receipts"
    (is (not (obligation/receipt-required?
              (assoc ordering :transaction/action :other-action)
              v1-candidate public-key))))
  (testing "missing candidate key is not a qualifying contract"
    (is (not (obligation/receipt-required?
              ordering (update-in v1-candidate [:attempt-receipt/validator] dissoc :key/id)
              public-key)))))

(deftest obligation-projection-freezes-semantic-inputs
  (let [o1 (obligation/build ordering v1-candidate public-key)
        o2 (obligation/build
            (assoc ordering :transaction-ordering/hash
                   "sha256:3333333333333333333333333333333333333333333333333333333333333333")
            v1-candidate public-key)
        o3 (obligation/build ordering v2-candidate public-key)
        o4 (obligation/build ordering v1-candidate "fedcba9876543210")]
    (is (obligation/valid? o1))
    (is (not= (:receipt-obligation/id o1) (:receipt-obligation/id o2)))
    (is (not= (:receipt-obligation/id o1) (:receipt-obligation/id o3)))
    (is (not= (:receipt-obligation/id o1) (:receipt-obligation/id o4)))
    (is (= #{:receipt-obligation/schema
             :receipt-obligation/transaction-ordering-hash
             :receipt-obligation/receipt-schema
             :receipt-obligation/receipt-authority-public-key
             :receipt-obligation/receipt-authority-key-id
             :receipt-obligation/subject-schema
             :receipt-obligation/attempt-subject-root}
           (set (keys (obligation/projection o1)))))))

(deftest lifecycle-is-not-obligation-identity
  (let [o (obligation/build ordering v1-candidate public-key)
        pending (obligation/pending-entry o)
        issued (assoc (obligation/issued-entry o {:attempt-receipt/id "sha256:4444444444444444444444444444444444444444444444444444444444444444"})
                      :retry/count 3
                      :worker/id "worker-a")]
    (is (= (:receipt-obligation/id o)
           (:receipt-obligation/id (:receipt-obligation pending))))
    (is (= (:receipt-obligation/id o)
           (:receipt-obligation/id (:receipt-obligation issued))))
    (is (obligation/pending? pending))
    (is (obligation/issued? issued))))

(deftest v1-v2-contract-is-frozen
  (let [v1 (obligation/build ordering v1-candidate public-key)
        v2 (obligation/build ordering v2-candidate public-key)]
    (is (= receipt/receipt-schema (:receipt-obligation/receipt-schema v1)))
    (is (nil? (:receipt-obligation/subject-schema v1)))
    (is (nil? (:receipt-obligation/attempt-subject-root v1)))
    (is (= receipt/receipt-v2-schema (:receipt-obligation/receipt-schema v2)))
    (is (= "acceptance-attempt-subject.v1"
           (:receipt-obligation/subject-schema v2)))
    (is (= (:attempt-receipt/attempt-subject-root v2-candidate)
           (:receipt-obligation/attempt-subject-root v2)))
    (is (obligation/valid? v1))
    (is (obligation/valid? v2))))

(deftest authority-key-remains-frozen
  (let [o (obligation/build ordering v1-candidate "K1")
        changed (assoc o :receipt-obligation/receipt-authority-public-key "K2")]
    (is (not= (:receipt-obligation/id o)
              (obligation/obligation-id changed)))
    (is (= "K1" (:receipt-obligation/receipt-authority-public-key o)))
    (testing "a rotated key does not silently rewrite the pending obligation"
      (is (= :pending (:receipt-obligation/status
                       (obligation/pending-entry o)))))))
