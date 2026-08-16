(ns resolver-sim.world.authority-test
  "Tests for resolver-sim.world.authority — the CAS-based world authority holder.

   Covers:
     - Basic world access (world-at, snapshot)
     - CAS commit / rejection / no-op semantics
     - Step-count and revision tracking
     - Concurrent CAS contention
     - Domain-level economic races (withdraw-shared double-spend, propagation idempotency)"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.world.authority :as auth])
  (:import [java.util.concurrent CountDownLatch]))

;; ---------------------------------------------------------------------------
;; Basic world access
;; ---------------------------------------------------------------------------

(deftest test-atomic-world-holder-creation
  (let [h (auth/atomic-world-holder {:a 1})]
    (is (= {:a 1} (auth/world-at h)))
    (is (= 0 (auth/revision-of h)))
    (is (= 0 (auth/step-count-of h)))))

(deftest test-snapshot-returns-world-revision-and-step-count
  (let [h (auth/atomic-world-holder {:initial true})]
    (let [snap (auth/snapshot h)]
      (is (= {:initial true} (:world snap)))
      (is (= 0 (:revision snap)))
      (is (= 0 (:step-count snap))))))

;; ---------------------------------------------------------------------------
;; CAS commit semantics
;; ---------------------------------------------------------------------------

(deftest test-cas-commit-advances-revision-and-step-count
  (let [h (auth/atomic-world-holder {:x 1})
        r (auth/cas-step! h (fn [w] {:ok true :world (assoc w :x 42)}))]
    (is (= :committed (:status r)))
    (is (= 1 (:revision r)))
    (is (= 1 (:step-count r)))
    (is (= {:x 42} (auth/world-at h)))
    (is (= 1 (auth/revision-of h)))
    (is (= 1 (auth/step-count-of h)))
    (is (= 1 (:attempts r)))
    (is (false? (:conflict? r)))))

(deftest test-cas-commit-with-step
  (let [h (auth/atomic-world-holder {:x 1})
        r (auth/cas-step! h (fn [w] {:ok true :world (assoc w :x 2)
                                     :step {:action :increment}}))]
    (is (= :committed (:status r)))
    (is (= {:action :increment} (:step r)))))

(deftest test-cas-multiple-commits-advance-revision
  (let [h (auth/atomic-world-holder {:x 1})]
    (auth/cas-step! h (fn [w] {:ok true :world (assoc w :x 2)}))
    (auth/cas-step! h (fn [w] {:ok true :world (assoc w :x 3)}))
    (is (= 2 (auth/revision-of h)))
    (is (= 2 (auth/step-count-of h)))
    (is (= {:x 3} (auth/world-at h)))))

;; ---------------------------------------------------------------------------
;; CAS rejection semantics
;; ---------------------------------------------------------------------------

(deftest test-cas-rejection-does-not-advance-state
  (let [h (auth/atomic-world-holder {:x 1})
        r (auth/cas-step! h (fn [_] {:ok false :world {:x 1}}))]
    (is (= :rejected (:status r)))
    (is (= 0 (:revision r)))
    (is (= 0 (:step-count r)))
    (is (= {:x 1} (auth/world-at h)))
    (is (= 1 (:attempts r)))
    (is (false? (:conflict? r)))))

;; ---------------------------------------------------------------------------
;; CAS no-op semantics
;; ---------------------------------------------------------------------------

(deftest test-cas-no-op-detected-for-temporal-only-change
  "When the transform returns {:ok true} but the world differs only in temporal
   context keys (:context/time, :block-time), the step is classified as :no-op
   and does not advance revision."
  (let [h (auth/atomic-world-holder {:x 1 :block-time 0})
        r (auth/cas-step! h (fn [w]
                              {:ok true
                               :world (assoc w :block-time 1)}))]
    (is (= :no-op (:status r)))         ;; world state unchanged (only temporal key)
    (is (= 0 (:revision r)))            ;; revision not advanced
    (is (= 0 (:step-count r)))
    (is (= 1 (:attempts r)))
    (is (false? (:conflict? r))))
  ;; World should still reflect the no-op (no commit)
  (let [h2 (auth/atomic-world-holder {:x 1 :block-time 10})
        _ (auth/cas-step! h2 (fn [w] {:ok true :world w}))]  ;; true no-op
    (is (= 0 (auth/revision-of h2)))))

