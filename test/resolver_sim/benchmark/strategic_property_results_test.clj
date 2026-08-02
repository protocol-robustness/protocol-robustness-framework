(ns resolver-sim.benchmark.strategic-property-results-test
  "Adapter tests for routing strategic-property verdicts into the structured
   :property-violated result vocabulary and the strategic gate."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.strategic-property-results :as spr]
            [resolver-sim.validation.gate :as gate]))

(def clean-artifact
  {:summary {:states-examined 100}
   :properties
   [{:property :strategy/split-invariance
     :status :verified :verdict :verified
     :state-count 100 :violation-count 0}]})

(def violated-artifact
  {:summary {:states-examined 100}
   :properties
   [{:property :allocation/exact-merge-invariance
     :status :violated :verdict :violated
     :state-count 100 :violation-count 3
     :counterexample {:claims [1 1 1] :liquidity 1}
     :sample-counterexamples [{:claims [0 1] :error 1}]}]})

(deftest verified-property-maps-to-pass-result
  (let [results (spr/strategic-properties->results clean-artifact)
        r (first results)]
    (is (= :strategy/split-invariance (:property r)))
    (is (= :pass (:status r)))
    (is (nil? (:reason r)))
    (is (= :validation.class/deviation-resistance (:validation-class r)))
    (is (= 100 (get-in r [:observed :state-count])))))

(deftest violated-property-maps-to-property-violated-fail
  (let [results (spr/strategic-properties->results violated-artifact)
        r (first results)]
    (is (= :allocation/exact-merge-invariance (:property r)))
    (is (= :fail (:status r)))
    (is (= :property-violated (:reason r)))
    (is (= :validation.class/deviation-resistance (:validation-class r)))
    (is (some #(= {:claims [1 1 1] :liquidity 1} %) (:offending r)))
    (is (some #(= {:claims [0 1] :error 1} %) (:offending r)))
    (is (= 3 (get-in r [:observed :violation-count])))))

(deftest empty-artifact-produces-empty-results
  (is (= [] (spr/strategic-properties->results nil)))
  (is (= [] (spr/strategic-properties->deviation-results nil))))

(deftest deviation-results-match-gate-input-contract
  (let [dev (spr/strategic-properties->deviation-results violated-artifact)]
    (is (= [{:property :allocation/exact-merge-invariance :verdict :violated}]
           dev))
    (let [g (gate/evaluate-strategic-gate
             {:gate :economic-model :verdict :pass}
             dev
             [])]
      (is (= :violated (:verdict g)))
      (is (some #(= :property-violated (:reason %))
                (:properties (gate/evaluate-strategic-gate
                              {:gate :economic-model :verdict :pass}
                              (spr/strategic-properties->results violated-artifact)
                              [])))))))

(deftest verified-deviation-results-pass-strategic-gate
  (let [dev (spr/strategic-properties->deviation-results clean-artifact)
        g (gate/evaluate-strategic-gate {:gate :economic-model :verdict :pass} dev [])]
    (is (= :verified (:verdict g)))))
