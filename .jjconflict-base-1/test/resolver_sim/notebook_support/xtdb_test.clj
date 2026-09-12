(ns resolver-sim.notebook-support.xtdb-test
  "Unit tests for the XTDB Temporal Explorer notebook-support helpers.
   The db layer is stubbed; these pin the explicit-scope comparison rule and the
   knowledge-diff composition without needing a live XTDB."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.db.execution-projection :as ep]
            [resolver-sim.notebook-support.xtdb :as sut]))

(deftest comparable-executions-uses-explicit-scope
  (testing "rows are grouped only under the caller-supplied scope; no equivalence is inferred"
    (with-redefs [ep/recent (fn [_ds _n]
                              [{:execution/_id "A" :execution/scenario_id "s1" :execution/bundle_root "R1"}
                               {:execution/_id "B" :execution/scenario_id "s1" :execution/bundle_root "R1"}
                               {:execution/_id "C" :execution/scenario_id "s2" :execution/bundle_root "R2"}])]
      (let [s1 (sut/comparable-executions nil {:scenario-id "s1"})]
        (is (= {:scenario-id "s1"} (:scope s1)))
        (is (= ["R1"] (:result-roots s1)))
        (is (= ["A" "B"] (get-in s1 [:groups "R1"])))
        (is (nil? (get-in s1 [:groups "R2"]))
            "rows outside the explicit scope are not grouped"))
      (let [none (sut/comparable-executions nil {:scenario-id "nope"})]
        (is (= [] (:result-roots none)))))))

(deftest execution-run-knowledge-diff-composes-queries
  (testing "then (as-known-at) vs now (valid-at), separating content from temporal-metadata changes"
    (with-redefs [ep/execution-runs-as-known-at
                  (fn [_ds _t] [{:execution/_id "r" :execution/bundle_root "THEN" :execution/status "pass"
                                 :execution/_system_from :t1}])
                  ep/execution-runs-valid-at
                  (fn [_ds _t] [{:execution/_id "r" :execution/bundle_root "NOW" :execution/status "pass"
                                 :execution/_system_from :t2}])]
      (let [diff (sut/execution-run-knowledge-diff
                  nil {:execution-run/id "r"
                       :valid-at (java.util.Date.)
                       :known-at (java.util.Date.)})]
        (is (= "THEN" (get-in diff [:then :execution/bundle_root])))
        (is (= "NOW" (get-in diff [:now :execution/bundle_root])))
        (is (= [:execution/bundle_root] (:changed-keys diff))
            "changed-keys describes projection-content changes, excluding XTDB temporal metadata")
        (is (= [:execution/_system_from] (:temporal-metadata-changed-keys diff))
            "the system-time bound is reported separately as temporal metadata")
        (is (not (some #{:execution/status} (:changed-keys diff)))))))

  (testing "returns {} when the run is absent from either snapshot"
    (with-redefs [ep/execution-runs-as-known-at (fn [_ds _t] [])
                  ep/execution-runs-valid-at (fn [_ds _t] [])]
      (is (= {} (sut/execution-run-knowledge-diff
                 nil {:execution-run/id "r"
                      :valid-at (java.util.Date.)
                      :known-at (java.util.Date.)}))))))