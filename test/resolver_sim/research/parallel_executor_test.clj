(ns resolver-sim.research.parallel-executor-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.research.execution-manifest :as manifest]
            [resolver-sim.research.parallel-executor :as executor]
            [resolver-sim.research.parallel-validation :as parallel-validation]
            [resolver-sim.research.scheduler-harness :as harness]
            [resolver-sim.validation.runtime-report :as runtime-report])
  (:import [java.nio.file Files]))

(defn root [digit]
  (str "sha256:" (apply str (repeat 64 digit))))

(def source
  {:artifact/schema research/source-schema
   :research/id :parallel/executor
   :research/title "Parallel executor fixture"
   :research/question "Does runtime concurrency preserve rooted cell results?"
   :research/vary {:fixture/load [1 2]}
   :research/measures [{:measure/id :fixture/makespan-ms
                        :measure/kind research/requirement-margin-kind
                        :measure/domain {:kind :integer :unit :unit/ms}
                        :measure/observation {:observation/id :fixture/makespan-ms}
                        :measure/requirement {:value 0 :unit :unit/ms}
                        :measure/satisfying-direction :at-least}]})

(defn fixture [policies]
  (let [frozen (research/freeze-research source)
        workloads [(harness/workload {:id :executor-a})
                   (harness/workload {:id :executor-b :gpu-scale 2})]
        cases (get-in frozen [:research-definition :research/cases])
        policy-roots (mapv #(harness/policy-subject-root % false) policies)
        execution-manifest
        (manifest/build-manifest
         frozen
         {:policy-subject-roots policy-roots
          :case-workloads (into {}
                                (map (fn [research-case workload]
                                       [(:case/id research-case)
                                        {:workload/root (:workload/root workload)
                                         :expected/root (harness/canonical-root workload)}])
                                     cases workloads))
          :repeats [{:execution/repeat-id 0 :execution/fixture-id :fixture/parallel}]})]
    {:frozen frozen
     :manifest execution-manifest
     :scope {:policy-by-subject-root (into {}
                                           (map (fn [policy]
                                                  [(harness/policy-subject-root policy false) policy])
                                                policies))
             :workload-by-root (into {} (map (juxt :workload/root identity) workloads))}}))

(deftest sequential-and-thread-pool-execution-have-identical-result-sets
  (let [{:keys [manifest scope]} (fixture [:static :work-stealing :speculative-stealing])
        sequential (executor/execute-manifest scope manifest)
        parallel (executor/execute-manifest scope manifest :thread-pool {:max-concurrency 2})]
    (is (= 6 (count (:execution/entries sequential))))
    (is (every? #(= :complete (:execution/status %)) (:execution/entries parallel)))
    (is (= (executor/result-set sequential) (executor/result-set parallel)))
    (is (= (mapv :research-execution-cell/root (:execution/entries parallel))
           (vec (sort (map :research-execution-cell/root (:execution/entries parallel))))))))

(deftest failures-are-explicit-and-ordered
  (let [{:keys [manifest scope]} (fixture [:static :work-stealing])
        broken-scope (assoc scope :policy-by-subject-root {})
        report (executor/execute-manifest broken-scope manifest :thread-pool {:max-concurrency 2})]
    (is (every? #(= :failed (:execution/status %)) (:execution/entries report)))
    (is (every? :execution/error (:execution/entries report)))
    (is (= (mapv :research-execution-cell/root (:execution/entries report))
           (mapv :research-execution-cell/root (executor/result-set report))))))

(deftest executor-options-and-manifest-validation-fail-closed
  (let [{:keys [manifest scope]} (fixture [:static])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (executor/execute-manifest scope manifest :thread-pool {:max-concurrency 0})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (executor/execute-manifest scope manifest :sequential {:max-concurrency 2})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (executor/execute-manifest scope (update manifest :dispatch/cells pop))))))

(deftest parallel-runtime-validation-persists-a-root-bound-report
  (let [{:keys [manifest scope]} (fixture [:static :work-stealing])
        dir (Files/createTempDirectory "parallel-validation" (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve dir "reports/parallel.edn")]
    (try
      (let [result (parallel-validation/execute-and-report!
                    scope manifest :thread-pool {:max-concurrency 2}
                    {:run/id "parallel-run-1" :source/revision (apply str (repeat 40 "a"))
                     :report/path (str path)})
            execution-report (:execution/report result)
            validation-report (:validation/report result)
            persisted (runtime-report/read-report path)]
        (is (= (count (:execution/entries execution-report)) (count (:checks validation-report))))
        (is (= :passed (:status validation-report)))
        (is (= (str path) (:validation/report-path result)))
        (is (= validation-report persisted))
        (is (some #{(:research-execution-manifest/root manifest)}
                  (:execution/roots validation-report)))
        (is (every? #(= :passed (:status %)) (:checks validation-report)))
        (is (= :thread-pool (get-in execution-report [:executor/kind]))))
      (finally
        (doseq [file (reverse (file-seq (.toFile dir)))]
          (Files/deleteIfExists (.toPath file)))))))

(deftest parallel-runtime-validation-records-failures-and-rejects-incomplete-coverage
  (let [{:keys [manifest scope]} (fixture [:static])
        broken-scope (assoc scope :policy-by-subject-root {})
        report (executor/execute-manifest broken-scope manifest)
        validation-report (parallel-validation/build-execution-report
                           manifest report {:run/id "parallel-failed"
                                            :source/revision (apply str (repeat 40 "a"))})]
    (is (= :failed (:status validation-report)))
    (is (every? #(= :failed (:status %)) (:checks validation-report)))
    (is (every? #(= :warning (:severity %)) (:checks validation-report)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parallel-validation/build-execution-report
                  manifest (update report :execution/entries pop)
                  {:run/id "parallel-incomplete" :source/revision (apply str (repeat 40 "a"))})))
    (let [dir (Files/createTempDirectory "parallel-failed-validation"
                                         (make-array java.nio.file.attribute.FileAttribute 0))
          path (.resolve dir "failed.edn")]
      (try
        (let [result (parallel-validation/execute-and-report!
                      broken-scope manifest :sequential {}
                      {:run/id "parallel-failed-persisted"
                       :source/revision (apply str (repeat 40 "a"))
                       :report/path (str path)})
              persisted (runtime-report/read-report path)]
          (is (= :failed (get-in result [:validation/report :status])))
          (is (= (:validation/report result) persisted))
          (is (every? #(= :warning (:severity %)) (:checks persisted))))
        (finally
          (doseq [file (reverse (file-seq (.toFile dir)))]
            (Files/deleteIfExists (.toPath file))))))))
