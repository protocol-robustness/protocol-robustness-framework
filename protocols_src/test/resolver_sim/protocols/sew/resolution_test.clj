(ns resolver-sim.protocols.sew.resolution-test
  "Tests for contract_model/resolution.clj."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol       :as proto]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.authority  :as auth]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew            :as sew]
            [resolver-sim.contract-model.replay     :as replay]
            [resolver-sim.time.context :as time-ctx]))

(def alice    "0xAlice")
(def bob      "0xBob")
(def resolver "0xResolver")
(def carol    "0xCarol")
(def usdc     "0xUSDC")

(defn- ^:private project-legacy-time
  "Set legacy :block-time from canonical temporal context."
  [w]
  (assoc w :block-time (time-ctx/block-ts w)))

(defn- base-world
  "World with one :disputed escrow, block-time=1000, appeal-window as given."
  [appeal-window-duration]
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps        50
                                        :max-dispute-duration  3600
                                        :appeal-window-duration appeal-window-duration})
        w0   (time-ctx/ensure-temporal-context (t/empty-world 1000))
        r    (lc/create-escrow w0 alice usdc bob 1000
                               (t/make-escrow-settings {}) snap)
        w    (:world r)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state]     :disputed)
        (assoc-in [:escrow-transfers 0 :sender-status]    :raise-dispute)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
        (assoc-in [:dispute-timestamps 0] 1000)
        project-legacy-time)))

(def direct-resolver-fn nil)  ; no module — use direct resolver

;; ---------------------------------------------------------------------------
;; execute-resolution: with appeal window
;; ---------------------------------------------------------------------------

(deftest execute-resolution-creates-pending-settlement
  (let [w (base-world 1800)
        r (res/execute-resolution w 0 resolver true "0xhash" direct-resolver-fn)]
    (is (true? (:ok r)))
    (is (= :disputed (t/escrow-state (:world r) 0))
        "state unchanged when deferred")
    (let [pending (t/get-pending (:world r) 0)]
      (is (:exists pending))
      (is (:is-release pending))
      (is (= (+ 1000 1800) (:appeal-deadline pending))
          "appeal-deadline = block-time + appeal-window-duration"))))

(deftest execute-resolution-clears-v2-only-principal-on-pending-replacement
  (let [w (-> (base-world 1800)
              (assoc-in [:pending-settlements 0]
                        (t/make-pending-settlement {:exists true
                                                    :is-release true
                                                    :appeal-deadline 2800
                                                    :resolution-hash "0xold"}))
              (assoc-in [:claimable-v2 0 :settlement/principal bob] 100)
              (assoc-in [:claimable 0] {}))
        r (res/execute-resolution w 0 resolver false "0xnew" direct-resolver-fn)]
    (is (true? (:ok r)))
    (is (:exists (t/get-pending (:world r) 0)) "replacement pending should exist")
    (is (nil? (get-in (:world r) [:claimable-v2 0 :settlement/principal bob]))
        "v2-only principal entitlement must be fully cleared")
    (is (nil? (get-in (:world r) [:claimable-v2 0 :settlement/principal]))
        "cleanup must never write principal under nil claimant")
    (is (empty? (get-in (:world r) [:claimable 0] {}))
        "legacy principal map should remain clear after replacement")))

