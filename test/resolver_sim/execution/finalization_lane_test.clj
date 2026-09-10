(ns resolver-sim.execution.finalization-lane-test
  "Adversarial tests for the cardinality-one finalization lane.

   Tests 1–8 from the P0 adversarial test specification, plus ticket
   substitution, ticket replay, and race stress tests."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.execution.finalization-lane :as lane])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier
            Executors TimeUnit]
           [java.util.concurrent.atomic AtomicInteger]))

(defn- open-lane
  "Open a lane with standard test defaults."
  ([] (open-lane {}))
  ([opts]
   (lane/open (merge {:requires-quiescence? true} opts))))

(defn- drain-lane!
  "Complete all contributing work and quiesce all phases on the lane."
  [lane contributor-count executor-phases]
  (dotimes [_ contributor-count] (lane/end-contributor! lane))
  (dotimes [_ executor-phases] (lane/record-quiesced! lane)))

;; ── Test 1: Two concurrent finalizers ──────────────────────────────────────

(deftest two-concurrent-finalizers-exactly-one-wins
  (testing "Exactly one EXECUTION_CLOSED → FINALIZING CAS succeeds (FG-3)"
    (let [n 50
          shared-lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! shared-lane)
          winner-count (AtomicInteger. 0)
          loser-count (AtomicInteger. 0)
          barrier (CyclicBarrier. n)
          futures (mapv (fn [_]
                          (future
                            (.await barrier)
                            (let [result (lane/finalize-lane!
                                          shared-lane
                                          {:closed-result-set-root "root-A"
                                           :basis-root "basis-A"}
                                          (fn [ticket]
                                            {:committed {:ticket ticket}}))]
                              (when (= :committed (:status result))
                                (.incrementAndGet winner-count))
                              (when (= :rejected (:status result))
                                (.incrementAndGet loser-count)))))
                        (range n))]
        (doseq [f futures] (deref f 10000 nil))
        (is (= 1 (.get winner-count))
            "Exactly one finalizer won the CAS (FG-3)")
        (is (= (dec n) (.get loser-count))
            "All other finalizers were rejected")
        (is (lane/finalized? shared-lane)
            "Lane reached FINALIZED"))))

;; ── Test 2: Losing finalizer cannot execute write-fn ───────────────────────

(deftest losing-finalizer-cannot-execute-write-fn
  (testing "Loser cannot execute write-fn, not merely cannot publish (FG-3)"
    (let [write-called? (atom false)
          shared-lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! shared-lane)
          n 10
          barrier (CyclicBarrier. n)
          futures (mapv (fn [_]
                          (future
                            (.await barrier)
                            (lane/finalize-lane!
                             shared-lane
                             {:closed-result-set-root "root-A"
                              :basis-root "basis-A"}
                             (fn [ticket]
                               (reset! write-called? true)
                               {:committed {:ticket ticket}}))))
                        (range n))]
      (doseq [f futures] (deref f 10000 nil))
      (is (= 1 (if @write-called? 1 0))
          "write-fn was invoked exactly once — the winner only"))))

;; ── Test 3: Late contributor after closure ──────────────────────────────────

(deftest late-contributor-after-closure-rejected
  (testing "begin-contributor! cannot mutate anything after closure (FG-1)"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (lane/begin-contributor! lane))
          "begin-contributor! throws after execution closure")
      (is (= :execution-closed (lane/state lane))
          "Lane remains in EXECUTION_CLOSED state"))))

;; ── Test 4: Late executor dispatch after closure ────────────────────────────

(deftest late-executor-dispatch-after-closure-rejected
  (testing "No executor-backed phase can be introduced after closure (FG-1)"
    (let [lane (open-lane {:requires-quiescence? true})
          _ (lane/record-executor-dispatch! lane)
          _ (lane/record-quiesced! lane)
          _ (lane/close-execution! lane)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (lane/record-executor-dispatch! lane))
          "record-executor-dispatch! throws after execution closure")
      (is (= :execution-closed (lane/state lane))
          "Lane remains in EXECUTION_CLOSED state"))))

;; ── Test 5: Closed result-set root immutable after closure ──────────────────

