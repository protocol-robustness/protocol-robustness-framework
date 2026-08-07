(ns resolver-sim.evidence.confidence-test
  "Tests for confidence composition: versioned policies, scope handling,
   fail-closed validation, and recomputability of aggregates."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.confidence :as c]))

(def ^:private cpt
  {:subject-hash "sha256:aaaa" :role :required :level :high :scope :unbounded})

(defn- req
  ([level] (assoc cpt :level level))
  ([subject level] (assoc cpt :subject-hash subject :level level))
  ([subject level scope] (assoc cpt :subject-hash subject :level level :scope scope)))

(defn- sup
  ([level] (assoc cpt :role :supporting :level level))
  ([level scope] (assoc cpt :role :supporting :level level :scope scope)))

;; ─────────────────────────────────────────────────────────────────────────────
;; all-required: minimum level, intersection scope
;; ─────────────────────────────────────────────────────────────────────────────

(deftest all-required-takes-minimum
  (let [agg (:confidence/aggregate
             (c/compose-confidence [(req "a" :high) (req "b" :medium) (req "c" :low)]))]
    (is (= :low (:level agg)))))

(deftest all-required-preserves-component-sequence
  (let [profile (c/compose-confidence [(req "a" :high) (req "b" :medium) (req "c" :low)])]
    (is (= [:high :medium :low] (map :level (:confidence/components profile))))
    (is (= :prf.confidence/all-required-v1 (:confidence/composition-policy profile)))
    (is (= :all-required (:confidence/relation profile)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; required vs supporting: supporting must NOT lower the aggregate
;; ─────────────────────────────────────────────────────────────────────────────

(deftest supporting-does-not-lower-aggregate
  (testing "a low-confidence supporting component does not drag an all-required aggregate down"
    (let [profile (c/compose-confidence [(req "a" :high) (req "b" :medium) (sup :low)])]
      (is (= :medium (:level (:confidence/aggregate profile))))
      (is (= :all-required (:confidence/relation profile))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; scope retention / adversarial scope handling
;; ─────────────────────────────────────────────────────────────────────────────

(deftest scope-intersection-retains-limitation
  (testing "[:high/unbounded :high/trace-bounded] must NOT collapse to :high"
    (let [agg (:confidence/aggregate
               (c/compose-confidence [(req "a" :high :unbounded)
                                      (req "b" :high :trace-bounded)]))]
      (is (= :high (:level agg)))
      (is (= :trace-bounded (:scope agg)) "the limitation must be retained"))))

(deftest scope-intersection-nested
  (is (= :bounded (c/scope-intersection :bounded :unbounded)))
  (is (= :trace-bounded (c/scope-intersection :trace-bounded :bounded)))
  (is (= :unbounded (c/scope-union :unbounded :trace-bounded)))
  (is (= :bounded (c/scope-union :bounded :trace-bounded))))

;; ─────────────────────────────────────────────────────────────────────────────
;; other relations
;; ─────────────────────────────────────────────────────────────────────────────

(deftest any-sufficient-takes-maximum
  (let [agg (:confidence/aggregate
             (c/compose-confidence [(req "a" :medium) (req "b" :low)]
                                   :prf.confidence/any-sufficient-v1))]
    (is (= :medium (:level agg)))))

(deftest corroboration-requires-at-least-two
  (is (thrown? clojure.lang.ExceptionInfo
               (c/compose-confidence [(req "a" :high)]
                                     :prf.confidence/independent-corroboration-v1)))
  (testing "two corroborating required sources raise/preserve assurance"
    (let [agg (:confidence/aggregate
               (c/compose-confidence [(req "a" :medium) (req "b" :medium)]
                                     :prf.confidence/independent-corroboration-v1))]
      (is (= :medium (:level agg))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; empty composition -> defined result, not a vacuous :high
;; ─────────────────────────────────────────────────────────────────────────────

(deftest empty-composition-is-not-evaluated
  (let [agg (:confidence/aggregate (c/compose-confidence []))]
    (is (= :not-evaluated (:level agg)))
    (is (= [:empty-composition] (:confidence/reasons (c/compose-confidence []))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; fail-closed validation
;; ─────────────────────────────────────────────────────────────────────────────

(deftest unknown-level-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (c/compose-confidence [(req "a" :very-high)]))))

(deftest unknown-scope-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (c/compose-confidence [(req "a" :high :everywhere)]))))

(deftest unknown-role-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (c/compose-confidence [(assoc cpt :role :optional)]))))

(deftest string-where-keyword-required-fails-closed
  (testing "a string level is NOT accepted in a component"
    (is (thrown? clojure.lang.ExceptionInfo
                 (c/compose-confidence [(assoc cpt :level "high")])))))

(deftest missing-subject-binding-fails-closed
  (testing "confidence must not be concatenated independently of its subject"
    (is (thrown? clojure.lang.ExceptionInfo
                 (c/compose-confidence [(dissoc cpt :subject-hash)])))))

(deftest required-component-without-level-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (c/compose-confidence [(assoc cpt :level nil)]))))

(deftest unknown-policy-id-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (c/compose-confidence [(req "a" :high)] :confidence/ordinal-v2))))

;; ─────────────────────────────────────────────────────────────────────────────
;; recomputability
;; ─────────────────────────────────────────────────────────────────────────────

(deftest verify-composition-accepts-derivable-aggregate
  (let [profile (c/compose-confidence [(req "a" :high) (req "b" :medium) (req "c" :low)])]
    (is (true? (c/verify-composition profile)))))

(deftest verify-composition-rejects-tampered-aggregate
  (let [profile (-> (c/compose-confidence [(req "a" :high) (req "b" :medium)])
                    (assoc-in [:confidence/aggregate :level] :high))]
    (is (false? (c/verify-composition profile))
        "a producer may not state an aggregate inconsistent with committed inputs")))

;; ─────────────────────────────────────────────────────────────────────────────
;; canonical ordering (set vs sequence semantics)
;; ─────────────────────────────────────────────────────────────────────────────

(deftest canonical-components-are-order-stable-by-subject
  (let [c1 [(req "b" :high) (req "a" :low)]
        c2 [(req "a" :low) (req "b" :high)]]
    (is (= (c/canonical-components c1) (c/canonical-components c2))
        "permutation must not change a set-semantics commitment")
    (is (not= c1 (c/canonical-components c1)))))

(deftest compose-preserves-explicit-order
  (testing "compose does not silently reorder; order-sensitive callers commit :order"
    (let [ordered [(req "a" :high) (req "b" :low)]]
      (is (= [:high :low] (map :level (:confidence/components (c/compose-confidence ordered))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; scope validation helpers
;; ─────────────────────────────────────────────────────────────────────────────

(deftest scope-intersection-rejects-unknown
  (is (nil? (c/scope-intersection :unbounded :not-a-scope)))
  (is (nil? (c/scope-union :trace-bounded :bogus))))

;; ─────────────────────────────────────────────────────────────────────────────
;; concatenate-bound: hash-bound concatenation of the full component sequence
;; ─────────────────────────────────────────────────────────────────────────────

(deftest concatenate-bound-returns-canonical-sha256-ref
  (let [ref (c/concatenate-bound [(req "a" :high) (req "b" :medium)])]
    (is (string? ref))
    (is (re-matches #"sha256:[0-9a-f]{64}" ref))))

(deftest concatenate-bound-set-semantics-permutation-stable
  (testing "default :by-subject ordering is permutation-invariant (set semantics)"
    (let [a [(req "a" :high) (req "b" :medium) (req "c" :low)]
          b [(req "c" :low) (req "a" :high) (req "b" :medium)]]
      (is (= (c/concatenate-bound a) (c/concatenate-bound b))))))

(deftest concatenate-bound-as-given-order-sensitive
  (testing ":as-given preserves order, so permutation changes the commitment"
    (let [a [(req "a" :high) (req "b" :medium)]
          b [(req "b" :medium) (req "a" :high)]]
      (is (not= (c/concatenate-bound a :as-given)
                (c/concatenate-bound b :as-given))))))

(deftest concatenate-bound-deterministic
  (let [components [(req "x" :high :trace-bounded) (req "y" :low) (sup :medium)]]
    (is (= (c/concatenate-bound components)
           (c/concatenate-bound components)))))

(deftest concatenate-bound-preserves-full-sequence
  (testing "commitment binds all components, not a collapsed minimum"
    (is (not= (c/concatenate-bound [(req "a" :high) (req "b" :low)])
              (c/concatenate-bound [(req "a" :high)])))))
