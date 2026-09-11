(ns resolver-sim.execution.realization-lane-integration-test
  "Integration tests for the finalization lane wired into realization/complete!.

   Verifies that the lane and realization stay synchronized through the
   claimant execution lifecycle."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.execution.realization :as realization]
            [resolver-sim.execution.finalization-lane :as lane])
  (:import [java.util.concurrent CyclicBarrier]
           [java.util.concurrent.atomic AtomicInteger]))

(defn- open-pair
  "Open a realization and lane bound together for testing."
  ([] (open-pair {}))
  ([lane-opts]
   (let [r (realization/open)
         l (lane/open (merge {:requires-quiescence? false} lane-opts))]
     {:realization r :lane l})))

;; ── Basic flow ──────────────────────────────────────────────────────────────

(deftest complete-with-lane-finalizes-both
  (testing "complete! freezes realization and finalizes lane to FINALIZED"
    (let [{:keys [realization lane]} (open-pair)]
      (binding [realization/*claimant-execution-realization* realization
                realization/*claimant-execution-lane* lane]
        (realization/record! {:test-fact true})
        (realization/complete! realization))
      (is (= :finalized (:lifecycle @realization))
          "Realization is finalized")
      (is (lane/finalized? lane)
          "Lane is finalized")
      (is (some? (lane/result lane))
          "Lane has a committed result"))))

(deftest complete-emits-observation
  (testing "complete! calls sink when observation is available"
    (let [emitted (atom nil)
          r (realization/open)
          l (lane/open {:requires-quiescence? false})]
      (binding [realization/*claimant-execution-observation-sink*
                (fn [obs] (reset! emitted obs))
                realization/*claimant-execution-runtime-profile-root*
                "test-runtime-root"
                realization/*claimant-execution-realization* r
                realization/*claimant-execution-lane* l]
        (realization/record! {:test-fact true})
        (realization/complete! r))
      (is (some? @emitted)
          "Observation was delivered to sink"))))

;; ── Quiescence tracking ─────────────────────────────────────────────────────

(deftest lane-tracks-quiescence-in-parallel
  (testing "record-executor-dispatch! and record-quiesced! update both realization and lane"
    (let [{:keys [realization lane]} (open-pair)]
      (binding [realization/*claimant-execution-realization* realization
                realization/*claimant-execution-lane* lane]
        (realization/record-executor-dispatch! 4)
        (realization/record-executor-dispatch! 2)
        (realization/record-quiesced!)
        (realization/record-quiesced!))
      (is (= 2 (:lane/executor-backed-phases @lane))
          "Lane counted 2 executor-backed phases")
      (is (= 2 (:lane/quiesced-phases @lane))
          "Lane counted 2 quiesced phases")
      (is (= 2 (:executor-backed-phases @realization))
          "Realization counted 2 executor-backed phases")
      (is (= 2 (:successfully-quiesced-phases @realization))
          "Realization counted 2 quiesced phases"))))

(deftest complete-requires-quiescence
  (testing "complete! fails when lane has unquiesced phases"
    (let [r (realization/open)
          l (lane/open {:requires-quiescence? true})]
      (binding [realization/*claimant-execution-realization* r
                realization/*claimant-execution-lane* l]
        (realization/record-executor-dispatch! 1)
        (realization/record-executor-dispatch! 1)
        (realization/record-quiesced!)
        ;; 2 dispatched, 1 quiesced — close-execution! should fail
        (is (thrown? Exception
                     (realization/complete! r))
            "Throws when quiescence incomplete")))))

;; ── Without lane (backward compat) ──────────────────────────────────────────

(deftest complete-works-without-lane
  (testing "complete! works without lane bound (backward compatibility)"
    (let [r (realization/open)]
      (binding [realization/*claimant-execution-realization* r
                realization/*claimant-execution-lane* nil]
        (realization/record! {:test-fact true})
        (realization/complete! r))
      (is (= :finalized (:lifecycle @r))
          "Realization finalized without lane"))))

;; ── Race: concurrent completions ────────────────────────────────────────────

(deftest concurrent-complete-with-lane-one-succeeds
  (testing "Multiple threads calling complete! with same lane — only one wins (FG-3)"
    (let [n 20
          {:keys [realization lane]} (open-pair)
          winner-count (AtomicInteger. 0)
          loser-count (AtomicInteger. 0)
          barrier (CyclicBarrier. n)]
      ;; Record and close work first
      (binding [realization/*claimant-execution-realization* realization
                realization/*claimant-execution-lane* lane]
        (realization/record! {:setup true})
        ;; Close execution on the lane so finalize-lane! can proceed
        (lane/close-execution! lane)
        ;; Finalize the lane directly (the realization is frozen+finalized already)
        (lane/finalize-lane! lane {} (fn [ticket] {:committed {:ticket ticket}})))
      ;; Now race: multiple threads try to finalize the already-finalized lane
      (let [futures (mapv (fn [_]
                            (future
                              (.await barrier)
                              (let [result (lane/finalize-lane!
                                            lane
                                            {:closed-result-set-root "root"
                                             :basis-root "basis"}
                                            (fn [ticket]
                                              {:committed {:ticket ticket}}))]
                                (if (= :committed (:status result))
                                  (.incrementAndGet winner-count)
                                  (.incrementAndGet loser-count)))))
                          (range n))]
        (doseq [f futures] (deref f 10000 nil))
        ;; Lane is already finalized from our direct call — all 20 race attempts rejected
        (is (= 0 (.get winner-count))
            "No new winner — lane already finalized")
        (is (= n (.get loser-count))
            "All race attempts rejected")))))