(deftest execute-resolution-pending-replacement-double-clear-idempotent
  (let [w0 (-> (base-world 1800)
               (assoc-in [:pending-settlements 0]
                         (t/make-pending-settlement {:exists true
                                                     :is-release true
                                                     :appeal-deadline 2800
                                                     :resolution-hash "0xold"}))
               (assoc-in [:claimable-v2 0 :settlement/principal bob] 100)
               (assoc-in [:claimable 0] {}))
        ;; First replacement clears stale v2 principal and writes a new pending.
        r1 (res/execute-resolution w0 0 resolver false "0xnew-1" direct-resolver-fn)
        w1 (:world r1)
        ;; Re-seed a principal claim to simulate another superseded pending write-set,
        ;; then replace again to verify clear path is idempotent/non-accumulative.
        w1' (assoc-in w1 [:claimable-v2 0 :settlement/principal alice] 77)
        r2 (res/execute-resolution w1' 0 resolver true "0xnew-2" direct-resolver-fn)
        w2 (:world r2)]
    (is (true? (:ok r1)))
    (is (true? (:ok r2)))
    (is (nil? (get-in w1 [:claimable-v2 0 :settlement/principal bob]))
        "first replacement clears original stale principal claim")
    (is (nil? (get-in w2 [:claimable-v2 0 :settlement/principal alice]))
        "second replacement clears re-seeded principal claim without accumulation")
    (is (empty? (get-in w2 [:claimable 0] {}))
        "legacy principal map remains clear after repeated cleanup")
    (is (:exists (t/get-pending w2 0))
        "pending remains valid after repeated replacements")))

(deftest execute-resolution-clears-legacy-mirror-principal-on-pending-replacement
  (let [w (-> (base-world 1800)
              (assoc-in [:pending-settlements 0]
                        (t/make-pending-settlement {:exists true
                                                    :is-release true
                                                    :appeal-deadline 2800
                                                    :resolution-hash "0xold"}))
              (assoc-in [:claimable-v2 0 :settlement/principal bob] 100)
              (assoc-in [:claimable 0 bob] 100))
        r (res/execute-resolution w 0 resolver false "0xnew" direct-resolver-fn)]
    (is (true? (:ok r)))
    (is (nil? (get-in (:world r) [:claimable-v2 0 :settlement/principal bob])))
    (is (nil? (get-in (:world r) [:claimable-v2 0 :settlement/principal]))
        "principal domain cleared")))

(deftest execute-resolution-pending-replacement-preserves-settlement-yield
  (let [w (-> (base-world 1800)
              (assoc-in [:pending-settlements 0]
                        (t/make-pending-settlement {:exists true
                                                    :is-release true
                                                    :appeal-deadline 2800
                                                    :resolution-hash "0xold"}))
              (assoc-in [:claimable-v2 0 :settlement/principal bob] 100)
              (assoc-in [:claimable-v2 0 :settlement/yield alice] 42)
              (assoc-in [:claimable 0] {}))
        r (res/execute-resolution w 0 resolver false "0xnew" direct-resolver-fn)]
    (is (true? (:ok r)))
    (is (nil? (get-in (:world r) [:claimable-v2 0 :settlement/principal bob])))
    (is (= 42 (get-in (:world r) [:claimable-v2 0 :settlement/yield alice]))
        "only :settlement/principal is cleared on pending replacement")))

;; ---------------------------------------------------------------------------
;; execute-resolution guards
;; ---------------------------------------------------------------------------

(deftest execute-resolution-not-authorized
  (let [r (res/execute-resolution (base-world 0) 0 carol true "0xhash" direct-resolver-fn)]
    (is (false? (:ok r)))
    (is (= :not-authorized-resolver (:error r)))))

(deftest execute-resolution-not-disputed
  (let [w (assoc-in (base-world 0) [:escrow-transfers 0 :escrow-state] :pending)
        r (res/execute-resolution w 0 resolver true "0xhash" direct-resolver-fn)]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))))

(defn- world-with-pending
  "World with a pending settlement set, block-time at/after deadline."
  [block-time appeal-deadline is-release]
  (-> (base-world 1800)
      (time-ctx/with-temporal-context {:block-ts block-time})
      (assoc-in [:pending-settlements 0]
                (t/make-pending-settlement
                 {:exists true :is-release is-release
                  :appeal-deadline appeal-deadline :resolution-hash "0xhash"}))
      project-legacy-time))

(deftest execute-resolution-invalid-workflow
  (let [r (res/execute-resolution (base-world 0) 99 resolver true "0xhash" direct-resolver-fn)]
    (is (false? (:ok r)))
    (is (= :invalid-workflow-id (:error r)))))

