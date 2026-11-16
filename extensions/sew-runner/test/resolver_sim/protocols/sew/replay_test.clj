(ns resolver-sim.protocols.sew.replay-test
  "Unit tests for the open-world scenario replay proto.

   Covers:
     - Structural validation (schema-version, seq, time)
     - Happy-path and dispute scenarios
     - Adversarial rejections (not halts)
     - Invariant enforcement (solvency = not <=, terminal irreversibility)
     - Edge cases: time regression, unknown agent, overflow guard, duplicate seq
     - Escalation flows: full chain (0→1→2), mid-pending escalation, adversarial rejections"
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay    :as replay]
            [resolver-sim.db.temporal              :as temporal]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew            :as sew]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.types     :as t]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.testing.scenario-builder :as sb]
            [resolver-sim.time.context             :as time-ctx]))

;; ---------------------------------------------------------------------------
;; Section 1: Structural validation
;; ---------------------------------------------------------------------------

(def ^:private alice    sb/alice)
(def ^:private bob      sb/bob)
(def ^:private mallory  sb/mallory)
(def ^:private resolver sb/resolver)
(def ^:private default-params sb/default-params)
;; ---------------------------------------------------------------------------

(deftest test-validation-wrong-schema-version
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :schema-version "2.0"
                  :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                            :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :unsupported-schema-version (:halt-reason r)))))

(deftest test-validation-missing-schema-version
  (let [r (sew/replay-with-sew-protocol
           (assoc (sb/sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                                   :params {:token "0xUSDC" :to "0xBob" :amount 5000}}])
                  :schema-version nil))]
    (is (= :invalid (:outcome r)))))

(deftest test-validation-non-contiguous-seq
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                            :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                           {:seq 2 :time 1001 :agent "alice" :action "release"  ; gap at 1
                            :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :non-contiguous-event-seq (:halt-reason r)))))

(deftest test-validation-duplicate-seq
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                            :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                           {:seq 0 :time 1001 :agent "alice" :action "release"  ; dup
                            :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))))

(deftest test-validation-non-monotonic-time
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events [{:seq 0 :time 1005 :agent "alice" :action "create_escrow"
                            :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                           {:seq 1 :time 1000 :agent "alice" :action "release"  ; backward
                            :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :non-monotonic-event-time (:halt-reason r)))))

(deftest test-validation-event-time-before-initial
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :init-time 2000
                  :events [{:seq 0 :time 999 :agent "alice" :action "create_escrow"
                            :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :event-time-before-initial (:halt-reason r)))))

(deftest test-validation-duplicate-agent-ids
  (let [alice2 {:id "alice" :type "attacker" :address "0xEvil"}  ; same id, different address
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice alice2 bob resolver]
                  :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                            :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :duplicate-agent-ids (:halt-reason r)))))

(deftest test-validation-duplicate-agent-addresses
  (let [alice2 {:id "alice2" :type "attacker" :address "0xAlice"}  ; same address, different id
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice alice2 bob resolver]
                  :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                            :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :duplicate-agent-addresses (:halt-reason r)))))

(deftest test-strict-expected-errors-enforced-and-trace-annotated
  (let [r (sew/replay-with-sew-protocol
           (assoc
            (sb/sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                             :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                         ;; invalid workflow-id -> deterministic reject, then valid close path
                            {:seq 1 :time 1001 :agent "alice" :action "release"
                             :params {:workflow-id 999}}
                            {:seq 2 :time 1002 :agent "alice" :action "release"
                             :params {:workflow-id 0}}])
            :strict-expected-errors? true
            :expected-errors [{:seq 1 :action "release" :error :invalid-workflow-id}]))]
    (is (= :pass (:outcome r)))
    (is (= {:ok? true
            :matched [{:seq 1 :action "release" :error :invalid-workflow-id}]
            :missing []
            :unexpected []}
           (:expected-error-analysis r)))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :invalid-workflow-id (get-in r [:trace 1 :reject-class])))
    (is (= :dispatch (get-in r [:trace 1 :reject-phase])))
    (is (true? (get-in r [:trace 1 :expected-failure?])))))

;; ---------------------------------------------------------------------------
;; Section 2: Time regression in process-step
;; ---------------------------------------------------------------------------

(deftest test-time-regression-is-rejected-not-halted
  ;; Build scenario with valid structure but inject a backward-time event via
  ;; process-step directly (bypasses replay-scenario validation for testing the
  ;; step-level guard independently)
  (let [world0  (t/empty-world 2000)
        context {:agent-index {"alice" alice "resolver" resolver}
                 :snapshot    (snap-fix/escrow-snapshot {:escrow-fee-bps 50})}
        event   {:seq 0 :time 999 :agent "alice" :action "set-paused" :params {:paused? true}}
        result  (replay/process-step sew/protocol context world0 event)]
    (testing "rejected, not halted"
      (is (= :rejected (get-in result [:trace-entry :result])))
      (is (false? (:halted? result))))
    (testing "world unchanged"
      (is (= 2000 (:block-time (:world result)))))))

;; ---------------------------------------------------------------------------
;; Section 3: Happy path
;; ---------------------------------------------------------------------------

(deftest test-happy-path-release
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 10000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (:events-processed r)))
    (is (= 1 (get-in r [:metrics :total-escrows])))
    (is (= 10000 (get-in r [:metrics :total-volume])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= 0 (get-in r [:trace 0 :extra :workflow-id])))))

(deftest test-compat-action-alias-underscore-vs-hyphen
  (let [base-events
        [{:seq 0 :time 1000 :agent "alice"
          :params {:token "0xUSDC" :to "0xBob" :amount 10000 :custom-resolver "0xResolver"}}
         {:seq 1 :time 1001 :agent "alice" :params {:workflow-id 0}}]
        r-underscore
        (sew/replay-with-sew-protocol
         (sb/sc :events [(assoc (nth base-events 0) :action "create_escrow")
                         (assoc (nth base-events 1) :action "release")]))
        r-hyphen
        (sew/replay-with-sew-protocol
         (sb/sc :events [(assoc (nth base-events 0) :action "create-escrow")
                         (assoc (nth base-events 1) :action "release")]))]
    (is (= :pass (:outcome r-underscore)))
    (is (= :pass (:outcome r-hyphen)))
    (is (= (mapv :result (:trace r-underscore))
           (mapv :result (:trace r-hyphen))))
    (is (= (get-in r-underscore [:trace 0 :transition/id])
           (get-in r-hyphen [:trace 0 :transition/id])))
    (is (= :scenario.transition/create_escrow
           (get-in r-underscore [:trace 0 :transition/id])))
    (is (= (get-in r-underscore [:trace 0 :extra :workflow-id])
           (get-in r-hyphen [:trace 0 :extra :workflow-id])))))

(deftest test-compat-workflow-id-alias-id-vs-workflow-id
  (let [create {:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                :params {:token "0xUSDC" :to "0xBob" :amount 10000 :custom-resolver "0xResolver"}}
        release-workflow-id {:seq 1 :time 1001 :agent "alice" :action "release"
                             :params {:workflow-id 0}}
        release-id          {:seq 1 :time 1001 :agent "alice" :action "release"
                             :params {:id 0}}
        r-workflow-id (sew/replay-with-sew-protocol (sb/sc :events [create release-workflow-id]))
        r-id          (sew/replay-with-sew-protocol (sb/sc :events [create release-id]))]
    (is (= :pass (:outcome r-workflow-id)))
    (is (= :pass (:outcome r-id)))
    (is (= (mapv :result (:trace r-workflow-id))
           (mapv :result (:trace r-id))))
    (is (= (get-in r-workflow-id [:trace 1 :error])
           (get-in r-id [:trace 1 :error])))))

