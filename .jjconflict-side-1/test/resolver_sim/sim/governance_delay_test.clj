(ns resolver-sim.sim.governance-delay-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.governance-delay :as gd]))

(deftest governance-approved-transitions-from-pending-to-executed
  (testing "slash executes only after governance approval epoch"
    (let [state0 (gd/initialize-pending-slashes)
          state1 (gd/mark-slash-detected state0 1500 7 1 3 :fraud)
          p0 (gd/process-governance-approvals state1 1)
          p1 (gd/process-governance-approvals (:pending-state p0) 2)
          p2 (gd/process-governance-approvals (:pending-state p1) 3)]
      ;; 3 day response => ceil(3/4)=1 epoch delay, so approval at epoch 2.
      (is (= 2 (:approval-epoch (first (:pending-slashes state1)))))
      (is (= 0 (count (:executable-slashes p0))) "must not execute before approval epoch")
      (is (= 1 (count (:executable-slashes p1))) "must execute at approval epoch")
      (is (= 0 (count (:executable-slashes p2))) "must not execute twice")
      (is (= :executed
             (:status (first (:resolved-slashes (:pending-state p1))))))
      (is (empty? (:pending-slashes (:pending-state p1)))))))

(deftest governance-approved-keeps-other-pending-slashes-queued
  (testing "processing one approval window preserves unrelated pending slashes"
    (let [s0 (gd/initialize-pending-slashes)
          s1 (gd/mark-slash-detected s0 1000 1 1 3 :timeout)  ; approval epoch 2
          s2 (gd/mark-slash-detected s1 1200 2 1 7 :fraud)    ; approval epoch 3
          p  (gd/process-governance-approvals s2 2)
          pending (:slashes-still-pending p)
          executed (:executable-slashes p)]
      (is (= 1 (count executed)))
      (is (= 1 (count pending)))
      (is (= 2 (:resolver-id (first pending))))
      (is (= 1 (:resolver-id (first executed)))))))
