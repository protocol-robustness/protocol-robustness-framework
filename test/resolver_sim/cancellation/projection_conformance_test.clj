(ns resolver-sim.cancellation.projection-conformance-test
  "P0-B: Differential conformance between Surface A (SEW protocol execution)
   and Surface B (sew_projection reconstruction).

   For each cancellation path, this test:
     1. Builds identical canonical initial conditions
     2. Executes the real Surface A transition
     3. Reconstructs effects/state via sew_projection
     4. Asserts equality: projected = executed

   This is NOT a test of sew_projection against itself — it tests projection
   against the authoritative SEW state machine."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.protocols.sew.types        :as t]
            [resolver-sim.protocols.sew.lifecycle    :as lc]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.registry     :as reg]
            [resolver-sim.protocols.sew.accounting   :as acct]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.cancellation.sew-projection :as proj]
            [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.cancellation.semantic :as semantic]))

;; ---------------------------------------------------------------------------
;; Test utilities
;; ---------------------------------------------------------------------------

(def alice "0xAlice")
(def bob   "0xBob")
(def carol "0xCarol")
(def usdc  :0xUSDC)

(def base-snapshot
  (snap-fix/escrow-snapshot
   {:escrow-fee-bps            50
    :default-auto-release-delay 0
    :default-auto-cancel-delay  0
    :max-dispute-duration       3600
    :appeal-window-duration     1800}))

(def base-settings
  (t/make-escrow-settings {}))

(defn- create-one
  "Create one escrow from alice -> bob, 1000 USDC."
  ([] (create-one {}))
  ([settings-overrides]
   (lc/create-escrow
    (t/empty-world 1000)
    alice usdc bob 1000
    (merge base-settings settings-overrides)
    base-snapshot)))

(defn- world-with-one-escrow []
  (:world (create-one)))

(defn- world-disputed []
  (-> (world-with-one-escrow)
      (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
      (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
      (assoc-in [:dispute-timestamps 0] 1000)))

(defn- world-disputed-with-auto-cancel-time
  ([] (world-disputed-with-auto-cancel-time 2000))
  ([auto-cancel-time]
   (-> (world-disputed)
       (assoc-in [:escrow-transfers 0 :auto-cancel-time] auto-cancel-time))))

(defn- set-block-time [world ts]
  (assoc world :block-time ts))

(defn- execute-and-project [world workflow-id path & [cancel-strategy]]
  "Execute Surface A path and project from the SAME initial world state.
   Returns [initial-world resulting-world projected-map]."
  (let [initial-world world
        ;; Execute Surface A
        exec-result (case path
                      :sender-cancel        (lc/sender-cancel initial-world workflow-id alice cancel-strategy)
                      :recipient-cancel     (lc/recipient-cancel initial-world workflow-id bob cancel-strategy)
                      :auto-cancel-disputed-escrow    (lc/auto-cancel-disputed-escrow initial-world workflow-id)
                      :auto-cancel-disputed-on-auto-time (lc/auto-cancel-disputed-on-auto-time initial-world workflow-id)
                      (throw (ex-info "unknown path" {:path path})))
        resulting-world (if (:ok exec-result) (:world exec-result) initial-world)
        ;; Project from INITIAL world state
        projected (proj/project initial-world workflow-id path)]
    [initial-world resulting-world projected exec-result]))

(defn- effect-kind [effects]
  (:effects/kind effects))

(defn- final? [effects]
  (:effects/final? effects))

(defn- root [m] (:effects/root m))

;; ---------------------------------------------------------------------------
;; Sender-cancel conformance
;; ---------------------------------------------------------------------------

(deftest sender-cancel-unilateral-allowed
  (testing "sender-cancel with {:can-cancel? true :unilateral-cancel? true} finalizes immediately"
    (let [[init result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :sender-cancel {:can-cancel? true :unilateral-cancel? true})]
      (is (true? (:ok exec)) "Surface A succeeds")
      (is (= :refunded (t/escrow-state result 0)) "Surface A finalizes to :refunded")
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))) "projected effect is :refund-sender")
      (is (true? (final? (:projection/effects proj-map))) "projected effect marks final?")
      (is (= :sender (:effects/by (:projection/effects proj-map))) "projected effect by :sender"))))

