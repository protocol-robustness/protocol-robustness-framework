(ns resolver-sim.protocols.sew.dispute-capacity-test
  "Unit and integration tests for DRM resolver capacity tracking.

   Covers:
     - resolver-capacity world-state accessors
     - raise-dispute blocked when resolver at maxConcurrentDisputes
     - counter increment on dispute open
     - counter decrement on execute-resolution → execute-pending-settlement
     - counter decrement on auto-cancel-disputed-escrow
     - resolver-capacity-invariant? holds throughout all paths
     - capacity freed and reusable after finalization
     - all-resolvers-full system liveness scenario"
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.config :as config]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.time.context :as time-ctx]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def alice    "0xAlice")
(def bob      "0xBob")
(def resolver "0xResolver")
(def usdc     "0xUSDC")

(def base-snapshot
  (snap-fix/escrow-snapshot
   (merge {:escrow-fee-bps 0}
          (select-keys config/DEFAULT_TIMEOUT_CONFIG
                       [:default-auto-release-delay
                        :default-auto-cancel-delay
                        :max-dispute-duration
                        :appeal-window-duration]))))

(def base-settings (t/make-escrow-settings {}))

(defn- fresh-world []
  (t/empty-world 1000))

(defn- make-escrow
  "Create one alice→bob escrow and return the resulting world."
  ([world] (make-escrow world {}))
  ([world settings-overrides]
   (let [r (lc/create-escrow
            world
            alice usdc bob 1000
            (merge base-settings settings-overrides {:custom-resolver resolver})
            base-snapshot)]
     (is (:ok r) "create-escrow must succeed")
     (:world r))))

(defn- resolve-and-finalize
  "Resolver rules → advance past appeal window → execute-pending-settlement."
  [world wf-id is-release rm-fn]
  (let [r1 (res/execute-resolution world wf-id resolver is-release "0xhash" rm-fn)
        _  (is (:ok r1) "execute-resolution must succeed")
        ;; advance time past appeal window (2 days)
        w1 (time-ctx/advance-time (:world r1) {:seconds 200000})
        r2 (res/execute-pending-settlement w1 wf-id)]
    (is (:ok r2) "execute-pending-settlement must succeed")
    (:world r2)))

(def always-resolver-fn
  "A resolution-module-fn that authorises any resolver."
  (fn [_world _wf caller] {:authorized? true :resolver caller}))

;; ---------------------------------------------------------------------------
;; Section 1: Accessor correctness
;; ---------------------------------------------------------------------------

(deftest test-resolver-capacity-accessors
  (testing "resolver-capacity returns nil when not configured"
    (is (nil? (t/resolver-capacity (fresh-world) resolver))))

  (testing "set-resolver-capacity stores the entry"
    (let [w (t/set-resolver-capacity (fresh-world) resolver 5)]
      (is (= {:max-concurrent 5 :current-active 0}
             (t/resolver-capacity w resolver)))))

  (testing "resolver-at-capacity? false when not configured"
    (is (not (t/resolver-at-capacity? (fresh-world) resolver))))

  (testing "resolver-at-capacity? false when below limit"
    (let [w (-> (fresh-world)
                (t/set-resolver-capacity resolver 3)
                (t/increment-resolver-capacity resolver))]
      (is (not (t/resolver-at-capacity? w resolver)))))

  (testing "resolver-at-capacity? true when at limit"
    (let [w (-> (fresh-world)
                (t/set-resolver-capacity resolver 2)
                (t/increment-resolver-capacity resolver)
                (t/increment-resolver-capacity resolver))]
      (is (t/resolver-at-capacity? w resolver))))

  (testing "decrement clamps at 0"
    (let [w (-> (fresh-world)
                (t/set-resolver-capacity resolver 3)
                (t/decrement-resolver-capacity resolver))]
      (is (zero? (get-in w [:resolver-capacities resolver :current-active])))))

  (testing "unlimited capacity (max-concurrent=0) never reports at-capacity"
    (let [w (-> (fresh-world)
                (t/set-resolver-capacity resolver 0)
                (t/increment-resolver-capacity resolver)
                (t/increment-resolver-capacity resolver)
                (t/increment-resolver-capacity resolver))]
      (is (not (t/resolver-at-capacity? w resolver))))))

;; ---------------------------------------------------------------------------
;; Section 2: raise-dispute capacity gate
;; ---------------------------------------------------------------------------

