(ns resolver-sim.io.scenario-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.scenario-runner :as runner]))

(def ^:private canonical-dispatch
  {:mode :production})

(def ^:private canonical-opts
  {})

(def ^:private canonical-runner-selection
  {:mode :pinned
   :runner-id :runner/local-bb
   :description "Default pinned local Babashka runner"})

(def ^:private clean-source-provenance
  {:source/dirty? false})

(deftest parallel-execution-marks-run-non-canonical
  (let [result ((var-get #'runner/determine-canonicality)
                canonical-dispatch
                (assoc canonical-opts :parallel? true)
                canonical-runner-selection
                clean-source-provenance)]
    (is (false? (:canonical? result)))
    (is (= :parallel-execution (:code (:non-canonical-reason result))))))

(deftest non-parallel-run-remains-canonical-when-other-conditions-hold
  (let [result ((var-get #'runner/determine-canonicality)
                canonical-dispatch
                canonical-opts
                canonical-runner-selection
                clean-source-provenance)]
    (is (true? (:canonical? result)))
    (is (nil? (:non-canonical-reason result)))))

(deftest single-scenario-selection-marks-run-non-canonical
  (let [result ((var-get #'runner/determine-canonicality)
                (assoc canonical-dispatch :scenario "S01_test.edn")
                canonical-opts
                canonical-runner-selection
                clean-source-provenance)]
    (is (false? (:canonical? result)))
    (is (= :single-scenario-selected (:code (:non-canonical-reason result))))))

(deftest dev-mode-marks-run-non-canonical
  (let [result ((var-get #'runner/determine-canonicality)
                (assoc canonical-dispatch :mode :dev)
                canonical-opts
                canonical-runner-selection
                clean-source-provenance)]
    (is (false? (:canonical? result)))
    (is (= :dev-mode (:code (:non-canonical-reason result))))))

(deftest dirty-source-marks-run-non-canonical
  (let [result ((var-get #'runner/determine-canonicality)
                canonical-dispatch
                canonical-opts
                canonical-runner-selection
                {:source/dirty? true})]
    (is (false? (:canonical? result)))
    (is (= :dirty-source (:code (:non-canonical-reason result))))))

(deftest capability-match-runner-marks-run-non-canonical
  (let [result ((var-get #'runner/determine-canonicality)
                canonical-dispatch
                canonical-opts
                {:mode :capability-match}
                clean-source-provenance)]
    (is (false? (:canonical? result)))
    (is (= :capability-match-runner (:code (:non-canonical-reason result))))))
