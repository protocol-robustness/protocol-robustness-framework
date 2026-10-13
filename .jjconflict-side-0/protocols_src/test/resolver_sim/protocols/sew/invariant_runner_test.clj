(ns resolver-sim.protocols.sew.invariant-runner-test
  "Smoke tests for the S01–S100 deterministic invariant suite runner."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.invariant-runner :as runner]
            [resolver-sim.protocols.sew.invariant-scenarios :as sc]
            [resolver-sim.scenario.runner :as scenario-runner]))

(deftest test-registry-size
  (testing "the canonical scenario registry is the set of registered scenario-type ids"
    (let [scenario-ids (set (map :scenario-id
                                 (mapcat (fn [[_ entry]] (if (vector? entry) entry [entry]))
                                         sc/all-scenarios)))
          registered   (set (keys sc/scenario-type-registry))]
      ;; Membership over magic count: every scenario emitted by all-scenarios must
      ;; have a registered type-registry key, and vice-versa (no orphans/missing).
      ;; Note: all-scenarios has 140 display rows; paired entries (S12/S45/S46/S47/
      ;; S51c/S51d) yield 143 distinct scenario ids.
      (is (= registered scenario-ids)
          "all-scenarios covers exactly the registered scenario-type ids")
      (is (= 143 (count scenario-ids))
          "canonical scenario registry contains 143 distinct ids"))))

(deftest test-registry-validation-passes
  (is (true? (sc/validate-all-scenarios!))))

(deftest test-scenario-type-registry-covers-all-scenarios
  (let [scenario-ids (set (map :scenario-id (mapcat (fn [[_ entry]] (if (vector? entry) entry [entry])) sc/all-scenarios)))]
    (is (= scenario-ids (set (keys sc/scenario-type-registry))))))

(deftest test-run-all-all-pass
  (let [{:keys [passed total results]} (runner/run-all)]
    (is (= passed total))
    (testing "no invariant violations in any scenario"
      (is (every? #(zero? (:violations %)) results)))))

(deftest test-run-all-shape
  (let [summary (runner/run-all)]
    (is (contains? summary :passed))
    (is (contains? summary :total))
    (is (contains? summary :elapsed-ms))
    (is (contains? summary :results))
    (is (every? #(contains? % :name) (:results summary)))))

(deftest test-print-report-exit-code
  (let [summary (runner/run-all)
        code    (runner/print-report summary)]
    (is (= 0 code))))

(deftest test-scenario-pass-aligned-with-run-all
  (let [{:keys [results]} (runner/run-all)]
    (testing "single-scenario entries (paired registry rows omit :outcome)"
      (is (every? #(= (:pass? %) (scenario-runner/scenario-pass? % {}))
                  (filter #(contains? % :outcome) results))))))