(deftest test-raise-dispute-blocked-when-at-capacity
  (testing "dispute succeeds when resolver has capacity remaining"
    (let [w0 (-> (fresh-world)
                 (t/set-resolver-capacity resolver 2))
          w1 (make-escrow w0)
          r  (lc/raise-dispute w1 0 alice)]
      (is (:ok r))
      (is (= :disputed (t/escrow-state (:world r) 0)))))

  (testing "dispute fails when resolver is at maxConcurrentDisputes"
    (let [w0 (-> (fresh-world)
                 (t/set-resolver-capacity resolver 1))
          ;; create both escrows before any dispute (create is not capacity-gated)
          w1 (make-escrow w0)
          w2 (make-escrow w1)
          r1 (lc/raise-dispute w2 0 alice)
          _  (is (:ok r1) "first dispute should succeed")
          r2 (lc/raise-dispute (:world r1) 1 alice)]
      (is (not (:ok r2)))
      (is (= :resolver-capacity-exceeded (:error r2)))))

  (testing "counter increments on successful dispute"
    (let [w0 (-> (fresh-world)
                 (t/set-resolver-capacity resolver 5))
          w1 (make-escrow w0)
          r  (lc/raise-dispute w1 0 alice)]
      (is (:ok r))
      (is (= 1 (get-in (:world r) [:resolver-capacities resolver :current-active])))))

  (testing "no increment when resolver not configured (unlimited)"
    (let [w1 (make-escrow (fresh-world))
          r  (lc/raise-dispute w1 0 alice)]
      (is (:ok r))
      (is (nil? (t/resolver-capacity (:world r) resolver))))))

;; ---------------------------------------------------------------------------
;; Section 3: capacity decremented after execute-pending-settlement
;; ---------------------------------------------------------------------------

(deftest test-capacity-decremented-after-resolution
  (testing "current-active returns to 0 after dispute is finalised (release)"
    (let [w0 (-> (fresh-world)
                 (t/set-resolver-capacity resolver 3))
          w1 (make-escrow w0)
          w2 (:world (lc/raise-dispute w1 0 alice))
          _  (is (= 1 (get-in w2 [:resolver-capacities resolver :current-active])))
          w3 (resolve-and-finalize w2 0 true always-resolver-fn)]
      (is (= 0 (get-in w3 [:resolver-capacities resolver :current-active])))))

  (testing "current-active returns to 0 after dispute is finalised (refund)"
    (let [w0 (-> (fresh-world)
                 (t/set-resolver-capacity resolver 3))
          w1 (make-escrow w0)
          w2 (:world (lc/raise-dispute w1 0 alice))
          w3 (resolve-and-finalize w2 0 false always-resolver-fn)]
      (is (= 0 (get-in w3 [:resolver-capacities resolver :current-active])))))

  (testing "capacity freed and reusable: open → resolve → open again"
    (let [w0 (-> (fresh-world)
                 (t/set-resolver-capacity resolver 1))
          w1 (make-escrow w0)
          w2 (:world (lc/raise-dispute w1 0 alice))
          _  (is (= 1 (get-in w2 [:resolver-capacities resolver :current-active])))
          w3 (resolve-and-finalize w2 0 true always-resolver-fn)
          _  (is (= 0 (get-in w3 [:resolver-capacities resolver :current-active])))
          ;; create and dispute a second escrow — should succeed now slot is free
          w4 (make-escrow w3)
          r  (lc/raise-dispute w4 1 alice)]
      (is (:ok r))
      (is (= 1 (get-in (:world r) [:resolver-capacities resolver :current-active]))))))

;; ---------------------------------------------------------------------------
;; Section 4: capacity decremented after auto-cancel-disputed-escrow
;; ---------------------------------------------------------------------------

(deftest test-capacity-decremented-after-auto-cancel
  (testing "current-active decrements when dispute times out"
    (let [w0 (-> (fresh-world)
                 (t/set-resolver-capacity resolver 2))
          w1 (make-escrow w0)
          w2 (:world (lc/raise-dispute w1 0 alice))
          _  (is (= 1 (get-in w2 [:resolver-capacities resolver :current-active])))
          ;; advance time past max-dispute-duration (86400s)
          w3 (time-ctx/advance-time w2 {:seconds 100000})
          r  (lc/auto-cancel-disputed-escrow w3 0)]
      (is (:ok r))
      (is (= :refunded (t/escrow-state (:world r) 0)))
      (is (= 0 (get-in (:world r) [:resolver-capacities resolver :current-active]))))))

