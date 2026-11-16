(ns resolver-sim.protocols.sew.phase-m-test
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]
            [resolver-sim.time.context             :as time-ctx]))

(defn- slash-id-for
  [world workflow-id kind level]
  (get-in world [:slash-by-context (t/slash-context-key workflow-id kind level)]))

(deftest evidence-gated-slashing-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        r0 "0xRes0"
        r1 "0xRes1"
        gov "0xGov"
        snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                        :reversal-slash-bps 10000
                                        :appeal-window-duration 86400
                                        :resolver-bond-bps 10000
                                        :escrow-fee-bps 50})
        world (-> world (reg/register-stake r0 2000) (reg/register-stake r1 2000))
        {:keys [world workflow-id]} (lc/create-escrow world buyer "0xT" seller 1000 {} snap)
        net-escrow (t/compute-amount-after-fee 1000 50) ; default 50 bps fee
        world (-> (lc/raise-dispute world workflow-id buyer) :world)
        world (-> (res/execute-resolution world workflow-id r0 true "h0" nil) :world)
        esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
        world (-> (res/escalate-dispute world workflow-id buyer esc-fn) :world)]

    (testing "TRACK 1: Automated Slash (Same Evidence)"
      (let [r-final (res/execute-resolution world workflow-id r1 false "h1" nil)
            world-final (:world r-final)
            slash-id (slash-id-for world-final workflow-id :reversal 0)]
        (is (= :executed (get-in world-final [:pending-fraud-slashes slash-id :status]))
            "Slash should be executed immediately on same-evidence reversal")
        (is (= 0 (reg/get-stake world-final r0))
            "100% reversal slash-bps on 2000 stake removes all stake")))

    (testing "TRACK 2: Manual Proposal (New Evidence)"
      (let [world-new-info (assoc-in world [:evidence-updated? workflow-id] true)
            r-final (res/execute-resolution world-new-info workflow-id r1 false "h1" nil)
            world-final (:world r-final)
            slash-id (slash-id-for world-final workflow-id :reversal 0)
            slash-entry (get-in world-final [:pending-fraud-slashes slash-id])]
        (is (= :pending (:status slash-entry)) "Slash should be PENDING when new evidence exists")
        (is (= 2000 (reg/get-stake world-final r0)) "Resolver NOT slashed yet")

        (testing "Successful Slashing Appeal"
          (let [world-appealed (-> (res/appeal-slash world-final workflow-id r0 slash-id) :world)
                world-upheld   (-> (res/resolve-appeal world-appealed workflow-id gov true slash-id
                                                       :authorization-provenance {:authorization/type :governance
                                                                                  :authorization/basis :test})
                                   :world)]
            (is (= :reversed (get-in world-upheld [:pending-fraud-slashes slash-id :status]))
                "Slash status should be REVERSED")

            (testing "Execution of reversed slash fails"
              (let [world-late (assoc world-upheld :block-time 1000000)
                    r-exec (res/execute-fraud-slash world-late workflow-id slash-id)]
                (is (false? (:ok r-exec)))
                (is (= :slash-already-reversed (:error r-exec)))))))))))

(deftest evidence-gated-via-submit-evidence
  (testing "Track 2 triggered via submit-evidence function, not direct state mutation"
    (let [world (t/empty-world 1000)
          buyer "0xBuyer"
          seller "0xSeller"
          r0 "0xRes0"
          r1 "0xRes1"
          snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                          :reversal-slash-bps 2500
                                          :appeal-window-duration 86400
                                          :resolver-bond-bps 10000
                                          :escrow-fee-bps 50})
          world (-> world (reg/register-stake r0 2000) (reg/register-stake r1 2000))
          {:keys [world workflow-id]}
          (lc/create-escrow world buyer "USDC" seller 1000 {} snap)
          world (-> (lc/raise-dispute world workflow-id buyer) :world)
          world (-> (res/execute-resolution world workflow-id r0 true "h0" nil) :world)
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
          world (-> (res/escalate-dispute world workflow-id buyer esc-fn) :world)
          ;; Submit evidence through the public function (has guards: must be :disputed)
          world-ev (-> (res/submit-evidence world workflow-id buyer) :world)
          r-final (res/execute-resolution world-ev workflow-id r1 false "h1" nil)
          world-final (:world r-final)
          slash-id (slash-id-for world-final workflow-id :reversal 0)
          slash (get-in world-final [:pending-fraud-slashes slash-id])]
      (is (true? (:ok (res/submit-evidence world workflow-id buyer))) "submit-evidence succeeds")
      (is (= :pending (:status slash)) "Track 2 via submit-evidence produces :pending slash"))))

(deftest evidence-updated-persists-across-levels
  (testing "evidence-updated? flag persists across multiple reversals (Solidity semantics)"
    (let [world (t/empty-world 1000)
          buyer "0xBuyer"
          seller "0xSeller"
          r0 "0xRes0"
          r1 "0xRes1"
          r2 "0xRes2"
      snap (snap-fix/escrow-snapshot {:dispute-resolver r0
                                      :reversal-slash-bps 2500
                                      :appeal-window-duration 200000
                                      :challenge-window-duration 200000
                                      :max-dispute-level 3
                                      :resolver-bond-bps 10000
                                      :escrow-fee-bps 50})
          world (-> world
                    (reg/register-stake r0 5000)
                    (reg/register-stake r1 5000)
                    (reg/register-stake r2 5000))
          {:keys [world workflow-id]}
          (lc/create-escrow world buyer "USDC" seller 1000 {} snap)
          world (-> (lc/raise-dispute world workflow-id buyer) :world)

          ;; L0 resolution (release)
          world (-> (res/execute-resolution world workflow-id r0 true "h0" nil) :world)

          ;; Escalate to L1
          esc-fn (fn [_ _ _ _] {:ok true :new-resolver r1})
          world (-> (res/escalate-dispute world workflow-id buyer esc-fn) :world)

          ;; Advance past the 1-day escalation cooldown before buyer's next escalation.
          world (time-ctx/advance-time world {:to (+ 1000 time-ctx/seconds-per-day 1)})

          ;; Flag evidence *before* first reversal — both reversals should use Track 2
          world (assoc-in world [:evidence-updated? workflow-id] true)

          ;; L1 resolves (refund → reverses L0) — Track 2, :pending
          world (-> (res/execute-resolution world workflow-id r1 false "h1" nil) :world)
          slash-l0-id (slash-id-for world workflow-id :reversal 0)
          flag-after-l1 (get-in world [:evidence-updated? workflow-id])]

      ;; Assert L1 reversal created :pending slash (Track 2)
      (is (= :pending (get-in world [:pending-fraud-slashes slash-l0-id :status]))
          "L1 reversal uses Track 2")
      (is (true? flag-after-l1) "evidence-updated? still true after first reversal")

      ;; Escalate to L2
      (let [esc-fn2 (fn [_ _ _ _] {:ok true :new-resolver r2})
            world (-> (res/challenge-resolution world workflow-id buyer esc-fn2) :world)

            ;; L2 resolves (release → reverses L1) — Track 2 again
            world (-> (res/execute-resolution world workflow-id r2 true "h2" nil) :world)
            slash-l1-id (slash-id-for world workflow-id :reversal 1)
            flag-after-l2 (get-in world [:evidence-updated? workflow-id])]

        (is (= :pending (get-in world [:pending-fraud-slashes slash-l1-id :status]))
            "L2 reversal also uses Track 2 (evidence-updated? still true)")
        (is (true? flag-after-l2) "evidence-updated? still true after second reversal")))))
