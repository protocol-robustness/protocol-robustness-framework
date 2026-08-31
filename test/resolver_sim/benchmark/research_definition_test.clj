(ns resolver-sim.benchmark.research-definition-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-definition :as research]))

(def coverage-measure
  {:measure/id :measure/coverage-margin
   :measure/kind :requirement-margin.v1
   :measure/domain {:kind :integer :unit :usdc}
   :measure/observation {:observation/id :coverage/covered-capacity}
   :measure/requirement {:requirement/kind :constant :value 800N :unit :usdc}
   :measure/satisfying-direction :at-least})

(def source
  {:artifact/schema "research-source.v1"
   :research/id :study/coverage-under-stress
   :research/title "Coverage under stress"
   :research/question "How much capacity remains under utilization stress?"
   :research/vary {:parameter/senior-bond [500N 1000N 2000N]
                   :parameter/utilization-bps [5000N 8000N 9500N]}
   :research/measures [coverage-measure]
   :research/hypotheses
   [{:hypothesis/id :hypothesis/coverage-remains-adequate
     :hypothesis/measure :measure/coverage-margin
     :hypothesis/predicate {:op :>= :value 0N}}]})

(defn observations-for [frozen offset]
  (into {}
        (map-indexed
         (fn [index {:case/keys [id]}]
           [id {:measure/coverage-margin (+ offset (* 100N index))}]))
        (get-in frozen [:research-definition :research/cases])))

(deftest freeze-resolves-template-and-generates-a-canonical-grid
  (let [template (dissoc source :artifact/schema :research/id)
        frozen (research/freeze-research
                {:artifact/schema "research-source.v1"
                 :research/id :study/template-derived
                 :research/from :template/coverage-under-stress}
                {:templates {:template/coverage-under-stress template}})
        definition (:research-definition frozen)]
    (is (= "research-definition.v1" (:artifact/schema definition)))
    (is (not (contains? definition :research-definition/root)))
    (is (= 9 (count (:research/cases definition))))
    (is (= (range 9)
           (map :axis/key (get-in frozen [:derived :case-axis :axis/members]))))
    (is (= [:measure/coverage-margin]
           (mapv :axis/id (get-in frozen [:derived :measure-axis :axis/members]))))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:research-definition/root frozen)))
    (is (= frozen (research/freeze-research
                   {:artifact/schema "research-source.v1"
                    :research/id :study/template-derived
                    :research/from :template/coverage-under-stress}
                   {:templates {:template/coverage-under-stress template}})))))

(deftest requirement-margin-normalizes-direction-and-classifies-results
  (is (= 20N (research/requirement-margin {:observed 120N :requirement 100N
                                           :domain {:kind :integer :unit :usdc}
                                           :satisfying-direction :at-least})))
  (is (= -5N (research/requirement-margin {:observed 95N :requirement 100N
                                           :domain {:kind :integer :unit :usdc}
                                           :satisfying-direction :at-least})))
  (is (= 20N (research/requirement-margin {:observed 40N :requirement 60N
                                           :domain {:kind :integer :unit :blocks}
                                           :satisfying-direction :at-most})))
  (is (= {:measure/status :deficit :measure/value -5N :measure/deficit 5N}
         (research/classify-margin -5N)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid requirement-margin measure"
                        (research/freeze-research
                         (assoc-in source [:research/measures 0 :measure/requirement :unit] :bps)))))