;; ---------------------------------------------------------------------------
;; Concurrent CAS contention
;; ---------------------------------------------------------------------------

(deftest test-concurrent-cas-multiple-threads-commit-all
  "Ten threads each submit a distinct transform that changes world state.
   The CAS boundary serialises them -- each one commits, revision advances 10x."
  (let [h (auth/atomic-world-holder {:counter 0})
        n 10
        snap-barrier (CountDownLatch. n)
        transform-fn (fn [w]
                       (.countDown snap-barrier)
                       (.await snap-barrier)
                       {:ok true :world (update w :counter inc)})
        futures (doall
                  (map (fn [_]
                         (future
                           (auth/cas-step! h transform-fn)))
                        (range n)))]
    (let [results (map deref futures)]
      (is (= n (count results)))
      (is (every? #(= :committed (:status %)) results))
      (is (= n (auth/revision-of h)))
      (is (= n (get-in (auth/world-at h) [:counter]))))))

(deftest test-concurrent-cas-loser-retried-on-conflict
  "Two threads target the same initial world. Both read snapshot R (revision 0)
   simultaneously, then race to CAS. The winner commits R->R+1. The loser CAS-fails,
   re-reads R+1, recomputes (its transform still succeeds on the updated world),
   and commits R+1->R+2. Proves the loser retried (:conflict? true, :attempts > 1)."
  (let [h (auth/atomic-world-holder {:counter 0})
        snap-barrier (CountDownLatch. 2)
        transform-fn (fn [w]
                       (.countDown snap-barrier)
                       (.await snap-barrier)
                       {:ok true :world (update w :counter inc)})
        futures (doall
                  (map (fn [_]
                         (future
                           (auth/cas-step! h transform-fn)))
                       (range 2)))]
    (let [results (map deref futures)
          sorted (sort-by :revision results)
          winner (first sorted)
          loser (second sorted)]
      (is (= :committed (:status winner)))
      (is (= 1 (:attempts winner)))
      (is (false? (:conflict? winner)))
      (is (= :committed (:status loser)))
      (is (:conflict? loser))
      (is (> (:attempts loser) 1)))
    (is (= 2 (auth/revision-of h)))
    (is (= 2 (get-in (auth/world-at h) [:counter])))))

;; ---------------------------------------------------------------------------
;; Step-count tracking
;; ---------------------------------------------------------------------------

(deftest test-step-count-increments-per-commit
  (let [h (auth/atomic-world-holder {:x 0})]
    (auth/cas-step! h (fn [w] {:ok true :world (assoc w :x 1)}))
    (is (= 1 (auth/step-count-of h)))
    (auth/cas-step! h (fn [w] {:ok true :world (assoc w :x 2)}))
    (is (= 2 (auth/step-count-of h)))))

(deftest test-step-count-not-advanced-on-rejection
  (let [h (auth/atomic-world-holder {:x 0})]
    (auth/cas-step! h (fn [_] {:ok false :world {:x 0}}))
    (is (= 0 (auth/step-count-of h)))
    (is (= 0 (auth/revision-of h)))))

(deftest test-step-count-not-advanced-on-no-op
  (let [h (auth/atomic-world-holder {:x 0})]
    (auth/cas-step! h (fn [w] {:ok true :world w}))  ;; true no-op
    (is (= 0 (auth/step-count-of h)))
    (is (= 0 (auth/revision-of h)))))

;; ---------------------------------------------------------------------------
;; Domain-level economic races
;; ---------------------------------------------------------------------------

(deftest domain-race-withdraw-shared-loser-recomputes-from-committed-world
  "Two distinct withdraw-shared operations race against the same initial liquidity.
   Both threads are forced to read the same initial snapshot R (revision 0) via
   a barrier inside the transform-fn. Only one can commit against R (revision 0→1).
   The loser CAS-fails, re-reads revision 1, recomputes from the updated liquidity,
   and is rejected (insufficient funds). Total withdrawn can never exceed available liquidity."
  (let [h (auth/atomic-world-holder {:liquidity {:USDC 100} :withdrawn {:USDC 0}})
        snap-barrier (CountDownLatch. 2)
        seen (atom [])
        withdraw-fn (fn [w]
                      (.countDown snap-barrier)              ;; signal: I've read the snapshot
                      (.await snap-barrier)                  ;; wait for both threads to have read
                      (let [available (get-in w [:liquidity :USDC] 0)
                            requested 80
                            _ (swap! seen conj available)]
                        (if (>= available requested)
                          {:ok true
                           :world (-> w
                                      (update-in [:liquidity :USDC] - requested)
                                      (update-in [:withdrawn :USDC] + requested))}
                          {:ok false :world w})))
        run (fn [_]
               (auth/cas-step! h withdraw-fn))
        results (doall (pmap run (range 2)))
        committed (filter #(= :committed (:status %)) results)
        rejected (filter #(= :rejected (:status %)) results)
        loser (first rejected)]
    ;; Exactly one winner commits at revision 0→1
    (is (= 1 (count committed)))
    ;; The loser was rejected — but only AFTER retrying: prove it conflicted & recomputed
    (is (:conflict? loser))                   ;; loser had CAS contention → retried
    (is (> (:attempts loser) 1))              ;; loser needed >1 attempt to resolve
    ;; Prove the loser re-read the committed world (not just CAS-fail):
    ;;   both threads read 100, then loser re-read 20 after winner committed
    (is (= [20 100 100] (sort @seen)))        ;; two initial reads of 100 + loser's recompute of 20
    ;; Final state: 100 - 80 = 20, no double-spend
    (is (= 20 (get-in (auth/world-at h) [:liquidity :USDC])))
    (is (= 80 (get-in (auth/world-at h) [:withdrawn :USDC])))
    (is (= 1 (auth/revision-of h)))))

(deftest domain-race-same-propagation-id-concurrent-application-commits-once
  "Two concurrent applications of the same propagation ID, forced to read the same
   initial snapshot R (revision 0) via a barrier inside the transform-fn. The winner
   commits the application (R→R+1). The loser CAS-fails, re-reads R+1, recomputes,
   sees P is already applied, and resolves as rejection. Exactly one economic effect."
  (let [h (auth/atomic-world-holder {:applied-pro-rata-propagations {} :block-time 1000})
        snap-barrier (CountDownLatch. 2)
        propagator :prop-456
        seen (atom [])
        apply-fn (fn [w]
                   (.countDown snap-barrier)
                   (.await snap-barrier)
                   (let [found (contains? (get w :applied-pro-rata-propagations {}) propagator)
                         _ (swap! seen conj (if found propagator :none))]
                     (if-not found
                       {:ok true
                        :world (assoc-in w [:applied-pro-rata-propagations propagator]
                                         {:token :USDC :amount 50})}
                       {:ok false :world w})))
        run (fn [_]
               (auth/cas-step! h apply-fn))
        results (doall (pmap run (range 2)))
        committed (filter #(= :committed (:status %)) results)
        rejected (filter #(= :rejected (:status %)) results)
        loser (first rejected)]
    (is (= 1 (count committed)))                          ;; exactly one commit
    (is (= 1 (count rejected)))                           ;; loser resolves to rejection
    (is (:conflict? loser))                                ;; loser had CAS contention → retried
    (is (> (:attempts loser) 1))                           ;; loser needed >1 attempt
    ;; Prove the loser re-read the committed world:
    ;;   both threads saw :none, then loser re-read and found :prop-456
    (is (= [:none :none :prop-456] (sort @seen)))
    (is (= 1 (auth/revision-of h)))                       ;; revision advanced only once
    (is (contains? (:applied-pro-rata-propagations (auth/world-at h)) propagator))))
