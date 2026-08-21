(ns resolver-sim.execution.parallel-test
  "Tests for the benchmark-safe ordered parallel map primitive.

  These tests exercise actual executor lifecycle/concurrency, not merely
  serial-vs-parallel output equality."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.execution.parallel :as parallel]
            [resolver-sim.execution.budget :as budget]
            [resolver-sim.execution.context :as exec-context]
            [resolver-sim.util.attribution :as attr]
            [resolver-sim.util.evidence :as ev]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.evidence.capture :as evcapture]
            [resolver-sim.util.thread-quiescence :as quiesce])
  (:import [java.util.concurrent CountDownLatch
            TimeUnit]))

(defn- thread-name []
  (.getName (Thread/currentThread)))

;; ── Deterministic result ordering ───────────────────────────────────────────

(deftest ordered-bounded-mapv-preserves-input-order
  (testing "results are returned in input order regardless of completion order"
    (let [delays [50 5 10 30 1 40 15]
          results (parallel/ordered-bounded-mapv
                   4 (fn [d]
                       (Thread/sleep d)
                       d)
                   delays)]
      (is (= delays results)
          "input order is preserved even when completion order differs"))))

(deftest ordered-bounded-mapv-single-element
  (testing "single element preserves order and returns correct result"
    (let [result (parallel/ordered-bounded-mapv 2 (fn [x] (* x x)) [5])]
      (is (= [25] result)))))

(deftest ordered-bounded-mapv-empty-collection
  (testing "empty collection returns empty vector"
    (let [result (parallel/ordered-bounded-mapv 4 (fn [x] x) [])]
      (is (= [] result)))))

;; ── Serial fallback ──────────────────────────────────────────────────────────