(deftest compiled-matrices-and-reproduction-comparison-use-frozen-axes
  (let [frozen (research/freeze-research source)
        observations (observations-for frozen 700N)
        matrix-a (research/compile-result-matrix frozen observations "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        matrix-b (research/compile-result-matrix frozen observations "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        first-case (:case/id (first (get-in frozen [:research-definition :research/cases])))
        divergent (research/compile-result-matrix
                   frozen
                   (assoc-in observations [first-case :measure/coverage-margin] 799N)
                   "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")]
    (is (= 9 (count (:matrix/values matrix-a))))
    (is (= :deficit (get-in matrix-a [:matrix/values [0 0] :measure/status])))
    (is (= :equal (:reproduction/status (research/compare-reproductions [matrix-a matrix-b]))))
    (let [comparison (research/compare-reproductions [matrix-a matrix-b divergent])]
      (is (= :disagreement (:reproduction/status comparison)))
      (is (= [{:matrix/cell [0 0]
               :reproduction/values [-100N -100N -1N]}]
             (:reproduction/disagreements comparison))))))

(deftest canonical-diff-enumerates-semantic-requirement-changes
  (let [base (research/freeze-research source)
        candidate (research/freeze-research
                   (assoc-in source [:research/measures 0 :measure/requirement :value] 900N))
        diff (research/definition-diff base candidate)]
    (is (= [{:change/kind :measure/requirement-changed
             :measure/id :measure/coverage-margin
             :change/before 800N
             :change/after 900N
             :change/domain {:kind :integer :unit :usdc}
             :change/key 0}]
           (:diff/changes diff)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:research-definition-diff/root diff)))
    (is (= diff (research/definition-diff base candidate)))))

(deftest definition-diff-fails-closed-on-unsupported-changes
  (let [base (research/freeze-research source)
        requirement-change
        (research/freeze-research
         (assoc-in source [:research/measures 0 :measure/requirement :value] 900N))]
    (is (= :identical (:diff/status (research/definition-diff base base))))
    (is (not (:diff/unsupported-change? (research/definition-diff base base))))
    (let [d (research/definition-diff base requirement-change)]
      (is (= :complete (:diff/status d)))
      (is (not (:diff/unsupported-change? d)))
      (is (= 1 (count (:diff/changes d)))))
    (let [d (research/definition-diff
              base
              (research/freeze-research
               (assoc-in source [:research/vary :parameter/senior-bond] [500N 1000N])))]
      (is (= :incomplete (:diff/status d)))
      (is (true? (:diff/unsupported-change? d)))
      (is (= :unsupported-semantic-change (:diff/reason d)))
      (is (= [] (:diff/changes d))))
    (let [d (research/definition-diff
              base
              (research/freeze-research (assoc source :research/hypotheses [])))]
      (is (= :incomplete (:diff/status d)))
      (is (true? (:diff/unsupported-change? d))))))

