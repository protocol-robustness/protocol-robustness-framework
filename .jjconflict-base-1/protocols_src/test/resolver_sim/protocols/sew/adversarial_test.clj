(ns resolver-sim.protocols.sew.adversarial-test
  "Adversarial test suite for the Sew contract model.

   Tests six attack categories:
     A. State machine attacks   — illegal transitions, double-finalize
     B. Authorization attacks   — impersonation, stale resolver, cross-workflow
     C. Solvency attacks        — accounting integrity under multi-escrow stress
     D. Timing attacks          — deadline boundaries, early execution
     E. Escalation chain attacks — MAX_ROUND violation, stale round resolver
     F. Input edge cases        — nil resolver, zero amounts, overflow, BPS edges

   Each test:
     1. Constructs the relevant world state
     2. Executes the attack or boundary sequence
     3. Asserts the expected outcome (rejection error or success)
     4. Runs check-all / check-transition to confirm no invariant is broken

   Invariant failure in a test = contract model bug."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types         :as t]
            [resolver-sim.protocols.sew.lifecycle     :as lc]
            [resolver-sim.protocols.sew.resolution    :as res]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.protocols.sew.accounting    :as acct]
            [resolver-sim.protocols.sew.authority     :as auth]
            [resolver-sim.protocols.sew.invariants    :as inv]
            [resolver-sim.time.context                 :as time-ctx]))

;; ---------------------------------------------------------------------------
;; Shared fixtures
;; ---------------------------------------------------------------------------

(def ^:private alice   "0xAlice")
(def ^:private bob     "0xBob")
(def ^:private mallory "0xMallory")
(def ^:private r0      "0xResolver0")    ; initial resolver
(def ^:private r1      "0xSeniorResolver")
(def ^:private r2      "0xKleros")
(def ^:private token   :0xUSDC)

(defn- base-snap
  "Standard module snapshot with 0 appeal window (zero waiting period; the
   pending-settlement lifecycle still applies — settlement executes immediately)."
  ([] (base-snap {}))
  ([overrides]
   (snap-fix/escrow-snapshot (merge {:max-dispute-duration   2592000
                                     :appeal-window-duration 0
                                     :escrow-fee-bps         50}
                                    overrides))))

(defn- windowed-snap
  "Snapshot with a non-zero appeal window."
  [window-secs]
  (base-snap {:appeal-window-duration window-secs}))

(defn- stamp-held-completeness
  "Declare the held-adjustment ledger complete on a world built through the
   canonical lifecycle path (mirrors the replay path which sets this flag), so
   the held-adjustments invariants are evaluated rather than :not-evaluated."
  [world]
  (assoc-in world [:params :held-adjustments/complete?] true))

(defn- make-escrow
  "Create one escrow. Returns {:world world :wf-id wf-id}."
  ([world] (make-escrow world {}))
  ([world snap-overrides]
   (let [snap (base-snap snap-overrides)
         settings (t/make-escrow-settings {})
         cr   (lc/create-escrow world alice token bob 10000 settings snap)]
     (when-not (:ok cr) (throw (ex-info "create-escrow failed" cr)))
     (let [wf-id (:workflow-id cr)
           w     (assoc-in (stamp-held-completeness (:world cr))
                           [:escrow-transfers wf-id :dispute-resolver] r0)]
       {:world w :wf-id wf-id}))))

(defn- make-disputed
  "Create and immediately dispute an escrow. Returns {:world :wf-id}."
  ([world] (make-disputed world {}))
  ([world snap-overrides]
   (let [{:keys [world wf-id]} (make-escrow world snap-overrides)
         dr (lc/raise-dispute world wf-id alice)]
     (when-not (:ok dr) (throw (ex-info "raise-dispute failed" dr)))
     {:world (:world dr) :wf-id wf-id})))