(deftest test-temporal-evidence-wiring-opt-in
  (testing "temporal evidence is emitted only when :temporal-evidence {:enabled? true}"
    (let [calls (atom [])
          scenario-base
          (sb/sc :events
                 [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                   :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                  {:seq 1 :time 1001 :agent "alice" :action "release"
                   :params {:workflow-id 0}}])]
      (with-redefs [temporal/record-from-replay!
                    (fn [ds temporal-cfg scenario-id outcome world metrics trace]
                      (swap! calls conj {:ds ds
                                         :temporal-cfg temporal-cfg
                                         :scenario-id scenario-id
                                         :outcome outcome
                                         :world world
                                         :metrics metrics
                                         :trace trace})
                      {:ok true})]
        ;; disabled / absent => no call
        (sew/replay-with-sew-protocol scenario-base)
        (is (= 0 (count @calls)))

        ;; enabled => exactly one terminal emission
        (sew/replay-with-sew-protocol
         (assoc scenario-base
                :temporal-evidence {:enabled? true
                                    :datasource nil
                                    :run-id "wire-test-run"
                                    :batch-id :wire-batch
                                    :suite-id :wire-suite
                                    :git-sha "abc123"}))
        (is (= 1 (count @calls)))
        (is (= "wire-test-run" (get-in @calls [0 :temporal-cfg :run-id])))
        (is (= :pass (:outcome (first @calls))))))))

;; ---------------------------------------------------------------------------
;; Section 4: Dispute + resolution
;; ---------------------------------------------------------------------------

(deftest test-dispute-and-resolution
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 10000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1001 :agent "bob" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1005 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true}}]))]
    (is (= :pass (:outcome r)))
    (is (= 1 (get-in r [:metrics :disputes-triggered])))
    (is (= 1 (get-in r [:metrics :resolutions-executed])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (every? #(= :ok (:result %)) (:trace r)))))

(deftest test-trace-projection-includes-funds-ledger-summary
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 10000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}]))
        p (proto/trace-projection sew/protocol r)]
    (is (map? (:funds-ledger-summary p)))
    (is (contains? (:trace-summary p) :funds-conservation-holds?))
    (is (contains? (:trace-summary p) :funds-drift-total))
    (is (contains? (:trace-summary p) :funds-drift-by-token))))

;; ---------------------------------------------------------------------------
;; Section 5: Adversarial rejections — non-fatal
;; ---------------------------------------------------------------------------

(deftest test-attacker-unauthorized-resolution-is-revert
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob mallory resolver]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 10000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1001 :agent "alice" :action "raise_dispute"
                    :params {:workflow-id 0}}
                ;; Mallory is not the resolver
                   {:seq 2 :time 1002 :agent "mallory" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release false}}
                ;; Legit resolver succeeds
                   {:seq 3 :time 1003 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (= 1 (get-in r [:metrics :attack-attempts])))
    (is (= 0 (get-in r [:metrics :attack-successes])))))

(deftest test-dispute-after-release-rejected
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob mallory]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1002 :agent "mallory" :action "raise_dispute"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

;; ---------------------------------------------------------------------------
;; Section 5b: Attack-attempts false-positive regression tests
;; ---------------------------------------------------------------------------

(deftest test-attacker-benign-action-not-counted
  "A labeled attacker doing a benign action (create_escrow) must NOT
   increment attack-attempts or attack-successes."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob mallory resolver]
                  :events
                  [{:seq 0 :time 1000 :agent "mallory" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= 0 (get-in r [:metrics :attack-attempts]))
        "benign action by attacker must not count as attack attempt")
    (is (= 0 (get-in r [:metrics :attack-successes]))
        "benign action by attacker must not count as attack success")))

(deftest test-adversarial-dispute-by-attacker-counted
  "A labeled attacker raising a dispute that gets rejected must still
   increment attack-attempts."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob mallory]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}
                ;; Mallory tries dispute after release — genuinely adversarial action
                   {:seq 2 :time 1002 :agent "mallory" :action "raise_dispute"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= 1 (get-in r [:metrics :attack-attempts]))
        "adversarial-capable action by attacker must count as attack attempt")
    (is (= 0 (get-in r [:metrics :attack-successes]))
        "rejected adversarial action must not count as success")
    (is (= 1 (get-in r [:metrics :rejected-attacks])))))

(deftest test-explicitly-adversarial-event-always-counted
  "An event with :adversarial? true must always count as attack attempt,
   regardless of agent type or action."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 5000}
                    :adversarial? true}]))]
    (is (= :pass (:outcome r)))
    (is (= 1 (get-in r [:metrics :attack-attempts]))
        "explicitly adversarial event must count as attack attempt")
    (is (= 1 (get-in r [:metrics :attack-successes]))
        "accepted adversarial event must count as attack success")))

(deftest test-honest-agent-never-counted
  "An honest agent doing any action (even adversarial-capable) must NOT
   increment attack-attempts."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 5000
                             :custom-resolver "0xResolver"}}
                ;; Honest agent raises legitimate dispute
                   {:seq 1 :time 1001 :agent "alice" :action "raise_dispute"
                    :params {:workflow-id 0}}
                ;; Honest resolver executes resolution
                   {:seq 2 :time 1002 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true}}]))]
    (is (= :pass (:outcome r)))
    (is (= 0 (get-in r [:metrics :attack-attempts]))
        "honest agent actions must not count as attack attempts")))

(deftest test-unknown-agent-in-action-is-revert-not-halt
  ;; Unknown agent passes validation because it's not in the event list
  ;; at the scenario level but IS here (we're testing process-step directly)
  (let [world0  (t/empty-world 1000)
        context {:agent-index {"alice" alice}
                 :snapshot    (snap-fix/escrow-snapshot {:escrow-fee-bps 50})}
        event   {:seq 0 :time 1000 :agent "nobody" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
        result  (replay/process-step sew/protocol context world0 event)]
    (is (= :rejected (get-in result [:trace-entry :result])))
    (is (false? (:halted? result)))
    (is (= :unknown-agent (:error (:trace-entry result))))))

;; ---------------------------------------------------------------------------
;; Section 6: Overflow guard
;; ---------------------------------------------------------------------------

(deftest test-amount-overflow-guard
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
               ;; amount > max-safe-amount = 922337203685477
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 999999999999999999}}]))]
    (is (= :pass (:outcome r)))    ; outcome is pass — it's a clean revert
    (is (= :rejected (get-in r [:trace 0 :result])))
    (is (= :amount-out-of-safe-range (get-in r [:trace 0 :error])))))

;; ---------------------------------------------------------------------------
;; Section 7: Mutual cancel
;; ---------------------------------------------------------------------------

(deftest test-mutual-cancel
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                   {:seq 1 :time 1001 :agent "alice" :action "sender_cancel"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1002 :agent "bob" :action "recipient_cancel"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (every? #(= :ok (:result %)) (:trace r)))))

;; ---------------------------------------------------------------------------
;; Section 8: Auto-cancel after max dispute duration
;; ---------------------------------------------------------------------------

(deftest test-auto-cancel-after-timeout
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :params (assoc default-params :max-dispute-duration 500)
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 8000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1000 :agent "bob" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1501 :agent "alice" :action "auto_cancel_disputed"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 2 :result])))))