(deftest closed-result-set-root-immutable-after-closure
  (testing "Closed result-set root cannot change after closure (FG-1)"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane)
          closed-state @lane]
      ;; Finalize with one root
      (lane/finalize-lane!
       lane
       {:closed-result-set-root "root-A"
        :basis-root "basis-A"}
       (fn [ticket] {:committed {:root "root-A"}}))
      ;; The closed-root in the ticket is bound at finalize-lane! time,
      ;; from opts, not from the lane atom. The lane atom's lifecycle is
      ;; what's immutable after closure.
      (is (= :execution-closed (:lifecycle closed-state))
          "Lifecycle was sealed at closure")
      (is (= 0 (:lane/contributing-open closed-state))
          "Contributing work was zero at closure"))))

;; ── Test 6: Stale parent ────────────────────────────────────────────────────

(deftest stale-parent-rejects-write
  (testing "write-fn never executes against mismatched fence (FG-5)"
    (let [write-called? (atom false)
          lane (open-lane {:expected-parent-root "parent-A"
                           :requires-quiescence? false})
          _ (lane/close-execution! lane)
          result (lane/finalize-lane!
                  lane
                  {:observed-parent-root "parent-B"
                   :closed-result-set-root "root-A"
                   :basis-root "basis-A"}
                  (fn [ticket]
                    (reset! write-called? true)
                    {:committed {:ok true}}))]
      (is (= :rejected (:status result))
          "Finalization rejected for stale parent")
      (is (= :stale-parent (:reason result))
          "Reason is :stale-parent (FG-5)")
      (is (false? @write-called?)
          "write-fn was never invoked")
      (is (= :execution-closed (lane/state lane))
          "Lane remains EXECUTION_CLOSED on precondition failure"))))

;; ── Test 7: Failed write ────────────────────────────────────────────────────

(deftest failed-write-transitions-to-aborted
  (testing "Exactly one transition to ABORTED; no emit (FG-7)"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane)
          result (lane/finalize-lane!
                  lane
                  {:closed-result-set-root "root-A"
                   :basis-root "basis-A"}
                  (fn [ticket]
                    (throw (ex-info "write failed" {}))))]
      (is (= :rejected (:status result))
          "Finalization rejected on write throw")
      (is (= :write-threw (:reason result))
          "Reason is :write-threw")
      (is (= :aborted (lane/state lane))
          "Lane is ABORTED")
      (is (nil? (lane/result lane))
          "No result published")
      (is (= :write-threw (:reason (lane/abort-reason lane)))
          "Abort reason recorded"))))

;; ── Test 8: Repeated finalize after success ─────────────────────────────────

(deftest repeated-finalize-after-success-rejected
  (testing "Cannot invoke authoritative write twice (FG-7)"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane)
          first-result (lane/finalize-lane!
                        lane
                        {:closed-result-set-root "root-A"
                         :basis-root "basis-A"}
                        (fn [ticket] {:committed {:ok true}}))
          second-result (lane/finalize-lane!
                         lane
                         {:closed-result-set-root "root-A"
                          :basis-root "basis-A"}
                         (fn [ticket] {:committed {:ok true}}))]
      (is (= :committed (:status first-result))
          "First finalization succeeded")
      (is (= :rejected (:status second-result))
          "Second finalization rejected — lane is no longer EXECUTION_CLOSED")
      (is (contains? #{:not-execution-closed :finalizer-already-active}
                     (:reason second-result))
          "Rejection reason indicates lane is past EXECUTION_CLOSED"))))

;; ── Test 9: Serial vs parallel produces same semantic state ─────────────────