(deftest ordered-bounded-mapv-serial-when-parallelism-one
  (testing "parallelism=1 runs serially on a single thread"
    (let [threads (atom #{})
          result (parallel/ordered-bounded-mapv
                  1 (fn [x]
                      (swap! threads conj (thread-name))
                      (inc x))
                  (range 5))]
      (is (= [1 2 3 4 5] result))
      (is (= 1 (count @threads))
          "all work ran on a single thread"))))

(deftest ordered-bounded-mapv-default-is-serial
  (testing "the default 2-arity (no parallelism specified) is serial"
    (let [threads (atom #{})
          result (parallel/ordered-bounded-mapv
                  (fn [x]
                    (swap! threads conj (thread-name))
                    x)
                  (range 10))]
      (is (= (vec (range 10)) result))
      (is (= 1 (count @threads))
          "default fallback is serial — no unowned pool"))))

;; ── Budget / serial fallback ─────────────────────────────────────────────────

(deftest ordered-bounded-mapv-budget-serial-fallback
  (testing "when budget has no spare permits, falls back to serial execution"
    (let [budget (java.util.concurrent.Semaphore. 1)
          threads (atom #{})]
      (binding [budget/*execution-budget* budget]
        (let [result (parallel/ordered-bounded-mapv
                      4 (fn [x]
                          (swap! threads conj (thread-name))
                          (inc x))
                      (range 5))]
          (is (= [1 2 3 4 5] result))
          (is (= 1 (count @threads))
              "no spare permits → serial execution, no new executor threads")
          (is (= 1 (.availablePermits budget))
              "borrowed permits were returned"))))))

(deftest ordered-bounded-mapv-budget-borrows-permits
  (testing "when budget has spare permits, borrows and runs parallel"
    (let [budget (java.util.concurrent.Semaphore. 5)
          threads (atom #{})]
      (binding [budget/*execution-budget* budget]
        (let [result (parallel/ordered-bounded-mapv
                      4 (fn [x]
                          (swap! threads conj (thread-name))
                          (Thread/sleep 10)
                          (inc x))
                      (range 4))]
          (is (= [1 2 3 4] result))
          (is (> (count @threads) 1)
              "spare permits → parallel execution on multiple threads")
          (is (<= (count @threads) 4)
              "bounded by requested parallelism")
          (is (= 5 (.availablePermits budget))
              "all borrowed permits returned after completion"))))))

;; ── Binding conveyance characterization ──────────────────────────────────────
;;
;; Clojure 1.12 `pmap` delegates to `future-call` which uses `bound-fn*`
;; internally, so dynamic Var bindings ARE conveyed to pmap workers.
;; `contextual-pmap` explicitly re-binds attribution/evidence capture on top
;; of that. The tests below establish what is genuinely visible.

(deftest pmap-conveys-dynamic-bindings
  (testing "ordinary pmap (and thus contextual-pmap) conveys execution-context/*context*"
    (let [observed (promise)]
      (binding [exec-context/*context* {:test/exec-context true}]
        (pmap (fn [_]
                (deliver observed exec-context/*context*))
              [:a :b]))
      (is (= {:test/exec-context true} (deref observed 5000 nil))
          "pmap workers see the bound *context* — bindings are NOT lost"))))

(deftest pmap-conveys-budget-binding
  (testing "ordinary pmap conveys budget/*execution-budget*"
    (let [observed (promise)
          budget (java.util.concurrent.Semaphore. 3)]
      (binding [budget/*execution-budget* budget]
        (pmap (fn [_]
                (deliver observed budget/*execution-budget*))
              [:a :b]))
      (is (identical? budget (deref observed 5000 nil))
          "pmap workers see the bound *execution-budget*"))))

(deftest ordered-bounded-mapv-conveys-all-dynamic-bindings
  (testing "the owned primitive conveys all submitting-thread bindings"
    (let [submitter-thread (Thread/currentThread)
          ctx-observed (promise)
          budget-observed (promise)
          thread-observed (promise)
          budget (java.util.concurrent.Semaphore. 4)]
      (binding [exec-context/*context* {:test/exec-context true}
                budget/*execution-budget* budget
                attr/*attribution* {:test/attribution true}]
        (parallel/ordered-bounded-mapv
         2 (fn [_]
             (deliver ctx-observed exec-context/*context*)
             (deliver budget-observed budget/*execution-budget*)
             (deliver thread-observed (Thread/currentThread))
             42)
         [:a :b]))
      (is (= {:test/exec-context true} (deref ctx-observed 5000 nil))
          "execution-context/*context* conveyed to worker")
      (is (identical? budget (deref budget-observed 5000 nil))
          "budget/*execution-budget* conveyed to worker")
      (let [worker (deref thread-observed 5000 nil)]
        (is (some? worker) "worker thread was observed — cross-thread execution")
        (is (not= submitter-thread worker)
            "work ran on a pool thread, not the submitter")))))

(deftest ordered-bounded-mapv-conveys-evidence-context
  (testing "attribution and evidence-capture bindings are visible in workers"
    (let [attr-observed (promise)
          capture-observed (promise)]
      (binding [attr/*attribution* {:yield-accrue {:module-id :test}}
                evcapture/*capture-event-evidence!* (fn [& _] :captured)]
        (parallel/ordered-bounded-mapv
         2 (fn [_]
             (deliver attr-observed attr/*attribution*)
             (deliver capture-observed evcapture/*capture-event-evidence!*)
             nil)
         [:a :b]))
      (is (= {:yield-accrue {:module-id :test}} (deref attr-observed 5000 nil))
          "attribution binding conveyed")
      (is (ifn? (deref capture-observed 5000 nil))
          "evidence-capture binding conveyed"))))

;; ── Adversarial quiescence ──────────────────────────────────────────────────
;;
;; These tests close the specific concurrency/lifecycle defect where benchmark
;; execution could spawn nested parallel work on Clojure's shared executor,
;; outside the benchmark's owned executor/quiescence lifecycle.
;;
;; With the OLD contextual-pmap (plain pmap), when a sibling task failed while
;; another was blocked, the blocking task continued running on the JVM-shared
;; fork-join pool and the benchmark could not cancel or prove quiescence of it.
;;
;; With ordered-bounded-mapv, the executor is owned: created, submitted, awaited,
;; and quiesced within the function. On ANY exit path (success or failure) the
;; finally block calls shutdownNow + awaitTermination.

(deftest ordered-bounded-mapv-interrupts-blocking-worker-on-sibling-failure
  (testing "a sibling failure interrupts blocking workers so the executor can quiesce before propagating"
    (let [blocking-started (CountDownLatch. 1)
          blocking-released (CountDownLatch. 1)
          was-interrupted? (atom false)
          f (fn [x]
              (case x
                :block
                (do
                  (.countDown blocking-started)
                  (try
                    (.await blocking-released 5 TimeUnit/SECONDS)
                    (catch InterruptedException _
                      (reset! was-interrupted? true))))
                :fail
                (do
                  (.await blocking-started 5 TimeUnit/SECONDS)
                  (throw (ex-info "sibling failure" {:item x})))))
          error (try
                  ;; Failing task is submitted BEFORE blocking task so that
                  ;; .get on future[0] throws before .get on future[1] blocks.
                  ;; The blocking task starts on a second thread and waits
                  ;; for its sibling to begin (via blocking-started latch) so
                  ;; we know it is genuinely running when the failure fires.
                  (parallel/ordered-bounded-mapv 2 f [:fail :block])
                  nil
                  (catch Exception e e))]
      (.countDown blocking-released)
      (is (some? error) "an exception propagated from the sibling failure")
      (is (re-find #"sibling failure" (.getMessage error))
          "original error message preserved")
      (is @was-interrupted?
          "the blocking worker was interrupted by shutdownNow during quiescence
           — the benchmark does not return while its owned nested worker remains live"))))

(deftest ordered-bounded-mapv-propagates-first-exception
  (testing "a failing task propagates its exception"
    (let [error (try
                  (parallel/ordered-bounded-mapv 4 (fn [x]
                                                     (when (= x 3)
                                                       (throw (ex-info "boom" {:x x})))
                                                     x)
                                                 (range 10))
                  nil
                  (catch Exception e e))]
      (is (some? error) "exception propagated")
      (is (re-find #"boom" (.getMessage error)) "original error message preserved"))))

(deftest ordered-bounded-mapv-fails-closed-on-quiescence-failure
  (testing "when the executor cannot quiesce, fails closed with quiescence exception"
    (let [original-quiesce quiesce/quiesce-executor!]
      (with-redefs [quiesce/quiesce-executor!
                    (fn [executor timeout-seconds]
                      (original-quiesce executor timeout-seconds)
                      {:status :termination-timeout
                       :remaining-tasks []})]
        (let [error (try
                      (parallel/ordered-bounded-mapv 2 (fn [x] x) [:a :b :c])
                      nil
                      (catch Exception e e))]
          (is (some? error) "a quiescence failure was thrown")
          (is (quiesce/quiescence-failed? error)
              "the error is a recognized quiescence-failed exception"))))))

;; ── Successful quiescence: no ghost threads ──────────────────────────────────

(deftest ordered-bounded-mapv-leaves-no-ghost-threads-on-success
  (testing "after successful completion, all executor worker threads have terminated"
    (let [worker-threads (atom [])]
      (let [results (parallel/ordered-bounded-mapv
                     4 (fn [x]
                         (swap! worker-threads conj (Thread/currentThread))
                         (Thread/sleep 5)
                         (* x x))
                     (range 8))]
        (is (= (vec (map #(* % %) (range 8))) results)
            "results are correct and ordered")
        (is (seq @worker-threads) "worker threads were actually used")
        (is (every? (fn [^Thread t] (not (.isAlive t))) @worker-threads)
            "no worker thread remains alive after the executor is shut down")))))

;; ── contextual-pmap binding characterization ───────────────────────────────────
;;
;; These tests establish which dynamic bindings are visible inside a
;; contextual-pmap worker and distinguish "binding is visible" from
;; "worker execution is actually governed by that binding."
;;
;; Clojure's pmap uses bound-fn* internally, so ALL dynamic vars bound on the
;; submitting thread are conveyed to worker threads. contextual-pmap additionally
;; explicitly re-binds attribution and evidence-capture on top.
;;
;; CRITICAL: merely seeing *execution-budget* or *context* inside a pmap worker
;; does NOT mean the pmap executor is bounded by it. pmap uses the JVM-shared
;; fork-join pool with its own parallelism, ignoring all execution budget permits.
;; The owned primitive (ordered-bounded-mapv) is the only safe choice for
;; benchmark-reachable paths.

(deftest contextual-pmap-characterization-all-bindings-visible
  (testing "contextual-pmap conveys all submitting-thread dynamic bindings via pmap's bound-fn"
    (let [ctx-observed (promise)
          budget-observed (promise)
          attr-observed (promise)
          capture-observed (promise)
          parallelism-observed (promise)
          threshold-observed (promise)
          budget (java.util.concurrent.Semaphore. 3)]
      (binding [exec-context/*context* {:test/exec-context true}
                budget/*execution-budget* budget
                attr/*attribution* {:test/attribution true}
                evcapture/*capture-event-evidence!* (fn [& _] :captured)
                payoffs/*pro-rata-parallelism* 4
                payoffs/*pro-rata-parallel-threshold* 2]
        (doall (ev/contextual-pmap
                (fn [_]
                  (deliver ctx-observed exec-context/*context*)
                  (deliver budget-observed budget/*execution-budget*)
                  (deliver attr-observed attr/*attribution*)
                  (deliver capture-observed evcapture/*capture-event-evidence!*)
                  (deliver parallelism-observed payoffs/*pro-rata-parallelism*)
                  (deliver threshold-observed payoffs/*pro-rata-parallel-threshold*)
                  :ok)
                [:a :b])))
      (is (= {:test/exec-context true} (deref ctx-observed 5000 nil))
          "execution-context/*context* is visible (conveyed by pmap bound-fn)")
      (is (identical? budget (deref budget-observed 5000 nil))
          "budget/*execution-budget* is visible (conveyed by pmap bound-fn)")
      (is (= {:test/attribution true} (deref attr-observed 5000 nil))
          "attr/*attribution* is visible (explicitly rebound by contextual-pmap)")
      (is (ifn? (deref capture-observed 5000 nil))
          "evcapture/*capture-event-evidence!* is visible (explicitly rebound by contextual-pmap)")
      (is (= 4 (deref parallelism-observed 5000 nil))
          "payoffs/*pro-rata-parallelism* is visible (conveyed by pmap bound-fn)")
      (is (= 2 (deref threshold-observed 5000 nil))
          "payoffs/*pro-rata-parallel-threshold* is visible (conveyed by pmap bound-fn"))))

(deftest contextual-pmap-does-not-govern-executor-by-budget
  (testing "contextual-pmap workers see the budget binding but are NOT bounded by it"
    (let [spawned-threads (atom #{})
          budget (java.util.concurrent.Semaphore. 1)]
      (binding [budget/*execution-budget* budget]
        (doall (ev/contextual-pmap
                (fn [_]
                  (swap! spawned-threads conj (Thread/currentThread))
                  (Thread/sleep 10)
                  42)
                (range 8))))
      ;; pmap uses the JVM-shared fork-join pool. With 8 items and a budget
      ;; of 1 permit, pmap still spawns multiple threads — the budget is
      ;; merely *visible*, not *enforced*.
      (is (> (count @spawned-threads) 1)
          "contextual-pmap spawns multiple threads despite budget=1 — budget is
           visible but does NOT govern pmap's parallelism"))))
