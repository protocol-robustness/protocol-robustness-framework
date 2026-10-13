(ns resolver-sim.benchmark.conservation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.conservation :as conservation]))

(defn entry [id status]
  {:execution_id id :result_ref (str "benchmark/executions/" id "/summary.edn")
   :result_sha256 "sha256:test" :invariant_id "conservation-of-funds" :status status})

(deftest aggregate-status-is-derived-from-expected-and-observed-executions
  (is (= :pass (conservation/aggregate-status ["a" "b"] [(entry "a" :pass) (entry "b" :pass)])))
  (is (= :fail (conservation/aggregate-status ["a" "b"] [(entry "a" :pass) (entry "b" :fail)])))
  (is (= :incomplete (conservation/aggregate-status ["a" "b"] [(entry "a" :pass)])))
  (is (= :not-exercised (conservation/aggregate-status [] []))))

(deftest projection-records-expected-set-and-derived-counts
  (let [artifact (conservation/project {:benchmark-id "sew/example" :run-id "run-1"
                                        :expected-execution-ids ["a" "b"]
                                        :executions [(entry "a" :pass) (entry "b" :pass)]})]
    (is (= "pass" (:status artifact)))
    (is (= 2 (get-in artifact [:summary :expected])))
    (is (= 2 (get-in artifact [:summary :passed])))
    (is (string? (conservation/final-ref artifact)))))