(defn- make-resolved
  "Create, dispute, resolve (release), producing a terminal :released escrow.
   With a zero appeal window the resolution finalizes immediately, so there is
   no pending settlement to execute — the resolved world is already terminal."
  [world]
  (let [{:keys [world wf-id]} (make-disputed world)
        rr (res/execute-resolution world wf-id r0 true "0xhash" nil)]
    (when-not (:ok rr) (throw (ex-info "execute-resolution failed" rr)))
    (let [ep (res/execute-pending-settlement (:world rr) wf-id)]
      (cond
        (:ok ep)
        {:world (:world ep) :wf-id wf-id}

        ;; Zero appeal window: resolution finalized immediately, no pending
        ;; settlement exists. The resolved world is already terminal.
        (#{:no-pending-settlement :transfer-not-in-dispute} (:error ep))
        {:world (:world rr) :wf-id wf-id}

        :else
        (throw (ex-info "execute-pending-settlement failed" ep))))))

(defn- make-pending
  "Create, dispute, then submit resolution that defers into appeal window.
   Returns {:world :wf-id :deadline}."
  [world window-secs]
  (let [{:keys [world wf-id]} (make-disputed world {:appeal-window-duration window-secs})
        rr (res/execute-resolution world wf-id r0 true "0xhash" nil)]
    (when-not (:ok rr) (throw (ex-info "execute-resolution (pending) failed" rr)))
    (let [w   (:world rr)
          dl  (:appeal-deadline (t/get-pending w wf-id))]
      {:world w :wf-id wf-id :deadline dl})))

(defn- esc-fn
  "Escalation fn that cycles through r0→r1→r2."
  [_world _wf _caller level]
  {:ok true :new-resolver (case level 0 r1 1 r2 "0xUnknown")})

(defn- invariants-hold? [world]
  ;; Worlds are built through the canonical lifecycle path (zero-origin,
  ;; reconstructable held ledger); declaring completeness mirrors the replay
  ;; path so the held-adjustments invariants are evaluated, not :not-evaluated.
  (:all-hold? (inv/check-all (stamp-held-completeness world))))

(defn- transition-holds? [before after]
  (:all-hold? (inv/check-transition before after)))

;; ---------------------------------------------------------------------------
;; Category A: State machine attacks
;; ---------------------------------------------------------------------------

(deftest a1-double-finalize-release
  "Attempt to finalize an already-released escrow. Second call must fail
   with :invalid-state-for-release; solvency invariant must still hold."
  (let [{:keys [world wf-id]} (make-resolved (t/empty-world 1000))
        ;; First finalize already done; try again via a fresh release
        r (sm/transition-to-released world wf-id)]
    (is (false? (:ok r)))
    (is (= :invalid-state-for-release (:error r)))
    (is (invariants-hold? world))))

(deftest a2-finalize-refund-after-release
  "Attempt to refund an already-released escrow.
   transition-to-refunded must reject; solvency must hold."
  (let [{:keys [world wf-id]} (make-resolved (t/empty-world 1000))
        r (sm/transition-to-refunded world wf-id)]
    (is (false? (:ok r)))
    (is (= :invalid-state-for-refund (:error r)))
    (is (invariants-hold? world))))

(deftest a3-raise-dispute-on-terminal-escrow
  "Attempt to raise a dispute after the escrow is released.
   transition-to-disputed must reject; state machine must remain frozen."
  (let [{:keys [world wf-id]} (make-resolved (t/empty-world 1000))
        r (lc/raise-dispute world wf-id alice)]
    (is (false? (:ok r)))
    (is (= :transfer-not-pending (:error r)))
    (is (= :released (t/escrow-state world wf-id)))
    (is (invariants-hold? world))))

(deftest a4-raise-dispute-twice
  "Attempt to raise a dispute on an already-disputed escrow.
   Second call must fail; no double dispute-resolver assignment."
  (let [{:keys [world wf-id]} (make-disputed (t/empty-world 1000))
        r (lc/raise-dispute world wf-id bob)]
    (is (false? (:ok r)))
    (is (= :transfer-not-pending (:error r)))
    (is (= :disputed (t/escrow-state world wf-id)))
    (is (invariants-hold? world))))

(deftest a5-execute-resolution-on-terminal
  "Attempt to call execute-resolution after escrow is finalized.
   Must fail with :transfer-not-in-dispute; world must not change."
  (let [{:keys [world wf-id]} (make-resolved (t/empty-world 1000))
        r (res/execute-resolution world wf-id r0 false "0xhash" nil)]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))
    (is (= :released (t/escrow-state world wf-id)))
    (is (invariants-hold? world))))

(deftest a6-release-while-pending-settlement
  "A second resolution while a pending settlement exists archives the previous
   pending and replaces it (superseded-pending fallback); exactly one active
   pending remains and the previous decision is recoverable via the superseded
   path.

   NOTE: This characterises current sim behaviour.  Solidity rejects a
   same-level re-resolution with PendingDecisionAlreadyExists — a known
   divergence tracked separately from the zero-appeal-window fix."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        before (t/get-pending world wf-id)
        r (res/execute-resolution world wf-id r0 false "0xhash2" nil)]
    (is (true? (:ok r)) "second resolution replaces the pending (archive+replace)")
    (let [after (t/get-pending (:world r) wf-id)]
      (is (:exists after) "exactly one active pending remains")
      (is (false? (:is-release after)) "replacement pending reflects the new decision")
      (is (= (:appeal-deadline after) (:appeal-deadline before))
          "replacement preserves the appeal deadline")
      (is (= 1 (count (get-in (:world r) [:superseded-pending-settlements wf-id] [])))
          "previous decision archived as superseded"))
    (is (invariants-hold? (:world r)))))

;; ---------------------------------------------------------------------------
;; Category B: Authorization attacks
;; ---------------------------------------------------------------------------