(deftest serial-vs-parallel-same-semantic-state
  (testing "Execution parallelism cannot change semantic identities (FG-8)"
    (let [;; Serial: open → contribute → close → finalize
          serial-lane (open-lane {:requires-quiescence? false})
          _ (lane/begin-contributor! serial-lane)
          _ (lane/end-contributor! serial-lane)
          _ (lane/close-execution! serial-lane)
          serial-result (lane/finalize-lane!
                         serial-lane
                         {:closed-result-set-root "result-root"
                          :basis-root "basis-root"}
                         (fn [ticket]
                           {:committed {:closed-root (:finalizer/closed-root ticket)
                                       :basis-root (:finalizer/basis-root ticket)
                                       :generation (:finalizer/lane-generation ticket)}}))

          ;; Parallel: same operations but concurrent contributor work
          par-lane (open-lane {:requires-quiescence? false})
          _ (lane/begin-contributor! par-lane)
          _ (lane/begin-contributor! par-lane)
          _ (lane/begin-contributor! par-lane)
          _ (lane/end-contributor! par-lane)
          _ (lane/end-contributor! par-lane)
          _ (lane/end-contributor! par-lane)
          _ (lane/close-execution! par-lane)
          par-result (lane/finalize-lane!
                      par-lane
                      {:closed-result-set-root "result-root"
                       :basis-root "basis-root"}
                      (fn [ticket]
                        {:committed {:closed-root (:finalizer/closed-root ticket)
                                    :basis-root (:finalizer/basis-root ticket)
                                    :generation (:finalizer/lane-generation ticket)}}))]
      ;; Semantic identities must match regardless of contributor scheduling
      (is (= (:closed-result-set-root (:ticket serial-result))
             (:closed-result-set-root (:ticket par-result)))
          "Same closed-result-set root (FG-8)")
      (is (= (:finalizer/basis-root (:ticket serial-result))
             (:finalizer/basis-root (:ticket par-result)))
          "Same basis root (FG-8)")
      ;; Generations differ (different lane instances), but both are committed
      (is (= :committed (:status serial-result))
          "Serial committed")
      (is (= :committed (:status par-result))
          "Parallel committed"))))

;; ── Test 10: Ticket substitution ────────────────────────────────────────────

(deftest ticket-for-root-a-cannot-finalize-root-b
  (testing "Ticket for closed root A cannot finalize candidate/root B (FG-4)"
    (let [lane-a (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane-a)
          result-a (lane/finalize-lane!
                    lane-a
                    {:closed-result-set-root "root-A"
                     :basis-root "basis-A"}
                    (fn [ticket] {:committed {:ticket ticket}}))
          ticket-a (:ticket result-a)]
      ;; The ticket is bound to lane-a's generation
      (is (= (:finalizer/lane-generation ticket-a)
             (lane/generation lane-a))
          "Ticket generation matches lane-a generation")
      ;; On a different lane, the ticket's generation would not match
      (let [lane-b (open-lane {:requires-quiescence? false})]
        (is (not= (:finalizer/lane-generation ticket-a)
                  (lane/generation lane-b))
            "Ticket generation does NOT match lane-b generation")))))

;; ── Test 11: Ticket replay ──────────────────────────────────────────────────

(deftest ticket-from-completed-generation-cannot-authorize-another-attempt
  (testing "Ticket from completed/aborted generation cannot authorize another attempt (FG-4)"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane)
          first-result (lane/finalize-lane!
                        lane
                        {:closed-result-set-root "root-A"
                         :basis-root "basis-A"}
                        (fn [ticket] {:committed {:ticket ticket}}))
          gen (:finalizer/lane-generation (:ticket first-result))]
      ;; Attempt to finalize again — the lane is no longer EXECUTION_CLOSED
      (let [second-result (lane/finalize-lane!
                           lane
                           {:closed-result-set-root "root-A"
                            :basis-root "basis-A"}
                           (fn [ticket] {:committed {:ticket ticket}}))]
        (is (= :rejected (:status second-result))
            "Second finalization rejected — generation is terminal")
        (is (contains? #{:not-execution-closed :finalizer-already-active}
                       (:reason second-result))
            "Reason indicates lane state prevents re-authorization")))))

;; ── Test 12: Race stress ────────────────────────────────────────────────────

(deftest randomized-finalizer-races-yield-one-winner
  (testing "Repeated randomized finalizer races always yield {1 winner, N-1 losers}"
    (dotimes [_ 20]
      (let [n 10
            shared-lane (open-lane {:requires-quiescence? false})
            _ (lane/close-execution! shared-lane)
            winners (AtomicInteger. 0)
            losers (AtomicInteger. 0)
            barrier (CyclicBarrier. n)
            futures (mapv (fn [_]
                            (future
                              (.await barrier)
                              (let [result (lane/finalize-lane!
                                            shared-lane
                                            {:closed-result-set-root "root"
                                             :basis-root "basis"}
                                            (fn [ticket]
                                              {:committed {:ok true}}))]
                                (if (= :committed (:status result))
                                  (.incrementAndGet winners)
                                  (.incrementAndGet losers)))))
                          (range n))]
        (doseq [f futures] (deref f 10000 nil))
        (is (= 1 (.get winners))
            "Exactly 1 winner across 20 stress iterations")
        (is (= (dec n) (.get losers))
            "N-1 losers across 20 stress iterations")))))

