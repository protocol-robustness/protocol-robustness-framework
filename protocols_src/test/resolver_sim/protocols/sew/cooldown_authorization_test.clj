(ns resolver-sim.protocols.sew.cooldown-authorization-test
  "Tests for escalation cooldown enforcement, nil-resolver validation,
  response-window authorization matrix, cannot-resolve lifecycle guards,
  and resolution-module adapter failure handling."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.authority  :as auth]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.time.context             :as time-ctx]))

(def alice    "0xAlice")
(def bob      "0xBob")
(def carol    "0xCarol")
(def resolver "0xResolver")
(def usdc     "0xUSDC")
(def mod-addr "0xModule")

(defn- project-legacy-time
  [w]
  (assoc w :block-time (time-ctx/block-ts w)))

(defn- base-world
  "World with one :disputed escrow at block-time=1000."
  ([appeal-window-duration]
   (base-world appeal-window-duration {}))
  ([appeal-window-duration settings-opts]
   (let [snap (snap-fix/escrow-snapshot
                (merge {:escrow-fee-bps        50
                        :max-dispute-duration  3600
                        :appeal-window-duration appeal-window-duration}
                       (dissoc settings-opts :custom-resolver)
                       (when-let [cr (:custom-resolver settings-opts)]
                         {:dispute-resolver cr})))
         sett (t/make-escrow-settings (dissoc settings-opts :dispute-resolver))
         r    (lc/create-escrow
                (-> (t/empty-world 1000)
                    (time-ctx/ensure-temporal-context))
                alice usdc bob 1000 sett snap)
         w    (:world r)]
     (-> w
         (assoc-in [:escrow-transfers 0 :escrow-state]     :disputed)
         (assoc-in [:escrow-transfers 0 :sender-status]    :raise-dispute)
         (assoc-in [:escrow-transfers 0 :dispute-resolver] (or (:dispute-resolver settings-opts)
                                                                 resolver))
         (assoc-in [:dispute-timestamps 0] 1000)
         project-legacy-time))))

(defn- with-pending
  "Manually add a pending settlement to a world."
  [world workflow-id is-release appeal-deadline]
  (let [w (assoc-in world [:pending-settlements workflow-id]
                    (t/make-pending-settlement {:exists true :is-release is-release
                                                :appeal-deadline appeal-deadline
                                                :resolution-hash "0xhash"}))]
    (project-legacy-time w)))

(defn- with-bond-balance
  "Ensure bond-balances exists for workflow-id=0."
  [world]
  (if (get-in world [:bond-balances 0])
    world
    (assoc-in world [:bond-balances 0] {})))

(defn- make-escalation-fn
  "Stub escalation fn: always succeeds, returns new-resolver."
  [new-resolver]
  (fn [_world _wf _caller _level]
    {:ok true :new-resolver new-resolver}))

(def senior-resolver "0xSenior")
(def ^:private cooldown-secs time-ctx/seconds-per-day)

(defn- world-with-pending
  "Disputed world at block-time=1000 with a pending settlement."
  ([appeal-deadline]
   (world-with-pending appeal-deadline {}))
  ([appeal-deadline settings-opts]
   (-> (base-world 5000 settings-opts)
       (with-pending 0 true appeal-deadline)
       with-bond-balance
       project-legacy-time)))

(defn- advance-past-cooldown
  "Advance time past the escalation cooldown for the given world."
  [w]
  (time-ctx/advance-time w {:to (+ 1000 cooldown-secs 1)}))

 (defn- reset-to-level
   "Reset dispute level to target-level, advance block-time, and re-add a pending
    settlement.  Used for same-level re-escalation cooldown tests: after the first
    escalation archives the pending and advances the level, we simulate a fresh
    resolution at the same level (with time advanced) to test the per-level cooldown."
   [world workflow-id target-level block-time appeal-deadline]
   (-> world
       (assoc-in [:dispute-levels workflow-id] target-level)
       (time-ctx/advance-time {:to block-time})
       (with-pending workflow-id true appeal-deadline)
       with-bond-balance
       project-legacy-time))

 (defn- re-pend
   "Re-add a pending settlement after escalation clears it."
   [world appeal-deadline]
   (-> world
       (with-pending 0 true appeal-deadline)
       with-bond-balance
       project-legacy-time))

;; ===========================================================================
;; Escalation cooldown rejection — escalate-dispute
;; ===========================================================================

(deftest escalate-dispute-cooldown-rejects-same-level-same-caller
  (testing "Same caller re-escalating the same level within cooldown is blocked"
    (let [w0 (world-with-pending 5000)
          r1 (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))]
      (is (true? (:ok r1)) "first escalation at level 0 should succeed")
      ;; Reset back to level 0 with a new pending to simulate same-level retry
      (let [w1 (reset-to-level (:world r1) 0 0 1000 5000)
            r2 (res/escalate-dispute w1 0 alice (make-escalation-fn senior-resolver))]
        (is (false? (:ok r2))
            "second escalation at same level within cooldown should fail")
        (is (= :escalation-cooldown-active (:error r2))
            "error should be :escalation-cooldown-active")))))

