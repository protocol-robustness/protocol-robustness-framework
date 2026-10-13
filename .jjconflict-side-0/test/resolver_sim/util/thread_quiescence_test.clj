(ns resolver-sim.util.thread-quiescence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.util.thread-quiescence :as quiesce])
  (:import [java.util.concurrent Callable Executors]
           [java.util.concurrent CountDownLatch]))

(defn- blocking-task
  "Create a task that re-blocks after interrupts, only exiting when
   the latch is counted down.  This simulates a worker that cannot be
   interrupted away from its blocking call (e.g. non-interruptible I/O)."
  [^CountDownLatch latch stop?]
  (reify Callable
    (call [_]
      (loop []
        (when-not @stop?
          (try
            (.await latch)
            (catch InterruptedException _))
          (when (pos? (.getCount latch))
            (recur))))
      42)))

(defn- fast-task
  "Create a task that completes immediately."
  []
  (reify Callable
    (call [_]
      :done)))

(deftest quiesce-executor-terminated-when-all-workers-finish
  (let [executor (Executors/newFixedThreadPool 2)
        _ (.submit executor (fast-task))
        status (quiesce/quiesce-executor! executor 5)]
    (is (= :terminated (:status status)))))

(deftest quiesce-executor-timeout-when-worker-stuck
  (testing "quiesce-executor! returns :termination-timeout when a worker is stuck"
    (let [latch (CountDownLatch. 1)
          stop? (atom false)
          executor (Executors/newFixedThreadPool 2)
          _ (.submit executor (blocking-task latch stop?))
          status (quiesce/quiesce-executor! executor 1)]
      (is (= :termination-timeout (:status status)))
      ;; Clean up: signal the blocked worker and allow it to exit
      (.countDown latch)
      (reset! stop? true))))

(deftest quiescence-failed-exception-is-distinguishable
  (testing "quiescence-failed-exception carries the :quiescence/failed? flag"
    (let [e (quiesce/quiescence-failed-exception "test" {:ctx :val})]
      (is (quiesce/quiescence-failed? e))
      (is (get (ex-data e) :quiescence/failed?))
      (is (= "test" (.getMessage e))))))

(deftest regular-ex-info-is-not-quiescence-failure
  (testing "ordinary ex-info is not a quiescence failure"
    (let [e (ex-info "regular" {:type :regular})]
      (is (not (quiesce/quiescence-failed? e))))))
