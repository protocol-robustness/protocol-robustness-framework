(ns resolver-sim.benchmark.corpus-validation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.corpus-validation :as corpus-validation]
            [resolver-sim.yield.invariants :as yield-invariants]))

(deftest registry-reachable-benchmark-corpus-is-classpath-loadable
  (let [result (corpus-validation/validate-corpus!)]
    (is (= :passed (:status result)))
    (is (= 2 (:packs result)))
    (is (= 11 (:benchmarks result)))
    (is (pos? (:hash-intent-count result)))
    (is (some? (:content-root result)))
    (is (some? (:reference-closure-root result)))))

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

;; ── Allocation domain invariants aggregate ─────────────────────────────────

(defn- make-direct-evidence-node
  "Create a minimal evidence node with a direct result for unit testing
   the focused evaluators (no shadow/projection artifacts needed)."
  [result]
  [{:result {:claims/direct-result result}}])

(deftest check-non-negative-allocation-passes
  (let [evidence (make-direct-evidence-node
                  {:available 150
                   :recovered-total 100
                   :unmet-total 50
                   :remainder 0
                   :allocations [{:id :a :paid 100 :unmet 0 :owed 100 :cap 1000 :basis-amount 100}
                                 {:id :b :paid 50 :unmet 50 :owed 100 :cap 100 :basis-amount 100}]})
        result (corpus-validation/check-allocation-domain-invariants evidence)
        nn-check (first (filter #(= (:name %) :pro-rata/non-negative-allocation) (:checks result)))]
    (is (= :pass (:status result)))
    (is (boolean? (:holds? nn-check)))))

(deftest check-non-negative-allocation-fails-on-negative
  (let [evidence (make-direct-evidence-node
                  {:allocations [{:id :a :paid -5 :unmet 0 :owed 100 :cap 1000 :basis-amount 100}]})
        result (corpus-validation/check-allocation-domain-invariants evidence)
        nn-check (first (filter #(= (:name %) :pro-rata/non-negative-allocation) (:checks result)))]
    (is (= :fail (:status result)))
    (is (false? (:holds? nn-check)))
    (is (seq (:violations nn-check)))))

(deftest check-allocation-not-above-request-fails
  (let [evidence (make-direct-evidence-node
                  {:allocations [{:id :a :paid 150 :owed 100 :unmet 0 :cap 1000}]})
        result (corpus-validation/check-allocation-domain-invariants evidence)
        req-check (first (filter #(= (:name %) :pro-rata/allocation-not-above-request) (:checks result)))]
    (is (= :fail (:status result)))
    (is (false? (:holds? req-check)))))

(deftest check-integer-domain-fails-on-non-integer
  (let [evidence (make-direct-evidence-node
                  {:allocations [{:id :a :paid 100.5 :unmet 0 :owed 100 :cap 1000}]})
        result (corpus-validation/check-allocation-domain-invariants evidence)
        dom-check (first (filter #(= (:name %) :pro-rata/integer-domain) (:checks result)))]
    (is (= :fail (:status result)))
    (is (false? (:holds? dom-check)))))

(deftest check-residual-accounting-passes
  (let [evidence (make-direct-evidence-node
                  {:available 150
                   :recovered-total 100
                   :unmet-total 50
                   :remainder 0
                   :allocations [{:id :a :paid 100 :unmet 50 :owed 100 :cap 1000}]})
        result (corpus-validation/check-allocation-domain-invariants evidence)
        resid-check (first (filter #(= (:name %) :pro-rata/residual-accounting) (:checks result)))]
    (is (= :pass (:status result)))
    (is (true? (:holds? resid-check)))))

(deftest check-residual-accounting-fails
  (let [evidence (make-direct-evidence-node
                  {:available 200
                   :recovered-total 100
                   :unmet-total 50
                   :remainder 0
                   :allocations [{:id :a :paid 100 :unmet 50 :owed 100 :cap 1000}]})
        result (corpus-validation/check-allocation-domain-invariants evidence)
        resid-check (first (filter #(= (:name %) :pro-rata/residual-accounting) (:checks result)))]
    (is (= :fail (:status result)))
    (is (false? (:holds? resid-check)))))

(deftest check-full-fill-consistency-fails-on-partial-without-unmet
  (let [evidence (make-direct-evidence-node
                  {:allocations [{:id :a :paid 50 :unmet 0 :owed 100 :cap 1000}]
                   :unmet-total 0})
        result (corpus-validation/check-allocation-domain-invariants evidence)
        fill-check (first (filter #(= (:name %) :pro-rata/full-fill-consistency) (:checks result)))]
    (is (= :fail (:status result)))
    (is (false? (:holds? fill-check)))))

(deftest check-allocation-domain-invariants-passes-with-full-evidence
  (let [evidence (make-direct-evidence-node
                  {:available 100
                   :recovered-total 100
                   :unmet-total 0
                   :remainder 0
                   :allocations [{:id :a :paid 100 :unmet 0 :owed 100 :cap 1000 :basis-amount 100}]})
        result (corpus-validation/check-allocation-domain-invariants evidence)]
    (is (= :pass (:status result)))
    (is (= 5 (:constituent-count result)))
    (is (every? #(:holds? %) (:checks result)))))

(deftest check-allocation-domain-invariants-default-uses-test-vectors
  (let [result (corpus-validation/check-allocation-domain-invariants)]
    (is (= :allocation-domain-invariants (:check result)))
    (is (= :pass (:status result)))
    (is (pos? (:constituent-count result))
        (str "Should run constituent checks against test vectors"))
    (is (every? #(:holds? %) (:checks result)))))

;; ── Expected results recompute ─────────────────────────────────────────────

(deftest check-expected-results-recompute
  (let [result (corpus-validation/check-expected-results-recompute)]
    (is (= :expected-results-recompute (:check result)))
    (is (= :pass (:status result)))
    (is (pos? (:vector-count result)))
    (is (vector? (:mismatches result)))
    (is (empty? (:mismatches result))))

;; ── Negative corpus / rejection witnesses ────────────────────────────────────

  (deftest check-negative-corpus-rejects-all-fixtures
    (let [result (corpus-validation/check-negative-corpus)]
      (is (= :negative-corpus (:check result)))
      (is (= :pass (:status result)))
      (is (pos? (:fixture-count result))
          (str "Should have negative fixtures. Results: " (:results result)))
      (is (every? #(= :pass (:status %)) (:results result))
          (str "All negative fixtures should pass. Results: "
               (map #(select-keys % [:fixture :status :expected-reasons :observed-reasons])
                    (:results result)))))))

;; ── P1: Order independence ────────────────────────────────────────────────────

(deftest check-order-independence-passes
  (let [result (corpus-validation/check-order-independence)]
    (is (= :order-independence (:check result)))
    (is (= :pass (:status result))
        (str "Corpus enumeration should be order-independent. Diff: " (:differences result)))
    (is (= 2 (:orderings-tested result)))
    (is (pos? (:pack-count result)))
    (is (pos? (:benchmark-count result)))))

;; ── P1: Verification fixed-point ──────────────────────────────────────────────

(deftest check-verification-fixed-point-passes
  (let [result (corpus-validation/check-verification-fixed-point)]
    (is (= :verification-fixed-point (:check result)))
    (is (= :pass (:status result))
        (str "Verification report should survive canonical round-trip. Mismatches: "
             (:mismatched result)))
    (is (pos? (:vector-count result)))
    (is (string? (:semantic-hash result)))))

;; ── P1: Corpus manifest / root ────────────────────────────────────────────────

(deftest check-corpus-produces-validated-manifest
  (let [result (corpus-validation/check-corpus)]
    (is (= :corpus (:check result)))
    (is (= :pass (:status result))
        (str "All corpus checks should pass. Error: " (:error result)))
    (is (some? (:manifest result)))
    (is (some? (:verification-root result)))
    (is (= 19 (:semantic-checks result)))
    (is (true? (:all-checks-pass? result)))
    (let [manifest (:manifest result)]
      (is (= "benchmark-corpus.v1" (:corpus/schema manifest)))
      (is (= 2 (:corpus/packs manifest)))
      (is (= 11 (:corpus/benchmark-count manifest)))
      (is (some? (:corpus/content-root manifest)))
      (is (some? (:corpus/reference-closure-root manifest)))
      (is (= "corpus-verification.v2" (:corpus/verification-profile manifest)))
      (is (= :verified (:corpus/status manifest))))))

(deftest check-claim-registry-closure-passes
  (let [result (corpus-validation/check-claim-registry-closure)]
    (is (= :claim-registry-closure (:check result)))
    (is (= :pass (:status result))
        (str "Claim registry should be closure-consistent. Mismatches: "
             (:evaluators-without-definitions result)
             (:definitions-without-evaluators result)
             (:duplicate-definitions result)
             (:schema-errors result)))
    (is (integer? (:evaluator-count result)))
    (is (integer? (:definition-count result)))
    (is (empty? (:evaluators-without-definitions result)))
    (is (empty? (:definitions-without-evaluators result)))
    (is (empty? (:duplicate-definitions result)))
    (is (empty? (:schema-errors result)))))

(deftest check-allocation-domain-invariants-evaluates-all-claims
  (let [result (corpus-validation/check-allocation-domain-invariants)]
    (is (= :allocation-domain-invariants (:check result)))
    (is (= :pass (:status result)))
    (is (pos? (:constituent-count result)))
    (doseq [expected [:pro-rata/non-negative-allocation
                      :pro-rata/allocation-not-above-request
                      :pro-rata/integer-domain
                      :pro-rata/residual-accounting
                      :pro-rata/full-fill-consistency]]
      (is (some #(= expected (:name %)) (:checks result))
          (str "Expected check " expected " to be in allocation domain invariants checks")))))

(deftest check-verifier-registry-consistency-passes
  (let [result (corpus-validation/check-verifier-registry-consistency)]
    (is (= :verifier-registry-consistency (:check result)))
    (is (= :pass (:status result))
        (str "Verifier registry roots should be consistent. Error: "
             (:error result)))
    (is (boolean (:matches? result)))
    (is (some? (:configuration-verifier-root result)))
    (is (some? (:transition-verifier-root result)))))