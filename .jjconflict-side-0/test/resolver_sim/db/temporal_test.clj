(ns resolver-sim.db.temporal-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.db.temporal :as temporal]
            [resolver-sim.protocols.sew :as sew]))

(deftest build-records-shape
  (testing "run/step/invariant/coverage builders return expected contract"
    (let [run (temporal/build-run-record {:run-id "r1"
                                          :batch-id :b1
                                          :protocol sew/protocol
                                          :suite-id :temporal-suite
                                          :scenario-id :s74
                                          :seed 42
                                          :git-sha "abc123"
                                          :outcome :pass
                                          :metrics {:invariant-violations 0}
                                          :block-time 1000})
          step (temporal/build-step-record {:run-id "r1"
                                            :step-index 3
                                            :action :execute_pending_settlement
                                            :result :ok
                                            :time-before {:time/block-ts 1179}
                                            :time-advance {:seconds 1}
                                            :time-after {:time/block-ts 1180}
                                            :projection-hash "h1"
                                            :block-time 1180})
          inv  (temporal/build-invariant-record {:run-id "r1"
                                                 :step-index 3
                                                 :invariant :time-non-decreasing
                                                 :holds? true
                                                 :severity :time
                                                 :violations []
                                                 :block-time 1180})
          cov  (temporal/build-coverage-record {:run-id "r1"
                                                :coverage {:offsets [-1 0 1]}
                                                :block-time 1180})]
      (is (= "r1" (:id run)))
      (is (= "sew-v1" (:protocol-id run)))
      (is (instance? java.util.Date (:valid-from run)))
      (is (= "r1" (:run-id step)))
      (is (= :ok (:result step)))
      (is (= :time-non-decreasing (:invariant inv)))
      (is (true? (:holds? inv)))
      (is (= {:offsets [-1 0 1]} (:coverage cov))))))

(deftest record-temporal-run-nil-ds
  (testing "record-temporal-run! is safe/no-op with nil datasource"
    (let [out (temporal/record-temporal-run!
               nil
               {:run {:run-id "r2" :batch-id :b2 :protocol sew/protocol :scenario-id :s75 :outcome :fail :block-time 1300}
                :steps [{:step-index 2 :action :automate_timed_actions :result :ok :block-time 1300}]
                :invariants [{:step-index 2 :invariant :time-non-decreasing :holds? true :severity :time :block-time 1300}]
                :coverage {:coverage {:same-block true} :block-time 1300}})]
      (is (= "r2" (get-in out [:run :id])))
      (is (= 1 (count (:steps out))))
      (is (= 1 (count (:invariants out))))
      (is (= {:same-block true} (get-in out [:coverage :coverage]))))))

(deftest record-temporal-run-rejects-decreasing-valid-time
  (testing "record-temporal-run! rejects decreasing valid-time in steps/invariants"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"non-decreasing valid-time violated"
         (temporal/record-temporal-run!
          nil
          {:run {:run-id "r3" :batch-id :b3 :protocol sew/protocol :scenario-id :s75 :outcome :fail :block-time 1300}
           :steps [{:step-index 1 :action :create_escrow :result :ok :block-time 1301}
                   {:step-index 2 :action :release :result :ok :block-time 1300}]
           :invariants [{:step-index 1 :invariant :time-non-decreasing :holds? true :severity :time :block-time 1301}
                        {:step-index 2 :invariant :time-non-decreasing :holds? true :severity :time :block-time 1300}]})))))

(deftest summary-helpers
  (testing "boundary/drift/determinism helper outputs"
    (let [boundary (temporal/summarize-boundary-outcomes
                    [{:offset -1 :result :rejected}
                     {:offset 0 :result :ok}
                     {:offset 0 :result :ok}
                     {:offset 1 :result :ok}])
          drift (temporal/summarize-drift-budget
                 [{:module :aave :drift 10 :budget 365}
                  {:module :fixed :drift 400 :budget 365}])
          det (temporal/summarize-determinism
               [{:cohort :s74 :projection-hash "h1"}
                {:cohort :s74 :projection-hash "h1"}
                {:cohort :s75 :projection-hash "h2"}
                {:cohort :s75 :projection-hash "h3"}])]
      (is (= 1 (get-in boundary [-1 :rejected])))
      (is (= 2 (get-in boundary [0 :ok])))
      (is (= 1 (:violations drift)))
      (is (true? (get-in det [:s74 :deterministic?])))
      (is (false? (get-in det [:s75 :deterministic?]))))))
