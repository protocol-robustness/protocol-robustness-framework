(ns resolver-sim.scenario.expectations-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.expectations :as expectations]))

;; Unit tests for evaluate-invariants 3-tier fallback logic
;; These tests use synthetic result maps with no replay needed.

(deftest test-empty-named-invariants
  (testing "Empty invariants list returns success"
    (let [result {:metrics {:invariant-violations 0 :invariant-results {}}}
          r (expectations/evaluate-invariants result [])]
      (is (= true (:ok? r)))
      (is (= [] (:violations r))))))

(deftest test-invariant-present-in-results-map
  (testing "Named invariant present in per-invariant map → precise :fail"
    (let [result {:metrics {:invariant-violations 1
                            :invariant-results {:conservation-of-funds :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds])]
      (is (= false (:ok? r)))
      (is (= 1 (count (:violations r))))
      (is (= :conservation-of-funds (get-in (:violations r) [0 :invariant])))
      (is (= "per-invariant result: fail" (get-in (:violations r) [0 :note]))))))

(deftest test-invariant-not-in-map-violations-zero
  (testing "Named NOT in map, but :invariant-violations = 0 → :pass"
    (let [result {:metrics {:invariant-violations 0
                            :invariant-results {}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds])]
      (is (= true (:ok? r)))
      (is (= [] (:violations r))))))

(deftest test-invariant-not-in-map-violations-gt-zero
  (testing "Named NOT in map, but :invariant-violations > 0 → conservative :fail"
    (let [result {:metrics {:invariant-violations 2
                            :invariant-results {:solvency :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds])]
      (is (= false (:ok? r)))
      (is (= 1 (count (:violations r))))
      (is (= :conservation-of-funds (get-in (:violations r) [0 :invariant])))
      (is (clojure.string/includes? (get-in (:violations r) [0 :note])
                                    "aggregate fallback")))))

(deftest test-multiple-invariants-one-failing
  (testing "Multiple invariants queried; only one fails in the map, other not in map but agg > 0 → both fail conservatively"
    (let [result {:metrics {:invariant-violations 1
                            :invariant-results {:conservation-of-funds :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds :solvency])]
      (is (= false (:ok? r)))
      ;; conservation-of-funds is in the map (precise fail)
      ;; solvency is NOT in the map, but agg > 0 so fails conservatively
      (is (= 2 (count (:violations r)))))))

(deftest test-multiple-invariants-both-in-map
  (testing "Multiple invariants both in the fail map → both fail"
    (let [result {:metrics {:invariant-violations 2
                            :invariant-results {:conservation-of-funds :fail
                                                :solvency :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds :solvency])]
      (is (= false (:ok? r)))
      (is (= 2 (count (:violations r)))))))

(deftest test-multiple-invariants-one-pass-one-fail-in-map
  (testing "One invariant fails in the map, other not in map but agg > 0 → both fail"
    (let [result {:metrics {:invariant-violations 1
                            :invariant-results {:conservation-of-funds :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds :solvency])]
      ;; conservation-of-funds is in the map → precise fail
      ;; solvency is NOT in the map, but agg > 0 → conservative fail
      (is (= false (:ok? r)))
      (is (= 2 (count (:violations r)))))))

(deftest test-string-invariant-name-converted-to-keyword
  (testing "String invariant names are converted to keywords"
    (let [result {:metrics {:invariant-violations 1
                            :invariant-results {:conservation-of-funds :fail}}}
          r (expectations/evaluate-invariants result ["conservation-of-funds"])]
      (is (= false (:ok? r)))
      (is (= :conservation-of-funds (get-in (:violations r) [0 :invariant]))))))

(deftest test-invariant-pass-when-violations-zero
  (testing "All invariants pass when violations = 0"
    (let [result {:metrics {:invariant-violations 0
                            :invariant-results {}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds :solvency :time-lock-integrity])]
      (is (= true (:ok? r)))
      (is (= [] (:violations r))))))

;; Unit tests for evaluate-expectations summary accounting.
;; The summary must be derived from evaluated results, never from declarations.

(defn- result-with-metrics
  ([metrics] (result-with-metrics metrics nil))
  ([metrics world]
   {:outcome :pass
    :metrics metrics
    :trace (cond-> [] world (conj {:seq 0 :world world}))}))

(deftest test-expectation-summary-all-pass
  (testing "Every declared metric evaluated and passing is counted"
    (let [r (expectations/evaluate-expectations
             (result-with-metrics {:yield/positions-count 2 :yield/total-principal 3000})
             {:metrics [{:name "yield/positions-count" :value 2}
                        {:name "yield/total-principal" :value 3000}]})]
      (is (= true (:ok? r)))
      (is (= [] (:violations r)))
      (is (= {:expectations/total 2
              :expectations/passed 2
              :expectations/failed 0
              :expectations/not-evaluated 0}
             (:summary r))))))

(deftest test-expectation-summary-failure-counted
  (testing "A failing metric is counted as failed, others pass"
    (let [r (expectations/evaluate-expectations
             (result-with-metrics {:yield/positions-count 2 :yield/total-principal 999})
             {:metrics [{:name "yield/positions-count" :value 2}
                        {:name "yield/total-principal" :value 3000}]})]
      (is (= false (:ok? r)))
      (is (= 1 (count (:violations r))))
      (is (= :metric-violation (get-in (:violations r) [0 :type])))
      (is (= {:expectations/total 2
              :expectations/passed 1
              :expectations/failed 1
              :expectations/not-evaluated 0}
             (:summary r))))))

(deftest test-expectation-summary-missing-metric-not-evaluated
  (testing "Declared metric absent from result metrics is fail-closed and counted as not-evaluated"
    (let [r (expectations/evaluate-expectations
             (result-with-metrics {:yield/positions-count 2})
             {:metrics [{:name "yield/positions-count" :value 2}
                        {:name "yield/focus-principal" :value 1000}]})]
      (is (= false (:ok? r)))
      (is (= 1 (count (:violations r))))
      (is (= :metric-not-evaluated (get-in (:violations r) [0 :type])))
      (is (= "yield/focus-principal" (get-in (:violations r) [0 :name])))
      (is (= {:expectations/total 2
              :expectations/passed 1
              :expectations/failed 0
              :expectations/not-evaluated 1}
             (:summary r))))))

(deftest test-expectation-summary-no-expectations-block
  (testing "No expectations block produces an empty summary rather than an invented count"
    (let [r (expectations/evaluate-expectations
             (result-with-metrics {:yield/positions-count 2})
             nil)]
      (is (= true (:ok? r)))
      (is (= [] (:violations r)))
      (is (= {:expectations/total 0
              :expectations/passed 0
              :expectations/failed 0
              :expectations/not-evaluated 0}
             (:summary r))))))
