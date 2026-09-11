(ns resolver-sim.protocols.sew.phase-k-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]
            [resolver-sim.protocols.sew.accounting :as acct]
            [resolver-sim.protocols.sew.reversal-fixtures :as rev-fx]
            [resolver-sim.time.context :as time-ctx]))

(deftest tiered-authority-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        resolver "0xResolver"
        token "0xToken"
        snap (snap-fix/escrow-snapshot {:dispute-resolver resolver
                                        :escrow-fee-bps 0
                                        :resolver-bond-bps 10000})]

    (testing "create-escrow succeeds with zero-stake resolver (no bond enforced)"
      (let [r (lc/create-escrow world buyer token seller 1000 {} snap)]
        (is (true? (:ok r)))
        (is (= 0 (:workflow-id r)))))

    (testing "create-escrow succeeds after resolver stakes"
      (let [world-staked (reg/register-stake world resolver 1000)
            r (lc/create-escrow world-staked buyer token seller 1000 {} snap)]
        (is (true? (:ok r)))
        (is (= 0 (:workflow-id r)))))

    (testing "create-escrow fails if stake is too low for escrow amount"
      (let [world-staked (reg/register-stake world resolver 999)
            r (lc/create-escrow world-staked buyer token seller 1000 {} snap)]
        (is (false? (:ok r)))
        (is (= :insufficient-resolver-stake (:error r)))))))

(deftest auto-slashing-on-reversal-test
  (let [r0 "0xRes0"
        r1 "0xRes1"
        {:keys [world workflow-id steps]}
        (rev-fx/build-reversal-world rev-fx/participant-escalation-reversal)]
    (testing "Level 0 resolution"
      (is (= r0 (get-in (:after-l0 steps) [:previous-decisions workflow-id 0 :resolver]))))
    (testing "Escalation to Level 1"
      (is (= 1 (t/dispute-level (:after-escalation steps) workflow-id))))
    (testing "Level 1 resolution reverses Level 0 -> slashing"
      ;; 25% of 5000 stake = 1250 slashed
      (is (= 3750 (reg/get-stake world r0)))
      (is (= 625 (get-in world [:bond-distribution :insurance])))
      (is (= 375 (get-in world [:bond-distribution :protocol]))))))

(deftest manual-fraud-slash-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        resolver "0xRes"
        gov "0xGov"
        token "0xToken"
        snap (snap-fix/escrow-snapshot {:appeal-window-duration 86400}) ; 1 day
        world (reg/register-stake world resolver 10000)
        {:keys [world workflow-id]}
        (let [{:keys [world workflow-id]}
              (lc/create-escrow world buyer token seller 5000 {:custom-resolver resolver} snap)
              world' (:world (lc/raise-dispute world workflow-id buyer))]
          {:world (:world (res/execute-resolution world' workflow-id resolver true "0xhash" nil))
           :workflow-id workflow-id})
        world-params (assoc-in world [:params :slash-epoch-cap-bps] 5000)
        r-prop (res/propose-fraud-slash world-params workflow-id gov resolver 5000)
        world-prop (:world r-prop)]

    (is (true? (:ok r-prop)))
    (is (= 10000 (reg/get-stake world-prop resolver))) ; Not slashed yet

    (testing "Cannot execute before timelock"
      (let [r-exec (res/execute-fraud-slash world-prop workflow-id)]
        (is (false? (:ok r-exec)))
        (is (= :timelock-not-expired (:error r-exec)))))

    (testing "Execute after timelock"
      (let [world-time (time-ctx/advance-time world-prop {:to 100000}) ; > 86400
            r-exec (res/execute-fraud-slash world-time workflow-id)
            world-final (:world r-exec)]
        (is (true? (:ok r-exec)))
        (is (= 5000 (reg/get-stake world-final resolver))) ; Slashed!
        (is (= 2500 (get-in world-final [:bond-distribution :insurance])))))))