(deftest execute-pending-release-after-deadline
  (let [w (world-with-pending 3000 2800 true)
        r (res/execute-pending-settlement w 0)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest execute-pending-refund-after-deadline
  (let [w (world-with-pending 3000 2800 false)
        r (res/execute-pending-settlement w 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest execute-pending-before-deadline
  (let [w (world-with-pending 2000 2800 true)
        r (res/execute-pending-settlement w 0)]
    (is (false? (:ok r)))
    (is (= :appeal-window-not-expired (:error r)))))

(deftest execute-pending-at-deadline
  "Boundary behavior: settlement is executable exactly at t == appeal-deadline."
  (let [w (world-with-pending 2800 2800 true)
        r (res/execute-pending-settlement w 0)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest execute-pending-no-pending-settlement
  (let [r (res/execute-pending-settlement (base-world 0) 0)]
    (is (false? (:ok r)))
    (is (= :no-pending-settlement (:error r)))))

(deftest execute-pending-falls-back-to-eligible-superseded
  "Fallback recovery must select the authoritative decision for the CURRENT
   dispute level: a superseded decision archived at a higher level than the
   current dispute level is stale and must not execute. Among same-level entries
   the most recently superseded decision is authoritative."
  (let [w (-> (base-world 0)
              (time-ctx/advance-time {:to 5000})
              (assoc :pending-settlements {})
              (assoc-in [:superseded-pending-settlements 0]
                        [{:pending (t/make-pending-settlement {:exists true
                                                               :is-release false
                                                               :appeal-deadline 4500
                                                               :resolution-hash "older"})
                          :superseded-at 4600
                          :level 0}
                         {:pending (t/make-pending-settlement {:exists true
                                                               :is-release true
                                                               :appeal-deadline 5000
                                                               :resolution-hash "newer"})
                          :superseded-at 4999
                          :level 1}]))
        r (res/execute-pending-settlement w 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0))
        "level-1 superseded decision is stale at dispute level 0; level-0 decision (refund) is authoritative")))

(deftest execute-pending-superseded-newest-same-level-decision-wins
  "When two same-level superseded decisions exist, the most recently superseded
   one is authoritative — an older decision must not execute just because its
   deadline passed earlier."
  (let [w (-> (base-world 0)
              (time-ctx/advance-time {:to 5000})
              (assoc :pending-settlements {})
              (assoc-in [:superseded-pending-settlements 0]
                        [{:pending (t/make-pending-settlement {:exists true
                                                               :is-release true
                                                               :appeal-deadline 4500
                                                               :resolution-hash "older-decision"})
                          :superseded-at 4600
                          :level 0}
                         {:pending (t/make-pending-settlement {:exists true
                                                               :is-release false
                                                               :appeal-deadline 5000
                                                               :resolution-hash "newest-decision"})
                          :superseded-at 4999
                          :level 0}]))
        r (res/execute-pending-settlement w 0)]
    (is (true? (:ok r)))
    (is (= :refunded (t/escrow-state (:world r) 0))
        "newest same-level decision (refund) is authoritative, not the older release")))

(deftest execute-pending-superseded-newest-decision-not-yet-executable
  "The authoritative (most recently superseded) same-level decision gates
   execution: when only an OLDER same-level decision's deadline has passed,
   settlement must wait rather than execute the stale older decision."
  (let [w (-> (base-world 0)
              (time-ctx/advance-time {:to 4800})
              (assoc :pending-settlements {})
              (assoc-in [:superseded-pending-settlements 0]
                        [{:pending (t/make-pending-settlement {:exists true
                                                               :is-release true
                                                               :appeal-deadline 4500
                                                               :resolution-hash "older-decision"})
                          :superseded-at 4600
                          :level 0}
                         {:pending (t/make-pending-settlement {:exists true
                                                               :is-release false
                                                               :appeal-deadline 5000
                                                               :resolution-hash "newest-decision"})
                          :superseded-at 4999
                          :level 0}]))
        r (res/execute-pending-settlement w 0)]
    (is (false? (:ok r)))
    (is (= :appeal-window-not-expired (:error r))
        "authoritative decision's deadline (5000) not yet passed at 4800")
    (is (= :disputed (t/escrow-state w 0))
        "escrow remains :disputed; the stale older decision was not executed")))

(deftest execute-pending-superseded-fallback-single-finalization
  "Superseded fallback must still be single-shot: second keeper execution cannot re-finalize."
  (let [w0 (-> (base-world 0)
               (time-ctx/advance-time {:to 5000})
               (assoc :pending-settlements {})
               (assoc-in [:superseded-pending-settlements 0]
                         [{:pending (t/make-pending-settlement {:exists true
                                                                :is-release true
                                                                :appeal-deadline 4900
                                                                :resolution-hash "fallback"})
                           :superseded-at 4950
                           :level 0}]))
        r1 (res/execute-pending-settlement w0 0)
        r2 (res/execute-pending-settlement (:world r1) 0)]
    (is (:ok r1))
    (is (= :released (t/escrow-state (:world r1) 0)))
    (is (false? (:ok r2)))
    (is (= :transfer-not-in-dispute (:error r2)))))

(deftest execute-pending-recovers-superseded-after-escalation
  "LIVENESS: after escalation supersedes the pending decision and no replacement
   is produced at the new level, the most recent superseded pending is recovered
   so settlement is not stalled indefinitely.

   State machine: level-0 resolution creates pending P0; escalation cancels P0
   (per _validateAndPrepareEscalation) and advances the dispute to level 1. No
   level-1 resolution has been submitted, so there is no active decision. Once
   P0's deadline passes, a keeper finalizes on the recovered level-0 decision,
   and the result records the recovery origin (level + supersession reason)."
  (let [w0         (base-world 1800)                 ;; block 1000, level 0
        esc-fn     (fn [world wf caller level] {:ok true :new-resolver "0xLevel1"})
        r0         (res/execute-resolution w0 0 resolver true "0xhash-level0" direct-resolver-fn)
        w1         (:world r0)
        pending-l0 (t/get-pending w1 0)
        w1'        (time-ctx/advance-time w1 {:to 2000})
        r-esc      (res/escalate-dispute w1' 0 bob esc-fn)
        w2         (:world r-esc)
        archived   (first (get-in w2 [:superseded-pending-settlements 0]))
        w3         (time-ctx/advance-time w2 {:to 3000})   ;; P0 deadline (2800) passed
        r-exec     (res/execute-pending-settlement w3 0)
        recovered  (get-in r-exec [:extra :settlement/recovered-from-superseded])]
    (is (:ok r0) "level-0 resolution accepted")
    (is (:exists pending-l0) "level-0 pending created")
    (is (= 2800 (:appeal-deadline pending-l0)) "P0 deadline = 1000 + 1800")
    (is (:ok r-esc) "escalation accepted")
    (is (= 1 (t/dispute-level w2 0)) "dispute advanced to level 1")
    (is (false? (:exists (t/get-pending w2 0))) "active pending cleared by escalation")
    (is (= 0 (:level archived)) "archived decision was made at level 0")
    (is (= 2800 (:appeal-deadline (:pending archived))) "archived P0 deadline")
    (is (false? (:exists (t/get-pending w3 0))) "no active pending at level 1")
    ;; The superseded level-0 decision IS recovered (no replacement exists).
    (is (true? (:ok r-exec))
        "superseded level-0 decision executes at level 1 when no replacement exists")
    (is (= :released (t/escrow-state (:world r-exec) 0))
        "escrow finalized on the recovered release decision")
    (is (= 0 (:level recovered)) "recovery records the originating level 0")
    (is (= :escalation (:reason recovered)) "recovery records :escalation supersession")
    (is (some? (:superseded-at recovered)) "recovery records when the decision was superseded")))

(deftest automate-timed-actions-recovers-superseded-like-direct-execution
  "PARITY: keeper automation must apply the SAME canonical eligibility rule as
   direct execution. After an escalation supersedes the level-0 pending with no
   replacement produced at level 1, automate-timed-actions settles from the
   recovered decision once its appeal window elapses — not while the window is
   open, and not via the dispute-timeout refund path."
  (let [w0     (base-world 1800)                     ;; block 1000, level 0
        esc-fn (fn [world wf caller level] {:ok true :new-resolver "0xLevel1"})
        r0     (res/execute-resolution w0 0 resolver true "0xhash-level0" direct-resolver-fn)
        r-esc  (res/escalate-dispute
                (time-ctx/advance-time (:world r0) {:to 2000}) 0 bob esc-fn)
        ;; Archived P0 deadline = 2800; max-dispute-duration expires at 4600.
        early  (res/automate-timed-actions
                (time-ctx/advance-time (:world r-esc) {:to 2500}) 0)
        late   (res/automate-timed-actions
                (time-ctx/advance-time (:world r-esc) {:to 3000}) 0)]
    (is (:ok r-esc) "escalation accepted")
    (testing "while the recovered decision's window is open, the keeper must not act"
      (is (= :none (:action early)))
      (is (= :disputed (t/escrow-state (:world early) 0))))
    (testing "after the window closes, the keeper settles on the same rule as a direct call"
      (is (:ok late))
      (is (= :execute-pending (:action late))
          "keeper executes the recovered superseded decision")
      (is (= :released (t/escrow-state (:world late) 0))
          "finalized per the recovered release decision, not the timeout refund path")
      (is (= 0 (get-in late [:extra :settlement/recovered-from-superseded :level]))
          "recovery origin recorded on the keeper path too"))))

(deftest execute-pending-refuses-superseded-when-active-replacement-exists
  "REGRESSION: a superseded pending must NOT be recovered once a newer decision
   has been submitted at the active level — the active pending takes precedence
   and no recovery marker is recorded."
  (let [w0     (base-world 1800)                     ;; block 1000, level 0
        esc-fn (fn [world wf caller level] {:ok true :new-resolver "0xLevel1"})
        r0     (res/execute-resolution w0 0 resolver true "0xhash-level0" direct-resolver-fn)
        w1     (time-ctx/advance-time (:world r0) {:to 2000})
        r-esc  (res/escalate-dispute w1 0 bob esc-fn)
        r-l1   (res/execute-resolution (:world r-esc) 0 "0xLevel1" false "0xhash-level1" direct-resolver-fn)
        w3     (time-ctx/advance-time (:world r-l1) {:to 5000}) ;; P0 deadline passed; P1 active
        r-exec (res/execute-pending-settlement w3 0)]
    (is (:ok r-esc) "escalation accepted")
    (is (:ok r-l1) "level-1 replacement resolution accepted")
    (is (:exists (t/get-pending w3 0)) "active replacement pending exists at level 1")
    (is (true? (:ok r-exec)) "settlement proceeds on the active replacement")
    (is (= :refunded (t/escrow-state (:world r-exec) 0))
        "active level-1 decision (refund) is authoritative, not the superseded level-0 release")
    (is (nil? (get-in r-exec [:extra :settlement/recovered-from-superseded]))
        "no recovery marker: an active replacement decision existed")))

;; ---------------------------------------------------------------------------
;; Yield-liveness freeze: execute-pending-settlement blocked by yield guard
;; ---------------------------------------------------------------------------

(deftest execute-pending-yield-liveness-freeze
  (testing "Yield guard blocks execute-pending-settlement when yield position is stale"
    (let [scenario {:initial-block-time 1000}
          world0 (-> (proto/init-world sew/protocol scenario)
                     (assoc-in [:yield/rates :fixed-rate "USDC"] 0.50))  ;; 50% APY to ensure measurable yield quickly
          snapshot (snap-fix/escrow-snapshot {:yield-generation-module :fixed-rate})
          {:keys [world workflow-id]} (lc/create-escrow
                                        world0 "0xAlice" "USDC" "0xBob" 10000
                                        (t/make-escrow-settings {:yield-preset :to-recipient})
                                        snapshot)
          ;; Advance 1 year so yield is clearly positive
          world1 (-> world
                     (time-ctx/advance-time {:seconds 31536000})
                     (lc/accrue-yield workflow-id))
          pos (get-in world1 [:yield/positions (t/escrow-yield-owner-id workflow-id)])]
      (is (some? pos) "Yield position must exist after accrual")
      (is (pos? (+ (:unrealized-yield pos 0) (:realized-yield pos 0)))
          "Yield must be positive")
      (is (some? (:last-accrual-time pos)) "last-accrual-time must be set")
      (let [resolver "0xResolver"
            deadline (+ 31536000 4000)
            ;; After accrual, block-time = 1000 + 31536000 = 31537000.
            ;; Advance further to simulate time passing without re-accruing.
            world2 (-> world1
                       (assoc-in [:escrow-transfers workflow-id :escrow-state] :disputed)
                       (assoc-in [:escrow-transfers workflow-id :dispute-resolver] resolver)
                       (assoc-in [:dispute-timestamps workflow-id] 31537000)
                       (assoc-in [:dispute-levels workflow-id] 0)
                       (time-ctx/advance-time {:to (+ 31537000 5000)})
                       (assoc-in [:pending-settlements workflow-id]
                                 (t/make-pending-settlement
                                  {:exists true :is-release true
                                   :appeal-deadline deadline
                                   :resolution-hash "0xresolution"})))
            r-direct (res/execute-pending-settlement world2 workflow-id)]
        (testing "Case A: Direct call without accruing yield first"
          (is (false? (:ok r-direct))
              "Direct execute-pending-settlement should be blocked")
          (is (= :yield-position-unsettled (:error r-direct))
              "Should fail with yield-position-unsettled when yield is stale"))
        (testing "Case B: Keeper path calls accrue-yield first and succeeds"
          (let [r-keeper (res/automate-timed-actions world2 workflow-id)]
            (is (true? (:ok r-keeper))
                "automate-timed-actions should succeed when yield module is available")
            (is (= :execute-pending (:action r-keeper))
                "Should dispatch execute-pending")))
        (testing "Case C: Module failure — keeper can't settle without yield module"
          (let [world4 (update world2 :yield/modules dissoc :fixed-rate)
                r-failed (res/automate-timed-actions world4 workflow-id)]
            (is (false? (:ok r-failed))
                "automate-timed-actions should fail when yield module is unavailable")
            (is (= :yield-position-unsettled (:error r-failed))
                "Should fail with yield-position-unsettled when module can't accrue")))))))

;; ---------------------------------------------------------------------------
;; automate-timed-actions
;; ---------------------------------------------------------------------------

(deftest automate-dispatches-execute-pending
  "Pending settlement ready → executes it first (highest priority)."
  (let [w (world-with-pending 3000 2800 true)
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :execute-pending (:action r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest automate-dispatches-auto-release
  (let [w (-> (base-world 0)
              (time-ctx/advance-time {:to 5000})
              (assoc-in [:escrow-transfers 0 :escrow-state]    :pending)
              (assoc-in [:escrow-transfers 0 :auto-release-time] 4000))
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :auto-release (:action r)))
    (is (= :released (t/escrow-state (:world r) 0)))))

(deftest automate-dispatches-auto-cancel
  (let [w (-> (base-world 0)
              (time-ctx/advance-time {:to 5000})
              (assoc-in [:escrow-transfers 0 :escrow-state]   :pending)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 4000))
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :auto-cancel (:action r)))
    (is (= :refunded (t/escrow-state (:world r) 0)))))

(deftest automate-returns-none-when-nothing-due
  (let [w (base-world 0)   ; block-time=1000, no auto-times
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :none (:action r)))))

;; ---------------------------------------------------------------------------
;; pending cleared on escalation (public API behavior)
;; ---------------------------------------------------------------------------

(deftest escalation-cancels-pending-settlement
  (let [w  (-> (base-world 0)
               (assoc-in [:pending-settlements 0]
                         (t/make-pending-settlement {:exists true :is-release true
                                                     :appeal-deadline 5000
                                                     :resolution-hash "0xhash"})))
        esc-fn (fn [_world _wf _caller _level]
                 {:ok true :new-resolver "0xSenior"})
        r  (res/escalate-dispute w 0 alice esc-fn)
        w' (:world r)]
    (is (true? (:ok r)))
    (is (nil? (get-in w' [:pending-settlements 0]))
        "pending settlement cleared when escalation proceeds")))

;; ---------------------------------------------------------------------------
;; escalate-dispute
;; ---------------------------------------------------------------------------

(def ^:private senior-resolver "0xSenior")

(defn- make-escalation-fn
  "Stub: always succeeds, returns new-resolver."
  [new-resolver]
  (fn [_world _wf _caller _level]
    {:ok true :new-resolver new-resolver}))

(defn- disputed-world-with-pending
  "Disputed world at level 0 with a pending settlement already set."
  []
  (-> (base-world 1800)
      (assoc-in [:dispute-timestamps 0] 1000)
      ;; Manually set a pending settlement to confirm it is cleared by escalation
      (assoc-in [:pending-settlements 0]
                (t/make-pending-settlement {:exists true :is-release true
                                            :appeal-deadline 2800
                                            :resolution-hash "0xhash"}))))

(defn- with-pending
  "Manually add a pending settlement to a world."
  [world workflow-id is-release appeal-deadline]
  (let [w (assoc-in world [:pending-settlements workflow-id]
                    (t/make-pending-settlement {:exists true :is-release is-release
                                                :appeal-deadline appeal-deadline
                                                :resolution-hash "0xhash"}))]
    (project-legacy-time w)))

(deftest escalate-dispute-ok
  (let [w   (-> (base-world 0) (with-pending 0 true 5000))
        r   (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (testing "returns ok"
      (is (true? (:ok r))))
    (testing "level increments to 1"
      (is (= 1 (t/dispute-level (:world r) 0)))
      (is (= 1 (:new-level r))))
    (testing "dispute-resolver updated to new resolver"
      (is (= senior-resolver (get-in (:world r) [:escrow-transfers 0 :dispute-resolver])))
      (is (= senior-resolver (:new-resolver r))))
    (testing "state remains disputed"
      (is (= :disputed (t/escrow-state (:world r) 0))))))

(deftest escalate-dispute-clears-pending-settlement
  (let [w (disputed-world-with-pending)
        r (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (is (true? (:ok r)))
    (is (nil? (get-in (:world r) [:pending-settlements 0]))
        "pending settlement cleared when escalation proceeds")))

(deftest escalate-dispute-clears-stale-principal-on-pending-cancel
  (let [w (-> (disputed-world-with-pending)
              (assoc-in [:claimable-v2 0 :settlement/principal bob] 100)
              (assoc-in [:claimable 0 bob] 100))
        r (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (is (true? (:ok r)))
    (is (nil? (get-in (:world r) [:claimable-v2 0 :settlement/principal bob])))
    (is (nil? (get-in (:world r) [:claimable-v2 0 :settlement/principal]))
        "esc pending cancel clears stale principal like replacement")))

(deftest escalate-dispute-not-participant
  (let [w (-> (base-world 0) (with-pending 0 true 5000))
        r (res/escalate-dispute w 0 carol (make-escalation-fn senior-resolver))]
    (is (false? (:ok r)))
    (is (= :not-participant (:error r)))))

(deftest escalate-dispute-third-party-sponsor-rejected
  "Current model requires appeal caller to be a participant.
   Third-party sponsorship is therefore NOT supported in the current state."
  (let [w0 (-> (base-world 0)
               (with-pending 0 true 5000)
               (assoc-in [:escrow-transfers 0 :amount-after-fee] 10000)
               (assoc-in [:escrow-transfers 0 :token] usdc)
               (assoc-in [:dispute-timestamps 0] 1000)
               (assoc-in [:bond-balances 0] {})
               (assoc-in [:escrow-transfers 0 :from] alice)
               (assoc-in [:escrow-transfers 0 :to] bob))
        r  (res/escalate-dispute w0 0 carol (make-escalation-fn senior-resolver))]
    (is (false? (:ok r)))
    (is (= :not-participant (:error r)))
    (is (= :disputed (t/escrow-state w0 0))
        "state remains disputed when third-party escalation is rejected")))

(deftest escalate-dispute-not-in-dispute
  (let [w (assoc-in (base-world 0) [:escrow-transfers 0 :escrow-state] :pending)
        r (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))))

(deftest escalate-dispute-no-escalation-fn
  (let [w (-> (base-world 0) (with-pending 0 true 5000))
        r (res/escalate-dispute w 0 alice nil)]
    (is (false? (:ok r)))
    (is (= :escalation-not-configured (:error r)))))

(deftest escalate-dispute-deadline-boundary
  "Boundary behavior: escalation is allowed at t-1 and rejected at t.
   This protects pending finality exactly at the appeal deadline."
  (let [esc-fn (make-escalation-fn senior-resolver)
        w-t-1  (-> (base-world 0)
                   (time-ctx/advance-time {:to 4999})
                   (with-pending 0 true 5000))
        r-t-1  (res/escalate-dispute w-t-1 0 alice esc-fn)
        w-t    (-> (base-world 0)
                   (time-ctx/advance-time {:to 5000})
                   (with-pending 0 true 5000))
        r-t    (res/escalate-dispute w-t 0 alice esc-fn)]
    (is (true? (:ok r-t-1)) "t-1 should still be appealable")
    (is (false? (:ok r-t)) "t should be expired for appeal")
    (is (= :appeal-window-expired (:error r-t)))))

(deftest escalate-dispute-at-max-level-rejected
  (let [w (-> (base-world 0)
              (assoc-in [:dispute-levels 0] t/max-dispute-level)
              (with-pending 0 true 5000))
        r (res/escalate-dispute w 0 alice (make-escalation-fn senior-resolver))]
    (is (false? (:ok r)))
    (is (= :escalation-not-allowed (:error r)))))

(deftest escalate-dispute-module-refusal
  (let [refusing-fn (fn [_w _wf _caller _level] {:ok false :error :module-declined})
        w           (-> (base-world 0) (with-pending 0 true 5000))
        r           (res/escalate-dispute w 0 alice refusing-fn)]
    (is (false? (:ok r)))
    (is (= :module-declined (:error r)))))

(deftest escalate-dispute-level-2-is-final-round
  "After two escalations the level reaches max-dispute-level; a third must be rejected."
  (let [w0 (-> (base-world 0) (with-pending 0 true 5000))
        r1 (res/escalate-dispute w0 0 alice (make-escalation-fn "0xSenior"))
        ;; Cooldown mitigation requires >= 1 day before same caller escalates again.
        w1 (-> (:world r1)
               (time-ctx/with-temporal-context {:block-ts 87401})
               (with-pending 0 true 90000))
        r2 (res/escalate-dispute w1 0 alice (make-escalation-fn "0xKleros"))
        w2 (-> (:world r2)
               (time-ctx/with-temporal-context {:block-ts 173802})
               (with-pending 0 true 180000))
        r3 (res/escalate-dispute w2 0 alice (make-escalation-fn "0xAnother"))]
    (is (true?  (:ok r1)) "first escalation ok")
    (is (= 1    (t/dispute-level (:world r1) 0)))
    (is (true?  (:ok r2)) "second escalation ok")
    (is (= 2    (t/dispute-level (:world r2) 0)))
    (is (true?  (t/final-round? (:world r2) 0)) "level 2 is final")
    (is (false? (:ok r3)) "third escalation rejected")
    (is (= :escalation-not-allowed (:error r3)))))

;; ---------------------------------------------------------------------------
;; execute-resolution: final-round bypasses appeal window
;; ---------------------------------------------------------------------------

(deftest execute-resolution-final-round-immediate
  "At max-dispute-level, resolution is immediate even when appeal-window-duration > 0."
  (let [w (-> (base-world 1800)            ; appeal-window-duration = 1800
              (assoc-in [:dispute-levels 0] t/max-dispute-level))
        r (res/execute-resolution w 0 resolver true "0xhash" nil)]
    (is (true? (:ok r)))
    (is (= :released (t/escrow-state (:world r) 0))
        "final round executes immediately — no pending settlement")
    (is (not (:exists (t/get-pending (:world r) 0))))))

(deftest resolution-trace-decision-absent-before-resolution
  (let [w (base-world 0)]
    (is (nil? (get-in w [:escrow-transfers 0 :resolution :trace-decision])))
    (is (nil? (get-in w [:escrow-transfers 0 :resolution :decision-evidence-hash])))))

(deftest emit-decision-evidence-returns-hash-on-success
  (let [info {:decision-id "resolve-0-0"
              :step 1000
              :alternatives [:release :refund]
              :selected :release
              :reasoning "Resolver released escrow"
              :caller "0xResolver"
              :workflow-id 0}
        result (res/emit-decision-evidence! info)]
    (is (map? result) "emit-decision-evidence! returns a map on success")
    (is (string? (:evidence-hash result)) "evidence-hash should be a string")))

(deftest emit-decision-evidence-best-effort-on-failure
  (testing "The try/catch in emit-decision-evidence! catches evidence-chain failures
            so the resolution can still complete."
  (let [info {:decision-id "resolve-0-0"
              :step 1000
              :alternatives [:release :refund]
              :selected :release
              :reasoning "Resolver released escrow"
              :caller "0xResolver"
              :workflow-id 0}]
    (is (map? (res/emit-decision-evidence! info))
        "happy path returns a map")
    (is (string? (:evidence-hash (res/emit-decision-evidence! info)))
        "evidence-hash should be a string")
    (is (map? (res/emit-decision-evidence! nil))
        "nil input returns a map without throwing (defensive try/catch no-ops)"))))

;; ---------------------------------------------------------------------------
;; replay: escalate_dispute action
;; ---------------------------------------------------------------------------

(deftest replay-escalate-dispute-action
  (let [agents  [{:id "alice"    :address alice    :type "honest"}
                 {:id "bob"      :address bob       :type "honest"}
                 {:id "resolver" :address resolver  :type "resolver"}]
        esc-fn  (make-escalation-fn senior-resolver)
        context (proto/build-execution-context sew/protocol agents
                                               {:resolver-fee-bps 50
                                                :escalation-resolvers {:1 senior-resolver}})
        ;; Build a disputed world manually with a prior resolution record so
        ;; the appeal-requires-prior-resolution? post-escalation invariant passes.
        ;; The escrow's held-adjustment history is complete and zero-origin, so
        ;; declare it: otherwise the custody reconstruction invariants report
        ;; :not-evaluated-incomplete-history and reject the step.
        world   (-> (base-world 0)
                    (with-pending 0 true 5000)
                    (assoc-in [:dispute-timestamps 0] 1000)
                    (assoc-in [:escrow-transfers 0 :resolution]
                              {:resolved-by resolver
                               :is-release true
                               :resolution-hash "0xhash"})
                    (assoc-in [:params :held-adjustments/complete?] true))
        event   {:seq 0 :time 1000 :agent "alice" :action "escalate_dispute"
                 :params {:workflow-id 0}}
        step    (replay/process-step sew/protocol context world event)]
    (is (= :ok (get-in step [:trace-entry :result])))
    (is (= 1   (t/dispute-level (:world step) 0)))
    (is (= senior-resolver
           (get-in (:world step) [:escrow-transfers 0 :dispute-resolver])))))

;; ---------------------------------------------------------------------------
;; challenge-resolution (open challenger path)
;; ---------------------------------------------------------------------------

(deftest challenge-resolution-third-party-allowed
  "challenge_resolution is intentionally open to non-participants.
   A third-party challenger can post challenge bond and escalate."
  (let [w0 (-> (base-world 0)
               (with-pending 0 true 5000)
               (assoc-in [:escrow-transfers 0 :amount-after-fee] 10000)
               (assoc-in [:escrow-transfers 0 :token] usdc)
               (assoc-in [:module-snapshots 0 :challenge-bond-bps] 500)
               (assoc-in [:dispute-timestamps 0] 1000)
               (assoc-in [:bond-balances 0] {}))
        r  (res/challenge-resolution w0 0 carol (make-escalation-fn senior-resolver))
        w1 (:world r)]
    (is (true? (:ok r)))
    (is (= 1 (t/dispute-level w1 0)))
    (is (= senior-resolver (get-in w1 [:escrow-transfers 0 :dispute-resolver])))
    (is (pos? (get-in w1 [:bond-balances 0 carol] 0))
        "third-party challenger posted challenge bond")
    (is (= carol (get-in w1 [:challengers 0 0])))))

(deftest challenge-resolution-deadline-boundary
  "Boundary behavior: challenge is allowed at t-1 and rejected at t.
   Open challengers must still respect exact deadline finality."
  (let [esc-fn (make-escalation-fn senior-resolver)
        w-t-1  (-> (base-world 0)
                   (time-ctx/with-temporal-context {:block-ts 4999})
                   (with-pending 0 true 5000)
                   (assoc-in [:bond-balances 0] {}))
        r-t-1  (res/challenge-resolution w-t-1 0 carol esc-fn)
        w-t    (-> (base-world 0)
                   (time-ctx/with-temporal-context {:block-ts 5000})
                   (with-pending 0 true 5000)
                   (assoc-in [:bond-balances 0] {}))
        r-t    (res/challenge-resolution w-t 0 carol esc-fn)]
    (is (true? (:ok r-t-1)) "t-1 should still be challengeable")
    (is (false? (:ok r-t)) "t should be expired for challenge")
    (is (= :appeal-window-expired (:error r-t)))))

(deftest deadline-ordering-execute-then-escalate
  "At exact deadline, executing pending settlement first should finalize dispute,
   and a same-timestamp escalation attempt must then be rejected."
  (let [esc-fn (make-escalation-fn senior-resolver)
        w0     (-> (base-world 0)
                   (time-ctx/advance-time {:to 5000})
                   (with-pending 0 true 5000))
        r-exec (res/execute-pending-settlement w0 0)
        w1     (:world r-exec)
        r-esc  (res/escalate-dispute w1 0 alice esc-fn)]
    (is (true? (:ok r-exec)))
    (is (= :released (t/escrow-state w1 0)))
    (is (false? (:ok r-esc)))
    (is (= :transfer-not-in-dispute (:error r-esc)))))

(deftest deadline-ordering-escalate-then-execute
  "At exact deadline, escalation/challenge should be expired, and same-timestamp
   pending execution should still succeed immediately afterward."
  (let [esc-fn  (make-escalation-fn senior-resolver)
        w-base  (-> (base-world 0)
                    (time-ctx/advance-time {:to 5000})
                    (with-pending 0 true 5000)
                    (assoc-in [:bond-balances 0] {}))
        r-esc   (res/escalate-dispute w-base 0 alice esc-fn)
        r-chal  (res/challenge-resolution w-base 0 carol esc-fn)
        r-exec  (res/execute-pending-settlement w-base 0)]
    (is (false? (:ok r-esc)))
    (is (= :appeal-window-expired (:error r-esc)))
    (is (false? (:ok r-chal)))
    (is (= :appeal-window-expired (:error r-chal)))
    (is (true? (:ok r-exec)))
    (is (= :released (t/escrow-state (:world r-exec) 0)))))

(deftest deadline-ordering-matrix-same-block-execute-vs-escalate
  "Matrix coverage for ordering semantics at boundary times:
   t=deadline-1, t=deadline, t=deadline+1 for both execute->escalate and escalate->execute."
  (let [esc-fn (make-escalation-fn senior-resolver)
        cases [{:label :deadline-minus-1 :time 4999 :expect-escalate-ok? true  :expect-exec-ok? false}
               {:label :deadline-exact   :time 5000 :expect-escalate-ok? false :expect-exec-ok? true}
               {:label :deadline-plus-1  :time 5001 :expect-escalate-ok? false :expect-exec-ok? true}]]
    (doseq [{:keys [label time expect-escalate-ok? expect-exec-ok?]} cases]
      (testing (str "execute->escalate at " label)
        (let [w0     (-> (base-world 0)
                         (time-ctx/with-temporal-context {:block-ts time})
                         (with-pending 0 true 5000))
              r-exec (res/execute-pending-settlement w0 0)
              w1     (if (:ok r-exec) (:world r-exec) w0)
              r-esc  (res/escalate-dispute w1 0 alice esc-fn)]
          (is (= expect-exec-ok? (:ok r-exec)))
          (is (= expect-escalate-ok? (:ok r-esc)))))
      (testing (str "escalate->execute at " label)
        (let [w0     (-> (base-world 0) (time-ctx/advance-time {:to time}) (with-pending 0 true 5000))
              r-esc  (res/escalate-dispute w0 0 alice esc-fn)
              w1     (if (:ok r-esc) (:world r-esc) w0)
              r-exec (res/execute-pending-settlement w1 0)]
          (is (= expect-escalate-ok? (:ok r-esc)))
          (is (= expect-exec-ok? (:ok r-exec))))))))
