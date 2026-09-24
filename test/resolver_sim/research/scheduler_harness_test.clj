(ns resolver-sim.research.scheduler-harness-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.research.scheduler-harness :as harness]))

(deftest five-policies-commit-the-same-root-with-explicit-failed-prediction
  (let [workload (harness/workload {:id :balanced})
        runs (mapv #(harness/run-policy workload % false) harness/policies)
        roots (mapv :committed/root runs)
        speculative (last runs)]
    (is (= 1 (count (set roots))))
    (is (= (harness/canonical-root workload) (first roots)))
    (is (every? #(= 1 (get-in % [:metrics :committed-root-agreement])) runs))
    (is (= [0 0 0 0] (mapv #(get-in % [:metrics :speculative-tasks-executed])
                        (butlast runs))))
    (is (= 3 (get-in speculative [:metrics :speculative-tasks-executed])))
    (is (= 2 (get-in speculative [:metrics :speculative-tasks-accepted])))
    (is (= 1 (get-in speculative [:metrics :speculative-tasks-discarded])))
    (is (= 2 (get-in speculative [:metrics :speculative-predictions-correct])))
    (is (= 1 (get-in speculative [:metrics :speculative-predictions-failed])))
    (is (= 1 (get-in speculative [:metrics :speculative-discard-verification-failed])))
    (is (pos? (get-in speculative [:metrics :speculative-work-discarded-ms])))
    (is (= 3 (get-in speculative [:metrics :verification-operations])))
    (is (= 6 (get-in speculative [:metrics :verifier-cost-ms])))
    (is (= (get-in speculative [:speculation/accounting :total-executed-work-ms])
           (+ (get-in speculative [:speculation/accounting :accepted-work-ms])
              (get-in speculative [:speculation/accounting :discarded-work-ms]))))
    (is (every? #(pos? (get-in % [:metrics :data-movement-bytes])) runs))
    (is (every? #(pos? (get-in % [:metrics :resource-interference-ms])) runs))
    (is (= 0 (get-in (first runs) [:metrics :steals])))
    (is (some #(pos? (get-in % [:metrics :steals])) (rest runs)))
    (is (every? #(pos? (get-in % [:metrics :failed-steals])) (rest runs)))
    (is (= runs (mapv #(harness/run-policy workload % false) harness/policies)))))

(deftest adaptive-granularity-is-an-ablation-with-the-same-logical-result
  (let [workload (harness/workload {:id :balanced})
        runs (for [policy harness/policies
                   adaptive? [false true]]
               (harness/run-policy workload policy adaptive?))]
    (is (= 1 (count (set (map :committed/root runs)))))
    (is (= 1 (count (set (map :workload/root runs)))))
    (is (some (fn [policy]
                (not= (get-in (harness/run-policy workload policy false)
                             [:metrics :makespan-ms])
                      (get-in (harness/run-policy workload policy true)
                              [:metrics :makespan-ms])))
              harness/policies))))

(deftest workload-root-and-prediction-injection-are-checked
  (let [workload (harness/workload {})
        wrong (assoc workload :transfer-bytes 100)
        scaled (harness/workload {:id :scaled :cpu-scale 2 :gpu-scale 3
                                  :tool-scale 4 :transfer-bytes 16})
        all-fail (harness/workload {:id :all-fail
                                   :false-predictions #{3 4 6}})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (harness/run-policy wrong :static false)))
    (is (not= (:workload/root workload) (:workload/root scaled)))
    (is (= (harness/canonical-root workload) (harness/canonical-root scaled)))
    (is (> (get-in (harness/run-policy scaled :static false) [:metrics :makespan-ms])
           (get-in (harness/run-policy workload :static false) [:metrics :makespan-ms])))
    (is (= #{false}
           (set (map :injection/success? (vals (:predictions all-fail))))))
    (is (= (harness/canonical-root all-fail)
           (:committed/root
            (harness/run-policy all-fail :speculative-stealing false))))))

(deftest rooted-research-matrices-record-the-same-cases-without-claim-promotion
  (let [experiment (harness/experiment harness/default-regimes)
        matrices (:matrices experiment)
        comparison (research/compare-reproductions
                    [(matrices :static) (matrices :work-stealing)])
        case-axis (get-in experiment [:runs :static])]
    (is (:all-committed-roots-identical? experiment))
    (is (= 3 (count (:workload-roots experiment))))
    (is (= 6 (count case-axis)))
    (is (= 5 (count matrices)))
    (is (= 6 (count (set (map first
                              (keys (:matrix/values (matrices :static))))))))
    (is (= :disagreement (:reproduction/status comparison)))
    (is (= experiment (harness/experiment harness/default-regimes)))
    (is (every? #(not (contains? % :research-claim/status))
                (vals matrices)))))

(deftest parameter-sweep-keeps-schedulers-fixed-and-records-rooted-regions
  (let [axes {:prediction/success-permille [0 1000]
              :gpu/bottleneck-scale [1 2]
              :transfer/bytes [0 12]}
        result (harness/parameter-sweep axes)
        summary (harness/sweep-summary result)]
    (is (= 8 (count (:regions result))))
    (is (= 5 (count (:runs result))))
    (is (= 5 (count (:analyses result))))
    (is (= 10 (count (:analysis-comparisons result))))
    (is (:all-committed-roots-identical? result))
    (is (= harness/policies (vec (keys (:runs result)))))
    (is (every? (fn [region]
                  (and (seq (:global-region/winners region))
                       (= (:global-region/minimum-makespan-ms region)
                          (apply min (vals (:policy/makespan-ms region))))))
                (:regions result)))
    (is (= #{:wins :ties :losses}
           (set (keys (get-in result [:policy-region-summary :static])))))
    (is (= result (harness/parameter-sweep axes)))
    (is (= (:policy-region-summary result) (:policy-region-summary summary)))
    (is (every? :research-analysis-comparison/root
                (vals (:analysis-comparisons result))))
    (is (= {:large-ms 10}
           (get-in result [:derived-region-analysis :margin/thresholds])))
    (is (= 10 (count (get-in result [:derived-region-analysis :pairwise/deltas]))))
    (is (every? (fn [entry]
                  (= (:pairwise/delta-ms entry)
                     (- (:left/makespan-ms entry) (:right/makespan-ms entry))))
                (mapcat identity (vals (get-in result [:derived-region-analysis
                                                       :pairwise/deltas])))))
    (is (every? #(contains? % :policy-region)
                (get-in result [:derived-region-analysis :policy-regions])))
    (is (string? (first (get-in result [:derived-region-analysis
                                        :human-readable-region-summaries]))))))

(deftest parameter-sweep-uses-explicit-representable-prediction-outcomes
  (let [zero (harness/sweep-workload-params
              {:prediction/success-permille [0]
               :gpu/bottleneck-scale [1]
               :transfer/bytes [0]})
        full (harness/sweep-workload-params
              {:prediction/success-permille [1000]
               :gpu/bottleneck-scale [1]
               :transfer/bytes [0]})
        partial (harness/sweep-workload-params
                 {:prediction/success-permille [333 667]
                  :gpu/bottleneck-scale [1]
                  :transfer/bytes [0]})]
    (is (= #{0} (set (map :prediction/success-count zero))))
    (is (= #{3} (set (map :prediction/success-count full))))
    (is (= #{1 2} (set (map :prediction/success-count partial))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (harness/sweep-workload-params
                  {:prediction/success-permille [500]
                   :gpu/bottleneck-scale [1]
                   :transfer/bytes [0]})))))