(deftest b1-third-party-verdict
  "Mallory (not a resolver) attempts to submit a verdict.
   Must fail with :not-authorized-resolver; world unchanged."
  (let [{:keys [world wf-id]} (make-disputed (t/empty-world 1000))
        r (res/execute-resolution world wf-id mallory true "0xhash" nil)]
    (is (false? (:ok r)))
    (is (= :not-authorized-resolver (:error r)))
    (is (= :disputed (t/escrow-state world wf-id)))
    (is (invariants-hold? world))))

(deftest b2-alice-submits-verdict
  "Alice (the sender, not a resolver) attempts to submit a verdict.
   Must fail with :not-authorized-resolver."
  (let [{:keys [world wf-id]} (make-disputed (t/empty-world 1000))
        r (res/execute-resolution world wf-id alice true "0xhash" nil)]
    (is (false? (:ok r)))
    (is (= :not-authorized-resolver (:error r)))
    (is (invariants-hold? world))))

(deftest b3-stale-resolver-after-escalation
  "r0 submits verdict, alice escalates to r1, then r0 tries to submit again.
   r0 must be rejected at level 1; r1 must be accepted."
  (let [{:keys [world wf-id]} (make-disputed (t/empty-world 1000)
                                             {:appeal-window-duration 86400})
        ;; r0 submits initial verdict → pending settlement
        rr0 (res/execute-resolution world wf-id r0 true "0xhash-r0" nil)
        _   (is (true? (:ok rr0)) "r0 initial verdict should succeed")
        w1  (:world rr0)
        ;; Alice escalates
        er  (res/escalate-dispute w1 wf-id alice esc-fn)
        _   (is (true? (:ok er)) "escalation should succeed")
        w2  (:world er)
        ;; r0 (stale) tries to resolve at level 1
        r-stale (res/execute-resolution w2 wf-id r0 true "0xhash-stale" nil)
        ;; r1 (current) resolves
        r-r1    (res/execute-resolution w2 wf-id r1 true "0xhash-r1" nil)]
    (is (false? (:ok r-stale)) "stale r0 must be rejected at level 1")
    (is (= :not-authorized-resolver (:error r-stale)))
    (is (true? (:ok r-r1)) "r1 must be accepted at level 1")
    (is (invariants-hold? w2))
    (is (transition-holds? w2 (:world r-r1)))))

(deftest b4-cross-workflow-resolution
  "Resolver r0 is authorized for workflow 0 but tries to resolve workflow 1
   for which a different resolver is set. Must be rejected."
  (let [w0    (t/empty-world 1000)
        {w1 :world wf0 :wf-id} (make-disputed w0)
        ;; Second escrow with a different resolver
        snap  (base-snap)
        settings (t/make-escrow-settings {})
        cr2   (lc/create-escrow w1 alice token bob 5000 settings snap)
        _     (is (true? (:ok cr2)))
        wf1   (:workflow-id cr2)
        w2    (assoc-in (:world cr2) [:escrow-transfers wf1 :dispute-resolver] "0xOtherResolver")
        dr2   (lc/raise-dispute w2 wf1 alice)
        _     (is (true? (:ok dr2)))
        w3    (:world dr2)
        ;; r0 tries to resolve wf1 (different resolver required)
        r     (res/execute-resolution w3 wf1 r0 true "0xhash" nil)]
    (is (false? (:ok r)))
    (is (= :not-authorized-resolver (:error r)))
    (is (= :disputed (t/escrow-state w3 wf0)))
    (is (= :disputed (t/escrow-state w3 wf1)))
    (is (invariants-hold? w3))))

(deftest b5-custom-resolver-blocks-all-others
  "When customResolver is set in escrowSettings, only that address may resolve.
   All other addresses — including the dispute-resolver field — are locked out."
  (let [w0 (t/empty-world 1000)
        snap (base-snap)
        ;; Create with a custom-resolver set in settings
        settings (t/make-escrow-settings {:custom-resolver "0xCustom"})
        cr   (lc/create-escrow w0 alice token bob 10000 settings snap)
        _    (is (true? (:ok cr)))
        wf   (:workflow-id cr)
        ;; Also set dispute-resolver (should be irrelevant once custom-resolver is set)
        w1   (assoc-in (:world cr) [:escrow-transfers wf :dispute-resolver] r0)
        dr   (lc/raise-dispute w1 wf alice)
        _    (is (true? (:ok dr)))
        w2   (:world dr)
        ;; r0 (dispute-resolver) tries to resolve — must fail
        r-r0      (res/execute-resolution w2 wf r0 true "0xhash" nil)
        ;; "0xCustom" resolves — must succeed
        r-custom  (res/execute-resolution w2 wf "0xCustom" true "0xhash" nil)]
    (is (false? (:ok r-r0)))
    (is (= :not-authorized-resolver (:error r-r0)))
    (is (true? (:ok r-custom)))
    (is (invariants-hold? w2))
    (is (transition-holds? w2 (:world r-custom)))))

