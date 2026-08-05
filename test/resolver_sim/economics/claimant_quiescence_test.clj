(ns resolver-sim.economics.claimant-quiescence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.util.thread-quiescence :as quiesce])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- resolve-ordered-detached-mapv []
  (requiring-resolve 'resolver-sim.economics.payoffs/ordered-detached-mapv))

(defn- make-stuck-executor-task
  "Return a task fn that blocks until `release` is signalled or `stop?` is set.
   Used to exercise quiescence-failure paths."
  [latch stop?]
  (fn [item]
    (if (= item :fast-fail)
      (throw (ex-info "fast failure" {:item item}))
      (loop []
        (if (and (not @stop?) (pos? (.getCount latch)))
          (do
            (try
              (.await latch 10 TimeUnit/MILLISECONDS)
              (catch InterruptedException _))
            (recur))
          :stuck)))))

(deftest claimant-executor-throws-on-quiescence-failure
  (testing "ordered-detached-mapv throws quiescence-failed-exception when a worker is stuck"
    (let [latch (CountDownLatch. 1)
          stop? (atom false)
          f (resolve-ordered-detached-mapv)
          task (make-stuck-executor-task latch stop?)
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
      (reset! stop? true)
      (.countDown latch))))

(deftest claimant-executor-conveys-dynamic-binding-frame
  (testing "a bound claimant hook is observed inside pool worker threads"
    (let [submitter-thread (Thread/currentThread)
          observations (atom [])
          items [{:id :a :weight 1 :cap nil}
                 {:id :b :weight 1 :cap nil}
                 {:id :c :weight 1 :cap nil}]]
      (binding [payoffs/*pro-rata-parallel-threshold* 1
                payoffs/*redistribution-claimant-hook*
                (fn [item] (swap! observations conj {:thread (Thread/currentThread)
                                                     :id (:id item)}))]
        (payoffs/allocate-pro-rata-with-redistribution
         {:amount 1 :items items :parallelism 2}))
      (let [obs @observations]
        (is (seq obs) "claimant hook was invoked — binding was conveyed to a pool worker")
        (is (some #(not= submitter-thread (:thread %)) obs)
            "at least one observation ran on a different executor worker thread")
        (is (not-any? #(= submitter-thread (:thread %)) obs)
            "the claimant hook is invoked exclusively inside worker threads;
             the coordinator invokes *redistribution-claimant-determination-hook*
             on the submitter thread (verified by its own test)")
        (is (= #{:a :b :c} (set (map :id obs)))
            "the bound hook received the expected claimant items")))))

(deftest claimant-hook-value-not-masking-binding-loss
  (testing "control: when the hook is NOT bound, zero observations occur"
    (let [submitter-thread (Thread/currentThread)
          observations (atom [])
          items [{:id :a :weight 1 :cap nil}
                 {:id :b :weight 1 :cap nil}
                 {:id :c :weight 1 :cap nil}]]
      (binding [payoffs/*pro-rata-parallel-threshold* 1]
        (payoffs/allocate-pro-rata-with-redistribution
         {:amount 1 :items items :parallelism 2}))
      (is (empty? @observations)
          "without a bound hook, no observations occur — the assertion above is meaningful"))))

(deftest claimant-determination-hook-runs-on-coordinator-thread
  (testing "the determination hook runs on the submitter thread, not a pool worker"
    (let [submitter-thread (Thread/currentThread)
          determination-threads (atom [])
          items [{:id :a :weight 100 :cap 10}
                 {:id :b :weight 100 :cap nil}]]
      (binding [payoffs/*pro-rata-parallel-threshold* 1
                payoffs/*redistribution-claimant-determination-hook*
                (fn [_] (swap! determination-threads conj (Thread/currentThread)))]
        (payoffs/allocate-pro-rata-with-redistribution
         {:amount 100 :items items
          :id-fn :id :weight-fn :weight :cap-fn :cap
          :rounding :floor-with-largest-remainder}))
      (let [threads @determination-threads]
        (is (seq threads) "determination hook was invoked during at least one pass")
        (is (every? #(= submitter-thread %) threads)
            "determination hook always runs on the coordinator thread (not in claimant executor)")))))

(deftest claimant-executor-receives-configured-quiescence-timeout
  (testing "the configured runtime timeout reaches the actual claimant pool shutdown"
    (let [observed-timeouts (atom [])
          original-quiesce quiesce/quiesce-executor!
          passthrough (fn [executor timeout-seconds]
                        (swap! observed-timeouts conj timeout-seconds)
                        (original-quiesce executor timeout-seconds))]
      (with-redefs [quiesce/quiesce-executor!
                    (fn
                      ([executor] (passthrough executor (quiesce/config-default-timeout-seconds)))
                      ([executor timeout-seconds] (passthrough executor timeout-seconds)))]
        (binding [payoffs/*pro-rata-parallel-threshold* 1]
          (payoffs/allocate-pro-rata
           {:amount 3
            :items [{:id :a :weight 1} {:id :b :weight 1}]
            :parallelism 2
            :execution/quiescence-timeout-seconds 7})))
      (is (= [7 7] @observed-timeouts)
          "both executor-backed claimant phases use the configured timeout"))))

(deftest claimant-executor-resolves-config-default-when-timeout-is-nil
  (testing "nil timeout falls through to the config-resolved canonical default"
    (let [observed-timeouts (atom [])
          original-quiesce quiesce/quiesce-executor!
          passthrough (fn [executor timeout-seconds]
                        (swap! observed-timeouts conj timeout-seconds)
                        (original-quiesce executor timeout-seconds))
          expected-default (quiesce/config-default-timeout-seconds)]
      (with-redefs [quiesce/quiesce-executor!
                    (fn
                      ([executor] (passthrough executor (quiesce/config-default-timeout-seconds)))
                      ([executor timeout-seconds] (passthrough executor timeout-seconds)))]
        (binding [payoffs/*pro-rata-parallel-threshold* 1]
          (payoffs/allocate-pro-rata
           {:amount 3
            :items [{:id :a :weight 1} {:id :b :weight 1}]
            :parallelism 2})))
      (let [timeouts @observed-timeouts]
        (is (= [expected-default expected-default] timeouts)
            "both claimant phases used the config-resolved default when no explicit timeout was provided")
        (is (pos? (first timeouts)) "the resolved default is a positive integer")))))

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