;; ---------------------------------------------------------------------------
;; Section 9: Appeal window
;; ---------------------------------------------------------------------------

(deftest test-appeal-window-deferred-settlement
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :params (assoc default-params :appeal-window-duration 100)
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 10000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1000 :agent "alice" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1000 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true}}
                ;; Before deadline (deadline = 1000 + 100 = 1100)
                   {:seq 3 :time 1050 :agent "bob" :action "execute_pending_settlement"
                    :params {:workflow-id 0}}
                ;; After deadline
                   {:seq 4 :time 1101 :agent "bob" :action "execute_pending_settlement"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 3 :result])))
    (is (= :ok (get-in r [:trace 4 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

;; ---------------------------------------------------------------------------
;; Section 10: Multiple concurrent escrows
;; ---------------------------------------------------------------------------

(deftest test-multiple-escrows-isolated
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 5000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 7000
                             :custom-resolver "0xResolver"}}
                   {:seq 2 :time 1001 :agent "alice" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 3 :time 1002 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release false}}
                   {:seq 4 :time 1003 :agent "alice" :action "release"
                    :params {:workflow-id 1}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (get-in r [:metrics :total-escrows])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

;; ---------------------------------------------------------------------------
;; Section 11: Solvency invariant — strict equality
;; ---------------------------------------------------------------------------

(deftest test-solvency-strict-equality-catches-overfunded-world
  ;; Directly construct a world where total-held > sum of live escrows
  ;; (simulates a finalization bug that forgot to call sub-held)
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0]
                            {:token "0xUSDC" :to "0xBob" :from "0xAlice"
                             :amount-after-fee 9000
                             :dispute-resolver nil
                             :auto-release-time 0 :auto-cancel-time 0
                             :escrow-state :released     ; terminal — not live
                             :sender-status :none :recipient-status :none})
                  ;; total-held still has 9000 — not decremented (the bug)
                  (assoc-in [:total-held "0xUSDC"] 9000))]
    (let [r (inv/check-all world)]
      (testing "strict equality catches orphaned held amount"
        ;; live-sum = 0 (escrow is terminal); held = 9000; 0 ≠ 9000 → violation
        (is (false? (:all-hold? r)))))))

(deftest test-solvency-strict-equality-passes-clean-world
  ;; Clean world: total-held exactly matches live escrow amount.
  ;; Built through the protocol command layer so held-adjustment history is
  ;; complete and the custody reconstruction invariants evaluate (pass), while
  ;; total-held strictly equals the live escrow sum.
  (let [ctx   (proto/build-execution-context
               sew/protocol
               [{:id "alice" :address "0xAlice" :type "honest"}
                {:id "bob"   :address "0xBob"   :type "honest"}]
               {:resolver-fee-bps 50})
        w0    (assoc-in (t/empty-world 1000) [:params :held-adjustments/complete?] true)
        s1    (replay/process-step sew/protocol ctx w0
                                   {:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                                    :params {:token "0xUSDC" :to "0xBob" :amount 5000}})]
    (let [r (inv/check-all (:world s1))]
      (is (= :ok (get-in s1 [:trace-entry :result])))
      (is (true? (:all-hold? r))))))

;; ---------------------------------------------------------------------------
;; Section 12: Terminal state irreversibility
;; ---------------------------------------------------------------------------

(deftest test-terminal-irreversibility-catches-regression
  ;; Simulate a world where an escrow regressed from :released to :pending
  (let [world-before (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :released))
        world-after  (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :pending))
        r            (inv/check-transition world-before world-after)]
    (is (false? (:all-hold? r)))
    (is (seq (get-in r [:results :terminal-states-unchanged :violations])))))

(deftest test-terminal-irreversibility-passes-valid-transition
  (let [world-before (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :pending))
        world-after  (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :disputed))
        r            (inv/check-transition world-before world-after)]
    (is (true? (:all-hold? r)))))

;; ---------------------------------------------------------------------------
;; Section 13: JSON serialization
;; ---------------------------------------------------------------------------

(deftest test-json-serialization
  (let [r    (sew/replay-with-sew-protocol
              (sb/sc :events
                     [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                       :params {:token "0xUSDC" :to "0xBob" :amount 500}}]))
        json (replay/result->json-str r)]
    (is (string? json))
    (is (clojure.string/includes? json "pass"))))

(deftest test-replay-idempotent-same-trace-helper
  (let [scenario (sb/sc :events
                        [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000
                                   :custom-resolver "0xResolver"}}
                         {:seq 1 :time 1001 :agent "alice" :action "release"
                          :params {:workflow-id 0}}])
        result (replay/replay-idempotent-same-trace? sew/protocol scenario)]
    (is (true? (:idempotent? result)))
    (is (= :pass (get-in result [:first :outcome])))
    (is (= :pass (get-in result [:second :outcome])))))

;; ---------------------------------------------------------------------------
;; Section 14: simulated time from event :time only
;; ---------------------------------------------------------------------------

(deftest test-block-time-follows-event-timestamps
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 1000}}
                   {:seq 1 :time 2000 :agent "alice" :action "sender_cancel"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2000 (get-in r [:trace 1 :world :block-time])))))

;; ---------------------------------------------------------------------------
;; Section 15: Full escalation chain 0 → 1 → 2
;;
;; Uses process-step directly with an escalation-fn in context.
;; Priority-3 authority: no custom-resolver; et.dispute-resolver tracks rounds.
;;
;; Setup:
;;   appeal-window-duration = 500 → execute-resolution defers verdict
;;   Level 0 → Level 1 → Level 2 (final)
;;   At level 2 execute-resolution is IMMEDIATE (bypasses appeal window)
;; ---------------------------------------------------------------------------

(def ^:private res-level-0 "0xResolver0")
(def ^:private res-level-1 "0xResolver1")
(def ^:private res-level-2 "0xResolver2")

(def ^:private appeal-params
  ;; Keep appeal window longer than the 1-day escalation cooldown so
  ;; multi-round escalation tests can validly appeal after cooldown.
  {:resolver-fee-bps 50 :appeal-window-duration 200000
   :max-dispute-duration 2592000 :appeal-bond-protocol-fee-bps 0})

(defn- make-step-context
  "Build a context with appeal-params snapshot and the cycling escalation-fn."
  []
  (let [agents [{:id "alice"    :address "0xAlice"    :type "honest"}
                {:id "bob"      :address "0xBob"      :type "honest"}
                {:id "resolver0" :address res-level-0  :type "resolver"}
                {:id "resolver1" :address res-level-1  :type "resolver"}
                {:id "resolver2" :address res-level-2  :type "resolver"}]]
    (proto/build-execution-context sew/protocol agents
                                   (assoc appeal-params :escalation-resolvers {:1 res-level-1 :2 res-level-2}))))

(defn- initial-disputed-world
  "Build a world with one :disputed escrow whose et.dispute-resolver = res-level-0.
   Uses process-step to drive create_escrow + raise_dispute so invariants hold.
   Declares held-adjustment completeness: the world is driven only through the
   protocol command layer from t/empty-world, so custody begins at zero, every
   held mutation is logged, and the reconstruction invariants evaluate (rather
   than reporting :not-evaluated-incomplete-history)."
  [context]
  (let [w0 (assoc-in (t/empty-world 1000) [:params :held-adjustments/complete?] true)
        s1 (replay/process-step sew/protocol context w0
                                {:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                                 :params {:token "0xUSDC" :to "0xBob" :amount 10000}})
        ;; Set Priority-3 resolver (no custom-resolver in create, so we patch directly)
        w1 (assoc-in (:world s1) [:escrow-transfers 0 :dispute-resolver] res-level-0)
        s2 (replay/process-step sew/protocol context w1
                                {:seq 1 :time 1000 :agent "alice" :action "raise_dispute"
                                 :params {:workflow-id 0}})]
    (:world s2)))

