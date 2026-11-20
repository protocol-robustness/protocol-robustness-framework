(ns resolver-sim.protocols.sew.reproduction-slash-bug
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry :as reg]
            [resolver-sim.protocols.sew.pro-rata.evidence]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]))

(defn- slash-id-for
  [world workflow-id kind level]
  (get-in world [:slash-by-context (t/slash-context-key workflow-id kind level)]))

(deftest manual-fraud-slash-rejects-while-reversal-slash-is-pending
  (testing "A second manual slash cannot be proposed while a reversal slash is pending"
    (let [gov "0xGov"
          r0 "0xRes0"
          r1 "0xRes1"
          buyer "0xBuyer"
          seller "0xSeller"
          snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                          :reversal-slash-bps 2500
                                          :fraud-slash-bps 5000
                                          :appeal-window-duration 2000000
                                          :max-dispute-level 2})
          world0 (-> (t/empty-world 1000)
                     (reg/register-stake r0 10000)
                     (reg/register-stake r1 5000))
          {:keys [world workflow-id]} (lc/create-escrow world0 buyer "USDC" seller 8000 {} snap)

          ;; 1. Raise dispute and create reversal condition
          after-raise-res (lc/raise-dispute world workflow-id buyer)
          after-raise (:world after-raise-res)
          after-l0 (:world (res/execute-resolution after-raise workflow-id r0 true "0xhash" nil))

          ;; 2. Escalate, provide new evidence to trigger Track 2 (Pending) Reversal Slash
          after-escalation-res (res/challenge-resolution after-l0 workflow-id buyer (fn [_ _ _ _] {:ok true :new-resolver r1}))
          after-escalation (:world after-escalation-res)
          after-evidence (assoc-in after-escalation [:evidence-updated? workflow-id] true)
          after-l1 (:world (res/execute-resolution after-evidence workflow-id r1 false "0xhash2" nil))

          slash-id (slash-id-for after-l1 workflow-id :reversal 0)

          ;; 3. Verify Reversal slash is pending
          _ (is (= :pending (get-in after-l1 [:pending-fraud-slashes slash-id :status])))

          ;; A manual fraud slash is rejected while this workflow already has
          ;; an active reversal slash; the pending reversal remains intact.
          fraud-proposal (res/propose-fraud-slash after-l1 workflow-id gov r0 4000)]
      (is (= :pending (get-in after-l1 [:pending-fraud-slashes slash-id :status])))
      (is (false? (:ok fraud-proposal)))
      (is (= :slash-already-pending (:error fraud-proposal))))))
