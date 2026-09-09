(ns resolver-sim.cancellation.projection-conformance-test
  "P0-B: Differential conformance between Surface A (SEW protocol execution)
   and Surface B (sew_projection reconstruction)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.state-machine :as sm]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.cancellation.sew-projection :as proj]
            [resolver-sim.cancellation.semantic :as semantic]))

(def alice "0xAlice")
(def bob "0xBob")
(def carol "0xCarol")
(def usdc :0xUSDC)

(def base-snapshot
  (snap-fix/escrow-snapshot
   {:escrow-fee-bps 50
    :default-auto-release-delay 0
    :default-auto-cancel-delay 0
    :max-dispute-duration 3600
    :appeal-window-duration 1800}))

(def base-settings
  (t/make-escrow-settings {}))

(defn- create-one
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
   Returns [initial-world resulting-world projected-map exec-result]."
  (let [initial-world world
        exec-result (case path
                      :sender-cancel (lc/sender-cancel initial-world workflow-id alice cancel-strategy)
                      :recipient-cancel (lc/recipient-cancel initial-world workflow-id bob cancel-strategy)
                      :auto-cancel-disputed-escrow (lc/auto-cancel-disputed-escrow initial-world workflow-id)
                      :auto-cancel-disputed-on-auto-time (lc/auto-cancel-disputed-on-auto-time initial-world workflow-id)
                      (throw (ex-info "unknown path" {:path path})))
        resulting-world (if (:ok exec-result) (:world exec-result) initial-world)
        projected (proj/project initial-world workflow-id path cancel-strategy)]
    [initial-world resulting-world projected exec-result]))

(defn- effect-kind [effects] (:effects/kind effects))
(defn- final? [effects] (:effects/final? effects))

;; --- sender-cancel ---

(deftest sender-cancel-unilateral-allowed
  (let [[_ result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :sender-cancel {:can-cancel? true :unilateral-cancel? true})]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))
    (is (= :sender (:effects/by (:projection/effects proj-map))))))

(deftest sender-cancel-unilateral-forbidden
  (let [[_ result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :sender-cancel {:can-cancel? false :unilateral-cancel? false})]
    (is (false? (:ok exec)))
    (is (= :pending (t/escrow-state result 0)))
    (is (= :record-party-agreement (effect-kind (:projection/effects proj-map))))
    (is (false? (final? (:projection/effects proj-map))))))

(deftest sender-cancel-unilateral-dominates
  (let [[_ result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :sender-cancel {:can-cancel? true :unilateral-cancel? true})]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))))

(deftest sender-cancel-mutual-consent-first-party
  (let [[_ result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :sender-cancel nil)]
    (is (true? (:ok exec)))
    (is (= :pending (t/escrow-state result 0)))
    (is (= :agree-to-cancel (:sender-status (t/get-transfer result 0))))
    (is (= :none (:recipient-status (t/get-transfer result 0))))
    (is (= :record-party-agreement (effect-kind (:projection/effects proj-map))))
    (is (false? (final? (:projection/effects proj-map))))))

(deftest sender-cancel-mutual-consent-both-agree
  (let [w (world-with-one-escrow)
        w1 (:world (lc/sender-cancel w 0 alice nil))
        [_ result proj-map exec] (execute-and-project w1 0 :recipient-cancel nil)]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))))

;; --- recipient-cancel ---

(deftest recipient-cancel-unilateral-allowed
  (let [[_ result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :recipient-cancel {:can-cancel? true :unilateral-cancel? true})]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))
    (is (= :recipient (:effects/by (:projection/effects proj-map))))))

(deftest recipient-cancel-unilateral-forbidden
  (let [[_ result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :recipient-cancel {:can-cancel? false :unilateral-cancel? false})]
    (is (false? (:ok exec)))
    (is (= :pending (t/escrow-state result 0)))
    (is (= :record-party-agreement (effect-kind (:projection/effects proj-map))))
    (is (false? (final? (:projection/effects proj-map))))))

(deftest recipient-cancel-unilateral-dominates
  (let [[_ result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :recipient-cancel {:can-cancel? true :unilateral-cancel? true})]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))))

(deftest recipient-cancel-mutual-consent-first-party
  (let [[_ result proj-map exec] (execute-and-project (world-with-one-escrow) 0 :recipient-cancel nil)]
    (is (true? (:ok exec)))
    (is (= :pending (t/escrow-state result 0)))
    (is (= :agree-to-cancel (:recipient-status (t/get-transfer result 0))))
    (is (= :none (:sender-status (t/get-transfer result 0))))
    (is (= :record-party-agreement (effect-kind (:projection/effects proj-map))))
    (is (false? (final? (:projection/effects proj-map))))))

(deftest recipient-cancel-mutual-consent-both-agree
  (let [w (world-with-one-escrow)
        w1 (:world (lc/recipient-cancel w 0 bob nil))
        [_ result proj-map exec] (execute-and-project w1 0 :sender-cancel nil)]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))))

