(ns resolver-sim.protocols.sew.invariant-registry-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.types :as t]))

(def ^:private snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50}))

(defn- sample-world []
  (:world (lc/create-escrow (t/empty-world 1000) "0xAlice" "0xUSDC" "0xBob" 1000
                            (t/make-escrow-settings {}) snap)))

(deftest canonical-ids-union
  (is (= inv/canonical-ids
         (set/union inv/world-invariant-ids inv/transition-invariant-ids))))

(deftest world-invariant-ids-match-check-all
  (testing "every world invariant ID is executed by check-all"
    (let [world  (sample-world)
          run-ids (set (keys (:results (inv/check-all world))))]
      (is (= inv/world-invariant-ids run-ids)
          (str "drift: check-all=" (sort run-ids)
               " registry=" (sort inv/world-invariant-ids))))))

(deftest transition-invariant-ids-match-check-transition
  (testing "every transition invariant ID is executed by check-transition"
    (let [w0     (sample-world)
          w1     (assoc-in w0 [:escrow-transfers 0 :escrow-state] :released)
          run-ids (set (keys (:results (inv/check-transition w0 w1))))]
      (is (= inv/transition-invariant-ids run-ids)
          (str "drift: check-transition=" (sort run-ids)
               " registry=" (sort inv/transition-invariant-ids))))))

(deftest no-stale-canonical-ids
  (testing "canonical-ids has no orphans outside the two check runners"
    (let [world  (sample-world)
          w1     (assoc-in world [:escrow-transfers 0 :escrow-state] :released)
          executed (set/union (set (keys (:results (inv/check-all world))))
                              (set (keys (:results (inv/check-transition world w1)))))]
      (is (set/subset? inv/canonical-ids executed))
      (is (set/subset? executed inv/canonical-ids)))))

(deftest persisted-escrow-state-valid-rejects-none
  (let [world (assoc-in (sample-world) [:escrow-transfers 0 :escrow-state] :none)]
    (is (not (:holds? (inv/persisted-escrow-state-valid? world))))
    (is (= [{:workflow-id 0
             :escrow-state :none
             :allowed #{:pending :disputed :released :refunded :resolved}}]
           (:violations (inv/persisted-escrow-state-valid? world))))))

(deftest dispute-level-bounded-rejects-pending-with-level
  (let [world (-> (sample-world)
                  (assoc-in [:dispute-levels 0] 1))]
    (is (not (:holds? (inv/dispute-level-bounded? world))))
    (is (= :dispute-level-on-non-disputed
           (:reason (first (:violations (inv/dispute-level-bounded? world))))))))

(deftest dispute-level-bounded-allows-terminal-with-level
  (let [world (-> (sample-world)
                  (assoc-in [:escrow-transfers 0 :escrow-state] :released)
                  (assoc-in [:dispute-levels 0] 1))]
    (is (:holds? (inv/dispute-level-bounded? world)))))

(deftest escrow-state-in-graph-rejects-unknown-state
  (let [world (assoc-in (sample-world) [:escrow-transfers 0 :escrow-state] :bogus)]
    (is (not (:holds? (inv/escrow-state-in-graph? world))))))

(deftest escrow-dispute-metadata-rejects-pending-with-timestamp
  (let [world (-> (sample-world)
                  (assoc-in [:dispute-timestamps 0] 1000))]
    (is (not (:holds? (inv/escrow-dispute-metadata-consistent? world))))
    (is (= :pending-with-dispute-metadata
           (:reason (first (:violations (inv/escrow-dispute-metadata-consistent? world))))))))

(deftest module-snapshot-immutable-detects-snapshot-change
  (let [w0 (sample-world)
        w1 (assoc-in w0 [:module-snapshots 0 :resolver-fee-bps] 999)]
    (is (not (:holds? (inv/module-snapshot-immutable? w0 w1))))))

(deftest escrow-state-transition-valid-rejects-circular-back-edge
  (let [w0 (-> (sample-world)
               (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
               (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
               (assoc-in [:dispute-timestamps 0] 1000))
        w1 (assoc-in w0 [:escrow-transfers 0 :escrow-state] :pending)]
    (is (not (:holds? (inv/escrow-state-transition-valid? w0 w1))))
    (is (= [{:workflow-id 0 :from :disputed :to :pending}]
           (:violations (inv/escrow-state-transition-valid? w0 w1))))))

(deftest expected-failures-suppresses-known-failure
  (testing "expected-failures in world[:params] flips holds? from false to true"
    (let [world (t/empty-world 1000)
          r1 (inv/check-all world "test")
          r2 (inv/check-all (assoc-in world [:params :expected-failures "test"] [:solvency]) "test")
          solv1 (get-in r1 [:results :solvency])
          solv2 (get-in r2 [:results :solvency])]
      (is (true? (:holds? solv2)) "expected-failures flips holds? to true")
      (is (true? (:expected-failure? solv2)) "expected-failure? is true when suppressed")
      (is (true? (:expected-failure? solv2)) "expected-failure? is true when suppressed"))))