;; ---------------------------------------------------------------------------
;; Section 5: invariant always holds
;; ---------------------------------------------------------------------------

(deftest test-capacity-invariant-holds-throughout
  (testing "invariant holds on empty world"
    (let [check (inv/resolver-capacity-invariant? (fresh-world))]
      (is (:holds? check))))

  (testing "invariant holds after dispute opened"
    (let [w0 (-> (fresh-world) (t/set-resolver-capacity resolver 3))
          w1 (make-escrow w0)
          w2 (:world (lc/raise-dispute w1 0 alice))]
      (is (:holds? (inv/resolver-capacity-invariant? w2)))))

  (testing "invariant holds after full finalization cycle"
    (let [w0 (-> (fresh-world) (t/set-resolver-capacity resolver 3))
          w1 (make-escrow w0)
          w2 (:world (lc/raise-dispute w1 0 alice))
          w3 (resolve-and-finalize w2 0 true always-resolver-fn)]
      (is (:holds? (inv/resolver-capacity-invariant? w3)))))

  (testing "invariant detects over-limit violation"
    ;; Force an over-limit state directly to confirm detection
    (let [w (-> (fresh-world)
                (t/set-resolver-capacity resolver 1)
                (t/increment-resolver-capacity resolver)
                (t/increment-resolver-capacity resolver))] ; current-active=2 > max=1
      (let [check (inv/resolver-capacity-invariant? w)]
        (is (not (:holds? check)))
        (is (some #(= :over-limit (:violation %)) (:violations check))))))

  (testing "invariant detects counter-below-open-disputes violation"
    ;; Force world where an escrow is DISPUTED but counter is 0
    (let [w0 (-> (fresh-world) (t/set-resolver-capacity resolver 3))
          w1 (make-escrow w0)
          w2 (:world (lc/raise-dispute w1 0 alice))
          ;; manually reset counter to 0 — simulating missing increment
          w-bad (assoc-in w2 [:resolver-capacities resolver :current-active] 0)]
      (let [check (inv/resolver-capacity-invariant? w-bad)]
        (is (not (:holds? check)))
        (is (some #(= :counter-below-open-disputes (:violation %)) (:violations check))))))

  (testing "check-all includes resolver-capacity invariant"
    (let [w0 (-> (fresh-world) (t/set-resolver-capacity resolver 3))
          w1 (make-escrow w0)
          w2 (:world (lc/raise-dispute w1 0 alice))
          all (inv/check-all w2)]
      (is (contains? (:results all) :resolver-capacity))
      (is (:holds? (get-in all [:results :resolver-capacity]))))))

;; ---------------------------------------------------------------------------
;; Section 6: multi-dispute capacity exhaustion scenario
;; ---------------------------------------------------------------------------

(deftest test-capacity-exhaustion-and-recovery
  (testing "capacity=2: first two disputes succeed, third blocked, then freed"
    (let [w0 (-> (fresh-world) (t/set-resolver-capacity resolver 2))
          ;; create 3 escrows
          w1 (make-escrow w0)
          w2 (make-escrow w1)
          w3 (make-escrow w2)
          ;; dispute first two — should both succeed
          r1 (lc/raise-dispute w3 0 alice)
          _  (is (:ok r1) "dispute 0 should succeed")
          r2 (lc/raise-dispute (:world r1) 1 alice)
          _  (is (:ok r2) "dispute 1 should succeed")
          ;; third dispute blocked — capacity=2 exhausted
          r3 (lc/raise-dispute (:world r2) 2 alice)
          _  (is (not (:ok r3)) "dispute 2 should be blocked")
          _  (is (= :resolver-capacity-exceeded (:error r3)))
          ;; resolve dispute 0 → frees one slot
          w-after0 (resolve-and-finalize (:world r2) 0 true always-resolver-fn)
          _  (is (= 1 (get-in w-after0 [:resolver-capacities resolver :current-active])))
          ;; now the third dispute should succeed
          r4 (lc/raise-dispute w-after0 2 alice)]
      (is (:ok r4) "dispute 2 should succeed after slot freed")
      (is (= 2 (get-in (:world r4) [:resolver-capacities resolver :current-active])))
      ;; invariant holds throughout
      (is (:holds? (inv/resolver-capacity-invariant? (:world r4)))))))