(deftest sender-cancel-unilateral-forbidden
  (testing "sender-cancel with {:can-cancel? false :unilateral-cancel? false} is rejected"
    (let [[init result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :sender-cancel {:can-cancel? false :unilateral-cancel? false})]
      (is (false? (:ok exec)) "Surface A rejects")
      (is (= :pending (t/escrow-state result 0)) "Surface A stays :pending")
      (is (= :record-party-agreement (effect-kind (:projection/effects proj-map))) "projected effect is :record-party-agreement (no finalization)")
      (is (false? (final? (:projection/effects proj-map))) "projected effect not final"))))

(deftest sender-cancel-unilateral-dominates
  (testing "sender-cancel with {:can-cancel? false :unilateral-cancel? true} still allows unilateral (can-cancel? dominates)"
    (let [[init result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :sender-cancel {:can-cancel? false :unilateral-cancel? true})]
      (is (true? (:ok exec)) "Surface A succeeds — unilateral-cancel? wins")
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map)))
      (is (true? (final? (:projection/effects proj-map)))))))

(deftest sender-cancel-mutual-consent-first-party
  (testing "sender-cancel with no strategy (mutual consent) records first agreement only"
    (let [[init result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :sender-cancel nil)]
      (is (true? (:ok exec)) "Surface A succeeds")
      (is (= :pending (t/escrow-state result 0)) "stays :pending — only one party agreed")
      (is (= :agree-to-cancel (:sender-status (t/get-transfer result 0))))
      (is (= :none (:recipient-status (t/get-transfer result 0))))
      (is (= :record-party-agreement (effect-kind (:projection/effects proj-map))) "projected effect is agreement")
      (is (false? (final? (:projection/effects proj-map)))))))

(deftest sender-cancel-mutual-consent-both-agree
  (testing "sender-cancel then recipient-cancel completes mutual-consent cancellation"
    (let [w (world-with-one-escrow)
          w1 (:world (lc/sender-cancel w 0 alice nil))
          [init result proj-map exec] (execute-and-project w1 0 :recipient-cancel nil)]
      (is (true? (:ok exec)) "Surface A succeeds")
      (is (= :refunded (t/escrow-state result 0)) "finalizes to :refunded")
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))) "projected effect is :refund-sender")
      (is (true? (final? (:projection/effects proj-map)))))))

;; ---------------------------------------------------------------------------
;; Recipient-cancel symmetric conformance
;; ---------------------------------------------------------------------------

(deftest recipient-cancel-unilateral-allowed
  (testing "recipient-cancel with {:can-cancel? true :unilateral-cancel? true} finalizes immediately"
    (let [[init result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :recipient-cancel {:can-cancel? true :unilateral-cancel? true})]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map))))
      (is (= :recipient (:effects/by (:projection/effects proj-map)))))))

(deftest recipient-cancel-unilateral-forbidden
  (testing "recipient-cancel with {:can-cancel? false :unilateral-cancel? false} is rejected"
    (let [[init result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :recipient-cancel {:can-cancel? false :unilateral-cancel? false})]
      (is (false? (:ok exec)))
      (is (= :pending (t/escrow-state result 0)))
      (is (= :record-party-agreement (effect-kind (:projection/effects proj-map))))
      (is (false? (final? (:projection/effects proj-map)))))))

(deftest recipient-cancel-unilateral-dominates
  (testing "recipient-cancel with {:can-cancel? false :unilateral-cancel? true} still allows unilateral"
    (let [[init result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :recipient-cancel {:can-cancel? false :unilateral-cancel? true})]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map)))))))

