(ns resolver-sim.research.result-store-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.execution-cell :as cell]
            [resolver-sim.research.execution-cell-runner :as runner]
            [resolver-sim.research.result-store :as store]
            [resolver-sim.research.scheduler-harness :as harness]))

(defn root [digit] (str "sha256:" (apply str (repeat 64 digit))))
(def workload (harness/workload {:id :result-store}))

(defn fixture-result []
  (let [policy :static
        execution-cell
        (cell/build-cell
         {:research-definition/root (root \1)
          :policy-subject/root (harness/policy-subject-root policy false)
          :case-axis/root (root \2) :measure-axis/root (root \3)
          :case/id :fixture/case-0 :case/parameters {:fixture/value 1}
          :workload/root (:workload/root workload)
          :execution/repeat-id 0 :execution/fixture-id :fixture/result-store
          :commit/predicate {:predicate/kind :canonical-root-equality.v1
                             :expected/root (harness/canonical-root workload)}})]
    (runner/run-cell {:policy-by-subject-root {(harness/policy-subject-root policy false) policy}
                      :workload-by-root {(:workload/root workload) workload}}
                     execution-cell)))

(deftest result-store-is-content-addressed-and-idempotent
  (let [result (fixture-result)
        result-store (store/new-store)
        first-put (store/put-result! result-store result)
        second-put (store/put-result! result-store result)]
    (is (= :stored (:store/status first-put)))
    (is (= :duplicate (:store/status second-put)))
    (is (= result (store/result-by-root result-store
                                        (:research-execution-cell-result/root result))))
    (is (= result (store/result-by-cell result-store
                                        (:research-execution-cell/root result))))
    (is (= :complete (store/cell-status result-store
                                        (:research-execution-cell/root result))))))

(deftest result-store-preserves-conflicts-without-overwriting-the-first-result
  (let [result (fixture-result)
        conflicting (-> result
                        (assoc-in [:metrics :makespan-ms]
                                  (inc (get-in result [:metrics :makespan-ms])))
                        (dissoc :research-execution-cell-result/root))
        conflicting (assoc conflicting :research-execution-cell-result/root
                           (runner/result-root conflicting))
        result-store (store/new-store)]
    (store/put-result! result-store result)
    (is (= :conflict (:store/status (store/put-result! result-store conflicting))))
    (is (= result (store/result-by-cell result-store
                                        (:research-execution-cell/root result))))
    (is (= :conflicting (store/cell-status result-store
                                           (:research-execution-cell/root result))))
    (is (= #{(:research-execution-cell-result/root result)
             (:research-execution-cell-result/root conflicting)}
           (get (store/conflicts result-store) (:research-execution-cell/root result))))))

(deftest missing-cells-retains-missing-and-conflicting-cells
  (let [result (fixture-result)
        result-store (store/new-store)
        manifest {:dispatch/cells [{:research-execution-cell/root (:research-execution-cell/root result)}
                                   {:research-execution-cell/root "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"}] }]
    (store/put-result! result-store result)
    (is (= [(second (:dispatch/cells manifest))]
           (store/missing-cells result-store manifest)))))

(deftest concurrent-duplicate-writes-remain-one-cell-binding
  (let [result (fixture-result)
        result-store (store/new-store)
        writes (doall (map deref (repeatedly 16 #(future (store/put-result! result-store result)))))]
    (is (= 1 (count (:by-cell-root (store/snapshot result-store)))))
    (is (= 1 (count (:by-result-root (store/snapshot result-store)))))
    (is (= 1 (count (filter #(= :stored (:store/status %)) writes))))))