;; --- auto-cancel-disputed-escrow ---

(deftest auto-cancel-disputed-not-due
  (let [w (-> (world-disputed)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] carol)
              (set-block-time 2000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
    (is (false? (:ok exec)))
    (is (= :disputed (t/escrow-state result 0)))
    (is (false? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/dispute-timeout-exceeded])))))

(deftest auto-cancel-disputed-exactly-due
  (let [w (-> (world-disputed)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] carol)
              (set-block-time 4600))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))
    (is (= :keeper (:effects/by (:projection/effects proj-map))))
    (is (true? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/dispute-timeout-exceeded])))))

(deftest auto-cancel-disputed-overdue
  (let [w (-> (world-disputed)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] carol)
              (set-block-time 5000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))))

(deftest auto-cancel-disputed-dispute-absent
  (let [w (set-block-time (world-with-one-escrow) 5000)
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
    (is (false? (:ok exec)))
    (is (= :pending (t/escrow-state result 0)))
    (is (false? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/dispute-active])))))

(deftest auto-cancel-disputed-has-pending-settlement
  (let [w (-> (world-disputed)
              (assoc-in [:escrow-transfers 0 :dispute-resolver] carol)
              (assoc-in [:pending-settlements 0] {:exists true})
              (set-block-time 5000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
    (is (false? (:ok exec)))
    (is (= :disputed (t/escrow-state result 0)))
    (is (true? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/pending-settlement])))))

(deftest auto-cancel-disputed-no-resolver
  (let [w (set-block-time (world-disputed) 5000)
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))
    (is (nil? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/dispute-resolver])))))

;; --- auto-cancel-disputed-on-auto-time ---

(deftest auto-cancel-on-auto-time-not-due
  (let [w (-> (world-disputed-with-auto-cancel-time 5000)
              (set-block-time 4000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
    (is (false? (:ok exec)))
    (is (= :disputed (t/escrow-state result 0)))
    (is (false? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/auto-cancel-due-on-disputed])))))

(deftest auto-cancel-on-auto-time-exactly-due
  (let [w (-> (world-disputed-with-auto-cancel-time 5000)
              (set-block-time 5000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))
    (is (= :keeper (:effects/by (:projection/effects proj-map))))
    (is (true? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/auto-cancel-due-on-disputed])))))