(deftest test-full-escalation-chain-0-to-2
  "Full chain: execute-resolution(deferred@0) → escalate → execute-resolution(deferred@1)
               → escalate → execute-resolution(immediate@2=final)."
  (let [ctx  (make-step-context)
        w    (initial-disputed-world ctx)
        ;; Level 0: resolver0 submits verdict → deferred (appeal window = 500)
        s1   (replay/process-step sew/protocol ctx w
                                  {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})
        _    (testing "level-0 verdict deferred"
               (is (= :ok (get-in s1 [:trace-entry :result])))
               (is (:exists (t/get-pending (:world s1) 0))))
        ;; Escalate 0→1 — each escalation must be in a distinct block AND respect cooldown
        s2   (replay/process-step sew/protocol ctx (:world s1)
                                  {:seq 3 :time 90001 :agent "alice" :action "escalate_dispute"
                                   :params {:workflow-id 0}})
        _    (testing "escalation 0→1"
               (is (= :ok (get-in s2 [:trace-entry :result])))
               (is (= 1 (t/dispute-level (:world s2) 0)))
               (is (= res-level-1 (get-in (:world s2) [:escrow-transfers 0 :dispute-resolver])))
               (is (nil? (get-in (:world s2) [:pending-settlements 0]))
                   "pending cleared by escalation"))
        ;; Level 1: resolver1 submits verdict → still deferred (not yet final round)
        s3   (replay/process-step sew/protocol ctx (:world s2)
                                  {:seq 4 :time 90001 :agent "resolver1" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})
        _    (testing "level-1 verdict deferred"
               (is (= :ok (get-in s3 [:trace-entry :result])))
               (is (:exists (t/get-pending (:world s3) 0))))
        ;; Escalate 1→2 — must respect cooldown vs alice's previous escalation
        s4   (replay/process-step sew/protocol ctx (:world s3)
                                  {:seq 5 :time 180002 :agent "alice" :action "escalate_dispute"
                                   :params {:workflow-id 0}})
        _    (testing "escalation 1→2"
               (is (= :ok (get-in s4 [:trace-entry :result])))
               (is (= 2 (t/dispute-level (:world s4) 0)))
               (is (= res-level-2 (get-in (:world s4) [:escrow-transfers 0 :dispute-resolver])))
               (is (nil? (get-in (:world s4) [:pending-settlements 0]))))
        ;; Level 2 (final round): resolver2 submits verdict → IMMEDIATE (no pending)
        s5   (replay/process-step sew/protocol ctx (:world s4)
                                  {:seq 6 :time 180002 :agent "resolver2" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})]
    (testing "final-round verdict is immediate"
      (is (= :ok (get-in s5 [:trace-entry :result])))
      (is (= :released (t/escrow-state (:world s5) 0)))
      (is (not (:exists (t/get-pending (:world s5) 0)))))
    (testing "invariants hold throughout"
      (is (true? (:all-hold? (inv/check-all (:world s5))))))))

;; ---------------------------------------------------------------------------
;; Section 16: Mid-pending escalation
;;
;; Pending settlement at level 0 → escalation clears it → stale execute-pending
;; is rejected → new resolver at level 1 proceeds.
;; ---------------------------------------------------------------------------

(deftest test-escalation-clears-pending-settlement
  "Escalation clears a pending settlement; stale execute-pending-settlement is rejected."
  (let [ctx (make-step-context)
        w   (initial-disputed-world ctx)
        ;; Submit verdict at level 0 → deferred
        s1  (replay/process-step sew/protocol ctx w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release false}})
        _   (is (:exists (t/get-pending (:world s1) 0)) "pending exists before escalation")
        ;; Escalate 0→1 — clears pending (must jump 1 day for cooldown)
        s2  (replay/process-step sew/protocol ctx (:world s1)
                                 {:seq 3 :time 90001 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (testing "pending cleared"
              (is (= :ok (get-in s2 [:trace-entry :result])))
              (is (nil? (get-in (:world s2) [:pending-settlements 0]))))
        ;; Try to execute-pending-settlement after escalation → rejected
        s3  (replay/process-step sew/protocol ctx (:world s2)
                                 {:seq 4 :time 90501 :agent "bob" :action "execute_pending_settlement"
                                  :params {:workflow-id 0}})
        _   (testing "stale execute-pending rejected"
              (is (= :rejected (get-in s3 [:trace-entry :result]))))
        ;; New resolver (level 1) successfully resolves
        s4  (replay/process-step sew/protocol ctx (:world s3)
                                 {:seq 5 :time 90501 :agent "resolver1" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release false}})]
    (testing "level-1 resolver succeeds"
      (is (= :ok (get-in s4 [:trace-entry :result]))))
    (testing "invariants hold"
      (is (true? (:all-hold? (inv/check-all (:world s4))))))))

;; ---------------------------------------------------------------------------
;; Section 17: Adversarial escalation rejections
;;
;; a. Non-participant tries to escalate → :not-participant (rejected, not halt)
;; b. After reaching max level, third escalation attempt → :escalation-not-allowed
;; ---------------------------------------------------------------------------