(deftest b6-non-participant-escalation
  "Mallory (not from/to) attempts to escalate. Must fail with :not-participant."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        r (res/escalate-dispute world wf-id mallory esc-fn)]
    (is (false? (:ok r)))
    (is (= :not-participant (:error r)))
    ;; Escalation level must be unchanged
    (is (= 0 (t/dispute-level world wf-id)))
    (is (invariants-hold? world))))

;; ---------------------------------------------------------------------------
;; Category C: Solvency attacks
;; ---------------------------------------------------------------------------

(deftest c1-multi-escrow-solvency
  "Create 5 escrows and resolve them in various orders.
   Solvency invariant must hold after each step."
  (let [w0    (t/empty-world 1000)
        n     5
        ;; Create and dispute all 5
        {:keys [world wf-ids]}
        (reduce (fn [{:keys [world wf-ids]} _]
                  (let [{w :world wf :wf-id} (make-disputed world)]
                    {:world w :wf-ids (conj wf-ids wf)}))
                {:world w0 :wf-ids []}
                (range n))
        _     (is (invariants-hold? world) "solvency after disputes")]
    ;; Resolve half as release, half as refund.  With a zero appeal window each
    ;; resolution finalizes immediately, so there is no pending settlement step.
    (let [final-world
          (reduce (fn [w [i wf]]
                    (let [is-release (even? i)
                          rr (res/execute-resolution w wf r0 is-release "0xhash" nil)]
                      (is (true? (:ok rr)) (str "resolution of wf " wf " failed " (:error rr)))
                      (is (transition-holds? w (:world rr)))
                      ;; Zero appeal window: resolution finalizes immediately.
                      (:world rr)))
                  world
                  (map-indexed vector wf-ids))]
      (is (invariants-hold? final-world) "solvency after all resolutions")
      ;; total-held must be zero after all escrows finalized
      (is (= 0 (get-in final-world [:total-held token] 0))
          "total-held must be 0 after all escrows resolved"))))

(deftest c2-cross-token-isolation
  "Two escrows in different tokens. Resolving one must not affect the other
   token's total-held."
  (let [token-b  :0xDAI
        w0       (t/empty-world 1000)
        snap     (base-snap)
        settings (t/make-escrow-settings {})
        ;; Escrow A: USDC
        cr-a  (lc/create-escrow w0 alice token bob 10000 settings snap)
        _     (is (true? (:ok cr-a)))
        wf-a  (:workflow-id cr-a)
        wa    (assoc-in (:world cr-a) [:escrow-transfers wf-a :dispute-resolver] r0)
        ;; Escrow B: DAI
        cr-b  (lc/create-escrow wa alice token-b bob 8000 settings snap)
        _     (is (true? (:ok cr-b)))
        wf-b  (:workflow-id cr-b)
        wb    (assoc-in (:world cr-b) [:escrow-transfers wf-b :dispute-resolver] r0)
        ;; Dispute both
        dr-a  (lc/raise-dispute wb wf-a alice)
        dr-b  (lc/raise-dispute (:world dr-a) wf-b alice)
        w-both (:world dr-b)
        held-a-before (get-in w-both [:total-held token] 0)
        held-b-before (get-in w-both [:total-held token-b] 0)
        ;; Resolve only escrow A.  With a zero appeal window the resolution
        ;; finalizes immediately, so there is no pending settlement to execute.
        rr-a  (res/execute-resolution w-both wf-a r0 true "0xhash" nil)
        _     (is (true? (:ok rr-a)))
        w-after (:world rr-a)]
    ;; Token B held must be unchanged
    (is (= held-b-before (get-in w-after [:total-held token-b] 0))
        "DAI total-held must not change when USDC escrow resolves")
    (is (< (get-in w-after [:total-held token] 0) held-a-before)
        "USDC total-held must decrease after resolution")
    (is (invariants-hold? w-after))))

(deftest c3-double-execute-pending
  "Execute pending settlement once successfully, then attempt again.
   Second call must fail; solvency must hold throughout."
  (let [{:keys [world wf-id deadline]} (make-pending (t/empty-world 1000) 86400)
        w-past-dl (time-ctx/advance-time world {:to (inc deadline)})
        ;; First execution
        r1 (res/execute-pending-settlement w-past-dl wf-id)
        _  (is (true? (:ok r1)))
        w1 (:world r1)
        ;; Second attempt
        r2 (res/execute-pending-settlement w1 wf-id)]
    (is (false? (:ok r2)))
    ;; Guard ordering: pending is cleared before state transitions, so
    ;; :no-pending-settlement fires before :transfer-not-in-dispute.
    (is (= :no-pending-settlement (:error r2)))
    (is (invariants-hold? w1))))

