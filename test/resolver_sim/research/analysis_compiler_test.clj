(ns resolver-sim.research.analysis-compiler-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.analysis-compiler :as compiler]
            [resolver-sim.research.parallel-executor :as executor]
            [resolver-sim.research.parallel-executor-test :as fixture]
            [resolver-sim.research.result-store :as store]
            [resolver-sim.research.execution-cell-runner :as runner]
            [resolver-sim.research.scheduler-harness :as harness]))

(defn populated-store [scope execution-manifest]
  (let [result-store (store/new-store)
        report (executor/execute-manifest scope execution-manifest)]
    (doseq [entry (:execution/entries report)]
      (store/put-result! result-store (:result entry)))
    result-store))

(deftest complete-policy-analysis-is-dense-and-rooted
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static :work-stealing])
        result-store (populated-store scope manifest)
        static-root (harness/policy-subject-root :static false)
        analysis (compiler/compile-policy-analysis frozen manifest result-store static-root
                                                   {:execution/repeat-id 0
                                                    :execution/fixture-id :fixture/parallel})]
    (is (= :complete (:analysis/status analysis)))
    (is (= 2 (count (:analysis/coordinates analysis))))
    (is (every? integer? (vals (:analysis/coordinates analysis))))
    (is (= #{:complete} (set (vals (:analysis/cell-statuses analysis)))))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:research-analysis/root analysis)))
    (is (= analysis (compiler/compile-policy-analysis frozen manifest result-store static-root
                                                      {:execution/repeat-id 0
                                                       :execution/fixture-id :fixture/parallel})))))

(deftest missing-and-conflicting-cells-remain-explicit
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static :work-stealing])
        result-store (store/new-store)
        report (executor/execute-manifest scope manifest)
        static-root (harness/policy-subject-root :static false)
        work-root (harness/policy-subject-root :work-stealing false)
        static-entry (first (filter #(= static-root (get-in % [:result :policy-subject/root]))
                                    (:execution/entries report)))]
    (store/put-result! result-store (:result static-entry))
    (let [static-analysis (compiler/compile-policy-analysis frozen manifest result-store static-root
                                                            {:execution/repeat-id 0 :execution/fixture-id :fixture/parallel})
          work-analysis (compiler/compile-policy-analysis frozen manifest result-store work-root
                                                          {:execution/repeat-id 0 :execution/fixture-id :fixture/parallel})]
      (is (= :incomplete (:analysis/status static-analysis)))
      (is (some #{:missing} (vals (:analysis/cell-statuses static-analysis))))
      (is (some #{:missing} (vals (:analysis/coordinates static-analysis))))
      (is (= :incomplete (:analysis/status work-analysis)))
      (is (every? #{:missing} (vals (:analysis/coordinates work-analysis)))))))

(deftest conflicting-cells-compile-as-not-comparable
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static])
        result-store (store/new-store)
        result (:result (first (:execution/entries (executor/execute-manifest scope manifest))))
        conflicting (-> result
                        (assoc-in [:metrics :makespan-ms]
                                  (inc (get-in result [:metrics :makespan-ms])))
                        (dissoc :research-execution-cell-result/root))
        conflicting (assoc conflicting :research-execution-cell-result/root
                           (runner/result-root conflicting))
        policy-root (harness/policy-subject-root :static false)]
    (store/put-result! result-store result)
    (store/put-result! result-store conflicting)
    (let [analysis (compiler/compile-policy-analysis frozen manifest result-store policy-root
                                                     {:execution/repeat-id 0 :execution/fixture-id :fixture/parallel})]
      (is (= :incomplete (:analysis/status analysis)))
      (is (some #{:conflicting} (vals (:analysis/cell-statuses analysis))))
      (is (some #{:not-comparable} (vals (:analysis/coordinates analysis)))))))

(deftest executor-failures-remain-visible-in-analysis-status
  (let [{:keys [frozen manifest scope]} (fixture/fixture [:static])
        policy-root (harness/policy-subject-root :static false)
        broken-scope (update scope :policy-by-subject-root dissoc policy-root)
        report (executor/execute-manifest broken-scope manifest)
        analysis (compiler/compile-policy-analysis
                  frozen manifest (store/new-store) policy-root
                  {:execution/repeat-id 0 :execution/fixture-id :fixture/parallel}
                  report)]
    (is (every? #(= :failed (:execution/status %)) (:execution/entries report)))
    (is (= #{:failed} (set (vals (:analysis/cell-statuses analysis)))))
    ;; Comparison consumers retain their established missing-cell convention,
    ;; while the analysis status records the distinct execution failure.
    (is (every? #{:missing} (vals (:analysis/coordinates analysis))))))
