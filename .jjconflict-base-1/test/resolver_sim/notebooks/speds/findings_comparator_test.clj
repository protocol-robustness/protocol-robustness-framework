(ns resolver-sim.notebooks.speds.findings-comparator-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.notebook-support.speds.findings :as findings]))

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
                           :threat-tags [:fork :appeal-escalation]}
                          {:id "S27_forking-strategist-l2-fork"
                           :title "Forking Strategist L2"
                           :purpose "theory-falsification"
                           :threat-tags [:fork]}]}
   :golden-reports {"s01-baseline-happy-path"
                    {:outcome :pass
                     :final-state-hash "baseline-hash"
                     :metrics {:attack-attempts 0 :total-volume 5000}}
                    "s26-forking-strategist-l1-reversal"
                    {:outcome :pass
                     :final-state-hash "fork-hash"
                     :metrics {:attack-attempts 3 :total-volume 5000}}}})

(defn- finding-by-id [bundle sid]
  (some #(when (= sid (:scenario_id %)) %) (:findings bundle)))

(deftest comparator-strategy-dispatch-test
  (testing "default comparator remains nearest-baseline-by-id"
    (let [bundle (findings/generate-findings-bundle sample-artifacts)
          f26   (finding-by-id bundle "S26_forking-strategist-l1-reversal")]
      (is (= "nearest_baseline_by_id"
             (get-in f26 [:story_artifact_spec :baseline_comparison :comparator_kind])))))

  (testing "unknown comparator strategy safely falls back to nearest-baseline-by-id"
    (let [bundle (findings/generate-findings-bundle
                  sample-artifacts
                  {:comparator-config {:strategy :unknown-strategy :enabled? true}})
          f26   (finding-by-id bundle "S26_forking-strategist-l1-reversal")]
      (is (= "unknown_strategy"
             (get-in f26 [:story_artifact_spec :baseline_comparison :comparator_kind])))
      (is (= "S01_baseline-happy-path"
             (get-in f26 [:story_artifact_spec :baseline_comparison :baseline_scenario_id]))))))

(deftest findings-include-definitions-hash
  (let [bundle (findings/generate-findings-bundle sample-artifacts)
        f26   (finding-by-id bundle "S26_forking-strategist-l1-reversal")]
    (is (string? (get-in bundle [:provenance :definitions/hash])))
    (is (string? (get-in bundle [:provenance :definitions/hash])))))

(deftest invariant-severity-fallback
  (let [artifacts (assoc-in sample-artifacts
                            [:coverage :scenarios 1 :invariant-results]
                            {:invariant/solvency :fail})
        bundle (findings/generate-findings-bundle artifacts)
        f26 (finding-by-id bundle "S26_forking-strategist-l1-reversal")]
    (is (= "high" (:severity f26)))))

(deftest one-line-description-present
  (let [bundle (findings/generate-findings-bundle sample-artifacts)
        f26   (finding-by-id bundle "S26_forking-strategist-l1-reversal")]
    (is (string? (:one_line_description f26)))
    (is (not (str/blank? (:one_line_description f26))))
    (is (= (:one_line_description f26) (:summary f26)))))

(deftest baseline-comparison-includes-replay-deltas
  (let [bundle (findings/generate-findings-bundle sample-artifacts)
        f26    (finding-by-id bundle "S26_forking-strategist-l1-reversal")
        delta  (get-in f26 [:story_artifact_spec :baseline_comparison :delta_summary])]
    (is (= true (:replay_available delta)))
    (is (= true (get-in delta [:outcome_delta :match])))
    (is (= false (:final_state_hash_match delta)))
    (is (= 3 (get-in delta [:metric_deltas :attack-attempts :delta])))
    (is (clojure.string/includes? (:narrative delta) "attack-attempts Δ=+3"))))