(deftest recipient-cancel-mutual-consent-first-party
  (testing "recipient-cancel with no strategy records first agreement only"
    (let [[init result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :recipient-cancel nil)]
      (is (true? (:ok exec)))
      (is (= :pending (t/escrow-state result 0)))
      (is (= :agree-to-cancel (:recipient-status (t/get-transfer result 0))))
      (is (= :none (:sender-status (t/get-transfer result 0))))
      (is (= :record-party-agreement (effect-kind (:projection/effects proj-map))))
      (is (false? (final? (:projection/effects proj-map)))))))

(deftest recipient-cancel-mutual-consent-both-agree
  (testing "recipient-cancel then sender-cancel completes mutual-consent cancellation"
    (let [w (world-with-one-escrow)
          w1 (:world (lc/recipient-cancel w 0 bob nil))
          [init result proj-map exec] (execute-and-project w1 0 :sender-cancel nil)]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map)))))))

;; ---------------------------------------------------------------------------
;; auto-cancel-disputed-escrow conformance (max-dispute-duration)
;; ---------------------------------------------------------------------------

(deftest auto-cancel-disputed-not-due
  (testing "auto-cancel-disputed-escrow before max-dispute-duration is rejected"
    (let [w (-> (world-disputed)
                (assoc-in [:escrow-transfers 0 :dispute-resolver] carol)
                (set-block-time 2000)) ;; 1000 + 3600 > 2000, not exceeded
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
      (is (false? (:ok exec)))
      (is (= :disputed (t/escrow-state result 0)))
      (is (= :disputed (:projection/inputs proj-map :projection/admissibility :admissibility/dispute-active)))
      (is (false? (:projection/inputs proj-map :projection/admissibility :admissibility/dispute-timeout-exceeded))))))

(deftest auto-cancel-disputed-exactly-due
  (testing "auto-cancel-disputed-escrow exactly at max-dispute-duration succeeds"
    (let [w (-> (world-disputed)
                (assoc-in [:escrow-transfers 0 :dispute-resolver] carol)
                (set-block-time 4600)) ;; 1000 + 3600 = 4600, exactly due
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map))))
      (is (= :keeper (:effects/by (:projection/effects proj-map))))
      (is (true? (:projection/inputs proj-map :projection/admissibility :admissibility/dispute-timeout-exceeded))))))

(deftest auto-cancel-disputed-overdue
  (testing "auto-cancel-disputed-escrow after max-dispute-duration succeeds"
    (let [w (-> (world-disputed)
                (assoc-in [:escrow-transfers 0 :dispute-resolver] carol)
                (set-block-time 5000)) ;; past due
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map)))))))

(deftest auto-cancel-disputed-dispute-absent
  (testing "auto-cancel-disputed-escrow on non-disputed escrow is rejected"
    (let [w (set-block-time (world-with-one-escrow) 5000)
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
      (is (false? (:ok exec)))
      (is (= :pending (t/escrow-state result 0)))
      (is (false? (:projection/inputs proj-map :projection/admissibility :admissibility/dispute-active))))))

(deftest auto-cancel-disputed-has-pending-settlement
  (testing "auto-cancel-disputed-escrow with pending settlement is rejected (CRIT-3)"
    (let [w (-> (world-disputed)
                (assoc-in [:escrow-transfers 0 :dispute-resolver] carol)
                (assoc-in [:pending-settlements 0] {:exists true})
                (set-block-time 5000))
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
      (is (false? (:ok exec)))
      (is (= :disputed (t/escrow-state result 0)))
      (is (true? (:projection/inputs proj-map :projection/admissibility :admissibility/pending-settlement))))))

(deftest auto-cancel-disputed-no-resolver
  (testing "auto-cancel-disputed-escrow succeeds when escrow has no resolver"
    (let [w (set-block-time (world-disputed) 5000)
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map))))
      (is (nil? (:projection/inputs proj-map :projection/admissibility :admissibility/dispute-resolver))))))

