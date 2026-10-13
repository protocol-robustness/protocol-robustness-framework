(ns resolver-sim.research.reproduction-fixture-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.reproduction-fixture :as reproduction]))

(defn- root [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record {:label label})))

(def descriptor
  (analysis/build-result-descriptor
   {:measure/id :metric/qualification-affine-sum :measure/value-domain :integer
    :measure/unit :unit/abstract :measure/direction :lower-is-better
    :measure/comparison-semantics :exact-integer}))

(defn implementation [artifact]
  (assurance/build-implementation
   {:algorithm-id :algorithm/qualification-affine-threshold
    :artifact-root (root artifact) :language-runtime-family :clojure/jvm
    :resolved-dependency-set-root (root :resolved-dependencies)
    :semantic-version "1.0.0"}))

(defn input [overrides]
  (reproduction/build-input
   (merge {:result-descriptor-root (:result-descriptor/root descriptor)
           :case-root (root :qualification-case)
           :values [3 -2 5] :weights [4 7 2] :threshold 8}
          overrides)))

(deftest fresh-computation-is-separate-from-comparison
  (let [in (input {}) impl (implementation :artifact-a)
        execution (reproduction/execute in impl)
        original (:execution/fresh-result execution)
        evidence (reproduction/compare-execution execution original)
        changed-original (assoc original :result/value 999)
        changed-original (assoc changed-original :result/root
                                (analysis/result-root changed-original))
        disagreement (reproduction/compare-execution execution changed-original)]
    (is (reproduction/valid-input? in))
    (is (reproduction/valid-execution? execution))
    (is (= :completed (:execution/outcome execution)))
    (is (= :reproduced (:reproduction/status evidence)))
    (is (reproduction/valid-evidence? evidence))
    ;; Original/expected data is absent from the execution body and cannot
    ;; affect a calculation that has already been freshly performed.
    (is (not (contains? execution :authoritative-result/root)))
    (is (not (contains? execution :reproduction/expected-result)))
    (is (= execution (reproduction/execute in impl)))
    (is (= :counterexample-found (:reproduction/status disagreement)))
    (is (= (:reproduction-execution/root execution)
           (:reproduction-execution/root evidence)
           (:reproduction-execution/root disagreement)))))

(deftest inputs-and-implementations-bind-fresh-execution-not-runner-metadata
  (let [in (input {}) changed-input (input {:values [4 -2 5]})
        impl-a (implementation :artifact-a) impl-b (implementation :artifact-b)
        a (reproduction/execute in impl-a)
        b (reproduction/execute in impl-b)
        changed (reproduction/execute changed-input impl-a)]
    (is (not= (:reproduction-execution/root a) (:reproduction-execution/root b)))
    (is (not= (:reproduction-execution/root a) (:reproduction-execution/root changed)))
    (is (= (:execution/fresh-result a)
           (:execution/fresh-result b)))
    ;; Execution identity intentionally has no runner, host, schedule, or
    ;; completion-order slot to contaminate semantic reproduction identity.
    (is (not-any? #(contains? a %)
                  [:runner/reference :runner/site-id :host :execution/duration
                   :completion-order]))))

(deftest counterexamples-failures-and-incomparability-remain-explicit
  (let [in (input {:threshold -100}) impl (implementation :artifact-a)
        counterexample-execution (reproduction/execute in impl)
        authoritative (assoc (:execution/fresh-result counterexample-execution)
                             :result/classification :supported
                             :result/witness-root nil)
        authoritative (dissoc authoritative :result/witness-root)
        authoritative (assoc authoritative :result/root (analysis/result-root authoritative))
        disagreement (reproduction/compare-execution counterexample-execution authoritative)
        failed (reproduction/execute in impl (fn [_] (throw (ex-info "fixture failure" {}))))
        failed-evidence (reproduction/compare-execution failed authoritative)
        incompatible (assoc authoritative :result/case-root (root :other-case))
        incompatible (assoc incompatible :result/root (analysis/result-root incompatible))
        incomparable (reproduction/compare-execution counterexample-execution incompatible)]
    (is (= :counterexample-found (:reproduction/status disagreement)))
    (is (= (:execution/fresh-result counterexample-execution)
           (:reproduction/counterexample-result disagreement)))
    (is (:reproduction/counterexample-witness-root disagreement))
    (is (= :failed (:execution/outcome failed)))
    (is (= :failed (:reproduction/status failed-evidence)))
    (is (= :not-comparable (:reproduction/status incomparable)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (reproduction/compare-execution nil authoritative)))))

(deftest execution-api-cannot-accept-an-authoritative-result
  (let [in (input {}) impl (implementation :artifact-a)
        expected (:execution/fresh-result (reproduction/execute in impl))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (reproduction/execute in impl expected)))))