(deftest c4-accounting-after-escalation
  "Escalation must not alter total-held. Escrow amount stays constant
   across all escalation rounds until final resolution."
  (let [{:keys [world wf-id]} (make-disputed (t/empty-world 1000)
                                             {:appeal-window-duration 86400})
        held-0 (get-in world [:total-held token] 0)
        ;; r0 submits → pending
        rr0  (res/execute-resolution world wf-id r0 true "0xhash0" nil)
        _    (is (true? (:ok rr0)))
        w1   (:world rr0)
        held-1 (get-in w1 [:total-held token] 0)
        ;; Escalate to level 1
        er1  (res/escalate-dispute w1 wf-id alice esc-fn)
        _    (is (true? (:ok er1)))
        w2   (:world er1)
        held-2 (get-in w2 [:total-held token] 0)
        ;; r1 submits → pending again
        rr1  (res/execute-resolution w2 wf-id r1 true "0xhash1" nil)
        _    (is (true? (:ok rr1)))
        w3   (:world rr1)
        held-3 (get-in w3 [:total-held token] 0)
        ;; Escalate to level 2 (final round)
        er2  (res/escalate-dispute w3 wf-id alice esc-fn)
        _    (is (true? (:ok er2)))
        w4   (:world er2)
        held-4 (get-in w4 [:total-held token] 0)
        ;; r2 submits final verdict — level 2 is final, executes immediately
        rr2  (res/execute-resolution w4 wf-id r2 true "0xhash2" nil)
        _    (is (true? (:ok rr2)))
        w5   (:world rr2)
        held-5 (get-in w5 [:total-held token] 0)]
    ;; held must stay constant through escalations, only drop on final resolution
    (is (= held-0 held-1) "escalation: pending does not change held")
    (is (= held-1 held-2) "escalation level 0→1 does not change held")
    (is (= held-2 held-3) "second pending does not change held")
    (is (= held-3 held-4) "escalation level 1→2 does not change held")
    (is (< held-5 held-4) "final resolution must decrease held")
    (is (= 0 held-5) "all held must be released after single-escrow resolution")
    (is (invariants-hold? w5))))

;; ---------------------------------------------------------------------------
;; Category D: Timing attacks
;; ---------------------------------------------------------------------------

(deftest d1-execute-pending-before-deadline
  "Attempt to execute a pending settlement before the appeal deadline.
   Must fail with :appeal-window-not-expired."
  (let [{:keys [world wf-id deadline]} (make-pending (t/empty-world 1000) 86400)
        ;; Set block-time to just before the deadline
        w-early (time-ctx/advance-time world {:to (dec deadline)})
        r       (res/execute-pending-settlement w-early wf-id)]
    (is (false? (:ok r)))
    (is (= :appeal-window-not-expired (:error r)))
    (is (invariants-hold? w-early))))

(deftest d2-execute-pending-at-deadline-boundary
  "Execute-pending at block-time == appeal-deadline must SUCCEED.
   The check is >= (not >), so the boundary is inclusive."
  (let [{:keys [world wf-id deadline]} (make-pending (t/empty-world 1000) 86400)
        w-at-dl (time-ctx/advance-time world {:to deadline})
        r       (res/execute-pending-settlement w-at-dl wf-id)]
    (is (true? (:ok r)) "execute-pending at exact deadline must succeed")
    (is (= :released (t/escrow-state (:world r) wf-id)))
    (is (invariants-hold? (:world r)))
    (is (transition-holds? w-at-dl (:world r)))))

(deftest d3-execute-pending-after-cleared-by-escalation
  "Execute-pending after escalation cleared active pending settlement.
   With superseded-pending fallback enabled, execution may still succeed
   using the latest eligible superseded pending at/after deadline."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        ;; Escalate — clears pending
        er  (res/escalate-dispute world wf-id alice esc-fn)
        _   (is (true? (:ok er)))
        w1  (:world er)
        ;; Confirm pending was cleared
        _   (is (not (:exists (t/get-pending w1 wf-id))) "pending must be cleared after escalation")
        ;; Try to execute the now-cleared pending (past deadline)
        dl  (:appeal-deadline (t/get-pending world wf-id))
        w2  (time-ctx/advance-time w1 {:to (+ dl 1)})
        r   (res/execute-pending-settlement w2 wf-id)]
    (is (true? (:ok r)) "eligible superseded pending should execute")
    (is (= :released (t/escrow-state (:world r) wf-id)))
    (is (invariants-hold? (:world r)))))

(deftest d4-dispute-timeout-before-exceeded
  "automate-timed-actions: dispute timeout check fires only after max-dispute-duration.
   Block-time just before should NOT finalize."
  (let [max-dur 86400
        w0      (t/empty-world 1000)
        {world :world wf-id :wf-id} (make-disputed w0 {:max-dispute-duration max-dur})
        ts      (get-in world [:dispute-timestamps wf-id] 0)
        ;; Set block-time to one second before timeout
        w-early (time-ctx/advance-time world {:to (+ ts max-dur -1)})
        auto-r  (res/automate-timed-actions w-early wf-id)]
    ;; Should return some ok result but NOT finalize the escrow
    (when (:ok auto-r)
      (is (= :disputed (t/escrow-state (:world auto-r) wf-id))
          "escrow must still be disputed before timeout"))
    (is (invariants-hold? (or (:world auto-r) w-early)))))

