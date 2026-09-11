(ns resolver-sim.resubmission.receipt-obligation-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.receipt-obligation :as obligation]
            [resolver-sim.resubmission.store :as store]))

(def ordering
  {:transaction/action :prf.resubmission/admit-child
   :transaction-ordering/hash
   "sha256:1111111111111111111111111111111111111111111111111111111111111111"})
(def candidate
  {:attempt-receipt/schema receipt/receipt-schema
   :attempt-receipt/validator {:key/id "receipt-key-1"}})
(def public-key "0123456789abcdef")
(defn make-store [] (store/new-resubmission-store "family" nil public-key))
(defn seed-pending [s o]
  (swap! (.state-atom s)
         assoc-in [:receipt-obligations (:receipt-obligation/id o)]
         (obligation/pending-entry o))
  s)

(deftest pending-discovery-is-deterministic-and-pure
  (let [s (make-store)
        o1 (obligation/build ordering candidate public-key)
        o2 (obligation/build
            (assoc ordering :transaction-ordering/hash
                   "sha256:2222222222222222222222222222222222222222222222222222222222222222")
            candidate public-key)
        before @(.state-atom s)]
    (seed-pending s o2)
    (seed-pending s o1)
    (is (= (sort-by #(get-in % [:receipt-obligation :receipt-obligation/id])
                    [(obligation/pending-entry o1) (obligation/pending-entry o2)])
           (store/pending-receipt-obligations s)))
    (is (= 2 (count (store/pending-receipt-obligations s))))
    (is (= before (dissoc @(.state-atom s) :receipt-obligations)))
    (is (empty? (store/pending-receipt-obligations (make-store)))))

  (deftest conditional-issuance-is-idempotent-and-conflict-safe
    (let [s (make-store)
          o (obligation/build ordering candidate public-key)
          id (:receipt-obligation/id o)
          r1 {:attempt-receipt/id
              "sha256:3333333333333333333333333333333333333333333333333333333333333333"}
          r2 {:attempt-receipt/id
              "sha256:4444444444444444444444444444444444444444444444444444444444444444"}]
      (seed-pending s o)
      (is (= :issued (:status (store/mark-receipt-issued! s id r1))))
      (is (= :idempotent (:status (store/mark-receipt-issued! s id r1))))
      (is (= :receipt-obligation/conflict
             (:status (store/mark-receipt-issued! s id r2))))
      (is (= "sha256:3333333333333333333333333333333333333333333333333333333333333333"
             (get-in (store/resolve-receipt-obligation s id)
                     [:receipt-obligation/issued-receipt-root])))
      (is (empty? (store/pending-receipt-obligations s)))))

  (deftest missing-and-invalid-obligations-fail-closed
    (let [s (make-store)
          o (obligation/build ordering candidate public-key)
          id (:receipt-obligation/id o)
          bad (assoc o :receipt-obligation/transaction-ordering-hash "not-a-root")]
      (is (= :receipt-obligation/not-found
             (:status (store/mark-receipt-issued! s id {:attempt-receipt/id "x"}))))
      (is (not (obligation/valid? bad)))
      (is (not (obligation/receipt-required?
                (assoc ordering :transaction/action :other) candidate public-key)))))

  (deftest concurrent-issuance-has-one-winning-transition
    (let [s (make-store)
          o (obligation/build ordering candidate public-key)
          id (:receipt-obligation/id o)
          receipt {:attempt-receipt/id
                   "sha256:5555555555555555555555555555555555555555555555555555555555555555"}
          _ (seed-pending s o)
          results (doall (pmap (fn [_] (store/mark-receipt-issued! s id receipt)) (range 8)))]
      (let [results results]
        (is (= 1 (count (filter #(= :issued (:status %)) results))))
        (is (= 7 (count (filter #(= :idempotent (:status %)) results))))
        (is (= :issued (:receipt-obligation/status
                        (store/resolve-receipt-obligation s id))))))))