(deftest escalate-dispute-cooldown-same-caller-different-levels-succeeds
  (testing "Same caller escalating through different levels within cooldown succeeds"
    (let [w0 (world-with-pending 5000)
          r1 (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))]
      (is (true? (:ok r1)))
      ;; After escalation, level is 1. Re-pend creates pending at level 1.
      ;; Cooldown key is (alice, 1) — different from (alice, 0) → no cooldown violation.
      (let [w1 (re-pend (:world r1) 5000)
            r2 (res/escalate-dispute w1 0 alice (make-escalation-fn senior-resolver))]
        (is (true? (:ok r2))
            "second escalation at a different level should succeed even within cooldown")
        (is (= 2 (t/dispute-level (:world r2) 0))
            "dispute level should advance to 2")))))

(deftest escalate-dispute-cooldown-allows-after-window
  (let [w0 (world-with-pending 5000)
        r1 (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))
        w1 (re-pend (:world r1) 100000)
        w2 (advance-past-cooldown w1)
        r2 (res/escalate-dispute w2 0 alice (make-escalation-fn senior-resolver))]
    (is (true? (:ok r1)) "first escalation should succeed")
    (is (true? (:ok r2)) "second escalation after cooldown should succeed")
    (is (= 2 (t/dispute-level (:world r2) 0)) "dispute level should be 2")))

(deftest escalate-dispute-cooldown-different-callers-bypass-cooldown
  (let [w0 (world-with-pending 5000)
        r1 (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))]
    (is (true? (:ok r1)))
    (let [w1 (re-pend (:world r1) 5000)
          r2 (res/escalate-dispute w1 0 bob (make-escalation-fn senior-resolver))]
      (is (true? (:ok r2))
          "different caller should not be blocked by same-caller cooldown")
      (is (= 2 (t/dispute-level (:world r2) 0))))))

(deftest escalate-dispute-cooldown-exact-boundary-same-level-succeeds
  (testing "Same-level escalation at exactly cooldown boundary succeeds"
    (let [w0 (world-with-pending 5000)
          r1 (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))
          w1 (reset-to-level (:world r1) 0 0 (+ 1000 cooldown-secs) 100000)]
      (is (true? (:ok r1)))
      (let [r2 (res/escalate-dispute w1 0 alice (make-escalation-fn senior-resolver))]
        (is (true? (:ok r2))
            "escalation at exactly cooldown-seconds should succeed (boundary inclusive)")
        (is (= 1 (t/dispute-level (:world r2) 0))
            "level should advance from 0 to 1")))))

(deftest escalate-dispute-cooldown-rejects-same-level-one-second-short
  (testing "Same-level escalation one second before cooldown expires is rejected"
    (let [w0 (world-with-pending 5000)
          r1 (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))
          w1 (reset-to-level (:world r1) 0 0 (+ 1000 cooldown-secs -1) 100000)]
      (is (true? (:ok r1)))
      (let [r2 (res/escalate-dispute w1 0 alice (make-escalation-fn senior-resolver))]
        (is (false? (:ok r2))
            "escalation at one second before cooldown expires should fail")
        (is (= :escalation-cooldown-active (:error r2)))))))

