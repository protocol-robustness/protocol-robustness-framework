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
  (testing "Concurrent complete! calls elect one finalizer and emit once"
    (let [n 20
          {:keys [realization lane]} (open-pair)
          winner-count (AtomicInteger. 0)
          loser-count (AtomicInteger. 0)
          emission-count (AtomicInteger. 0)
          barrier (CyclicBarrier. n)
          futures (binding [realization/*claimant-execution-realization* realization
                            realization/*claimant-execution-lane* lane
                            realization/*claimant-execution-runtime-profile-root* "test-runtime-root"
                            realization/*claimant-execution-observation-sink*
                            (fn [_] (.incrementAndGet emission-count))]
                    (realization/record! {:setup true})
                    (mapv (fn [_]
                            (future
                              (.await barrier)
                              (try
                                (realization/complete! realization)
                                (.incrementAndGet winner-count)
                                (catch clojure.lang.ExceptionInfo _
                                  (.incrementAndGet loser-count)))))
                          (range n)))]
      (doseq [f futures] (deref f 10000 nil))
      (is (= 1 (.get winner-count))
          "One caller completes the realization")
      (is (= (dec n) (.get loser-count))
          "Other callers cannot pass realization or lane finalization")
      (is (= 1 (.get emission-count))
          "The elected completion emits exactly once")
      (is (= :finalized (:lifecycle @realization)))
      (is (lane/finalized? lane)))))

(deftest rejected-lane-leaves-realization-frozen
  (testing "Lane rejection cannot falsely finalize or emit the realization"
    (let [{:keys [realization lane]} (open-pair)
          emitted (AtomicInteger. 0)]
      (binding [realization/*claimant-execution-realization* realization
                realization/*claimant-execution-lane* lane
                realization/*claimant-execution-observation-sink*
                (fn [_] (.incrementAndGet emitted))]
        (with-redefs [lane/finalize-lane!
                      (fn [_ _ _] {:status :rejected :reason :stale-parent})]
          (is (thrown? clojure.lang.ExceptionInfo
                       (realization/complete! realization)))))
      (is (= :frozen (:lifecycle @realization))
          "Realization is not finalized before lane commitment")
      (is (= 0 (.get emitted))
          "Rejected lane never emits an observation"))))

(deftest authority-commit-precedes-realization-finalization
  (let [{:keys [realization lane]} (open-pair)
        events (atom [])
        emitted (AtomicInteger. 0)]
    (binding [realization/*claimant-execution-runtime-profile-root* "test-runtime-root"
              realization/*claimant-execution-observation-sink*
              (fn [_]
                (swap! events conj :emit)
                (.incrementAndGet emitted))]
      (let [result (realization/complete-after-authority!
                    realization
                    {:lane lane
                     :closed-result-set-root "closed-root"
                     :basis-root "basis-root"
                     :valid-basis? true
                     :commit! (fn [_]
                                (swap! events conj :authority-commit)
                                {:status :committed :publication/head :head})})]
        (is (= :committed (:status result)))
        (is (= :finalized (:lifecycle @realization)))))
    (is (= [:authority-commit :emit] @events))
    (is (= 1 (.get emitted)))))

(deftest idempotent-authority-suppresses-local-finalization-and-emission
  (let [{:keys [realization lane]} (open-pair)
        emitted (AtomicInteger. 0)]
    (binding [realization/*claimant-execution-runtime-profile-root* "test-runtime-root"
              realization/*claimant-execution-observation-sink*
              (fn [_] (.incrementAndGet emitted))]
      (let [result (realization/complete-after-authority!
                    realization
                    {:lane lane
                     :closed-result-set-root "closed-root"
                     :basis-root "basis-root"
                     :valid-basis? true
                     :commit! (fn [_] {:status :idempotent :publication/head :head})})]
        (is (= :suppressed (:status result)))))
    (is (= :frozen (:lifecycle @realization)))
    (is (= 0 (.get emitted)))))

(deftest committed-authority-is-not-reclassified-when-local-projection-fails
  (let [{:keys [realization lane]} (open-pair)
        emitted (AtomicInteger. 0)]
    (binding [realization/*claimant-execution-observation-sink*
              (fn [_] (.incrementAndGet emitted))]
      (with-redefs [realization/finalize! (fn [_] (throw (ex-info "projection failed" {})))]
        (let [error (try
                      (realization/complete-after-authority!
                       realization
                       {:lane lane
                        :closed-result-set-root "closed-root"
                        :basis-root "basis-root"
                        :valid-basis? true
                        :commit! (fn [_] {:status :committed :publication/head :head})})
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= :authoritative-committed-local-projection-failed
                 (:status (ex-data error)))))))
    (is (lane/finalized? lane))
    (is (= :frozen (:lifecycle @realization)))
    (is (= 0 (.get emitted)))))

(deftest authority-rejection-leaves-realization-frozen
  (let [{:keys [realization lane]} (open-pair)
        emitted (AtomicInteger. 0)]
    (binding [realization/*claimant-execution-observation-sink*
              (fn [_] (.incrementAndGet emitted))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (realization/complete-after-authority!
                    realization
                    {:lane lane
                     :closed-result-set-root "closed-root"
                     :basis-root "basis-root"
                     :valid-basis? true
                     :commit! (fn [_] {:status :contention :reason :version-mismatch})}))))
    (is (= :frozen (:lifecycle @realization)))
    (is (lane/aborted? lane))
    (is (= 0 (.get emitted)))))