(deftest test-non-participant-escalation-rejected
  "Attacker (not :from or :to) cannot escalate a dispute."
  (let [ctx-with-mallory
        (let [agents [{:id "alice"    :address "0xAlice"    :type "honest"}
                      {:id "bob"      :address "0xBob"      :type "honest"}
                      {:id "mallory"  :address "0xMallory"  :type "attacker"}
                      {:id "resolver0" :address res-level-0 :type "resolver"}
                      {:id "resolver1" :address res-level-1 :type "resolver"}]]
          (proto/build-execution-context sew/protocol agents
                                         (assoc appeal-params :escalation-resolvers {:1 res-level-1})))
        w   (initial-disputed-world ctx-with-mallory)
        ;; Submit verdict → deferred
        s1  (replay/process-step sew/protocol ctx-with-mallory w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        ;; Mallory (not a participant) tries to escalate
        s2  (replay/process-step sew/protocol ctx-with-mallory (:world s1)
                                 {:seq 3 :time 1000 :agent "mallory" :action "escalate_dispute"
                                  :params {:workflow-id 0}})]
    (testing "non-participant escalation rejected"
      (is (= :rejected (get-in s2 [:trace-entry :result])))
      (is (= :not-participant (get-in s2 [:trace-entry :error]))))
    (testing "world unchanged (level still 0)"
      (is (= 0 (t/dispute-level (:world s2) 0))))
    (testing "no halt"
      (is (false? (:halted? s2))))))

(deftest test-max-level-escalation-rejected
  "After two escalations (level = max-dispute-level), a third attempt is rejected."
  (let [ctx (make-step-context)
        w   (initial-disputed-world ctx)
        ;; Level 0: submit → deferred → escalate 0→1
        s1  (replay/process-step sew/protocol ctx w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        s2  (replay/process-step sew/protocol ctx (:world s1)
                                 {:seq 3 :time 90001 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (is (= 1 (t/dispute-level (:world s2) 0)))
        ;; Level 1: submit → deferred → escalate 1→2
        s3  (replay/process-step sew/protocol ctx (:world s2)
                                 {:seq 4 :time 90001 :agent "resolver1" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        s4  (replay/process-step sew/protocol ctx (:world s3)
                                 {:seq 5 :time 180002 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (testing "second escalation ok"
              (is (= :ok (get-in s4 [:trace-entry :result])))
              (is (= 2 (t/dispute-level (:world s4) 0))))
        ;; Level 2 is final: execute-resolution is immediate (no pending created)
        s5  (replay/process-step sew/protocol ctx (:world s4)
                                 {:seq 6 :time 180002 :agent "resolver2" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        _   (testing "final-round verdict is immediate"
              (is (= :ok (get-in s5 [:trace-entry :result])))
              (is (= :released (t/escrow-state (:world s5) 0))))
        ;; After finalization, a third escalation is impossible (not :disputed)
        s6  (replay/process-step sew/protocol ctx (:world s5)
                                 {:seq 7 :time 180002 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})]
    (testing "escalation rejected after finalization"
      (is (= :rejected (get-in s6 [:trace-entry :result]))))
    (testing "no halt — just a revert"
      (is (false? (:halted? s6))))
    (testing "invariants hold throughout"
      (is (true? (:all-hold? (inv/check-all (:world s6))))))))

;; ---------------------------------------------------------------------------
;; Section 18: Workflow-id sequential integer IDs
;; ---------------------------------------------------------------------------
;; Sew assigns workflow IDs as sequential integers (0, 1, 2, ...).
;; Use direct integers in scenario events — no aliasing required.

(deftest test-wf-id-first-escrow-is-zero
  "First created escrow gets workflow-id 0; subsequent events use integer 0 directly."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 5000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (:events-processed r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))))

(deftest test-wf-id-multi-escrow-sequential
  "Two creates produce IDs 0 and 1; operations targeting each work independently."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 4000}}
                   {:seq 2 :time 1002 :agent "alice" :action "release"
                    :params {:workflow-id 1}}
                   {:seq 3 :time 1003 :agent "alice" :action "release"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 4 (:events-processed r)))
    (is (= 2 (get-in r [:metrics :total-escrows])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

(deftest test-wf-id-nonexistent-returns-rejected
  "A non-existent integer workflow-id (999) causes the action to be :rejected.
   The scenario itself still :pass — the kernel doesn't halt on a rejected action."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 999}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 1 :result])))))

(deftest test-wf-id-integer-zero-passes-through
  "Integer workflow-id 0 is accepted as-is without any transformation."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))))

;; ---------------------------------------------------------------------------
;; Section 19b: withdraw_stake behavior
;; ---------------------------------------------------------------------------

(deftest test-withdraw-stake-blocked-during-active-dispute
  (let [r (sew/replay-with-sew-protocol
           (assoc (sb/sc :events
                         [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                           :params {:amount 5000}}
                          {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                           :params {:token "0xUSDC" :to "0xBob" :amount 3000
                                    :custom-resolver "0xResolver"}}
                          {:seq 2 :time 1001 :agent "bob" :action "raise_dispute"
                           :params {:workflow-id 0}}
                          {:seq 3 :time 1002 :agent "resolver" :action "withdraw_stake"
                           :params {:amount 1000}}])
                  :allow-open-disputes? true))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 3 :result])))
    (is (= :active-disputes-block-withdrawal (get-in r [:trace 3 :error])))))

(deftest test-withdraw-stake-succeeds-after-dispute-resolution
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :params (assoc default-params :appeal-window-duration 0)
                  :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                    :params {:amount 5000}}
                   {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000
                             :custom-resolver "0xResolver"}}
                   {:seq 2 :time 1001 :agent "bob" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 3 :time 1002 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true}}
                   {:seq 4 :time 1003 :agent "resolver" :action "withdraw_stake"
                    :params {:amount 1000}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 4 :result])))))

(deftest test-withdraw-stake-invalid-amount-nil-rejected
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                    :params {:amount 5000}}
                   {:seq 1 :time 1001 :agent "resolver" :action "withdraw_stake"
                    :params {}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :invalid-amount (get-in r [:trace 1 :error])))))

(deftest test-withdraw-stake-invalid-amount-zero-rejected
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                    :params {:amount 5000}}
                   {:seq 1 :time 1001 :agent "resolver" :action "withdraw_stake"
                    :params {:amount 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :invalid-amount (get-in r [:trace 1 :error])))))

(deftest test-withdraw-stake-invalid-amount-negative-rejected
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                    :params {:amount 5000}}
                   {:seq 1 :time 1001 :agent "resolver" :action "withdraw_stake"
                    :params {:amount -1}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :invalid-amount (get-in r [:trace 1 :error])))))

(deftest test-withdraw-stake-pending-slash-boundary-allows-withdraw
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :params (assoc (assoc default-params :governance-mode :legacy)
                                 :appeal-window-duration 100
                                 :appeal-bond-amount 50)
                  :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                    :params {:amount 5000}}
                   {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000 :custom-resolver "0xResolver"}}
                   {:seq 2 :time 1060 :agent "alice" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                   {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                    :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                   {:seq 5 :time 1230 :agent "alice" :action "execute_pending_settlement"
                    :params {:workflow-id 0}}
                ;; current=5000, pending-slash=500, amount=4000 => current-amount=1000 (allowed)
                   {:seq 6 :time 1231 :agent "resolver" :action "withdraw_stake"
                     :params {:amount 4000}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 6 :result])))))

(deftest test-withdraw-stake-pending-slash-boundary-blocks-withdraw
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :params (assoc (assoc default-params :governance-mode :legacy)
                                 :appeal-window-duration 100
                                 :appeal-bond-amount 50)
                  :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                    :params {:amount 5000}}
                   {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000 :custom-resolver "0xResolver"}}
                   {:seq 2 :time 1060 :agent "alice" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                   {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                    :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 1000}}
                   {:seq 5 :time 1230 :agent "alice" :action "execute_pending_settlement"
                    :params {:workflow-id 0}}
                ;; current=5000, pending-slash=1000, amount=4001 => current-amount=999 (blocked)
                   {:seq 6 :time 1231 :agent "resolver" :action "withdraw_stake"
                    :params {:amount 4001}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 6 :result])))
    (is (= :pending-slash-blocks-withdrawal (get-in r [:trace 6 :error])))))

(deftest test-withdraw-stake-blocked-while-resolver-frozen
  "Mirrors S40: keeper settles at 1241, governance executes slash at 1255 (72h freeze)."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        keeper {:id "keeper" :type "keeper" :address "0xKeeper"}
        slash-at 1255
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov keeper]
                  :params (assoc (assoc default-params :governance-mode :legacy) :appeal-window-duration 120)
                  :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                    :params {:amount 10000}}
                   {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "USDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                   {:seq 2 :time 1060 :agent "alice" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                   {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                    :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                   {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement"
                    :params {:workflow-id 0}}
                   {:seq 6 :time slash-at :agent "gov" :action "execute_fraud_slash"
                    :params {:workflow-id 0}}
                   {:seq 7 :time (+ slash-at 100) :agent "resolver" :action "withdraw_stake"
                    :params {:amount 100}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 6 :result])))
    (is (= :rejected (get-in r [:trace 7 :result])))
    (is (= :resolver-frozen (get-in r [:trace 7 :error])))))

