(ns resolver-sim.benchmark.research-analysis-comparison-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-analysis-comparison :as comparison]))

(def roots
  {:definition "sha256:1111111111111111111111111111111111111111111111111111111111111111"
   :case "sha256:2222222222222222222222222222222222222222222222222222222222222222"
   :measure "sha256:3333333333333333333333333333333333333333333333333333333333333333"
   :researcher "sha256:4444444444444444444444444444444444444444444444444444444444444444"})

(defn analysis [root researcher values possession]
  {:research-analysis/root root
   :research-definition/root (:definition roots)
   :subject-roots ["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
   :case-axis/root (:case roots)
   :measure-axis/root (:measure roots)
   :researcher-axis/root (:researcher roots)
   :researcher researcher
   :analysis/coordinates values
   :proof-of-possession possession})

(deftest comparison-preserves-explicit-result-classes-and-lineage
  (let [left (analysis "sha256:5555555555555555555555555555555555555555555555555555555555555555"
                       {:researcher/id "alice"} {[0 0] 0N [1 0] 2N [2 0] :not-comparable}
                       {:proof-of-possession/requirement :requested
                        :proof-of-possession/status :not-established})
        right (analysis "sha256:6666666666666666666666666666666666666666666666666666666666666666"
                        {:researcher/id "bob"} {[0 0] 0N [1 0] 3N [2 0] 9N [3 0] 0N}
                        {:proof-of-possession/requirement :required
                         :proof-of-possession/status :established})
        result (comparison/compare-analyses left right)]
    (is (= [:equal :different :not-comparable :missing]
           (mapv :comparison/status (:comparison/output result))))
    (is (= :missing (get-in result [:comparison/output 3 :left/value])))
    (is (= 0N (get-in result [:comparison/output 3 :right/value])))
    (is (= [{:research-analysis/root (:research-analysis/root left)
             :researcher {:researcher/id "alice"}}
            {:research-analysis/root (:research-analysis/root right)
             :researcher {:researcher/id "bob"}}]
           (:comparison/inputs result)))
    (is (= 2 (count (:proof-of-possession/lineage result))))
    (is (= result (comparison/compare-analyses left right)))
    (is (re-matches #"sha256:[0-9a-f]{64}"
                    (:research-analysis-comparison/root result)))))

(deftest comparison-rejects-incompatible-axes
  (let [left (analysis "sha256:5555555555555555555555555555555555555555555555555555555555555555"
                       {:researcher/id "alice"} {[0 0] 1N} nil)
        right (assoc (analysis "sha256:6666666666666666666666666666666666666666666666666666666666666666"
                               {:researcher/id "bob"} {[0 0] 1N} nil)
                     :case-axis/root
                     "sha256:7777777777777777777777777777777777777777777777777777777777777777")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incompatible"
                          (comparison/compare-analyses left right)))))
