(ns resolver-sim.protocols.sew.temporal-boundary-test
  (:require [clojure.test :refer :all]
            [resolver-sim.io.scenarios :as scen-io]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.contract-model.replay :as replay]))

(defn- load-scenario [path]
  (scen-io/load-scenario-file path))

(deftest test-s74-appeal-deadline-boundary
  (testing "execute_pending_settlement is rejected at t-1 and accepted at t"
    (let [scenario (load-scenario "scenarios/edn/S74_appeal-deadline-boundary.edn")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          r2       (replay/replay-with-protocol sew/protocol scenario)
          trace    (:trace r1)
          p1       (-> r1 :trace last :projection)
          p2       (-> r2 :trace last :projection)]
      (is (= :pass (:outcome r1)))
      (is (= :pass (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= p1 p2))
      ;; seq=3 t-1 should reject; seq=4 at deadline should finalize.
      (is (= :rejected (get-in trace [3 :result])))
      (is (= :ok (get-in trace [4 :result])))
      (is (= :released (get-in p1 [:escrow-transfers 0 :escrow-state]))))))

(deftest test-s75-auto-release-vs-dispute-race
  (testing "Dispute before auto-release boundary prevents timed auto-release path"
    (let [scenario (load-scenario "scenarios/edn/S75_auto-release-vs-dispute-race.edn")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          r2       (replay/replay-with-protocol sew/protocol scenario)
          trace    (:trace r1)
          p1       (-> r1 :trace last :projection)
          p2       (-> r2 :trace last :projection)]
      ;; This scenario intentionally includes a conflicting late action, so the
      ;; replay outcome is expected to be :fail while invariants remain safe.
      (is (= :fail (:outcome r1)))
      (is (= :fail (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= p1 p2))
      ;; automate_timed_actions executes, but release in same timestamp should not
      ;; cause illegal transition; trace path remains deterministic.
      (is (= :ok (get-in trace [2 :result])))
      (is (= :rejected (get-in trace [3 :result]))))))

(deftest test-s77-challenge-clears-pending-before-deadline-without-next-resolver
  (testing "BUG repro: challenge at t-1 can clear pending even if no usable next resolver executes"
    (let [scenario (load-scenario "scenarios/edn/S77_appeal-window-challenge-clears-pending-without-next-resolver.edn")
          r1       (replay/replay-with-protocol sew/protocol scenario)
          trace    (:trace r1)
          p1       (-> r1 :trace last :projection)]
      ;; Fixed behavior: challenge archives superseded pending, allowing execution
      ;; at deadline if no replacement decision has been submitted.
      (is (= :pass (:outcome r1)))
      (is (= :ok (get-in trace [3 :result])) "challenge succeeds at t-1")
      (is (= :ok (get-in trace [4 :result])) "deadline settlement executes from superseded pending")
      (is (nil? (get-in trace [4 :error])))
      (is (= :rejected (get-in trace [5 :result]))
          "after deadline execution, dispute is terminal and later resolver action is rejected")
      (is (= :transfer-not-in-dispute (get-in trace [5 :error])))
      (is (= :released (get-in p1 [:escrow-transfers 0 :escrow-state]))))))