(deftest test-withdraw-stake-allows-at-unfreeze-boundary
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        keeper {:id "keeper" :type "keeper" :address "0xKeeper"}
        slash-at 1255
        freeze-until (+ slash-at (* 3 time-ctx/seconds-per-day))
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov keeper]
                  :params (assoc (assoc default-params :governance-mode :legacy) :appeal-window-duration 120)
                  :events
                  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                    :params {:amount 10000}}
                   {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "USDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                   {:seq 2 :time 1060 :agent "alice" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                   {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                    :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                   {:seq 5 :time 1241 :agent "keeper" :action "execute_pending_settlement"
                    :params {:workflow-id 0}}
                   {:seq 6 :time slash-at :agent "gov" :action "execute_fraud_slash"
                    :params {:workflow-id 0}}
                   {:seq 7 :time freeze-until :agent "resolver" :action "withdraw_stake"
                    :params {:amount 100}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 6 :result])))
    (is (= :ok (get-in r [:trace 7 :result])))))

(deftest test-withdraw-escrow-liquidity-crunch-ordering-toggle-then-withdraw
  "Same timestamp ordering: if liquidity crunch is enabled first,
   withdraw_escrow should be rejected as liquidity-insufficient."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1002 :agent "gov" :action "set_token_liquidity_crunch"
                    :params {:token "0xUSDC" :active? true}}
                   {:seq 3 :time 1002 :agent "bob" :action "withdraw_escrow"
                    :params {:workflow-id 0}}] :params sb/legacy-default-params))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :rejected (get-in r [:trace 3 :result])))
    (is (= :liquidity-insufficient (get-in r [:trace 3 :error])))))

(deftest test-withdraw-escrow-liquidity-crunch-ordering-withdraw-then-toggle
  "Same timestamp ordering: if withdraw_escrow executes before liquidity crunch
   is enabled, withdrawal should succeed."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1002 :agent "bob" :action "withdraw_escrow"
                    :params {:workflow-id 0}}
                   {:seq 3 :time 1002 :agent "gov" :action "set_token_liquidity_crunch"
                    :params {:token "0xUSDC" :active? true}}] :params sb/legacy-default-params))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

(deftest test-withdraw-fees-liquidity-crunch-ordering-toggle-then-withdraw
  "Same timestamp ordering: if liquidity crunch is enabled first,
   withdraw_fees should be rejected as liquidity-insufficient."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "gov" :action "set_token_liquidity_crunch"
                    :params {:token "0xUSDC" :active? true}}
                   {:seq 2 :time 1001 :agent "gov" :action "withdraw_fees"
                    :params {:token "0xUSDC"}}] :params sb/legacy-default-params))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= :liquidity-insufficient (get-in r [:trace 2 :error])))))

(deftest test-withdraw-fees-liquidity-crunch-ordering-withdraw-then-toggle
  "Same timestamp ordering: if withdraw_fees executes before liquidity crunch
   is enabled, fee withdrawal should succeed."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "gov" :action "withdraw_fees"
                    :params {:token "0xUSDC"}}
                   {:seq 2 :time 1001 :agent "gov" :action "set_token_liquidity_crunch"
                    :params {:token "0xUSDC" :active? true}}] :params sb/legacy-default-params))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))))

(deftest test-withdraw-fees-non-governance-rejected
  "Security: only governance may withdraw protocol fees."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                ;; alice is not governance; fee withdrawal must be rejected
                   {:seq 1 :time 1001 :agent "alice" :action "withdraw_fees"
                    :params {:token "0xUSDC"}}
                ;; governance path remains valid
                   {:seq 2 :time 1002 :agent "gov" :action "withdraw_fees"
                    :params {:token "0xUSDC"}}] :params sb/legacy-default-params))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :rejected (get-in r [:trace 1 :result])))
    (is (= :not-governance (get-in r [:trace 1 :error])))
    (is (= :ok (get-in r [:trace 2 :result])))))

(deftest test-mixed-withdraw-ordering-same-timestamp-escrow-then-fees
  "With both withdrawals in same block/time after release:
   withdraw_escrow first then withdraw_fees should both succeed."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1002 :agent "bob" :action "withdraw_escrow"
                    :params {:workflow-id 0}}
                   {:seq 3 :time 1002 :agent "gov" :action "withdraw_fees"
                    :params {:token "0xUSDC"}}] :params sb/legacy-default-params))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

(deftest test-mixed-withdraw-ordering-same-timestamp-fees-then-escrow
  "With both withdrawals in same block/time after release:
   withdraw_fees first then withdraw_escrow should both succeed."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [alice bob resolver gov]
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                   {:seq 1 :time 1001 :agent "alice" :action "release"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1002 :agent "gov" :action "withdraw_fees"
                    :params {:token "0xUSDC"}}
                   {:seq 3 :time 1002 :agent "bob" :action "withdraw_escrow"
                    :params {:workflow-id 0}}] :params sb/legacy-default-params))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

;; ---------------------------------------------------------------------------
;; Section 20: Invariant-violation metric tracking
;;
;; Tests that accum-metrics correctly:
;;   a. Increments :invariant-violations for every failing invariant
;;   b. Records ONLY the failing invariants in :invariant-results
;;   c. Ignores passing invariants (:holds? true)
;;   d. Auto-cancel-after-timeout: full replay path where invariants hold
;; ---------------------------------------------------------------------------

(deftest test-accum-metrics-invariant-tracking
  "accum-metrics increments :invariant-violations and records :invariant-results
   only for entries where :holds? is false."
  (let [accum-fn #'replay/accum-metrics
        base-metrics {:total-escrows 0 :total-volume 0 :disputes-triggered 0
                      :resolutions-executed 0 :pending-settlements-executed 0
                      :attack-attempts 0 :attack-successes 0 :rejected-attacks 0
                      :reverts 0 :invariant-violations 0 :invariant-results {}
                      :double-settlements 0 :invalid-state-transitions 0 :funds-lost 0}
        event       {:seq 0 :time 1000 :agent "alice" :action "release" :params {:workflow-id 0}}
        ;; Synthetic violations: conservation-of-funds fails, solvency passes
        trace-entry {:result :ok
                     :world  {:total-held {} :block-time 1000}
                     :violations {:conservation-of-funds {:holds? false :violations ["held mismatch"]}
                                  :solvency              {:holds? true  :violations []}}}
        agent-index {}
        world-before {:total-held {} :block-time 1000}]
    (testing "violations map triggers increment"
      (let [m (accum-fn sew/protocol base-metrics event trace-entry agent-index world-before)]
        (is (= 1 (:invariant-violations m)))
        (is (= {:conservation-of-funds :fail} (:invariant-results m)))
        (is (not (contains? (:invariant-results m) :solvency))
            "passing invariant must NOT appear in :invariant-results")))))

(deftest test-accum-metrics-multiple-violations
  "Multiple failing invariants in one step each appear in :invariant-results."
  (let [accum-fn #'replay/accum-metrics
        base-metrics {:total-escrows 0 :total-volume 0 :disputes-triggered 0
                      :resolutions-executed 0 :pending-settlements-executed 0
                      :attack-attempts 0 :attack-successes 0 :rejected-attacks 0
                      :reverts 0 :invariant-violations 0 :invariant-results {}
                      :double-settlements 0 :invalid-state-transitions 0 :funds-lost 0}
        event       {:seq 0 :time 1000 :agent "alice" :action "release" :params {:workflow-id 0}}
        trace-entry {:result :ok
                     :world  {:total-held {} :block-time 1000}
                     :violations {:conservation-of-funds {:holds? false :violations ["mismatch"]}
                                  :no-negative-balances  {:holds? false :violations ["negative"]}
                                  :solvency              {:holds? true  :violations []}}}
        m (accum-fn sew/protocol base-metrics event trace-entry {} {:total-held {} :block-time 1000})]
    (is (= 1 (:invariant-violations m))
        "aggregate counter increments once per step regardless of violation count")
    (is (= {:conservation-of-funds :fail :no-negative-balances :fail}
           (:invariant-results m)))
    (is (not (contains? (:invariant-results m) :solvency)))))

