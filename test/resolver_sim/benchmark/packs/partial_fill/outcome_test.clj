(ns resolver-sim.benchmark.packs.partial-fill.outcome-test
  (:require [clojure.test :refer [deftest is]]
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
