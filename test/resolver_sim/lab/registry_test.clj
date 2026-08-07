(ns resolver-sim.lab.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.lab.registry :as registry]
            [resolver-sim.lab.runner :as runner]))

(deftest registry-validates
  (is (registry/validate-registry!)))

(deftest known-experiment-resolves
  (doseq [[id slug] [[:withdrawal/constrained-liquidity "withdrawal-constrained-liquidity"]
                     [:insolvency/impairment "insolvency-after-loss"]
                     [:pro-rata/allocation "pro-rata-allocation"]]]
    (testing (str id)
      (is (= id (:experiment/id (registry/find-experiment id))))
      (is (= id (:experiment/id (registry/find-experiment slug))))
      (is (= slug (:experiment/slug (registry/find-experiment slug)))))))

(deftest version-suffix-resolution
  (let [{:keys [experiment version]}
        (registry/resolve-reference "pro-rata-allocation.v1")]
    (is (= :pro-rata/allocation (:experiment/id experiment)))
    (is (= 1 version))))

(deftest unknown-experiment-rejected
  (is (nil? (registry/find-experiment :not/real)))
  (is (nil? (registry/find-experiment "not-a-real-experiment")))
  (is (:error (registry/resolve-reference "not-a-real-experiment.v1")))
  (is (:error (registry/resolve-reference "pro-rata-allocation.v99"))))

(deftest duplicate-ids-rejected
  (let [dupe (first registry/experiments)
        bad (into registry/experiments [dupe])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/validate-registry! bad)))))

(deftest every-experiment-parameter-spec-well-formed
  (doseq [experiment registry/experiments
          param (:parameters experiment)]
    (is (some? (:parameter/id param)) (str "missing parameter id in " (:experiment/id experiment)))
    (is (contains? #{:integer :enum :boolean} (:type param)))
    (when (= :integer (:type param))
      (is (<= (:min param) (:max param))))
    (when (= :enum (:type param))
      (is (seq (:options param))))))

(deftest every-registry-runner-is-dispatched
  (doseq [experiment registry/experiments]
    (is (contains? runner/runner-dispatch (:runner experiment))
        (str "registry runner not wired for " (:experiment/id experiment)))
    (is (fn? (get runner/runner-dispatch (:runner experiment))))))

(deftest runner-dispatch-is-closed
  (is (= (set (map :runner registry/experiments))
         (set (keys runner/runner-dispatch)))))

(deftest public-view-shape
  (let [view (registry/experiment->public (first registry/experiments))]
    (is (string? (:experiment/id view)))
    (is (string? (:experiment/ref view)))
    (is (seq (:parameters view)))
    (doseq [p (:parameters view)]
      (is (string? (:parameter/id p)))
      (is (string? (:parameter/type p))))))