;; ===========================================================================
;; Challenge-resolution cooldown rejection
;; ===========================================================================

 (deftest challenge-resolution-cooldown-rejects-same-level-same-caller
   (testing "Same caller re-challenging the same level within cooldown is blocked"
     (let [w0 (world-with-pending 5000)
           r1 (res/challenge-resolution w0 0 carol (make-escalation-fn senior-resolver))]
       (is (true? (:ok r1)) "first challenge at level 0 should succeed")
       ;; Reset back to level 0 with a new pending
       (let [w1 (reset-to-level (:world r1) 0 0 1000 5000)
             r2 (res/challenge-resolution w1 0 carol (make-escalation-fn senior-resolver))]
         (is (false? (:ok r2))
             "second challenge at same level within cooldown should fail")
         (is (= :escalation-cooldown-active (:error r2))
             "error should be :escalation-cooldown-active")))))

 (deftest challenge-resolution-cooldown-same-caller-different-levels-succeeds
   (testing "Same caller challenging through different levels within cooldown succeeds"
     (let [w0 (world-with-pending 5000)
           r1 (res/challenge-resolution w0 0 carol (make-escalation-fn senior-resolver))]
       (is (true? (:ok r1)))
       ;; After challenge, level is 1. Re-pend at level 1 → different cooldown key.
       (let [w1 (re-pend (:world r1) 5000)
             r2 (res/challenge-resolution w1 0 carol (make-escalation-fn senior-resolver))]
         (is (true? (:ok r2))
             "second challenge at a different level should succeed even within cooldown")
         (is (= 2 (t/dispute-level (:world r2) 0))
             "dispute level should advance to 2")))))

 (deftest challenge-resolution-cooldown-allows-after-window
   (let [w0 (world-with-pending 5000)
         r1 (res/challenge-resolution w0 0 carol (make-escalation-fn senior-resolver))
         w1 (re-pend (:world r1) 100000)
         w2 (advance-past-cooldown w1)]
     (is (true? (:ok r1)))
    (let [r2 (res/challenge-resolution w2 0 carol (make-escalation-fn senior-resolver))]
      (is (true? (:ok r2)) "challenge after cooldown should succeed")
      (is (= 2 (t/dispute-level (:world r2) 0))))))

(deftest challenge-resolution-cooldown-different-callers-bypass
  (let [w0 (world-with-pending 5000)
        r1 (res/challenge-resolution w0 0 carol (make-escalation-fn senior-resolver))]
    (is (true? (:ok r1)))
    (let [w1 (re-pend (:world r1) 5000)
          r2 (res/challenge-resolution w1 0 alice (make-escalation-fn senior-resolver))]
      (is (true? (:ok r2))
          "different challenger bypasses cooldown"))))

;; ===========================================================================
;; Nil / blank new-resolver rejection
;; ===========================================================================

(deftest escalate-dispute-rejects-nil-new-resolver
  (let [w (world-with-pending 5000)
        esc-fn (fn [_ _ _ _] {:ok true :new-resolver nil})
        r (res/escalate-dispute w 0 alice esc-fn)]
    (is (false? (:ok r)))
    (is (= :invalid-new-resolver (:error r)))))

(deftest escalate-dispute-rejects-blank-new-resolver
  (let [w (world-with-pending 5000)
        esc-fn (fn [_ _ _ _] {:ok true :new-resolver ""})
        r (res/escalate-dispute w 0 alice esc-fn)]
    (is (false? (:ok r)))
    (is (= :invalid-new-resolver (:error r)))))

(deftest escalate-dispute-nil-resolver-leaves-cooldown-untouched
  (testing "Nil-resolver rejection leaves cooldown state and escrow state untouched"
    (let [w (world-with-pending 5000)
          bad-fn (fn [_ _ _ _] {:ok true :new-resolver nil})
          r  (res/escalate-dispute w 0 alice bad-fn)]
      (is (false? (:ok r)))
      (is (= :invalid-new-resolver (:error r)))
      (is (= :disputed (t/escrow-state w 0))
          "original escrow state unchanged (guard-fail has no side effects)")
      (is (nil? (get-in w [:last-escalation-block-time-per-addr alice]))
          "cooldown timestamp not set on rejected escalation"))))

