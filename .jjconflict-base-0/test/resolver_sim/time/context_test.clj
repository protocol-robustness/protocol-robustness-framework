(ns resolver-sim.time.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.time.context :as ctx]))

(deftest test-temporal-context-derivation
  (testing "Creation from legacy world (no :context/time)"
    (let [w {:block-time 1000}
          c (ctx/temporal-context w)]
      (is (= 1000 (:block-ts c)))
      (is (= 0 (:step c)))
      (is (= :legacy (:clock/source c)))))

  (testing "Creation from rich world (with :context/time)"
    (let [w {:context/time {:block-ts 2000 :step 5 :clock/source :scenario}}]
      (is (= 2000 (ctx/block-ts w)))
      (is (= 5 (ctx/step w)))
      (is (= :scenario (:clock/source (ctx/temporal-context w))))))

  (testing "Synchronization during update"
    (let [w {:block-time 1000}
          w' (ctx/with-temporal-context w {:block-ts 2000 :step 1})]
      (is (= 2000 (:block-time w')) "Legacy key should be updated")
      (is (= 2000 (get-in w' [:context/time :block-ts])))
      (is (= 1 (get-in w' [:context/time :step]))))))

(deftest test-advance-time
  (testing "Relative advancement"
    (let [w {:block-time 1000}
          w' (ctx/advance-time w {:seconds 60})]
      (is (= 1060 (ctx/block-ts w')))
      (is (= 1 (ctx/step w')) "Step should increment by 1 default")))

  (testing "Absolute advancement"
    (let [w {:block-time 1000}
          w' (ctx/advance-time w {:to 2000 :steps 10})]
      (is (= 2000 (ctx/block-ts w')))
      (is (= 10 (ctx/step w')))))

  (testing "Atomic step preservation"
    (let [w {:context/time {:block-ts 1000 :step 42}}
          w' (ctx/advance-time w {:seconds 0})]
      (is (= 1000 (ctx/block-ts w')))
      (is (= 43 (ctx/step w'))))))

(deftest test-ensure-temporal-context-normalizes-malformed
  (testing "truthy-but-empty :context/time is rebuilt rather than passed through"
    (let [w (ctx/ensure-temporal-context {:context/time {}})]
      (is (= 0 (ctx/block-ts w)))
      (is (= 0 (ctx/step w)))
      (is (= :legacy (:clock/source (ctx/temporal-context w))))))

  (testing "a valid existing context is preserved unchanged"
    (let [ctx' {:context/time {:block-ts 2000 :step 5 :clock/source :scenario}}
          w (ctx/ensure-temporal-context ctx')]
      (is (= ctx' w)))))

(deftest test-with-temporal-context-single-source-of-truth
  (testing ":block-ts is authoritative; a divergent sub-second :instant is floored"
    (let [w (ctx/with-temporal-context {} {:block-ts 1000
                                           :instant (java.time.Instant/ofEpochSecond 1000 500000000)})]
      (is (= 1000 (get-in w [:context/time :block-ts])))
      (is (= "1970-01-01T00:16:40Z" (str (get-in w [:context/time :instant])))
          "instant must be derived from block-ts at second precision")
      (is (= 1000 (:block-time w)))))

  (testing ":block-ts derived from :instant when :block-ts absent"
    (let [w (ctx/with-temporal-context {} {:instant (java.time.Instant/ofEpochSecond 5000 123000000)})]
      (is (= 5000 (get-in w [:context/time :block-ts])))
      (is (= 5000 (:block-time w))))))

(deftest test-advance-time-rejects-regression-via-seconds
  (testing "advance-time via :seconds cannot move time backwards"
    (let [w {:block-time 1000}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"non-negative"
                            (ctx/advance-time w {:seconds -5}))))))
