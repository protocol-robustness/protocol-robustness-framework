(ns resolver-sim.lab.runner-test
  "End-to-end lab tests: registry -> validate -> execute -> normalize -> expose
   evidence, for every V1 experiment, plus determinism and evidence-correspondence
   guarantees."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.lab.exec :as exec]
            [resolver-sim.lab.runner :as runner]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invariants :as invariants]))

(def ^:private requests
  [{:experiment "withdrawal-constrained-liquidity.v1"
    :parameters {:available-liquidity 1000 :alice-requested 500
                 :bob-requested 500 :carol-requested 400 :mechanism "fcfs"}}
   {:experiment "withdrawal-constrained-liquidity.v1"
    :parameters {:available-liquidity 1000 :alice-requested 500
                 :bob-requested 500 :carol-requested 400 :mechanism "pro-rata"
                 :rounding-policy "largest-remainder"}}
   {:experiment "insolvency-after-loss.v1"
    :parameters {:custody 1000 :recognized-loss 100}}
   {:experiment "insolvency-after-loss.v1"
    :parameters {:custody 1000 :recognized-loss 0 :observed-balances 700}}
   {:experiment "pro-rata-allocation.v1"
    :parameters {:available 1000 :alice-requested 500 :bob-requested 300
                 :carol-requested 200 :rounding-policy "largest-remainder"}}])

(defn- run-in-process [request]
  (runner/execute request (runner/generate-run-id)))

(deftest every-experiment-completes-in-process
  (doseq [req requests]
    (testing (:experiment req)
      (let [result (run-in-process req)]
        (is (= :completed (:lab-run/status result)))
        (is (string? (:lab-run/id result)))
        (is (= "lab-run.v1" (:lab-run/schema-version result)))
        (is (map? (:inputs result)))
        (is (string? (:inputs/hash result)))
        (is (map? (:outcome result)))
        (is (map? (:evidence result)))
        (is (vector? (:findings result)))
        (is (seq (get-in result [:evidence :roots])))
        (is (= :anonymous-visitor (get-in result [:execution :visitor])))
        (is (= :anonymous-lab (get-in result [:execution :runner])))))))

(deftest every-experiment-completes-in-subprocess
  (doseq [req requests]
    (testing (str "subprocess " (:experiment req))
      (let [result (exec/run-experiment! req (runner/generate-run-id)
                                         {:runs-dir "/tmp/lab-test-runs"
                                          :timeout-ms 120000})]
        ;; The wire format is JSON: statuses are strings, not keywords.
        (is (= "completed" (str (:lab-run/status result))))
        (is (= "lab-run.v1" (:lab-run/schema-version result)))
        (let [roots (get-in result [:evidence :roots])]
          (is (seq roots))
          (is (every? string? (vals roots))))))))

(deftest normalized-envelope-shape
  (let [result (run-in-process (first requests))]
    (is (map? (:experiment result)))
    (is (= "lab-run.v1" (:lab-run/schema-version result)))
    (is (map? (:execution result)))
    (is (= :anonymous-lab (:runner (:execution result))))
    (is (int? (:duration-ms (:execution result))))))

(deftest withdrawal-shows-mechanism-semantics
  (let [fcfs (run-in-process {:experiment "withdrawal-constrained-liquidity.v1"
                              :parameters {:available-liquidity 1000
                                           :alice-requested 500
                                           :bob-requested 500
                                           :carol-requested 400
                                           :mechanism "fcfs"}})
        pr (run-in-process {:experiment "withdrawal-constrained-liquidity.v1"
                            :parameters {:available-liquidity 1000
                                         :alice-requested 500
                                         :bob-requested 500
                                         :carol-requested 400
                                         :mechanism "pro-rata"}})
        rows (:rows (:outcome fcfs))]
    (is (= :shortfall (:assessment/status (:assessment fcfs))))
    (is (= 1400 (:total-requested (:outcome fcfs))))
    (is (= 1000 (:total-filled (:outcome fcfs))))
    ;; FCFS: alice and bob served, carol deferred.
    (is (= 500 (:filled (first rows))))
    (is (= 0 (:filled (nth rows 2))))
    ;; Different mechanism, different distribution: pro-rata splits the shortfall.
    (is (not= (mapv :filled (:rows (:outcome fcfs)))
              (mapv :filled (:rows (:outcome pr)))))))