(deftest d5-auto-release-before-time
  "automate-timed-actions for an auto-releasable pending escrow must not
   fire before auto-release-time is reached."
  (let [now    2000
        rel-t  (+ now 3600)
        w0     (t/empty-world now)
        snap   (base-snap {:default-auto-release-delay 3600})
        settings (t/make-escrow-settings {})
        cr     (lc/create-escrow w0 alice token bob 10000 settings snap)
        _      (is (true? (:ok cr)))
        wf     (:workflow-id cr)
        world  (:world cr)
        ;; Block-time one second before auto-release
        w-early (time-ctx/advance-time world {:to (dec rel-t)})
        auto-r  (res/automate-timed-actions w-early wf)]
    (is (= :pending (t/escrow-state (or (:world auto-r) w-early) wf))
        "escrow must remain :pending before auto-release-time")
    (is (invariants-hold? (or (:world auto-r) w-early)))))

;; ---------------------------------------------------------------------------
;; Category E: Escalation chain attacks
;; ---------------------------------------------------------------------------

(deftest e1-escalate-beyond-max-round
  "Attempt a third escalation on a level-2 (final-round) escrow.
   Must fail with :escalation-not-allowed."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        ;; Level 0 → 1
        er1 (res/escalate-dispute world wf-id alice esc-fn)
        _   (is (true? (:ok er1)))
        w2  (:world er1)
        ;; Level 1: r1 submits → pending
        rr1 (res/execute-resolution w2 wf-id r1 true "0xhash1" nil)
        _   (is (true? (:ok rr1)))
        w3  (:world rr1)
        ;; Level 1 → 2
        er2 (res/escalate-dispute w3 wf-id alice esc-fn)
        _   (is (true? (:ok er2)))
        w4  (:world er2)
        _   (is (= 2 (t/dispute-level w4 wf-id)) "must be at level 2")
        _   (is (true? (t/final-round? w4 wf-id)) "must be final round")
        ;; Attempt third escalation — must be rejected
        er3 (res/escalate-dispute w4 wf-id alice esc-fn)]
    (is (false? (:ok er3)))
    (is (= :escalation-not-allowed (:error er3)))
    (is (= 2 (t/dispute-level w4 wf-id)) "level must remain 2")
    (is (invariants-hold? w4))))

(deftest e2-level-2-resolution-is-immediate
  "At level 2 (final round), execute-resolution must finalize immediately
   even when appeal-window-duration > 0. No pending settlement should be
   created."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        ;; 0 → 1
        er1 (res/escalate-dispute world wf-id alice esc-fn)
        w1  (:world er1)
        ;; 1 → 2
        rr1 (res/execute-resolution w1 wf-id r1 true "0xhash1" nil)
        _   (is (true? (:ok rr1)))
        w2  (:world rr1)
        er2 (res/escalate-dispute w2 wf-id alice esc-fn)
        _   (is (true? (:ok er2)))
        w3  (:world er2)
        ;; Level 2: execute-resolution with appeal-window > 0 — must still finalize immediately
        rr2 (res/execute-resolution w3 wf-id r2 true "0xhash2" nil)]
    (is (true? (:ok rr2)) "final-round resolution must succeed")
    (is (= :released (t/escrow-state (:world rr2) wf-id))
        "must be immediately released, not pending")
    (is (not (:exists (t/get-pending (:world rr2) wf-id)))
        "no pending settlement at final round")
    (is (invariants-hold? (:world rr2)))
    (is (transition-holds? w3 (:world rr2)))))

(deftest e3-escalation-with-nil-fn
  "Escalate-dispute with nil escalation-fn must fail with :escalation-not-configured."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        r (res/escalate-dispute world wf-id alice nil)]
    (is (false? (:ok r)))
    (is (= :escalation-not-configured (:error r)))
    (is (= 0 (t/dispute-level world wf-id)))
    (is (invariants-hold? world))))

(deftest e4-escalation-fn-returns-failure
  "When the escalation-fn returns {:ok false}, escalate-dispute must
   propagate failure and not mutate world state."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        fail-fn (fn [_ _ _ _] {:ok false :error :governance-rejected})
        r       (res/escalate-dispute world wf-id alice fail-fn)]
    (is (false? (:ok r)))
    (is (= :governance-rejected (:error r)))
    (is (= 0 (t/dispute-level world wf-id)))
    (is (invariants-hold? world))))

(deftest e5-escalate-non-disputed-escrow
  "Escalation on a :pending (not yet disputed) escrow must fail.
   Escalation on a :released escrow must also fail."
  (let [w0  (t/empty-world 1000)
        ;; Pending escrow (not disputed)
        {world-p :world wf-p :wf-id} (make-escrow w0)
        r-pending (res/escalate-dispute world-p wf-p alice esc-fn)
        ;; Released escrow
        {world-r :world wf-r :wf-id} (make-resolved w0)]
    (is (false? (:ok r-pending)))
    (is (= :transfer-not-in-dispute (:error r-pending)))
    (let [r-released (res/escalate-dispute world-r wf-r alice esc-fn)]
      (is (false? (:ok r-released)))
      (is (= :transfer-not-in-dispute (:error r-released))))
    (is (invariants-hold? world-p))
    (is (invariants-hold? world-r))))