(deftest escalate-dispute-success-after-nil-resolver-sets-cooldown
  (testing "After a nil-resolver rejection, a valid escalation succeeds and sets cooldown"
    (let [w (world-with-pending 5000)
          bad-fn (fn [_ _ _ _] {:ok true :new-resolver nil})
          good-fn (make-escalation-fn senior-resolver)]
      (is (false? (:ok (res/escalate-dispute w 0 alice bad-fn))))
      (let [r (res/escalate-dispute w 0 alice good-fn)]
        (is (true? (:ok r)) "valid escalation succeeds after nil-resolver rejection")
        (is (some? (get-in (:world r) [:last-escalation-block-time-per-addr alice]))
            "cooldown timestamp set on successful escalation")))))

(deftest challenge-resolution-rejects-nil-new-resolver
  (let [w (world-with-pending 5000)
        esc-fn (fn [_ _ _ _] {:ok true :new-resolver nil})
        r (res/challenge-resolution w 0 carol esc-fn)]
    (is (false? (:ok r)))
    (is (= :invalid-new-resolver (:error r)))))

(deftest challenge-resolution-rejects-blank-new-resolver
  (let [w (world-with-pending 5000)
        esc-fn (fn [_ _ _ _] {:ok true :new-resolver ""})
        r (res/challenge-resolution w 0 carol esc-fn)]
    (is (false? (:ok r)))
    (is (= :invalid-new-resolver (:error r)))))

;; ===========================================================================
;; Response-window authorization matrix
;; ===========================================================================

