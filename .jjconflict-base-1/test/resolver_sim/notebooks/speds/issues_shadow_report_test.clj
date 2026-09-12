(ns resolver-sim.notebooks.speds.issues-shadow-report-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [resolver-sim.notebook-support.speds.issues :as issues]))

(def ^:private sample-artifacts
  {:summary {:run-id "RUN-TEST"
             :overall-status "pass"
             :replay_match_pct 100.0}
   :coverage {:scenarios [{:id "S01_baseline-happy-path"
                           :title "Baseline"
                           :purpose "baseline"
                           :threat-tags []}
                          {:id "S26_forking-strategist-l1-reversal"
                           :title "Forking Strategist"
                           :purpose "theory-falsification"
                           :threat-tags [:fork :appeal-escalation]}]}})

(deftest comparator-shadow-report-shape-test
  (testing "shadow report returns one run per requested strategy with stable shape"
    (let [strategies [:nearest-baseline-by-id :matched-by-purpose]
          report (issues/generate-comparator-shadow-report
                  sample-artifacts
                  {:strategies strategies
                   :enabled? true})]
      (is (= "speds-comparator-shadow-v1" (:schema_version report)))
      (is (string? (:definitions_hash report)))
      (is (= strategies (:strategies report)))
      (is (= 2 (count (:runs report))))
      (doseq [r (:runs report)]
        (is (contains? r :strategy))
        (is (contains? r :finding-count))
        (is (contains? r :issue-count))
        (is (contains? r :comparator-config))))))

(deftest generated-issues-include-one-line-description
  (let [bundle (issues/generate-issues-bundle sample-artifacts)
        issue  (first (:issues bundle))]
    (is (some? issue))
    (is (string? (:one_line_description issue)))
    (is (not (str/blank? (:one_line_description issue))))
    (is (= (:one_line_description issue) (:summary issue)))))