(deftest auto-cancel-on-auto-time-overdue
  (let [w (-> (world-disputed-with-auto-cancel-time 5000)
              (set-block-time 6000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))))

(deftest auto-cancel-on-auto-time-dispute-absent
  (let [w (-> (world-with-one-escrow)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 5000)
              (set-block-time 6000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
    (is (false? (:ok exec)))
    (is (= :pending (t/escrow-state result 0)))
    (is (false? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/dispute-active])))))

(deftest auto-cancel-on-auto-time-has-pending-settlement
  (let [w (-> (world-disputed-with-auto-cancel-time 5000)
              (assoc-in [:pending-settlements 0] {:exists true})
              (set-block-time 6000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
    (is (false? (:ok exec)))
    (is (true? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/pending-settlement])))))

(deftest auto-cancel-on-auto-time-no-resolver
  (let [w (-> (world-disputed-with-auto-cancel-time 5000)
              (set-block-time 6000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
    (is (true? (:ok exec)))
    (is (= :refunded (t/escrow-state result 0)))
    (is (= :refund-sender (effect-kind (:projection/effects proj-map))))
    (is (true? (final? (:projection/effects proj-map))))))

(deftest auto-cancel-on-auto-time-zero-time
  (let [w (-> (world-disputed)
              (assoc-in [:escrow-transfers 0 :auto-cancel-time] 0)
              (set-block-time 6000))
        [_ result proj-map exec] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
    (is (false? (:ok exec)))
    (is (false? (get-in proj-map [:projection/inputs :projection/admissibility :admissibility/auto-cancel-due-on-disputed])))))

;; --- semantic distinction ---

(deftest auto-cancel-two-paths-are-distinct
  (let [w1 (-> (world-disputed-with-auto-cancel-time 2000) (set-block-time 1500))
        [_ r1 p1 e1] (execute-and-project w1 0 :auto-cancel-disputed-escrow)
        [_ r2 p2 e2] (execute-and-project w1 0 :auto-cancel-disputed-on-auto-time)]
    (is (false? (:ok e1)))
    (is (false? (:ok e2)))

    (let [w2 (-> (world-disputed-with-auto-cancel-time 2000) (set-block-time 2500))
          [_ r3 p3 e3] (execute-and-project w2 0 :auto-cancel-disputed-escrow)
          [_ r4 p4 e4] (execute-and-project w2 0 :auto-cancel-disputed-on-auto-time)]
      (is (false? (:ok e3)))
      (is (true? (:ok e4)))

      (let [w3 (-> (world-disputed-with-auto-cancel-time 2000) (set-block-time 5000))
            [_ r5 p5 e5] (execute-and-project w3 0 :auto-cancel-disputed-escrow)
            [_ r6 p6 e6] (execute-and-project w3 0 :auto-cancel-disputed-on-auto-time)]
        (is (true? (:ok e5)))
        (is (true? (:ok e6)))))))

;; --- declared-outside-scope invariant ---

(deftest declared-outside-scope-basis-root
  (testing "cancel-strategy is request parameter"
    (let [w (world-with-one-escrow)
          proj-map (proj/project w 0 :sender-cancel)]
      (is (contains? (:projection/declared (:projection/inputs proj-map)) :cancel-strategy))))

  (testing "auto-cancel-time is time-dependent"
    (let [w (world-disputed-with-auto-cancel-time 5000)
          proj-map (proj/project w 0 :auto-cancel-disputed-on-auto-time)]
      (is (contains? (:projection/declared (:projection/inputs proj-map)) :auto-cancel-time))))

  (testing "dispute-timestamps is world-level"
    (let [w (world-disputed-with-auto-cancel-time 5000)
          proj-map (proj/project w 0 :auto-cancel-disputed-escrow)]
      (is (contains? (:projection/declared (:projection/inputs proj-map)) :dispute-timestamps))))

  (testing "caller/identity is authorization-bound"
    (let [w (world-with-one-escrow)
          proj-map (proj/project w 0 :sender-cancel)]
      (is (contains? (:projection/declared (:projection/inputs proj-map)) :caller/identity))))

  (testing "coverage audit passes"
    (is (true? (:audit/ok? (proj/coverage-audit (world-with-one-escrow) 0 :sender-cancel))))
    (is (true? (:audit/ok? (proj/coverage-audit (world-with-one-escrow) 0 :recipient-cancel))))
    (is (true? (:audit/ok? (proj/coverage-audit (world-disputed-with-auto-cancel-time) 0 :auto-cancel-disputed-escrow))))
    (is (true? (:audit/ok? (proj/coverage-audit (world-disputed-with-auto-cancel-time) 0 :auto-cancel-disputed-on-auto-time))))))

;; --- snapshot root stability ---

(deftest snapshot-root-deterministic
  (let [w1 (world-with-one-escrow)
        w2 (world-with-one-escrow)
        snap1 (proj/project-snapshot w1 0)
        snap2 (proj/project-snapshot w2 0)]
    (is (= (:snapshot/root snap1) (:snapshot/root snap2)))))

(deftest snapshot-root-excludes-time-dependent-facts
  (let [w1 (set-block-time (world-with-one-escrow) 1000)
        w2 (set-block-time (world-with-one-escrow) 5000)
        snap1 (proj/project-snapshot w1 0)
        snap2 (proj/project-snapshot w2 0)]
    (is (= (:snapshot/root snap1) (:snapshot/root snap2)))))

;; --- state-after projection ---

(deftest state-after-projection-matches-execution
  (testing "sender-cancel unilateral"
    (let [w (world-with-one-escrow)
          [_ result proj-map _] (execute-and-project w 0 :sender-cancel {:can-cancel? true :unilateral-cancel? true})]
      (is (= (t/escrow-state result 0) (:state-after/escrow-state (:projection/state-after proj-map))))
      (is (= (:sender-status (t/get-transfer result 0)) (:state-after/sender-status (:projection/state-after proj-map))))
      (is (= (:recipient-status (t/get-transfer result 0)) (:state-after/recipient-status (:projection/state-after proj-map)))))

    (testing "recipient-cancel unilateral"
      (let [w (world-with-one-escrow)
            [_ result proj-map _] (execute-and-project w 0 :recipient-cancel {:can-cancel? true :unilateral-cancel? true})]
        (is (= (t/escrow-state result 0) (:state-after/escrow-state (:projection/state-after proj-map)))))

      (testing "auto-cancel-disputed-escrow"
        (let [w (-> (world-disputed-with-auto-cancel-time 5000) (set-block-time 6000))
              [_ result proj-map _] (execute-and-project w 0 :auto-cancel-disputed-escrow)]
          (is (= (t/escrow-state result 0) (:state-after/escrow-state (:projection/state-after proj-map))))
          (is (true? (:state-after/terminal? (:projection/state-after proj-map)))))

        (testing "auto-cancel-disputed-on-auto-time"
          (let [w (-> (world-disputed-with-auto-cancel-time 5000) (set-block-time 6000))
                [_ result proj-map _] (execute-and-project w 0 :auto-cancel-disputed-on-auto-time)]
            (is (= (t/escrow-state result 0) (:state-after/escrow-state (:projection/state-after proj-map))))
            (is (true? (:state-after/terminal? (:projection/state-after proj-map))))))))))
