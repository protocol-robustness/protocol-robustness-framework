(ns resolver-sim.benchmark.corpus-validation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.corpus-validation :as corpus-validation]
            [resolver-sim.yield.invariants :as yield-invariants]))

(deftest registry-reachable-benchmark-corpus-is-classpath-loadable
  (is (= {:packs 2 :benchmarks 11 :status :passed}
         (corpus-validation/validate-corpus!))))

(deftest check-all-intents-have-contract-fields
  (let [result (corpus-validation/check-all-intents-have-contract-fields)]
    (is (= :all-intents-have-contract-fields (:check result)))
    (is (zero? (:issue-count result))
        (str "All hash intents should have complete contract fields. Issues: "
             (:issues result)))))

(deftest check-aggregate-validates-yield-invariants
  (let [result (corpus-validation/check-aggregate)]
    (is (= :aggregate (:check result)))
    (is (boolean? (:valid? result)))))

(deftest check-aggregate-empty-world-passes
  (let [result (corpus-validation/check-aggregate {})]
    (is (= :aggregate (:check result)))
    (is (:valid? result))
    (is (empty? (:violations result)))))

(deftest check-aggregate-nil-world-passes
  (let [result (corpus-validation/check-aggregate nil)]
    (is (= :aggregate (:check result)))
    (is (:valid? result))
    (is (empty? (:violations result)))))

(deftest check-aggregate-delegates-to-yield-invariant
  (let [world {:yield/positions
               {0 {:module/id :modular-v2 :token :ETH
                   :principal 1000 :shortfall {:basis-amount 1000}}
                1 {:module/id :modular-v2 :token :ETH
                   :principal 1000 :shortfall {:basis-amount 1000}}}}
        result (corpus-validation/check-aggregate world)
        invariant-result (yield-invariants/check-aggregate world)]
    (is (= :aggregate (:check result)))
    (is (= (:holds? invariant-result) (:valid? result)))
    (is (= (:violations invariant-result) (:violations result)))))

(deftest check-aggregate-violation-attribution
  (let [world {:yield/positions
               {0 {:module/id :modular-v2 :token :ETH
                   :principal 100 :shortfall {:basis-amount 200
                                              :fulfilled-amount 10
                                              :deferred-amount 10
                                              :haircut-amount 10}}
                1 {:module/id :modular-v2 :token :ETH
                   :principal 100 :shortfall {:basis-amount 200
                                              :fulfilled-amount 10
                                              :deferred-amount 10
                                              :haircut-amount 10}}}}
        result (corpus-validation/check-aggregate world)]
    (is (false? (:valid? result)))
    (is (seq (:violations result)))
    (is (every? #(contains? % :module-id) (:violations result)))))

(deftest check-cap-respecting-default
  (let [result (corpus-validation/check-cap-respecting)]
    (is (= :cap-respecting (:check result)))
    (is (boolean? (:holds? result)))
    (is (vector? (:violations result)))))

(deftest check-conservation-default
  (let [result (corpus-validation/check-conservation)]
    (is (= :conservation (:check result)))
    (is (boolean? (:holds? result)))
    (is (vector? (:violations result)))))

;; P0: Reference Closure Tests

(deftest check-reference-closure
  (let [result (corpus-validation/check-reference-closure)]
    (is (= :reference-closure (:check result)))
    (is (boolean? (:valid? result)))))

(deftest check-no-orphan-artifacts
  (let [result (corpus-validation/check-no-orphan-artifacts)]
    (is (= :no-orphan-artifacts (:check result)))
    (is (vector? (:orphan-paths result)))))

(deftest check-hash-integrity
  (let [result (corpus-validation/check-hash-integrity)]
    (is (= :hash-integrity (:check result)))
    (is (vector? (:mismatched result)))))

(deftest check-canonical-fixed-point
  (let [result (corpus-validation/check-canonical-fixed-point)]
    (is (= :canonical-fixed-point (:check result)))
    (is (integer? (:failures result)))))

(deftest check-unique-identities
  (let [result (corpus-validation/check-unique-identities)]
    (is (= :unique-identities (:check result)))
    (is (vector? (:duplicates result)))))

(deftest check-schema-version-support
  (let [result (corpus-validation/check-schema-version-support)]
    (is (= :schema-version-support (:check result)))
    (is (vector? (:unsupported-versions result)))))