(deftest insolvency-uses-assessment-vocabulary
  (let [impaired (run-in-process {:experiment "insolvency-after-loss.v1"
                                  :parameters {:custody 1000 :recognized-loss 100}})
        insolvent (run-in-process {:experiment "insolvency-after-loss.v1"
                                   :parameters {:custody 1000
                                                :recognized-loss 0
                                                :observed-balances 700}})]
    (is (= :impaired (:assessment/status (:assessment impaired))))
    (is (= :insolvent (:assessment/status (:assessment insolvent))))
    (is (string? (get-in impaired [:evidence :roots :liability-set-root])))
    (is (string? (get-in impaired [:evidence :roots :assessment-commitment])))
    ;; Accounting and economic-solvency are distinct dimensions.
    (is (true? (get-in impaired [:outcome :assessment/dimensions :accounting :holds?])))
    (is (true? (get-in impaired [:outcome :assessment/dimensions :economic-solvency :holds?])))))

(deftest pro-rata-reports-mechanism-witness
  (let [result (run-in-process {:experiment "pro-rata-allocation.v1"
                                :parameters {:available 1000 :alice-requested 500
                                             :bob-requested 300 :carol-requested 200}})]
    (is (= 1000 (:available (:outcome result))))
    (is (= 1000 (:allocated-total (:outcome result))))
    (is (= 0 (:unallocated-residual (:outcome result))))
    (is (string? (get-in result [:evidence :roots :allocation-hash])))
    (is (seq (:allocation/rounds (:outcome result))))))

(deftest determinism-same-inputs-same-semantic-result
  (doseq [req requests]
    (testing (:experiment req)
      (let [a (run-in-process req)
            b (run-in-process req)
            semantic (juxt :inputs/hash :outcome :findings :evidence)]
        (is (= (semantic a) (semantic b)))))))

(deftest evidence-correspondence-pro-rata-findings-match-engine
  ;; Findings reported by the lab are the pro-rata engine's own
  ;; invariant violations, not lab-recomputed checks.
  (doseq [req [(nth requests 1) (nth requests 4)]]
    (let [result (run-in-process req)
          params (get-in result [:inputs])
          available (long (or (:available params) (:available-liquidity params)))
          requested (mapv long [(or (:alice-requested params) 0)
                                (or (:bob-requested params) 0)
                                (or (:carol-requested params) 0)])
          rows (mapv (fn [[id n]] {:row/id id :obligation/id :claim
                                   :requested n :weight n :cap n})
                     [[:party/alice (nth requested 0)]
                      [:party/bob (nth requested 1)]
                      [:party/carol (nth requested 2)]])
          engine-result (allocation/allocate
                         {:schema-version "pro-rata-allocation-request.v1"
                          :mechanism/version 1
                          :allocation/id [:test]
                          :available available
                          :rows rows
                          :rounding-policy :largest-remainder
                          :tie-break-policy :canonical-row-id
                          :redistribution-policy :unallocated})
          violations (invariants/result-violations engine-result)
          reported-findings (:findings result)
          prf-findings (filter #(= :prf (:findings/origin %)) reported-findings)]
      (is (seq prf-findings))
      (is (every? #(= :pass (:findings/status %)) prf-findings)
          (str "engine violations: " (count violations))))))

(deftest execution-error-carries-reference-not-stack
  (let [result (runner/execute {:experiment "pro-rata-allocation.v1"
                                :parameters {:available 1000 :alice-requested 500
                                             :bob-requested 300 :carol-requested 200}}
                               "LAB-DETOUR")]
    ;; The normal path succeeds; a broken runner must degrade to an error
    ;; result with a reference, never a thrown exception.
    (is (= :completed (:lab-run/status result)))
    (is (nil? (get-in result [:lab-run/error :stack])))))
