(ns resolver-sim.trace-metadata-test
  "Boundary tests for the trace vocabulary extraction.

   Verifies:
   1. Core vocabulary shape — sets of keywords, no SEW-specific terms
   2. SEW classifier outputs are members of core vocabulary sets
   3. Invariant category mapping is valid
   4. Compatibility aliases match core values"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.trace-metadata :as core]
            [resolver-sim.protocols.sew.trace-metadata :as sew-meta]))

;; ===========================================================================
;; 1. Core vocabulary shape
;; ===========================================================================

(def ^:private core-collections
  "All vocabulary sets defined in resolver-sim.trace-metadata."
  [#'core/actor-types
   #'core/actor-roles
   #'core/adversary-types
   #'core/adversary-traits
   #'core/transition-types
   #'core/effect-types
   #'core/invariant-category-types
   #'core/scenario-types
   #'core/outcome-types
   #'core/resolution-quality-values
   #'core/resolution-finality-values
   #'core/resolution-timing-values
   #'core/resolution-participation-values
   #'core/resolution-escalation-values
   #'core/resolution-economic-values
   #'core/resolution-failure-values
   #'core/resolution-integrity-values])

(deftest all-core-collections-are-sets
  (doseq [v core-collections]
    (is (set? @v) (str (symbol v) " is not a set"))))

(deftest all-core-collections-contain-only-keywords
  (doseq [v core-collections]
    (is (every? keyword? @v)
        (str (symbol v) " contains non-keyword values"))))

(deftest all-core-collections-have-no-duplicates
  (doseq [v core-collections]
    (is (= (count @v) (count (set @v)))
        (str (symbol v) " contains duplicate values"))))

(deftest no-sew-namespaced-keywords-in-core
  (doseq [v core-collections]
    (is (not-any? #(= (namespace %) "sew") @v)
        (str (symbol v) " contains :sew/... namespaced keywords"))
    ;; Also check for SEW-specific action names as bare keywords
    (is (not-any? #{:create-escrow :raise-dispute :sender-cancel
                    :recipient-cancel :automate-timed-actions
                    :execute-resolution :execute-pending-settlement} @v)
        (str (symbol v) " contains SEW-specific action keywords"))))

;; ===========================================================================
;; 2. SEW classifier output membership
;; ===========================================================================

(deftest scenario-classifier-output-is-valid
  (let [test-scenarios [{:scenario-id "S01_baseline-happy-path"}
                        {:scenario-id "profit-maximizer-slash-lifecycle"}
                        {:scenario-id "edge-case-snapshot-isolation"}
                        {:scenario-id "depletion-cascade"}
                        {:scenario-type :baseline}]]
    (doseq [s test-scenarios]
      (let [result (sew-meta/classify-scenario s)]
        (is (contains? core/scenario-types result)
            (str "classify-scenario returned " result " which is not in core/scenario-types"))))))

(deftest outcome-classifier-output-is-valid
  (let [test-results [{:outcome :pass :metrics {:invariant-violations 0}}
                      {:outcome :fail :halt-reason :invariant-violation}
                      {:outcome :fail :halt-reason :open-disputes-at-end}
                      {:outcome :fail}
                      {:outcome :pass :metrics {:invariant-violations 0}
                       :expected-fail? false}]]
    (doseq [r test-results]
      (let [result (sew-meta/classify-outcome r nil)]
        (is (contains? core/outcome-types result)
            (str "classify-outcome returned " result " which is not in core/outcome-types"))))))

(deftest resolution-classifier-output-is-valid
  (testing "Each resolution dimension is a member of the corresponding core set"
    (let [states-and-fn
          [[:released  (fn [w] (assoc-in w [:escrow-transfers 0 :escrow-state] :released))]
           [:refunded  (fn [w] (assoc-in w [:escrow-transfers 0 :escrow-state] :refunded))]
           [:resolved  (fn [w] (assoc-in w [:escrow-transfers 0 :escrow-state] :resolved))]
           [:disputed  (fn [w] (assoc-in w [:escrow-transfers 0 :escrow-state] :disputed))]]]
      (doseq [[state-label world-fn] states-and-fn]
        (let [world (world-fn {})
              res   (sew-meta/classify-resolution world 0)]
          (is (contains? core/resolution-finality-values (:resolution/finality res))
              (str "finality " (:resolution/finality res) " for " state-label " not in core set"))
          (is (contains? core/resolution-timing-values (:resolution/timing res))
              (str "timing " (:resolution/timing res) " for " state-label " not in core set"))
          (is (contains? core/resolution-integrity-values (:resolution/integrity res))
              (str "integrity " (:resolution/integrity res) " for " state-label " not in core set"))
          (is (contains? core/resolution-escalation-values (:resolution/escalation res))
              (str "escalation " (:resolution/escalation res) " for " state-label " not in core set"))
          (is (contains? core/resolution-participation-values (:resolution/participation res))
              (str "participation " (:resolution/participation res) " for " state-label " not in core set")))))))

(deftest actor-classifier-output-is-valid
  (testing "classify-actor-type produces members of core/actor-types"
    (is (contains? core/actor-types (sew-meta/classify-actor-type {:role "resolver"})))
    (is (contains? core/actor-types (sew-meta/classify-actor-type {:role "governance"})))
    (is (contains? core/actor-types (sew-meta/classify-actor-type {:role "keeper"}))))

  (testing "classify-actor-role produces members of core/actor-roles"
    (is (contains? core/actor-roles (sew-meta/classify-actor-role {:strategy "honest"})))
    (is (contains? core/actor-roles (sew-meta/classify-actor-role {:strategy "malicious"})))
    (is (contains? core/actor-roles (sew-meta/classify-actor-role {:strategy "lazy"})))))

(deftest adversary-classifier-output-is-valid
  (testing "classify-adversary with explicit type produces valid type"
    (let [result (sew-meta/classify-adversary
                  {:adversary/type :profit-maximizer
                   :adversary/traits #{:multi-step :capital-efficient}})]
      (is (contains? core/adversary-types (:adversary/type result))
          (str "adversary type " (:adversary/type result) " not in core/adversary-types"))))

  (testing "adversary traits are in core/adversary-traits"
    (let [result (sew-meta/classify-adversary
                  {:adversary/type :forking-strategist
                   :adversary/traits #{:multi-step :adaptive}})]
      (doseq [trait (:adversary/traits result)]
        (is (contains? core/adversary-traits trait)
            (str "adversary trait " trait " not in core/adversary-traits"))))))

(deftest transition-type-output-is-valid
  (testing "transition-type produces :transition/* keywords within core/transition-types"
    (let [mapping {"create-escrow"              :transition/creation
                   "raise-dispute"              :transition/state-change
                   "execute-resolution"         :transition/resolution
                   "escalate-dispute"           :transition/escalation
                   "automate-timed-actions"     :transition/maintenance
                   "auto-cancel-disputed"       :transition/timeout
                   "propose-fraud-slash"        :transition/governance
                   "execute-fraud-slash"        :transition/economic}]
      (doseq [[action expected] mapping]
        (let [result (sew-meta/transition-type action)]
          (is (= expected result) (str "transition-type " action " should be " expected)))
        ;; Also verify the transition namespace part matches a core type
        (let [result (sew-meta/transition-type action)
              kw-name (keyword (name result))]
          (is (contains? core/transition-types kw-name)
              (str "transition-type " action " → " result " namespace part "
                   kw-name " not in core/transition-types")))))))

;; ===========================================================================
;; 3. Invariant mapping validity
;; ===========================================================================

;; ===========================================================================
;; 5. Resolution-quality->confidence mapping
;; ===========================================================================

(def ^:private expected-quality->confidence
  "Only resolution-outcome-values have confidence mappings.
   Legacy confidence values (:high-confidence, :low-confidence) return nil."
  {:correct   {:level :high   :status :final      :scope :unbounded}
   :incorrect {:level :low    :status :final      :scope :unbounded}
   :contested {:level nil     :status :provisional :scope :unbounded}
   :unverified{:level nil     :status :provisional :scope :bounded}})

(deftest resolution-quality->confidence-maps-outcome-values
  (doseq [q core/resolution-outcome-values]
    (let [expected (get expected-quality->confidence q)
          actual (core/resolution-quality->confidence q)]
      (is (= expected actual)
          (str "resolution-quality->confidence " q " — expected " expected " got " actual)))))

(deftest resolution-quality->confidence-returns-nil-for-confidence-values
  (doseq [q core/resolution-confidence-legacy-values]
    (is (nil? (core/resolution-quality->confidence q))
        (str "resolution-quality->confidence " q " must return nil — it is a confidence descriptor, not an outcome assessment"))))

(deftest resolution-quality->confidence-unknown-value
  (is (nil? (core/resolution-quality->confidence :unknown-quality))
      "unrecognized quality value must return nil"))

(deftest resolution-quality->confidence-covers-all-outcome-values
  (is (= (set (keys expected-quality->confidence)) core/resolution-outcome-values)
      "Every resolution-outcome-value must have a mapping entry"))

;; ── Classifier tests ──────────────────────────────────────────────────────

(deftest classify-resolution-quality-correct-on-match
  (is (= :correct
         (core/classify-resolution-quality
          {:authoritative-expected-outcome :released
           :actual-outcome :released
           :has-unresolved-dissent? false
           :verification-facts-complete? true}))
      "authoritative match must produce :correct"))

(deftest classify-resolution-quality-incorrect-on-mismatch
  (is (= :incorrect
         (core/classify-resolution-quality
          {:authoritative-expected-outcome :released
           :actual-outcome :refunded
           :has-unresolved-dissent? false
           :verification-facts-complete? true}))
      "authoritative mismatch must produce :incorrect"))

(deftest classify-resolution-quality-contested-on-dissent
  (is (= :contested
         (core/classify-resolution-quality
          {:authoritative-expected-outcome nil
           :actual-outcome :released
           :has-unresolved-dissent? true
           :verification-facts-complete? true}))
      "unresolved dissent without authoritative truth must produce :contested"))

(deftest classify-resolution-quality-unverified-without-facts
  (is (= :unverified
         (core/classify-resolution-quality
          {:authoritative-expected-outcome nil
           :actual-outcome nil
           :has-unresolved-dissent? false
           :verification-facts-complete? false}))
      "insufficient facts must produce :unverified"))

(deftest classify-resolution-quality-no-implicit-correctness
  (is (not= :correct
            (core/classify-resolution-quality
             {:authoritative-expected-outcome nil
              :actual-outcome :released
              :has-unresolved-dissent? false
              :verification-facts-complete? true}))
      "release alone must not imply :correct without authoritative truth"))

(deftest classify-resolution-quality-requires-actual-outcome-with-authoritative
  (is (thrown? Exception
               (core/classify-resolution-quality
                {:authoritative-expected-outcome :released
                 :actual-outcome nil}))
      "authoritative truth without actual outcome must be rejected"))

(deftest invariant-category-values-are-valid
  (is (every? core/invariant-category-types (vals sew-meta/invariant-categories))
      "Every value in sew-meta/invariant-categories must be in core/invariant-category-types"))

;; ===========================================================================
;; 4. Compatibility aliases match core values
;; ===========================================================================

(deftest aliases-match-core
  (is (= core/actor-types               sew-meta/actor-types))
  (is (= core/actor-roles               sew-meta/actor-roles))
  (is (= core/adversary-types           sew-meta/adversary-types))
  (is (= core/adversary-traits          sew-meta/adversary-traits))
  (is (= core/transition-types          sew-meta/transition-types))
  (is (= core/effect-types              sew-meta/effect-types))
  (is (= core/scenario-types            sew-meta/scenario-types))
  (is (= core/outcome-types             sew-meta/outcome-types))
  (is (= core/invariant-category-types  sew-meta/invariant-category-types))
  (is (= core/resolution-quality-values       sew-meta/resolution-quality-values))
  (is (= core/resolution-finality-values      sew-meta/resolution-finality-values))
  (is (= core/resolution-timing-values        sew-meta/resolution-timing-values))
  (is (= core/resolution-participation-values sew-meta/resolution-participation-values))
  (is (= core/resolution-escalation-values    sew-meta/resolution-escalation-values))
  (is (= core/resolution-economic-values      sew-meta/resolution-economic-values))
  (is (= core/resolution-failure-values       sew-meta/resolution-failure-values))
  (is (= core/resolution-integrity-values     sew-meta/resolution-integrity-values)))
