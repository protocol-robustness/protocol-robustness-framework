(ns resolver-sim.scenario.theory-validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.theory-validation :as tv]))

(defn -main
  [& _]
  (clojure.test/run-tests 'resolver-sim.scenario.theory-validation-test))

;; ---------------------------------------------------------------------------
;; Metric predicate validation
;; ---------------------------------------------------------------------------

(deftest test-valid-metric-predicate-pass
  (testing "basic metric leaf passes validation"
    (is (= {:valid? true} (tv/validate-theory {:falsifies-if [{:metric :attack-attempts :op :> :value 0}]})))))

(deftest test-missing-op-fails
  (testing "metric leaf without :op fails"
    (let [result (tv/validate-theory {:falsifies-if [{:metric :attack-attempts :value 0}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"missing :op" %) (:errors result))))))

(deftest test-invalid-op-fails
  (testing "metric leaf with unrecognized :op fails"
    (let [result (tv/validate-theory {:falsifies-if [{:metric :attack-attempts :op :foo :value 0}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"invalid :op" %) (:errors result))))))

(deftest test-nil-metric-fails
  (testing "metric leaf with nil metric key is unrecognized shape"
    (let [result (tv/validate-theory {:falsifies-if [{:metric nil :op :> :value 0}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"unrecognized predicate" %) (:errors result))))))

;; ---------------------------------------------------------------------------
;; Logical predicate shapes
;; ---------------------------------------------------------------------------

(deftest test-and-predicate-valid
  (testing ":and predicate with valid children passes"
    (let [theory {:falsifies-if [{:and [{:metric :a :op :> :value 0}
                                        {:metric :b :op := :value 1}]}]}]
      (is (= {:valid? true} (tv/validate-theory theory))))))

(deftest test-or-predicate-valid
  (testing ":or predicate with valid children passes"
    (let [theory {:falsifies-if [{:or [{:metric :a :op :> :value 0}
                                       {:metric :b :op := :value 1}]}]}]
      (is (= {:valid? true} (tv/validate-theory theory))))))

(deftest test-and-empty-fails
  (testing ":and with empty children"
    (let [theory {:falsifies-if [{:and []}]}]
      (is (= {:valid? true} (tv/validate-theory theory))
          "empty :and is structurally valid (no errors returned)"))))

(deftest test-and-invalid-child-fails
  (testing ":and with an invalid child propagates the error"
    (let [result (tv/validate-theory {:falsifies-if [{:and [{:metric :a :op :> :value 0}
                                                            {:metric nil :op := :value 1}]}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"unrecognized predicate" %) (:errors result))))))

(deftest test-not-predicate-valid
  (testing ":not predicate passes"
    (is (= {:valid? true} (tv/validate-theory {:falsifies-if [{:not {:metric :a :op :> :value 0}}]})))))

(deftest test-implies-predicate-valid
  (testing ":implies predicate passes"
    (is (= {:valid? true}
           (tv/validate-theory {:falsifies-if [{:implies {:if {:metric :a :op :> :value 0}
                                                          :then {:metric :b :op := :value 1}}}]})))))

(deftest test-always-predicate-valid
  (testing ":always predicate passes"
    (is (= {:valid? true} (tv/validate-theory {:falsifies-if [{:always {:metric :a :op :> :value 0}}]})))))

(deftest test-eventually-predicate-valid
  (testing ":eventually predicate passes"
    (is (= {:valid? true} (tv/validate-theory {:falsifies-if [{:eventually {:metric :a :op :> :value 0}}]})))))

(deftest test-after-predicate-valid
  (testing ":after predicate passes"
    (is (= {:valid? true}
           (tv/validate-theory {:falsifies-if [{:after {:event "create_escrow"
                                                        :predicate {:metric :a :op :> :value 0}}}]})))))

(deftest test-after-missing-event-fails
  (testing ":after without :event fails"
    (let [result (tv/validate-theory {:falsifies-if [{:after {:predicate {:metric :a :op :> :value 0}}}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"expected :event in :after" %) (:errors result))))))

(deftest test-before-predicate-valid
  (testing ":before predicate passes"
    (is (= {:valid? true}
           (tv/validate-theory {:falsifies-if [{:before {:event "release"
                                                         :predicate {:metric :a :op :> :value 0}}}]})))))

(deftest test-before-missing-event-fails
  (testing ":before without :event fails"
    (let [result (tv/validate-theory {:falsifies-if [{:before {:predicate {:metric :a :op :> :value 0}}}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"expected :event in :before" %) (:errors result))))))

;; ---------------------------------------------------------------------------
;; State predicate validation
;; ---------------------------------------------------------------------------

(deftest test-state-predicate-valid
  (testing ":state predicate with valid fields passes"
    (is (= {:valid? true}
           (tv/validate-theory {:falsifies-if [{:state {:query :escrow-count :op :> :value 5}}]})))))

(deftest test-state-missing-op-fails
  (testing ":state without :op fails"
    (let [result (tv/validate-theory {:falsifies-if [{:state {:query :escrow-count :value 5}}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"missing :op" %) (:errors result))))))

(deftest test-state-missing-query-fails
  (testing ":state without :query fails"
    (let [result (tv/validate-theory {:falsifies-if [{:state {:op :> :value 5}}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"missing :query" %) (:errors result))))))

;; ---------------------------------------------------------------------------
;; Theory block structure
;; ---------------------------------------------------------------------------

(deftest test-nil-theory-passes
  (testing "nil theory block passes"
    (is (= {:valid? true} (tv/validate-theory nil)))))

(deftest test-valid-theory-block
  (testing "fully specified theory block passes"
    (let [theory {:falsifies-if [{:metric :attack-successes :op :> :value 0}]
                  :claim-id :test-claim
                  :assumptions [:no-sybil :rational-agents]}]
      (is (= {:valid? true} (tv/validate-theory theory))))))

(deftest test-invalid-claim-id-fails
  (testing "non-keyword/string :claim-id fails"
    (let [result (tv/validate-theory {:claim-id 42})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"claim-id must be a keyword or string" %) (:errors result))))))

(deftest test-numeric-claim-id-fails
  (testing "numeric :claim-id fails"
    (let [result (tv/validate-theory {:claim-id 12345})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"claim-id must be a keyword or string" %) (:errors result))))))

(deftest test-keyword-assumptions-pass
  (testing "vector of keyword :assumptions passes"
    (is (= {:valid? true} (tv/validate-theory {:assumptions [:foo :bar]})))))

(deftest test-non-keyword-assumptions-fails
  (testing "non-keyword elements in :assumptions fail"
    (let [result (tv/validate-theory {:assumptions ["str" :foo]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"assumptions must be a vector of keywords" %) (:errors result))))))

;; ---------------------------------------------------------------------------
;; Falsifies-if as single map (not vector)
;; ---------------------------------------------------------------------------

(deftest test-single-predicate-not-vector
  (testing "falsifies-if as a single map validates"
    (is (= {:valid? true} (tv/validate-theory {:falsifies-if {:metric :a :op :> :value 0}})))))

;; ---------------------------------------------------------------------------
;; Unrecognized predicate shape
;; ---------------------------------------------------------------------------

(deftest test-unknown-predicate-shape-fails
  (testing "predicate with unknown key fails"
    (let [result (tv/validate-theory {:falsifies-if [{:foobar {:metric :a :op :> :value 0}}]})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"unrecognized predicate" %) (:errors result))))))

;; ---------------------------------------------------------------------------
;; Depth bounding
;; ---------------------------------------------------------------------------

(deftest test-deeply-nested-predicate
  (testing "deeply nested valid predicate passes"
    (let [theory {:falsifies-if
                  [{:and [{:or [{:not {:metric :a :op :> :value 0}}
                                {:implies {:if {:metric :b :op := :value 1}
                                           :then {:always {:eventually {:metric :c :op :< :value 5}}}}}]}
                          {:metric :d :op :>= :value 10}]}]}]
      (is (= {:valid? true} (tv/validate-theory theory))))))
