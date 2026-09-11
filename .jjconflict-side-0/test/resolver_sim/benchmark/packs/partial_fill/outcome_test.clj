(ns resolver-sim.benchmark.packs.partial-fill.outcome-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.packs.partial-fill.outcome :as outcome]))

(deftest fulfilled-is-not-full-fill
  (let [partial (outcome/normalise-participant-outcome
                 {:participant-id :a :obligation-before 100 :fulfilled 40 :deferred 60})
        haircut (outcome/normalise-participant-outcome
                 {:participant-id :a :obligation-before 100 :fulfilled 80 :deferred 0 :haircut 20})
        full (outcome/normalise-participant-outcome
              {:participant-id :a :obligation-before 100 :fulfilled 100 :deferred 0 :haircut 0})]
    (is (true? (:allocation/positive-amount-applied? partial)))
    (is (false? (:allocation/fully-satisfied? partial)))
    (is (true? (:allocation/no-deferred-residual? haircut)))
    (is (false? (:allocation/fully-satisfied? haircut))
        "no deferred residual does not erase a haircut")
    (is (true? (:allocation/fully-satisfied? full)))))

(deftest decision-outcome-accounts-for-row-haircut
  (let [result (outcome/normalise-decision-outcome
                {:decision/id :haircut
                 :evidence {:allocation-rows [{:key :a :owed 100 :filled 80 :haircut 20}]}})
        participant (first (:participants result))]
    (is (= 0 (:obligation/deferred participant)))
    (is (= 20 (:obligation/haircut participant)))
    (is (false? (:allocation/fully-satisfied? participant)))))

(deftest overshoot-participant-normaliser-rejects
  (testing "participant normaliser throws on filled + haircut > obligation"
    (is (thrown? clojure.lang.ExceptionInfo
                 (outcome/normalise-participant-outcome
                  {:participant-id :a :obligation-before 100 :fulfilled 140 :deferred 0})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (outcome/normalise-participant-outcome
                  {:participant-id :a :obligation-before 100 :fulfilled 80 :haircut 40})))))

(deftest overshoot-decision-normaliser-rejects
  (testing "decision normaliser throws on filled + haircut > owed rather than masking"
    (is (thrown? clojure.lang.ExceptionInfo
                 (outcome/normalise-decision-outcome
                  {:decision/id :d :evidence {:allocation-rows [{:key :a :owed 100 :filled 140}]}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (outcome/normalise-decision-outcome
                  {:decision/id :d :evidence {:allocation-rows [{:key :a :owed 100 :filled 80 :haircut 60}]}})))))

(deftest participant-and-decision-normalisers-converge
  (testing "The two normalisers derive identical allocation predicates for the same obligation"
    (let [p (outcome/normalise-participant-outcome
             {:participant-id :a :obligation-before 100 :fulfilled 40 :deferred 60})
          d (first (:participants
                    (outcome/normalise-decision-outcome
                     {:decision/id :d :evidence {:allocation-rows [{:key :a :owed 100 :filled 40}]}})))
          predicate-keys [:allocation/positive-amount-applied?
                          :allocation/no-deferred-residual?
                          :allocation/fully-satisfied?
                          :allocation/applied?]]
      (doseq [k predicate-keys]
        (is (= (get p k) (get d k))
            (str k " agrees across participant and decision normalisers"))))))