(deftest test-accum-metrics-no-violations-unchanged
  "When :violations is nil (normal step), :invariant-violations stays zero."
  (let [accum-fn #'replay/accum-metrics
        base-metrics {:total-escrows 0 :total-volume 0 :disputes-triggered 0
                      :resolutions-executed 0 :pending-settlements-executed 0
                      :attack-attempts 0 :attack-successes 0 :rejected-attacks 0
                      :reverts 0 :invariant-violations 0 :invariant-results {}
                      :double-settlements 0 :invalid-state-transitions 0 :funds-lost 0}
        event       {:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                     :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
        trace-entry {:result :ok :world {:total-held {} :block-time 1000} :violations nil}
        m (accum-fn sew/protocol base-metrics event trace-entry {} {:total-held {} :block-time 1000})]
    (is (= 0 (:invariant-violations m)))
    (is (= {} (:invariant-results m)))))

(deftest test-auto-cancel-after-timeout
  "auto_cancel_disputed succeeds when block-time > dispute-timestamp + max-dispute-duration."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :params (assoc default-params :max-dispute-duration 500)
                  :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                    :params {:token "0xUSDC" :to "0xBob" :amount 8000
                             :custom-resolver "0xResolver"}}
                   {:seq 1 :time 1000 :agent "bob" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1501 :agent "alice" :action "auto_cancel_disputed"
                    :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

(deftest test-replay-appeal-bond-custody-lifecycle
  "Replay-level parity check for appeal bond custody:
   - appeal holds custody/bond
   - upheld appeal refunds bond to resolver claimable
   - rejected appeal forfeits bond to insurance accounting"
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        keeper {:id "keeper" :type "keeper" :address "0xKeeper"}
        r-upheld
        (sew/replay-with-sew-protocol
         (sb/sc :agents [alice bob resolver gov]
                        :params (assoc (assoc default-params :governance-mode :legacy)
                                       :appeal-window-duration 120
                                       :appeal-bond-amount 70)
                        :events
                        [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                          :params {:amount 10000}}
                         {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "USDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                         {:seq 2 :time 1060 :agent "alice" :action "raise_dispute"
                          :params {:workflow-id 0}}
                          {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                           :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                          {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                           :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                          {:seq 5 :time 1140 :agent "gov" :action "appeal_slash"
                           :params {:workflow-id 0}}
                          {:seq 6 :time 1160 :agent "gov" :action "resolve_appeal"
                           :params {:workflow-id 0 :upheld? true}}
                          {:seq 7 :time 1255 :agent "gov" :action "execute_fraud_slash"
                           :params {:workflow-id 0}}]
                         :allow-open-disputes? true))
        r-rejected
         (sew/replay-with-sew-protocol
          (sb/sc :agents [alice bob resolver gov keeper]
                 :params (assoc (assoc default-params :governance-mode :legacy)
                                :appeal-window-duration 120
                                :appeal-bond-amount 80)
                 :events
                 [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                   :params {:amount 10000}}
                  {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                   :params {:token "USDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                  {:seq 2 :time 1060 :agent "alice" :action "raise_dispute"
                   :params {:workflow-id 0}}
                  {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                   :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                  {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                   :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                  {:seq 5 :time 1140 :agent "gov" :action "appeal_slash"
                   :params {:workflow-id 0}}
                  {:seq 6 :time 1160 :agent "gov" :action "resolve_appeal"
                   :params {:workflow-id 0 :upheld? false}}
                  {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement"
                   :params {:workflow-id 0}}
                  {:seq 8 :time 1255 :agent "gov" :action "execute_fraud_slash"
                   :params {:workflow-id 0}}]))
        w-upheld   (get-in r-upheld [:trace 6 :world])
        w-rejected (get-in r-rejected [:trace 6 :world])
        assert-appeal-resolution-semantics
        (fn [world upheld?]
          (if upheld?
            (is (pos? (get-in world [:claimable-v2 0 :bond/refund "0xResolver"] 0))
                "upheld?=true should refund appeal bond to resolver :bond/refund claimable")
            (is (pos? (reduce + 0 (vals (get world :appeal-bond-distributions-by-token {}))))
                "upheld?=false should forfeit appeal bond to insurance bucket")))]
    (is (= :pass (:outcome r-upheld)))
    (is (= :pass (:outcome r-rejected)))
    (is (= :rejected (get-in r-upheld [:trace 7 :result])))
    (is (= :slash-already-reversed (get-in r-upheld [:trace 7 :error])))
    (assert-appeal-resolution-semantics w-upheld true)
    (assert-appeal-resolution-semantics w-rejected false)
    (is (= 70 (get-in w-upheld [:claimable-v2 0 :bond/refund "0xResolver"] 0)))
    (is (= 80 (get-in w-rejected [:appeal-bond-distributions-by-token :USDC] 0)))
    (is (= 0 (get-in w-rejected [:claimable-v2 0 :bond/refund "0xResolver"] 0)))))

(deftest test-replay-s35-profit-maximizer-governance-wins-appeal
  "Dedicated replay test mirroring invariant scenario S35 exactly.
   Path: pending -> appealed -> pending (upheld? false) -> executed after deadline."
  (let [gov {:id "gov" :type "governance" :address "0xGov"}
        keeper {:id "keeper" :type "keeper" :address "0xKeeper"}
        r   (sew/replay-with-sew-protocol
             (sb/sc :agents [alice bob resolver gov keeper]
                    :params (assoc (assoc default-params :governance-mode :legacy) :appeal-window-duration 120)
                    :events
                    [{:seq 0 :time 1000 :agent "resolver" :action "register_stake"
                      :params {:amount 10000}}
                     {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                      :params {:token "USDC" :to "0xBob" :amount 8000 :custom-resolver "0xResolver"}}
                     {:seq 2 :time 1060 :agent "alice" :action "raise_dispute"
                      :params {:workflow-id 0}}
                     {:seq 3 :time 1120 :agent "resolver" :action "execute_resolution"
                      :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                     {:seq 4 :time 1130 :agent "gov" :action "propose_fraud_slash"
                       :params {:workflow-id 0 :resolver-addr "0xResolver" :amount 500}}
                      {:seq 5 :time 1140 :agent "gov" :action "appeal_slash"
                       :params {:workflow-id 0}}
                      {:seq 6 :time 1160 :agent "gov" :action "resolve_appeal"
                       :params {:workflow-id 0 :upheld? false}}
                      {:seq 7 :time 1241 :agent "keeper" :action "execute_pending_settlement"
                       :params {:workflow-id 0}}
                      {:seq 8 :time 1255 :agent "gov" :action "execute_fraud_slash"
                       :params {:workflow-id 0}}]))
        w-appealed (get-in r [:trace 5 :world])
        w-resolved (get-in r [:trace 6 :world])
        w-slashed  (get-in r [:trace 8 :world])
        w-final    (get-in r [:trace 8 :world])]
    (is (= :pass (:outcome r)))
    ;; Snapshot-level proxy checks for appealed->rejected path.
    ;; In this S35 mirror, appeal-bond-amount is default 0, so no insurance
    ;; movement occurs at resolve_appeal; slash remains live until execution.
    (is (= 0 (get-in w-appealed [:claimable 0 "0xResolver"] 0)))
    (is (= 0 (get-in w-resolved [:claimable 0 "0xResolver"] 0)))
    (is (= 9500 (get-in w-slashed [:resolver-stakes "0xResolver"])))
    (is (= 500 (get-in w-slashed [:resolver-slash-total "0xResolver"])))
    (is (= :released (get-in w-final [:escrow-transfers 0 :escrow-state])))))

;; ---------------------------------------------------------------------------
;; Section 21: Pro-rata slash allocation action (non-governance user facing)
;; ---------------------------------------------------------------------------

(deftest test-compute-prorata-slash-allocation-non-governance
  (testing "non-governance agent can call compute-prorata-slash-allocation"
    (let [r (sew/replay-with-sew-protocol
             (sb/sc :agents [alice]
                    :events
                    [{:seq 0 :time 1000 :agent "alice"
                      :action "compute_prorata_slash_allocation"
                      :params {:slash-obligation 400
                               :liable-parties
                               [{:id "resolver-a" :slashable-stake 1000 :available-slashable 1000}
                                {:id "resolver-b" :slashable-stake 500  :available-slashable 60}
                                {:id "resolver-c" :slashable-stake 500  :available-slashable 500}]}}]))]
      (is (= :pass (:outcome r)))
      (is (= :ok (get-in r [:trace 0 :result])))
      (let [allocation (get-in r [:trace 0 :extra :allocation])]
        (is (some? allocation) "trace extra should contain allocation")
        (is (= 360 (:recovered-total allocation)))
        (is (= 40 (:unmet-total allocation)))
        (is (= 3 (count (:allocations allocation))))
        (is (= 200 (:paid (first (:allocations allocation))))))))

  (testing "compute-prorata-slash-allocation with zero-basis returns structured failure"
    (let [r (sew/replay-with-sew-protocol
             (sb/sc :agents [alice]
                    :events
                    [{:seq 0 :time 1000 :agent "alice"
                      :action "compute_prorata_slash_allocation"
                      :params {:slash-obligation 100
                               :liable-parties
                               [{:id "resolver-a" :slashable-stake 0 :available-slashable 0}]}}]))]
      (is (= :pass (:outcome r)))
      (is (= :ok (get-in r [:trace 0 :result])))
      (is (= :no-liable-basis (get-in r [:trace 0 :extra :allocation :status]))))))

;; ---------------------------------------------------------------------------
;; Section 22: Withdrawal race condition and reentrancy tests
;; ---------------------------------------------------------------------------

(deftest test-s53-reentrant-withdrawal-guard
  "S53: Only the intended recipient can withdraw after resolution.
   With is-release=true only the seller (recipient) has a claimable balance.
   Seller withdraw succeeds; buyer attempt fails with :no-claimable-balance."
  (let [p (assoc sb/default-params :appeal-window-duration 120)
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                           {:id "seller"   :address "0xseller"   :strategy "honest"}
                           {:id "resolver" :address "0xresolver" :role "resolver"}
                           {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
                  :params p
                  :init-time 1000
                  :events
                  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                    :params {:token "USDC" :to "0xseller" :amount 5000
                             :custom-resolver "0xresolver"}}
                   {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                   {:seq 3 :time 1300 :agent "keeper" :action "execute_pending_settlement"
                    :params {:workflow-id 0}}
                   {:seq 4 :time 1310 :agent "seller" :action "withdraw_escrow"
                    :params {:workflow-id 0}}
                   {:seq 5 :time 1310 :agent "buyer" :action "withdraw_escrow"
                    :params {:workflow-id 0}}])
           {:allow-dirty? true :skip-finalize true})]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 3 :result])))
    (is (= :ok (get-in r [:trace 4 :result])))
    (is (= :rejected (get-in r [:trace 5 :result])))
    (is (= :no-claimable-balance (get-in r [:trace 5 :error])))))

