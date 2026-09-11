(ns resolver-sim.stake.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.stake.ledger :as sl]))

(deftest with-fresh-ledger-isolates-state
  (sl/bond-stake :entity-a 1000)
  (is (= 1000 (sl/get-stake-amount :entity-a)))
  (sl/with-fresh-ledger
    (is (nil? (sl/get-stake :entity-a)))
    (is (zero? (sl/get-stake-amount :entity-a)))
    (sl/bond-stake :entity-b 500)
    (is (= 500 (sl/get-stake-amount :entity-b))))
  (is (= 1000 (sl/get-stake-amount :entity-a)))
  (is (zero? (sl/get-stake-amount :entity-b))))

(deftest init-ledger-sets-initial-stakes
  (sl/with-fresh-ledger
    (sl/init-ledger! {:attestor-a 2000 :attestor-b 1500})
    (is (= 2000 (sl/get-stake-amount :attestor-a)))
    (is (= 1500 (sl/get-stake-amount :attestor-b)))))

(deftest clear-ledger-empties-all-stakes
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 1000)
    (sl/clear-ledger!)
    (is (nil? (sl/get-stake :entity-a)))))

(deftest get-stake-returns-nil-for-unknown
  (sl/with-fresh-ledger
    (is (nil? (sl/get-stake :nonexistent)))
    (is (zero? (sl/get-stake-amount :nonexistent)))))

(deftest bond-stake-adds-to-empty-account
  (sl/with-fresh-ledger
    (let [entry (sl/bond-stake :entity-a 500)]
      (is (= 500 (:stake/amount entry)))
      (is (= :accountability-points (:stake/unit entry)))
      (is (= :bonded (:stake/status entry))))))

(deftest bond-stake-accumulates
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 500)
    (sl/bond-stake :entity-a 300)
    (is (= 800 (sl/get-stake-amount :entity-a)))))

(deftest bond-stake-accepts-bigint
  (sl/with-fresh-ledger
    (let [large (bigint 1e12)]
      (sl/bond-stake :entity-a large)
      (is (= large (sl/get-stake-amount :entity-a))))))

(deftest top-up-is-alias-for-bond
  (sl/with-fresh-ledger
    (sl/top-up-stake :entity-a 100)
    (is (= 100 (sl/get-stake-amount :entity-a)))
    (sl/top-up-stake :entity-a 50)
    (is (= 150 (sl/get-stake-amount :entity-a)))))

(deftest release-stake-deducts-from-existing
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 1000)
    (let [result (sl/release-stake :entity-a 300)]
      (is (:ok result))
      (is (= 700 (:stake/amount (:entry result))))
      (is (= 700 (sl/get-stake-amount :entity-a))))))

(deftest release-stake-rejects-negative-amount
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 1000)
    (let [result (sl/release-stake :entity-a -50)]
      (is (not (:ok result)))
      (is (= :invalid-amount (:error result))))))

(deftest release-stake-rejects-zero-amount
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 1000)
    (let [result (sl/release-stake :entity-a 0)]
      (is (not (:ok result)))
      (is (= :invalid-amount (:error result))))))

(deftest release-stake-rejects-excessive-amount
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 100)
    (let [result (sl/release-stake :entity-a 200)]
      (is (not (:ok result)))
      (is (= :insufficient-stake (:error result))))))

(deftest release-stake-rejects-nil-amount
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 100)
    (let [result (sl/release-stake :entity-a nil)]
      (is (not (:ok result))))))

(deftest slash-stake-deducts-amount
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 1000)
    (let [result (sl/slash-stake :entity-a 400)]
      (is (:ok result))
      (is (= 400 (:slashed-amount result)))
      (is (= 600 (sl/get-stake-amount :entity-a))))))

(deftest slash-stake-caps-at-current-balance
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 500)
    (let [result (sl/slash-stake :entity-a 1000)]
      (is (:ok result))
      (is (= 500 (:slashed-amount result)))
      (is (zero? (sl/get-stake-amount :entity-a))))))

(deftest slash-stake-rejects-negative-amount
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 500)
    (let [result (sl/slash-stake :entity-a -50)]
      (is (not (:ok result)))
      (is (= :invalid-amount (:error result))))))

(deftest slash-stake-rejects-zero-on-empty
  (sl/with-fresh-ledger
    (let [result (sl/slash-stake :entity-a 500)]
      (is (not (:ok result)))
      (is (= :no-stake (:error result))))))

(deftest multiple-entities-are-independent
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 1000)
    (sl/bond-stake :entity-b 2000)
    (sl/slash-stake :entity-a 300)
    (is (= 700 (sl/get-stake-amount :entity-a)))
    (is (= 2000 (sl/get-stake-amount :entity-b)))))

(deftest apply-stake-actions-batch-bond-slash
  (sl/with-fresh-ledger
    (sl/bond-stake :entity-a 2000)
    (let [result (sl/apply-stake-actions
                  [{:action/type :stake/slash
                    :attestor/id :entity-a
                    :stake/amount 500}
                   {:action/type :stake/release
                    :attestor/id :entity-a
                    :stake/amount 300}])]
      (is (= 2 (count (:applied result))))
      (is (empty? (:errors result)))
      (is (= 1200 (sl/get-stake-amount :entity-a))))))

(deftest apply-stake-actions-reports-errors
  (sl/with-fresh-ledger
    (let [result (sl/apply-stake-actions
                  [{:action/type :stake/slash
                    :attestor/id :entity-a
                    :stake/amount 500}
                   {:action/type :stake/release
                    :attestor/id :entity-a
                    :stake/amount 300}])]
      (is (= 2 (count (:errors result)))) ;; both actions fail (no stake)
      (is (= 0 (count (:applied result)))))))

(deftest apply-stake-actions-unknown-type-skipped
  (sl/with-fresh-ledger
    (let [result (sl/apply-stake-actions
                  [{:action/type :stake/unknown
                    :attestor/id :entity-a
                    :stake/amount 500}])]
      (is (= 1 (count (:errors result))))
      (is (= :unknown-action-type (:error (first (:errors result))))))))
