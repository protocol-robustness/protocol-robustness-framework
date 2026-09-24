(ns resolver-sim.benchmark.research-analysis-composition-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-analysis-composition :as composition]
            [resolver-sim.benchmark.research-analysis-comparison-test :as fixture]
            [resolver-sim.benchmark.incentive-target-qualification :as qualification]
            [resolver-sim.benchmark.researcher-reward :as reward]))

(def exact-policy
  {:operator :maximum
   :missing-data-policy :propagate
   :not-comparable-policy :propagate
   :integer-arithmetic-policy :exact-integer.v1
   :rounding-policy :not-applicable})

(deftest composition-is-deterministic-canonical-and-non-promoting
  (let [a (fixture/analysis
           "sha256:5555555555555555555555555555555555555555555555555555555555555555"
           {:researcher/id "alice"} {[0 0] 1N [1 0] :missing [2 0] 5N}
           {:proof-of-possession/requirement :required
            :proof-of-possession/status :established})
        b (fixture/analysis
           "sha256:6666666666666666666666666666666666666666666666666666666666666666"
           {:researcher/id "bob"} {[0 0] 3N [1 0] 8N [2 0] :not-comparable}
           {:proof-of-possession/requirement :required
            :proof-of-possession/status :not-established})
        ab (composition/compose-analyses [a b] exact-policy)
        ba (composition/compose-analyses [b a] exact-policy)]
    (is (= ab ba))
    (is (= [(:research-analysis/root a) (:research-analysis/root b)]
           (:composition/input-analysis-roots ab)))
    (is (= {[0 0] 3N [1 0] :missing [2 0] :not-comparable}
           (:composition/output ab)))
    (is (false? (:research-analysis/general-ic-proven? ab)))
    (is (false? (:research-claim/accepted? ab)))
    (is (= :not-established
           (get-in ab [:proof-of-possession/lineage 1
                       :proof-of-possession/status])))
    (is (not= (:research-analysis-composition/root ab)
              (:research-analysis-composition/root
               (composition/compose-analyses
                [a (assoc-in b [:analysis/coordinates [0 0]] 4N)] exact-policy))))))

(deftest composition-policies-are-explicit
  (let [a (fixture/analysis
           "sha256:5555555555555555555555555555555555555555555555555555555555555555"
           {:researcher/id "alice"} {[0 0] 2N [1 0] :missing} nil)
        b (fixture/analysis
           "sha256:6666666666666666666666666666666666666666666666666666666666666666"
           {:researcher/id "bob"} {[0 0] 2N [1 0] 7N} nil)
        agreement-policy (assoc exact-policy
                                :operator :agreement-count
                                :missing-data-policy :ignore)
        result (composition/compose-analyses [a b] agreement-policy)]
    (is (= {[0 0] 2N [1 0] 1N} (:composition/output result)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (composition/compose-analyses [a b]
                                               (assoc exact-policy :operator :majority))))))

(deftest composition-root-is-a-valid-incentive-target
  (let [a (fixture/analysis
           "sha256:5555555555555555555555555555555555555555555555555555555555555555"
           {:researcher/id "alice"} {[0 0] 1N} nil)
        b (fixture/analysis
           "sha256:6666666666666666666666666666666666666666666666666666666666666666"
           {:researcher/id "bob"} {[0 0] 2N} nil)
        derived (composition/compose-analyses [a b] exact-policy)
        policy (reward/build-policy
                {:accepted-contribution-types #{:reproduction}
                 :required-verification #{:verified}
                 :required-claim-statuses #{:counterexample-found}
                 :reward-amount 10N
                 :reward-currency "USDC"
                 :split-rules {}
                 :duplicate-rule {}
                 :conflict-of-interest-rule {}})
        eligibility (reward/evaluate
                     {:policy policy
                      :researcher "alice"
                      :assignment-root
                      "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                      :contribution-root (:research-analysis-composition/root derived)
                      :contribution-type :reproduction
                      :verification {:verified? false}
                      :claim {:claimable? true
                              :research-claim/status :counterexample-found}})]
    (is (re-matches #"sha256:[0-9a-f]{64}"
                    (:research-analysis-composition/root derived)))
    (is (false? (:reward/eligible? eligibility)))
    (is (some #{:verification-failed} (:reward/reasons eligibility)))
    (is (false? (:research-analysis/general-ic-proven? derived)))
    (is (not (contains? derived :reward/amount)))))

(deftest target-scoped-possession-qualification-blocks-required-gap
  (let [a (fixture/analysis
           "sha256:5555555555555555555555555555555555555555555555555555555555555555"
           {:researcher/id "alice"} {[0 0] 1N}
           {:proof-of-possession/requirement :required
            :proof-of-possession/status :established})
        b (fixture/analysis
           "sha256:6666666666666666666666666666666666666666666666666666666666666666"
           {:researcher/id "bob"} {[0 0] 2N}
           {:proof-of-possession/requirement :required
            :proof-of-possession/status :not-established})
        derived (composition/compose-analyses [a b] exact-policy)
        result (qualification/qualify
                {:target-root (:research-analysis-composition/root derived)
                 :possession-lineage (:proof-of-possession/lineage derived)
                 :possession-scope :target})]
    (is (map? derived))
    (is (false? (:incentive/qualified? result)))
    (is (= [:proof-of-possession-required-not-established]
           (:incentive/reasons result)))))

(deftest requested-possession-is-visible-but-nonblocking
  (let [result (qualification/qualify
                {:target-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                 :possession-lineage
                 [{:researcher/id "alice"
                   :proof-of-possession/requirement :requested
                   :proof-of-possession/status :not-established}]
                 :possession-scope :target})]
    (is (true? (:incentive/qualified? result)))
    (is (true? (:possession/outstanding-request? result)))
    (is (= :requested-but-not-established
           (get-in result [:possession/evaluations 0
                           :proof-of-possession/outcome])))))

(deftest contributor-scoped-possession-qualification-is-explicit
  (let [lineage [{:researcher/id "alice"
                  :proof-of-possession/requirement :required
                  :proof-of-possession/status :established}
                 {:researcher/id "bob"
                  :proof-of-possession/requirement :required
                  :proof-of-possession/status :not-established}]
        base {:target-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              :possession-lineage lineage
              :possession-scope :contributor}]
    (is (true? (:incentive/qualified? (qualification/qualify
                                       (assoc base :contributor "alice")))))
    (is (false? (:incentive/qualified? (qualification/qualify
                                        (assoc base :contributor "bob")))))))
