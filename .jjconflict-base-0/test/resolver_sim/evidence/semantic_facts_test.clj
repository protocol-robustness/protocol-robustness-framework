(ns resolver-sim.evidence.semantic-facts-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.semantic-facts :as facts]))

(deftest emits-normalized-semantic-facts
  (let [artifacts {:coverage {:scenarios [{:id "scenarios/S68"
                                           :purpose "adversarial-robustness"
                                           :threat-tags [:conservation :solvency]
                                           :transitions [:create_escrow :release]
                                           :expectations {:invariants [:invariant/solvency]}
                                           :invariant-results {:invariant/solvency :fail}}]}}
        findings-bundle {:run {:run_id "RUN-1"}
                         :findings [{:scenario_id "scenarios/S68"
                                     :status_kind "observed"
                                     :severity "high"}]}
        out (facts/artifacts->semantic-facts artifacts findings-bundle)]
    (is (= "semantic-facts.v1" (:schema/version out)))
    (is (= "RUN-1" (:run/id out)))
    (is (string? (:definitions/hash out)))
    (is (pos? (:fact-count out)))
    (is (some #(= :scenario/uses-purpose (:fact/type %)) (:facts out)))
    (is (some #(= :trial/covers-scenario (:fact/type %)) (:facts out)))
    (is (some #(= :trial/checks-invariant (:fact/type %)) (:facts out)))
    (is (some #(= :trial/invariant-result (:fact/type %)) (:facts out)))))