(deftest execute-resolution-custom-resolver-rejected-before-response-window
  (testing "Custom-resolver escrow: non-configured caller is rejected before window expires"
    (let [w (-> (base-world 1800 {:custom-resolver resolver})
                (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
                project-legacy-time)]
      (is (false? (sm/resolver-response-exceeded? w 0)) "window not expired")
      (let [r (res/execute-resolution w 0 carol true "0xhash" nil)]
        (is (false? (:ok r)))
        (is (= :not-authorized-resolver (:error r))
            "Priority 1 exclusive to custom-resolver before window expiry")))))

(deftest execute-resolution-custom-resolver-any-caller-after-response-window
  (testing "Custom-resolver escrow: after response window expiry, any caller may resolve (emergency override)"
    (let [w (-> (base-world 1800 {:custom-resolver resolver})
                (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
                (time-ctx/advance-time {:to 1100})
                project-legacy-time)]
      (is (true? (sm/resolver-response-exceeded? w 0))
          "response window should be expired")
      (let [r (res/execute-resolution w 0 carol true "0xhash" nil)]
        (is (true? (:ok r))
            "non-custom-resolver caller authorized after window expiry (emergency override)"))
      (testing "custom-resolver itself is also still authorized"
        (let [r (res/execute-resolution w 0 resolver true "0xhash" nil)]
          (is (true? (:ok r))
              "custom-resolver should still be authorized"))))))

(deftest execute-resolution-non-custom-anyone-after-response-window
  (testing "Non-custom escrow: after response-window expiry, any caller can resolve"
    (let [w (-> (base-world 1800)
                (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
                (assoc-in [:dispute-levels 0] t/max-dispute-level)
                (time-ctx/advance-time {:to 1100})
                project-legacy-time)]
      (is (true? (sm/resolver-response-exceeded? w 0)))
      (let [r (res/execute-resolution w 0 carol true "0xhash" nil)]
        (is (true? (:ok r))
            "arbitrary caller authorized after response window expiry")
        (is (= :released (t/escrow-state (:world r) 0)))))
    (testing "Before response-window expiry: non-resolver caller rejected"
      (let [w (-> (base-world 1800)
                  (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
                  (assoc-in [:dispute-levels 0] t/max-dispute-level)
                  project-legacy-time)]
        (is (false? (sm/resolver-response-exceeded? w 0)))
        (let [r (res/execute-resolution w 0 carol true "0xhash" nil)]
          (is (false? (:ok r))
              "non-resolver caller before window expiry must be rejected")
          (is (= :not-authorized-resolver (:error r))))))))

(deftest execute-resolution-module-authorizes-fallback-not-resolver
  (testing "Resolution module can authorize a non-fallback resolver (Priority 2) before response window"
    (let [w (-> (base-world 1800)
                (assoc-in [:module-snapshots 0 :resolution-module] mod-addr)
                (assoc-in [:module-snapshots 0 :resolver-response-window] 60)
                (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
                project-legacy-time)
          mod-fn (fn [_wf caller] {:authorized? (= caller carol)})]
      (is (false? (sm/resolver-response-exceeded? w 0)) "window not expired")
      (let [r (res/execute-resolution w 0 carol true "0xhash" mod-fn)]
        (is (true? (:ok r))
            "module-authorized non-fallback resolver should succeed")
        (is (= carol (get-in (:world r) [:escrow-transfers 0 :resolution :resolved-by])))))))

;; ===========================================================================
;; Cannot-resolve lifecycle
;; ===========================================================================

(deftest execute-resolution-refused-blocks-pending-settlement
  (testing "execute-resolution-refused records refusal and blocks pending settlement"
    (let [w (-> (base-world 1800)
                (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
                (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
                project-legacy-time)
          r (res/execute-resolution-refused w 0 resolver "0xrefused-hash" nil)]
      (is (true? (:ok r)))
      (is (false? (:exists (t/get-pending (:world r) 0)))
          "no pending settlement created on refusal")
      (is (= :disputed (t/escrow-state (:world r) 0))
          "escrow remains :disputed after refusal"))))

(deftest execute-resolution-refused-requires-authorized-resolver
  (testing "execute-resolution-refused rejects unauthorized callers"
    (let [w (-> (base-world 1800)
                (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
                project-legacy-time)
          r (res/execute-resolution-refused w 0 carol "0xrefused-hash" nil)]
      (is (false? (:ok r)))
      (is (= :not-authorized-resolver (:error r))))))

;; ===========================================================================
;; Resolution-module adapter failure tests (authority.clj fail-closed)
;; ===========================================================================

(deftest authorized-resolver-module-throws-returns-false
  (let [w (-> (base-world 1800)
              (assoc-in [:module-snapshots 0 :resolution-module] mod-addr)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
              project-legacy-time)
        throwing-fn (fn [_wf _caller] (throw (ex-info "module failure" {})))]
    (is (false? (auth/authorized-resolver? w 0 resolver throwing-fn))
        "module throwing exception → fail-closed (denies even the direct resolver)")
    (is (false? (auth/authorized-resolver? w 0 carol throwing-fn))
        "module throwing → carol also denied")))

(deftest authorized-resolver-module-returns-nil
  (let [w (-> (base-world 1800)
              (assoc-in [:module-snapshots 0 :resolution-module] mod-addr)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
              project-legacy-time)
        nil-fn (fn [_wf _caller] nil)]
    (is (false? (auth/authorized-resolver? w 0 resolver nil-fn))
        "module returning nil → fail-closed")
    (is (false? (auth/authorized-resolver? w 0 carol nil-fn))
        "module returning nil → carol also denied")))

(deftest authorized-resolver-module-returns-malformed-map
  (let [w (-> (base-world 1800)
              (assoc-in [:module-snapshots 0 :resolution-module] mod-addr)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
              project-legacy-time)
        malformed-fn (fn [_wf _caller] {:foo :bar})]
    (is (false? (auth/authorized-resolver? w 0 resolver malformed-fn))
        "module returning malformed map (no :authorized? key) → fail-closed")
    (is (false? (auth/authorized-resolver? w 0 carol malformed-fn))
        "module returning malformed → carol also denied")))

(deftest authorized-resolver-module-false-falls-through-to-direct
  (let [w (-> (base-world 1800)
              (assoc-in [:module-snapshots 0 :resolution-module] mod-addr)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
              project-legacy-time)
        false-fn (fn [_wf _caller] {:authorized? false})]
    (is (true? (auth/authorized-resolver? w 0 resolver false-fn))
        "module says false but direct-resolver fallback matches → authorized")
    (is (false? (auth/authorized-resolver? w 0 carol false-fn))
        "module says false, carol is not direct resolver → not authorized")))

(deftest authorized-resolver-module-true-short-circuits
  (let [w (-> (base-world 1800)
              (assoc-in [:module-snapshots 0 :resolution-module] mod-addr)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
              project-legacy-time)
        true-fn (fn [_wf caller] {:authorized? (= caller carol)})]
    (is (true? (auth/authorized-resolver? w 0 carol true-fn))
        "module says true → short-circuits, carol authorized")
    (is (false? (auth/authorized-resolver? w 0 alice true-fn))
        "module says false for alice and alice != dispute-resolver → denied")))

;; ===========================================================================
;; P1 — Cannot-resolve lifecycle and auto-cancel-refused-resolution
;; ===========================================================================

(def ^:private dispute-ts 1000)
(def ^:private max-dispute-dur 3600)
(def ^:private refused-deadline (+ dispute-ts max-dispute-dur))

(defn- refused-world
  "Disputed world (block-time=1000) with resolver set for refusal tests."
  ([]
   (refused-world 1800))
  ([appeal-window-duration]
   (-> (base-world appeal-window-duration)
       (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)
       project-legacy-time)))

(defn- advance-to
  "Advance block-time to the given absolute timestamp."
  [w ts]
  (-> w
      (time-ctx/advance-time {:to ts})
      project-legacy-time))

(deftest cannot-resolve-records-refusal-and-stays-disputed
  (let [w (refused-world 1800)
        r (res/execute-resolution-refused w 0 resolver "0xrefused-hash" nil)]
    (is (true? (:ok r)))
    (is (= :disputed (t/escrow-state (:world r) 0))
        "escrow remains :disputed after refusal")
    (is (false? (:exists (t/get-pending (:world r) 0)))
        "no pending settlement created by refusal")
    (let [et (get-in (:world r) [:escrow-transfers 0])]
      (is (true? (:resolution/refused et)) "refusal recorded")
      (is (= "0xrefused-hash" (:resolution/refused-hash et)))
      (is (= resolver (:resolution/refused-by et)))
      (is (some? (:resolution/refused-at et)) "refusal timestamp recorded"))))

(deftest cannot-resolve-automate-no-action-before-deadline
  (let [w-pending (-> (refused-world 1800)
                      (res/execute-resolution-refused 0 resolver "0xhash" nil)
                      :world)
        r (res/automate-timed-actions w-pending 0)]
    (is (true? (:ok r)))
    (is (= :none (:action r))
        "no timed action before max-dispute-duration elapsed")
    (is (= :disputed (t/escrow-state (:world r) 0))
        "escrow unchanged before deadline")))

(deftest cannot-resolve-automate-auto-cancel-at-deadline
  (let [w-refused (-> (refused-world 1800)
                      (res/execute-resolution-refused 0 resolver "0xhash" nil)
                      :world)
        w (advance-to w-refused refused-deadline)
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :auto-cancel-refused-resolution (:action r))
        "auto-cancel-refused-resolution fires at deadline")
    (is (some #{:refunded :released}
              #{(t/escrow-state (:world r) 0)})
        "escrow left :disputed after auto-cancel")))

(deftest cannot-resolve-automate-before-deadline-is-none
  (let [w-refused (-> (refused-world 1800)
                      (res/execute-resolution-refused 0 resolver "0xhash" nil)
                      :world)
        w (advance-to w-refused (dec refused-deadline))
        r (res/automate-timed-actions w 0)]
    (is (true? (:ok r)))
    (is (= :none (:action r))
        "t-1 before deadline: no action")
    (is (= :disputed (t/escrow-state (:world r) 0))
        "still disputed at t-1")))

(deftest cannot-resolve-guard-invalid-workflow
  (let [w (refused-world 1800)]
    (is (false? (:ok (res/execute-resolution-refused w 999 resolver "0xhash" nil))))
    (is (= :invalid-workflow-id (:error (res/execute-resolution-refused w 999 resolver "0xhash" nil))))))

(deftest cannot-resolve-guard-not-disputed
  (let [w (-> (refused-world 1800)
              (assoc-in [:escrow-transfers 0 :escrow-state] :released))]
    (is (false? (:ok (res/execute-resolution-refused w 0 resolver "0xhash" nil))))
    (is (= :transfer-not-in-dispute (:error (res/execute-resolution-refused w 0 resolver "0xhash" nil))))))

(deftest cannot-resolve-guard-unauthorized-caller
  (let [w (refused-world 1800)]
    (is (false? (:ok (res/execute-resolution-refused w 0 carol "0xhash" nil))))
    (is (= :not-authorized-resolver (:error (res/execute-resolution-refused w 0 carol "0xhash" nil))))))

;; ===========================================================================
;; P1 — Bond-scaling economics
;; ===========================================================================

(def ^:private bond-amount 100)

(defn- bond-world
  "Disputed world with a pending settlement and appeal-bond-amount configured.
   Appeal deadline is set high enough to survive the cooldown advance (~87401)."
  []
  (-> (base-world 100000 {:appeal-bond-amount bond-amount
                          :appeal-bond-protocol-fee-bps 0})
      (with-pending 0 true 100000)
      with-bond-balance
      project-legacy-time))

(defn- pending-at
  "Re-add a pending settlement with a high appeal-deadline."
  [world]
  (with-pending world 0 true 100000))

 (defn- after-cooldown
   "Advance world past escalation cooldown for caller at a given dispute level.
    Reads the level-specific cooldown timestamp set by escalation at that level."
   [w caller level]
   (let [last-esc (get-in w [:last-escalation-block-time-per-addr caller level])
         target   (if (some? last-esc)
                    (+ last-esc time-ctx/seconds-per-day 1)
                    (time-ctx/block-ts w))]
     (-> w
         (time-ctx/advance-time {:to target})
         project-legacy-time)))

(deftest bond-scaling-first-vs-second-escalation-same-caller
  (let [w0 (bond-world)
        r1 (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))]
    (is (true? (:ok r1)))
    (is (= bond-amount (get-in (:world r1) [:bond-balances 0 alice]))
        "first escalation posts base bond (esc-count=0)")
    (is (= 1 (get-in (:world r1) [:escalation-counts-per-addr alice]))
        "escalation count = 1 after first")
     (let [w1 (-> (re-pend (:world r1) 100000)
                  (after-cooldown alice 1))
           r2 (res/escalate-dispute w1 0 alice (make-escalation-fn senior-resolver))]
      (is (true? (:ok r2)))
      (is (= 2 (get-in (:world r2) [:escalation-counts-per-addr alice]))
          "escalation count = 2 after second")
      (let [second-bond (quot (* bond-amount (+ 10000 1000)) 10000)
            total (+ bond-amount second-bond)]
        (is (= total (get-in (:world r2) [:bond-balances 0 alice]))
            "cumulative bond = base + floor(base*1.1)")))))

(deftest bond-scaling-separate-callers-both-base
  (let [w0 (bond-world)
        r1 (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))]
    (is (true? (:ok r1)))
     (let [w1 (re-pend (:world r1) 100000)
          r2 (res/escalate-dispute w1 0 bob (make-escalation-fn senior-resolver))]
      (is (true? (:ok r2)))
      (is (= bond-amount (get-in (:world r2) [:bond-balances 0 bob]))
          "separate caller posts base bond (esc-count=0 for bob)")
      (is (= 1 (get-in (:world r2) [:escalation-counts-per-addr alice]))
          "alice's count unchanged when bob escalates")
      (is (= 1 (get-in (:world r2) [:escalation-counts-per-addr bob]))
          "bob's count = 1"))))

(deftest bond-scaling-rejected-escalation-does-not-increment
  (let [w0 (bond-world)
        bad-fn (fn [_ _ _ _] {:ok true :new-resolver nil})
        r (res/escalate-dispute w0 0 alice bad-fn)]
    (is (false? (:ok r)))
    (is (= :invalid-new-resolver (:error r)))
    (is (nil? (get-in w0 [:bond-balances 0 alice]))
        "no bond posted on rejected escalation")
    (is (nil? (get-in w0 [:escalation-counts-per-addr alice]))
        "escalation count unchanged on rejected escalation")))

(deftest bond-scaling-zeroth-count-not-stored-explicitly
  (let [w0 (bond-world)
        r (res/escalate-dispute w0 0 alice (make-escalation-fn senior-resolver))]
    (is (true? (:ok r)))
    (is (= 1 (get-in (:world r) [:escalation-counts-per-addr alice]))
        "counter starts at 1, not 0 or missing")))
