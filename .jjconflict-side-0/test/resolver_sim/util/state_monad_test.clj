(ns resolver-sim.util.state-monad-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.util.state-monad :as sm]))

(deftest test-return
  (testing "return wraps a value and leaves state unchanged"
    (let [[v s] (sm/run-state (sm/return 42) {:initial true})]
      (is (= 42 v))
      (is (= {:initial true} s)))))

(deftest test-bind
  (testing "bind threads state through chained computations"
    (let [comp (sm/bind (sm/return 1)
                        (fn [a]
                          (sm/bind (sm/return 2)
                                   (fn [b]
                                     (sm/return (+ a b))))))]
      (is (= [3 {}] (sm/run-state comp {}))))))

(deftest test-get-state
  (testing "get-state captures the current state as the value"
    (let [[v s] (sm/run-state (sm/get-state) {:x 10})]
      (is (= {:x 10} v))
      (is (= {:x 10} s)))))

(deftest test-put-state
  (testing "put-state replaces the state entirely"
    (let [comp (sm/bind (sm/put-state {:replaced true})
                        (fn [_] (sm/get-state)))]
      (is (= [{:replaced true} {:replaced true}]
             (sm/run-state comp {:original true}))))))

(deftest test-update-state
  (testing "update-state applies f to the state"
    (let [comp (sm/bind (sm/update-state assoc :a 1)
                        (fn [_]
                          (sm/bind (sm/update-state assoc :b 2)
                                   (fn [_]
                                     (sm/get-state)))))]
      (is (= [{:a 1 :b 2} {:a 1 :b 2}]
             (sm/run-state comp {}))))))

(deftest test-eval-state
  (testing "eval-state returns only the value"
    (is (= 42 (sm/eval-state (sm/return 42) {:state true})))))

(deftest test-exec-state
  (testing "exec-state returns only the final state"
    (is (= {:state true} (sm/exec-state (sm/return 42) {:state true})))))

(deftest test-composed-validation-style
  (testing "validation-style accumulation"
    (let [m (sm/bind (sm/update-state update :status-keys conj :yield/shortfall-tested)
                     (fn [_]
                       (sm/bind (sm/update-state update :error-keys conj :yield/deferred-mismatch)
                                (fn [_]
                                  (sm/bind
                                   (sm/update-state update :evidence conj
                                                    {:check/id :yield/deferred-accounting :status :failed})
                                   (fn [_] (sm/get-state)))))))
          final (sm/exec-state m {:status-keys #{}
                                  :error-keys #{}
                                  :evidence []})]
      (is (contains? (:status-keys final) :yield/shortfall-tested))
      (is (contains? (:error-keys final) :yield/deferred-mismatch))
      (is (= 1 (count (:evidence final))))
      (is (= :yield/deferred-accounting (:check/id (first (:evidence final))))))))

(deftest test-sequence-state
  (testing "sequence-state runs computations in order and collects values"
    (let [m (sm/sequence-state [(sm/return 1) (sm/return 2) (sm/return 3)])]
      (is (= [[1 2 3] {}] (sm/run-state m {}))))))

(deftest test-traverse-state
  (testing "traverse-state maps over a collection with state threading"
    (let [f (fn [x]
              (sm/bind (sm/update-state update :seen conj x)
                       (fn [_] (sm/return (* 2 x)))))
          m (sm/traverse-state f [1 2 3])]
      (is (= [[2 4 6] {:seen [1 2 3]}] (sm/run-state m {:seen []}))))))

(deftest test-run-state-example
  (testing "the documented example works"
    (let [result (sm/run-state
                  (sm/bind (sm/get-state)
                           (fn [s]
                             (sm/bind (sm/update-state assoc :validated? true)
                                      (fn [_]
                                        (sm/return (:run-id s))))))
                  {:run-id "abc"})]
      (is (= ["abc" {:run-id "abc" :validated? true}] result)))))

(deftest test-state-is-immutable
  (testing "computation does not mutate the original state data"
    (let [original {:count 0}
          m (sm/update-state update :count inc)]
      (sm/run-state m original)
      (is (= {:count 0} original)
          "original state should remain unchanged")))
  (testing "run-state returns a new state, does not mutate input"
    (let [original {:count 0}
          [_ final] (sm/run-state (sm/update-state update :count inc) original)]
      (is (= {:count 0} original))
      (is (= {:count 1} final)))))