;; ---------------------------------------------------------------------------
;; auto-cancel-disputed-on-auto-time conformance (semantic distinction!)
;; ---------------------------------------------------------------------------

(deftest auto-cancel-on-auto-time-not-due
  (testing "auto-cancel-disputed-on-auto-time before auto-cancel-time is rejected"
    (let [w (-> (world-disputed-with-auto-cancel-time 5000)
                (set-block-time 4000)) ;; before 5000
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
      (is (false? (:ok exec)))
      (is (= :disputed (t/escrow-state result 0)))
      (is (false? (:projection/inputs proj-map :projection/admissibility :admissibility/auto-cancel-due-on-disputed))))))

(deftest auto-cancel-on-auto-time-exactly-due
  (testing "auto-cancel-disputed-on-auto-time exactly at auto-cancel-time succeeds"
    (let [w (-> (world-disputed-with-auto-cancel-time 5000)
                (set-block-time 5000))
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map))))
      (is (= :keeper (:effects/by (:projection/effects proj-map))))
      (is (true? (:projection/inputs proj-map :projection/admissibility :admissibility/auto-cancel-due-on-disputed))))))

(deftest auto-cancel-on-auto-time-overdue
  (testing "auto-cancel-disputed-on-auto-time after auto-cancel-time succeeds"
    (let [w (-> (world-disputed-with-auto-cancel-time 5000)
                (set-block-time 6000))
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map)))))))

(deftest auto-cancel-on-auto-time-dispute-absent
  (testing "auto-cancel-disputed-on-auto-time on non-disputed escrow is rejected"
    (let [w (-> (world-with-one-escrow)
                (assoc-in [:escrow-transfers 0 :auto-cancel-time] 5000)
                (set-block-time 6000))
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-on-auto-time)]
      (is (false? (:ok exec)))
      (is (= :pending (t/escrow-state result 0)))
      (is (false? (:projection/inputs proj-map :projection/admissibility :admissibility/dispute-active))))))

(deftest auto-cancel-on-auto-time-has-pending-settlement
  (testing "auto-cancel-disputed-on-auto-time with pending settlement is rejected"
    (let [w (-> (world-disputed-with-auto-cancel-time 5000)
                (assoc-in [:pending-settlements 0] {:exists true})
                (set-block-time 6000))
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-on-auto-time)]
      (is (false? (:ok exec)))
      (is (true? (:projection/inputs proj-map :projection/admissibility :admissibility/pending-settlement))))))

(deftest auto-cancel-on-auto-time-no-resolver
  (testing "auto-cancel-disputed-on-auto-time succeeds when escrow has no resolver"
    (let [w (-> (world-disputed-with-auto-cancel-time 5000)
                (set-block-time 6000))
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-on-auto-time)]
      (is (true? (:ok exec)))
      (is (= :refunded (t/escrow-state result 0)))
      (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
      (is (true? (final? (:projection/effects proj-map)))))))

(deftest auto-cancel-on-auto-time-zero-time
  (testing "auto-cancel-disputed-on-auto-time fails when auto-cancel-time is 0 (disabled)"
    (let [w (-> (world-disputed)
                (assoc-in [:escrow-transfers 0 :auto-cancel-time] 0)
                (set-block-time 6000))
          [init result proj-map exec] (execute-and-project w 0 :auto-cancel-on-auto-time)]
      (is (false? (:ok exec)))
      (is (false? (:projection/inputs proj-map :projection/admissibility :admissibility/auto-cancel-due-on-disputed))))))

;; ---------------------------------------------------------------------------
;; Semantic distinction: the two auto-cancel paths are DIFFERENT
;; ---------------------------------------------------------------------------