(deftest e6-escalate-after-finalization
  "Fully escalate to level 2, finalize, then attempt escalation on the
   terminal escrow. Must fail with :transfer-not-in-dispute."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        er1  (res/escalate-dispute world wf-id alice esc-fn)
        w1   (:world er1)
        rr1  (res/execute-resolution w1 wf-id r1 true "0xhash1" nil)
        w2   (:world rr1)
        er2  (res/escalate-dispute w2 wf-id alice esc-fn)
        w3   (:world er2)
        ;; Final round: r2 resolves immediately
        rr2  (res/execute-resolution w3 wf-id r2 true "0xhash2" nil)
        _    (is (true? (:ok rr2)))
        w4   (:world rr2)
        _    (is (= :released (t/escrow-state w4 wf-id)))
        ;; Now try to escalate the finalized escrow
        r    (res/escalate-dispute w4 wf-id alice esc-fn)]
    (is (false? (:ok r)))
    (is (= :transfer-not-in-dispute (:error r)))
    (is (invariants-hold? w4))))

;; ---------------------------------------------------------------------------
;; Category F: Input edge cases
;; ---------------------------------------------------------------------------

(deftest f1-nil-dispute-resolver-direct-path
  "When dispute-resolver is nil (no custom-resolver, no snapshot resolver),
   no caller can be authorized under Priority-3.
   A dispute with nil resolver must be unresolvable via execute-resolution."
  (let [w0    (t/empty-world 1000)
        snap  (base-snap)
        settings (t/make-escrow-settings {}) ; no custom-resolver
        ;; Create escrow WITHOUT setting dispute-resolver at all
        cr    (lc/create-escrow w0 alice token bob 10000 settings snap)
        _     (is (true? (:ok cr)))
        wf    (:workflow-id cr)
        world (:world cr)
        ;; dispute-resolver is nil (no override applied)
        _     (is (nil? (get-in world [:escrow-transfers wf :dispute-resolver]))
                  "dispute-resolver should be nil without override")
        dr    (lc/raise-dispute world wf alice)
        _     (is (true? (:ok dr)))
        w-dis (:world dr)
        ;; No one can resolve — r0 is not authorized (nil != r0)
        r     (res/execute-resolution w-dis wf r0 true "0xhash" nil)]
    (is (false? (:ok r)))
    (is (= :not-authorized-resolver (:error r)))
    (is (invariants-hold? w-dis))))

(deftest f2-zero-amount-escrow-rejected
  "create-escrow with amount=0 must fail with :amount-zero."
  (let [w0   (t/empty-world 1000)
        snap (base-snap)
        settings (t/make-escrow-settings {})
        r    (lc/create-escrow w0 alice token bob 0 settings snap)]
    (is (false? (:ok r)))
    (is (= :amount-zero (:error r)))
    (is (= {} (:escrow-transfers w0)) "world must not be mutated")))

(deftest f3-negative-amount-escrow-rejected
  "create-escrow with amount=-1 must fail with :amount-zero."
  (let [w0   (t/empty-world 1000)
        snap (base-snap)
        settings (t/make-escrow-settings {})
        r    (lc/create-escrow w0 alice token bob -1 settings snap)]
    (is (false? (:ok r)))
    (is (= :amount-zero (:error r)))))