(deftest frozen-definition-is-independent-of-template-mutation
  (let [template (dissoc source :artifact/schema :research/id)
        src {:artifact/schema "research-source.v1"
             :research/id :study/template-derived
             :research/from :template/coverage-under-stress}
        frozen-a (research/freeze-research src {:templates {:template/coverage-under-stress template}})
        root-a (:research-definition/root frozen-a)
        cases-a (:research/cases (:research-definition frozen-a))
        mutated (assoc-in template [:research/measures 0 :measure/requirement :value] 900N)
        frozen-b (research/freeze-research src {:templates {:template/coverage-under-stress mutated}})]
    (is (not= root-a (:research-definition/root frozen-b)))
    (is (= 800N (get-in frozen-a [:research-definition :research/measures 0 :measure/requirement :value])))
    (let [m1 (research/compile-result-matrix frozen-a (observations-for frozen-a 700N) "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          m2 (research/compile-result-matrix frozen-a (observations-for frozen-a 700N) "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
      (is (= m1 m2))
      (is (= cases-a (:research/cases (:research-definition frozen-a)))))))

(deftest scoped-integer-keys-are-anti-aliased-across-axes
  (let [frozen1 (research/freeze-research source)
        frozen2 (research/freeze-research
                 (assoc-in source [:research/vary :parameter/senior-bond] [600N 900N 1500N]))
        root1 (get-in frozen1 [:derived :case-axis/root])
        root2 (get-in frozen2 [:derived :case-axis/root])]
    (is (= :generated/case-0 (:axis/id (research/axis-member frozen1 :case 0))))
    (is (= :generated/case-0 (:axis/id (research/axis-member frozen2 :case 0))))
    (is (not= root1 root2))
    (is (not= (:axis/member (research/axis-member frozen1 :case 0))
              (:axis/member (research/axis-member frozen2 :case 0))))
    (is (not= (research/scoped-key root1 :case 0)
              (research/scoped-key root2 :case 0)))
    (is (thrown? clojure.lang.ExceptionInfo (research/axis-member frozen1 :case 99)))
    (is (thrown? clojure.lang.ExceptionInfo (research/axis-member frozen1 :bogus 0)))))

(deftest case-generation-is-normalized-by-parameter-name
  (let [reordered (assoc source :research/vary
                         {:parameter/utilization-bps [5000N 8000N 9500N]
                          :parameter/senior-bond [500N 1000N 2000N]})
        frozen (research/freeze-research source)
        frozen-reordered (research/freeze-research reordered)]
    (is (= (:research-definition frozen) (:research-definition frozen-reordered)))
    (is (= (:research-definition/root frozen) (:research-definition/root frozen-reordered))))
  (let [different-values (assoc-in source [:research/vary :parameter/senior-bond] [2000N 1000N 500N])
        frozen (research/freeze-research source)
        frozen-different (research/freeze-research different-values)]
    (is (not= (:research-definition/root frozen) (:research-definition/root frozen-different)))))

(deftest matrices-bind-axes-and-reject-out-of-scope-observations
  (let [frozen1 (research/freeze-research source)
        frozen-cases (research/freeze-research
                      (assoc-in source [:research/vary :parameter/senior-bond] [600N 900N 1500N]))
        frozen-measure (research/freeze-research
                        (assoc-in source [:research/measures 0 :measure/requirement :value] 900N))
        obs (observations-for frozen1 700N)
        m1 (research/compile-result-matrix frozen1 obs "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        mc (research/compile-result-matrix frozen-cases obs "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        mm (research/compile-result-matrix frozen-measure obs "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")]
    (is (= 9 (count (:matrix/values m1))))
    (is (= (count (:matrix/values m1)) (count (:matrix/values mc))))
    (is (not= (:research-definition/root m1) (:research-definition/root mc)))
    (is (not= (:case-axis/root m1) (:case-axis/root mc)))
    (is (= (:measure-axis/root m1) (:measure-axis/root mc)))
    (is (not= (:research-definition/root m1) (:research-definition/root mm)))
    (is (= (:case-axis/root m1) (:case-axis/root mm)))
    (is (not= (:measure-axis/root m1) (:measure-axis/root mm)))
    (is (= :not-comparable (:reproduction/status (research/compare-reproductions [m1 mc])))))
  (let [frozen (research/freeze-research source)
        good (observations-for frozen 700N)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (research/compile-result-matrix frozen (assoc good :case/bogus {:measure/coverage-margin 1N}) "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (research/compile-result-matrix frozen (assoc good :generated/case-0 {:measure/not-a-measure 1N}) "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (research/compile-result-matrix frozen (dissoc good :generated/case-0) "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
    (let [swapped (-> good
                      (assoc :generated/case-0 (:generated/case-1 good))
                      (assoc :generated/case-1 (:generated/case-0 good)))
          ma (research/compile-result-matrix frozen good "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          mb (research/compile-result-matrix frozen swapped "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
      (is (= (:case-axis/root ma) (:case-axis/root mb)))
      (is (= :disagreement (:reproduction/status (research/compare-reproductions [ma mb])))))))

(deftest reproduction-comparison-distinguishes-incomparable-scopes
  (let [frozen1 (research/freeze-research source)
        frozen2 (research/freeze-research
                 (assoc-in source [:research/vary :parameter/senior-bond] [600N 900N 1500N]))
        m1 (research/compile-result-matrix frozen1 (observations-for frozen1 700N) "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        m2 (research/compile-result-matrix frozen2 (observations-for frozen2 700N) "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        result (research/compare-reproductions [m1 m2])]
    (is (= :not-comparable (:reproduction/status result)))
    (is (= 2 (count (:reproduction/scopes result))))
    (is (not (contains? result :reproduction/disagreements)))))