(deftest auto-cancel-two-paths-are-distinct
  "Demonstrates the semantic distinction between the two auto-cancel paths:
   - :auto-cancel-disputed-escrow fires on max-dispute-duration elapsed (since raiseDispute)
   - :auto-cancel-disputed-on-auto-time fires on auto-cancel-time elapsed (independent deadline)
   They can fire at different times; neither subsumes the other."
  (let [;; Escrow disputed at t=1000, max-dispute-duration=3600, auto-cancel-time=2000
        w-disputed-1000 (-> (world-disputed-with-auto-cancel-time 2000)
                            (set-block-time 1500))
        [init1 result1 proj1 exec1] (execute-and-project w-disputed-1000 0 :auto-cancel-disputed-escrow)
        [init2 result2 proj2 exec2] (execute-and-project w-disputed-1000 0 :auto-cancel-disputed-on-auto-time)]
    (is (false? (:ok exec1)) "max-dispute-duration path not yet due")
    (is (false? (:ok exec2)) "auto-cancel-time path not yet due")

    (let [;; At t=2500: max-dispute-duration not elapsed (4600), but auto-cancel-time ELAPSED (2000 < 2500)
          w-disputed-1000 (-> (world-disputed-with-auto-cancel-time 2000)
                              (set-block-time 2500))
          [init3 result3 proj3 exec3] (execute-and-project w-disputed-1000 0 :auto-cancel-disputed-escrow)
          [init4 result4 proj4 exec4] (execute-and-project w-disputed-1000 0 :auto-cancel-disputed-on-auto-time)]
      (is (false? (:ok exec3)) "max-dispute-duration path still not due at t=2500")
      (is (true? (:ok exec4))  "auto-cancel-time path FIRES at t=2500")

    (let [;; At t=5000: both elapsed
          w-disputed-1000 (-> (world-disputed-with-auto-cancel-time 2000)
                              (set-block-time 5000))
          [init5 result5 proj5 exec5] (execute-and-project w-disputed-1000 0 :auto-cancel-disputed-escrow)
          [init6 result6 proj6 exec6] (execute-and-project w-disputed-1000 0 :auto-cancel-disputed-on-auto-time)]
      (is (true? (:ok exec5)) "max-dispute-duration path fires")
      (is (true? (:ok exec6)) "auto-cancel-time path fires"))))

;; ---------------------------------------------------------------------------
;; declared-outside-scope rooted basis invariant
;; ---------------------------------------------------------------------------

(deftest declared-outside-scope-basis-root-is-exact
  "Every outside-scope fact used in reconstruction must be exact and rooted
   in the reconstruction basis. This prevents the verifier from using a
   different dependency than execution used.

   The reconstruction basis for a path is:
     snapshot-root
     + cancel-strategy-root (if applicable)
     + time-basis-root (block-time + relevant deadlines)
     + caller/authority-root
     + path
   All must be bound exactly — no ambiguity."
  (testing "cancel-strategy is a request parameter, not world state — must be bound on operation"
    (let [w (world-with-one-escrow)
          proj-map (proj/project w 0 :sender-cancel)]
      (is (contains? (:projection/declared proj-map) :cancel-strategy))
      (is (= :projection/reason-is-request-parameter
             (:projection/reason (:cancel-strategy (:projection/declared proj-map)))))))

  (testing "auto-cancel-time is time-dependent, declared on operation"
    (let [w (world-disputed-with-auto-cancel-time 5000)
          proj-map (proj/project w 0 :auto-cancel-disputed-on-auto-time)]
      (is (contains? (:projection/declared proj-map) :auto-cancel-time))
      (is (= :projection/reason-is-time-dependent-declared-on-operation
             (:projection/reason (:auto-cancel-time (:projection/declared proj-map)))))))

  (testing "dispute-timestamps is world-level, declared on operation for auto paths"
    (let [w (world-disputed-with-auto-cancel-time 5000)
          proj-map (proj/project w 0 :auto-cancel-disputed-escrow)]
      (is (contains? (:projection/declared proj-map) :dispute-timestamps))
      (is (= :projection/reason-is-world-level-declared-on-operation
             (:projection/reason (:dispute-timestamps (:projection/declared proj-map)))))))

  (testing "caller/identity is authorization-bound"
    (let [w (world-with-one-escrow)
          proj-map (proj/project w 0 :sender-cancel)]
      (is (contains? (:projection/declared proj-map) :caller/identity))
      (is (= :projection/reason-is-authorization-bound
             (:projection/reason (:caller/identity (:projection/declared proj-map)))))))

  (testing "coverage audit passes — no consulted fact lacks a declared home"
    (is (true? (:audit/ok? (proj/coverage-audit (world-with-one-escrow) 0 :sender-cancel)))
    (is (true? (:audit/ok? (proj/coverage-audit (world-with-one-escrow) 0 :recipient-cancel)))
    (is (true? (:audit/ok? (proj/coverage-audit (world-disputed-with-auto-cancel-time) 0 :auto-cancel-disputed-escrow)))
    (is (true? (:audit/ok? (proj/coverage-audit (world-disputed-with-auto-cancel-time) 0 :auto-cancel-disputed-on-auto-time))))))

;; ---------------------------------------------------------------------------
;; Snapshot root stability: same world state -> same snapshot root
;; ---------------------------------------------------------------------------

(deftest snapshot-root-deterministic
  "The snapshot is a pure function of world state — same world state
   produces identical snapshot root regardless of path or block-time."
  (let [w1 (world-with-one-escrow)
        w2 (world-with-one-escrow)
        snap1 (proj/project-snapshot w1 0)
        snap2 (proj/project-snapshot w2 0)]
    (is (= (:snapshot/root snap1) (:snapshot/root snap2)))))

(deftest snapshot-root-excludes-time-dependent-facts
  "The snapshot identity does NOT include time-dependent facts.
   Two worlds identical except for block-time have the same snapshot root."
  (let [w1 (set-block-time (world-with-one-escrow) 1000)
        w2 (set-block-time (world-with-one-escrow) 5000)
        snap1 (proj/project-snapshot w1 0)
        snap2 (proj/project-snapshot w2 0)]
    (is (= (:snapshot/root snap1) (:snapshot/root snap2)))))

;; ---------------------------------------------------------------------------
;; State-after projection matches Surface A terminal state
;; ---------------------------------------------------------------------------

(deftest state-after-projection-matches-execution
  "The projected state-after matches the actual Surface A resulting state."
  (testing "sender-cancel unilateral"
    (let [w (world-with-one-escrow)
          [init result proj-map exec] (execute-and-project w 0 :sender-cancel {:can-cancel? true :unilateral-cancel? true})]
      (is (= (t/escrow-state result 0) (:state-after/escrow-state (:projection/state-after proj-map))))
      (is (= (:sender-status (t/get-transfer result 0)) (:state-after/sender-status (:projection/state-after proj-map))))
      (is (= (:recipient-status (t/get-transfer result 0)) (:state-after/recipient-status (:projection/state-after proj-map)))))

    (testing "recipient-cancel unilateral"
      (let [w (world-with-one-escrow)
            [init result proj-map exec] (execute-and-project w 0 :recipient-cancel {:can-cancel? true :unilateral-cancel? true})]
        (is (= (t/escrow-state result 0) (:state-after/escrow-state (:projection/state-after proj-map)))))

    (testing "auto-cancel-disputed-escrow"
      (let [w (-> (world-disputed-with-auto-cancel-time 5000) (set-block-time 6000))
            [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
        (is (= (t/escrow-state result 0) (:state-after/escrow-state (:projection/state-after proj-map))))
        (is (true? (:state-after/terminal? (:projection/state-after proj-map)))))

    (testing "auto-cancel-disputed-on-auto-time"
      (let [w (-> (world-disputed-with-auto-cancel-time 5000) (set-block-time 6000))
            [init result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
        (is (= (t/escrow-state result 0) (:state-after/escrow-state (:projection/state-after proj-map))))
        (is (true? (:state-after/terminal? (:projection/state-after proj-map))))))))
