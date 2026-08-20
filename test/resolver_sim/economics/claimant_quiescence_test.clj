(ns resolver-sim.economics.claimant-quiescence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.util.thread-quiescence :as quiesce])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- resolve-ordered-detached-mapv []
  (requiring-resolve 'resolver-sim.economics.payoffs/ordered-detached-mapv))

(deftest claimant-executor-throws-on-quiescence-failure
  (testing "ordered-detached-mapv throws quiescence-failed-exception when a worker is stuck"
    (let [latch (CountDownLatch. 1)
          stop? (atom false)
          f (resolve-ordered-detached-mapv)
          task (fn [item]
                 (if (= item :fast-fail)
                   (throw (ex-info "fast failure" {:item item}))
                   (loop []
                     (if (and (not @stop?) (pos? (.getCount latch)))
                       (do
                         (try
                           (.await latch)
                           (catch InterruptedException _))
                         (recur))
                       :stuck))))
          ;; Save original so the redefined version can delegate to it
          original-quiesce quiesce/quiesce-executor!
          result
          (with-redefs [quiesce/quiesce-executor!
                        (fn
                          ([executor]
                           (original-quiesce executor 1))
                          ([executor _timeout-seconds]
                           (original-quiesce executor 1)))]
            (try
              (f 2 task [:fast-fail :stuck])
              nil
              (catch Throwable e e)))]
      (is (quiesce/quiescence-failed? result))
      (is (re-find #"did not terminate" (.getMessage result)))
      ;; Clean up: allow the stuck worker to exit
      (.countDown latch)
      (reset! stop? true))))

(deftest claimant-executor-conveys-dynamic-binding-frame
  (testing "a claimant hook bound by the submitting thread runs in a pool worker"
    (let [submitter-thread (Thread/currentThread)
          observed-thread (promise)
          items [{:id :a :weight 1 :cap nil}
                 {:id :b :weight 1 :cap nil}]]
      (binding [payoffs/*pro-rata-parallel-threshold* 1
                payoffs/*redistribution-claimant-hook*
                (fn [_] (deliver observed-thread (Thread/currentThread)))]
        (payoffs/allocate-pro-rata-with-redistribution
         {:amount 1 :items items :parallelism 2}))
      (is (not= submitter-thread (deref observed-thread 1000 nil))
          "control: the claimant hook ran on a different executor worker thread"))))

(deftest claimant-executor-receives-configured-quiescence-timeout
  (testing "the configured runtime timeout reaches the actual claimant pool shutdown"
    (let [observed-timeouts (atom [])
          original-quiesce quiesce/quiesce-executor!]
      (with-redefs [quiesce/quiesce-executor!
                    (fn
                      ([executor] (original-quiesce executor))
                      ([executor timeout-seconds]
                       (swap! observed-timeouts conj timeout-seconds)
                       (original-quiesce executor timeout-seconds)))]
        (binding [payoffs/*pro-rata-parallel-threshold* 1]
          (payoffs/allocate-pro-rata
           {:amount 3
            :items [{:id :a :weight 1} {:id :b :weight 1}]
            :parallelism 2
            :execution/quiescence-timeout-seconds 7})))
      (is (= [7 7] @observed-timeouts)
          "both executor-backed claimant phases use the configured timeout"))))

(deftest claimant-executor-succeeds-when-workers-finish
  (testing "ordered-detached-mapv returns results when all workers finish promptly"
    (let [f (resolve-ordered-detached-mapv)
          result (f 2 (fn [x] (inc x)) (range 10))]
      (is (= (vec (range 1 11)) result)))))

(deftest claimant-executor-serial-path-is-unaffected
  (testing "Serial path (parallelism=1) does not use executor, so quiescence is moot"
    (let [f (resolve-ordered-detached-mapv)
          result (f 1 (fn [x] (* x 2)) [1 2 3])]
      (is (= [2 4 6] result)))))
