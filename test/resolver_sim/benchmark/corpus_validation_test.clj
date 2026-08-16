(ns resolver-sim.benchmark.corpus-validation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.corpus-validation :as corpus-validation]))

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