(deftest test-s67-reentrancy-callback
  "S67: execute-reentrant-withdraw sets reentrancy guard before callback.
   Callback attempt to withdraw-escrow is rejected with :reentrancy-guard-violated.
   World state is preserved (no funds lost)."
  (let [r (sew/replay-with-sew-protocol
           (sb/sc :agents [{:id "attacker" :address "0xattacker" :strategy "malicious"}
                           {:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                           {:id "resolver" :address "0xresolver" :role "resolver"}
                           {:id "seller"   :address "0xseller"   :strategy "honest"}]
                  :init-time 1000
                  :params (assoc sb/default-params :appeal-window-duration 259200)
                  :events
                  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                    :params {:token "USDC" :to "0xattacker" :amount 10000
                             :custom-resolver "0xresolver"}}
                    {:seq 1 :time 1060 :agent "buyer" :action "release"
                     :params {:workflow-id 0}}
                    {:seq 2 :time 1120 :agent "attacker" :action "execute_reentrant_withdraw"
                      :params {:workflow-id 0
                               :callback {:agent "attacker" :action "withdraw_escrow" :params {:workflow-id 0}}}}])
            {:allow-dirty? true :skip-finalize true})]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= :reentrancy-guard-violated (get-in r [:trace 2 :error])))
    (let [world-after (get-in r [:trace 2 :world])]
      (is (nil? (:reentrancy-guard world-after)) "guard must be cleared after event")
      (is (zero? (get-in world-after [:total-withdrawn "USDC"] 0)) "no funds withdrawn"))))

(deftest test-s86-double-settlement-guard
  "S86: After resolution with appeal window, execute_pending_settlement works once.
   Second call at the same timestamp is rejected (escrow no longer :disputed)."
  (let [p (assoc sb/default-params :appeal-window-duration 120)
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                           {:id "seller"   :address "0xseller"   :strategy "honest"}
                           {:id "resolver" :address "0xresolver" :role "resolver"}
                           {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
                  :params p
                  :init-time 1000
                  :events
                  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                    :params {:token "USDC" :to "0xseller" :amount 5000
                             :custom-resolver "0xresolver"}}
                   {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                   {:seq 3 :time 1300 :agent "keeper" :action "execute_pending_settlement"
                     :params {:workflow-id 0}}
                   {:seq 4 :time 1300 :agent "seller" :action "execute_pending_settlement"
                     :params {:workflow-id 0}}])
           {:allow-dirty? true :skip-finalize true})]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 3 :result])))
    (is (= :rejected (get-in r [:trace 4 :result])))))

(deftest test-settlement-withdrawal-same-timestamp-race
  "Settlement and withdrawal at same timestamp: settlement first creates
   claimable, then withdrawal consumes it. Both should succeed."
  (let [p (assoc sb/default-params :appeal-window-duration 120)
        r (sew/replay-with-sew-protocol
           (sb/sc :agents [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                           {:id "seller"   :address "0xseller"   :strategy "honest"}
                           {:id "resolver" :address "0xresolver" :role "resolver"}
                           {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
                  :params p
                  :init-time 1000
                  :events
                  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                    :params {:token "USDC" :to "0xseller" :amount 5000
                             :custom-resolver "0xresolver"}}
                   {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                    :params {:workflow-id 0}}
                   {:seq 2 :time 1120 :agent "resolver" :action "execute_resolution"
                    :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}}
                   {:seq 3 :time 1300 :agent "keeper" :action "execute_pending_settlement"
                    :params {:workflow-id 0}}
                   {:seq 4 :time 1300 :agent "seller" :action "withdraw_escrow"
                    :params {:workflow-id 0}}])
           {:allow-dirty? true :skip-finalize true})]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 3 :result])))
    (is (= :ok (get-in r [:trace 4 :result])))))