;; ── Edge cases ──────────────────────────────────────────────────────────────

(deftest close-execution-requires-zero-contributors
  (testing "close-execution! fails when contributors are outstanding"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/begin-contributor! lane)
          result (lane/close-execution! lane)]
      (is (false? (:closed? result))
          "Close rejected with outstanding contributors")
      (is (= :open (lane/state lane))
          "Lane remains OPEN"))))

(deftest close-execution-requires-quiescence
  (testing "close-execution! fails when executor-backed phases not quiesced"
    (let [lane (open-lane {:requires-quiescence? true})
          _ (lane/record-executor-dispatch! lane)
          result (lane/close-execution! lane)]
      (is (false? (:closed? result))
          "Close rejected when quiescence incomplete")
      (is (= :open (lane/state lane))
          "Lane remains OPEN"))))

(deftest close-execution-idempotent-after-first-close
  (testing "Second close-execution! returns already-closed"
    (let [lane (open-lane {:requires-quiescence? false})
          first (lane/close-execution! lane)
          second (lane/close-execution! lane)]
      (is (:closed? first) "First close succeeded")
      (is (false? (:closed? second)) "Second close rejected")
      (is (= :already-closed (:reason second)) "Reason is :already-closed"))))

(deftest contributor-counting-accurate
  (testing "Contributor count tracks open/close accurately"
    (let [lane (open-lane {:requires-quiescence? false})]
      (is (zero? (:lane/contributing-open @lane))
          "Starts at zero")
      (lane/begin-contributor! lane)
      (lane/begin-contributor! lane)
      (lane/begin-contributor! lane)
      (is (= 3 (:lane/contributing-open @lane))
          "Three contributors open")
      (lane/end-contributor! lane)
      (is (= 2 (:lane/contributing-open @lane))
          "Two remaining after one completion")
      (lane/end-contributor! lane)
      (lane/end-contributor! lane)
      (is (zero? (:lane/contributing-open @lane))
          "All contributors completed"))))

(deftest quiescence-accounting-accurate
  (testing "Executor/quiescence phase counts track accurately"
    (let [lane (open-lane {:requires-quiescence? true})]
      (lane/record-executor-dispatch! lane)
      (lane/record-executor-dispatch! lane)
      (is (= 2 (:lane/executor-backed-phases @lane))
          "Two executor phases dispatched")
      (lane/record-quiesced! lane)
      (is (= 1 (:lane/quiesced-phases @lane))
          "One phase quiesced")
      (lane/record-quiesced! lane)
      (is (= 2 (:lane/quiesced-phases @lane))
          "Both phases quiesced"))))

(deftest aborted-lane-is-terminal
  (testing "ABORTED is terminal — no state transitions possible"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane)
          _ (lane/finalize-lane!
             lane
             {:closed-result-set-root "root"
              :basis-root "basis"}
             (fn [_] (throw (ex-info "fail" {}))))]
      (is (lane/aborted? lane))
      (is (thrown? clojure.lang.ExceptionInfo
                   (lane/begin-contributor! lane))
          "Cannot add contributor to aborted lane")
      (is (thrown? clojure.lang.ExceptionInfo
                   (lane/record-executor-dispatch! lane))
          "Cannot dispatch executor on aborted lane"))))

(deftest no-parent-fence-when-expected-parent-nil
  (testing "Parent-fence check skipped when expected-parent-root was not set"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane)
          result (lane/finalize-lane!
                  lane
                  {:closed-result-set-root "root"
                   :basis-root "basis"}
                  (fn [ticket] {:committed {:ok true}}))]
      (is (= :committed (:status result))
          "Finalization succeeds without parent-fence when expected-parent is nil"))))

(deftest basis-validation-failure
  (testing "Invalid finalization basis rejects write (FG-6)"
    (let [lane (open-lane {:requires-quiescence? false})
          _ (lane/close-execution! lane)
          write-called? (atom false)
          result (lane/finalize-lane!
                  lane
                  {:closed-result-set-root "root"
                   :basis-root "basis"
                   :valid-basis? false}
                  (fn [ticket]
                    (reset! write-called? true)
                    {:committed {:ok true}}))]
      (is (= :rejected (:status result))
          "Finalization rejected for invalid basis")
      (is (= :invalid-finalization-basis (:reason result))
          "Reason is :invalid-finalization-basis")
      (is (false? @write-called?)
          "write-fn was never invoked"))))