(deftest f4-bps-at-10000-fee
  "fee-bps = 10000 means 100% fee. amount-after-fee = 0.
   create-escrow must still succeed (it's not invalid per protocol),
   but the escrow holds 0 for the recipient."
  (let [w0   (t/empty-world 1000)
        snap (base-snap {:escrow-fee-bps 10000})
        settings (t/make-escrow-settings {})
        cr   (lc/create-escrow w0 alice token bob 10000 settings snap)
        _    (is (true? (:ok cr)))
        wf   (:workflow-id cr)
        world (:world cr)]
    ;; amount-after-fee = 10000 - 10000 = 0
    (is (= 0 (get-in world [:escrow-transfers wf :amount-after-fee]))
        "100% fee means afa = 0")
    ;; total-held for token is 0 (nothing held, fee taken entirely)
    (is (= 0 (get-in world [:total-held token] 0))
        "total-held = 0 when afa = 0")
    (is (invariants-hold? world))))

(deftest f5-both-auto-times-set-rejected
  "Passing both auto-release-time and auto-cancel-time in settings must fail."
  (let [w0   (t/empty-world 1000)
        snap (base-snap)
        settings (t/make-escrow-settings {:auto-release-time 5000
                                          :auto-cancel-time  8000})
        r    (lc/create-escrow w0 alice token bob 10000 settings snap)]
    (is (false? (:ok r)))
    (is (= :cannot-set-both-auto-times (:error r)))))

(deftest f6-invalid-workflow-id-all-functions
  "Every public function that accepts a workflow-id must reject an
   out-of-bounds workflow-id with :invalid-workflow-id."
  (let [w0  (t/empty-world 1000)
        wf  999]
    (testing "raise-dispute"
      (is (= :invalid-workflow-id (:error (lc/raise-dispute w0 wf alice)))))
    (testing "execute-resolution"
      (is (= :invalid-workflow-id (:error (res/execute-resolution w0 wf r0 true "h" nil)))))
    (testing "execute-pending-settlement"
      (is (= :invalid-workflow-id (:error (res/execute-pending-settlement w0 wf)))))
    (testing "escalate-dispute"
      (is (= :invalid-workflow-id (:error (res/escalate-dispute w0 wf alice esc-fn)))))
    (testing "automate-timed-actions"
      (is (= :invalid-workflow-id (:error (res/automate-timed-actions w0 wf)))))))

;; ---------------------------------------------------------------------------
;; Category G: Invariant violation detection
;;
;; These tests directly corrupt world state (bypassing guards) and confirm
;; that check-all detects the violation. This validates the invariants
;; themselves are load-bearing, not just the guards.
;; ---------------------------------------------------------------------------

(deftest g1-invariant-catches-sub-held-underflow
  "Directly force total-held below the sum of live escrows.
   check-all must detect the solvency violation."
  (let [{:keys [world wf-id]} (make-disputed (t/empty-world 1000))
        ;; Corrupt: subtract more than what's held
        corrupt (update-in world [:total-held token] - 1)]
    (is (not (invariants-hold? corrupt))
        "solvency invariant must fire on under-accounted total-held")
    (let [result (inv/check-all corrupt)
          sol    (get-in result [:results :solvency])]
      (is (false? (:holds? sol)))
      (is (seq (:violations sol))))))

(deftest g2-invariant-catches-negative-held
  "Directly set total-held to a negative value.
   held-non-negative invariant must fire."
  (let [{:keys [world]} (make-disputed (t/empty-world 1000))
        corrupt (assoc-in world [:total-held token] -500)]
    (is (not (invariants-hold? corrupt)))
    (let [result (inv/check-all corrupt)
          neg    (get-in result [:results :held-non-negative])]
      (is (false? (:holds? neg))))))

(deftest g3-invariant-catches-terminal-not-subtracted
  "Manually transition escrow to :released without calling sub-held.
   finalization-accounting-correct must fire."
  (let [{:keys [world wf-id]} (make-disputed (t/empty-world 1000))
        ;; Corrupt: transition state without updating total-held
        corrupt (assoc-in world [:escrow-transfers wf-id :escrow-state] :released)
        result  (inv/check-transition world corrupt)]
    (is (false? (:all-hold? result))
        "cross-world invariant must detect missing sub-held on finalization")
    (is (false? (get-in result [:results :finalization-accounting-correct :holds?])))))

(deftest g4-invariant-catches-dispute-level-overflow
  "Manually set dispute-level to 3 (> MAX_ROUND=2).
   dispute-level-bounded must fire."
  (let [{:keys [world wf-id]} (make-disputed (t/empty-world 1000))
        corrupt (assoc-in world [:dispute-levels wf-id] 3)]
    (is (not (invariants-hold? corrupt)))
    (let [result (inv/check-all corrupt)
          dl     (get-in result [:results :dispute-level-bounded])]
      (is (false? (:holds? dl))))))

(deftest g5-invariant-catches-escalation-level-decrease
  "Manually set dispute-level back to 0 after it was at 1.
   escalation-level-monotonic must fire."
  (let [{:keys [world wf-id]} (make-pending (t/empty-world 1000) 86400)
        er  (res/escalate-dispute world wf-id alice esc-fn)
        _   (is (true? (:ok er)))
        w1  (:world er)
        ;; Corrupt: decrease dispute-level
        w-corrupt (assoc-in w1 [:dispute-levels wf-id] 0)
        result    (inv/check-transition w1 w-corrupt)]
    (is (false? (:all-hold? result)))
    (is (false? (get-in result [:results :escalation-level-monotonic :holds?])))))

(deftest g6-invariant-catches-terminal-state-reversal
  "Manually set a :released escrow back to :disputed.
   terminal-states-unchanged must fire."
  (let [{:keys [world wf-id]} (make-resolved (t/empty-world 1000))
        ;; Corrupt: revert terminal state
        w-corrupt (assoc-in world [:escrow-transfers wf-id :escrow-state] :disputed)
        result    (inv/check-transition world w-corrupt)]
    (is (false? (:all-hold? result)))
    (is (false? (get-in result [:results :terminal-states-unchanged :holds?])))))
