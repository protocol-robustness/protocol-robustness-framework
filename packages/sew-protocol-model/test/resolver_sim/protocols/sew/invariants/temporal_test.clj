(ns resolver-sim.protocols.sew.invariants.temporal-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.invariants.temporal :as inv]))

(deftest test-temporal-consistency-invariant
  (testing "Holds when perfectly synchronized"
    (let [w {:block-time 1000 :context/time {:block-ts 1000 :step 0}}]
      (is (:holds? (inv/check-temporal-consistency w)))))

  (testing "Holds for legacy worlds (missing context)"
    (let [w {:block-time 1000}]
      (is (:holds? (inv/check-temporal-consistency w)))))

  (testing "Fails when drift detected"
    (let [w {:block-time 1001 :context/time {:block-ts 1000 :step 0}}
          res (inv/check-temporal-consistency w)]
      (is (false? (:holds? res)))
      (is (= :temporal-drift (get-in res [:violations 0 :error]))))))
