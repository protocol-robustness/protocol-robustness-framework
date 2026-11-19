(ns resolver-sim.protocols.sew.replay-idempotency-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]))

(defn- context-with-escalation
  []
  (let [agents [{:id "buyer" :address "0xBuyer" :type "honest"}
                {:id "seller" :address "0xSeller" :type "honest"}
                {:id "resolver0" :address "0xResolver0" :type "resolver"}
                {:id "watchdog" :address "0xWatchdog" :type "attacker"}]]
    (proto/build-execution-context
     sew/protocol
     agents
     {:resolver-fee-bps 50
      :resolver-bond-bps 0
      :appeal-window-duration 200
      :escalation-resolvers {:1 "0xResolver1"}})))

(defn- disputed-world-with-pending
  [ctx]
  (let [w0 (t/empty-world 1000)
        s1 (replay/process-step sew/protocol ctx w0
                                {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                                 :params {:token "0xUSDC" :to "0xSeller" :amount 1000
                                          :custom-resolver "0xResolver0"}})
        s2 (replay/process-step sew/protocol ctx (:world s1)
                                {:seq 1 :time 1010 :agent "buyer" :action "raise_dispute"
                                 :params {:workflow-id 0}})
        s3 (replay/process-step sew/protocol ctx (:world s2)
                                {:seq 2 :time 1020 :agent "resolver0" :action "execute_resolution"
                                 :params {:workflow-id 0 :is-release true :resolution-hash "0xhash"}})]
    (:world s3)))

(deftest duplicate-event-id-escalate-dispute-noops
  (let [ctx (context-with-escalation)
        w0 (disputed-world-with-pending ctx)
        e1 {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
            :params {:workflow-id 0 :event-id "evt-esc-1" :hop-id 0}}
        e2 {:seq 4 :time 1031 :agent "buyer" :action "escalate_dispute"
            :params {:workflow-id 0 :event-id "evt-esc-1" :hop-id 0}}
        r1 (replay/process-step sew/protocol ctx w0 e1)
        r2 (replay/process-step sew/protocol ctx (:world r1) e2)]
    (is (= :ok (get-in r1 [:trace-entry :result])))
    (is (= 1 (t/dispute-level (:world r1) 0)))
    (is (= :ok (get-in r2 [:trace-entry :result])))
    (is (= :no-op-duplicate (get-in r2 [:trace-entry :extra :idempotency])))
    (is (= 1 (t/dispute-level (:world r2) 0)))))

(deftest duplicate-event-id-challenge-resolution-noops
  (let [ctx (context-with-escalation)
        w0 (disputed-world-with-pending ctx)
        c1 {:seq 3 :time 1030 :agent "watchdog" :action "challenge_resolution"
            :params {:workflow-id 0 :event-id "evt-chal-1" :hop-id 0}}
        c2 {:seq 4 :time 1031 :agent "watchdog" :action "challenge_resolution"
            :params {:workflow-id 0 :event-id "evt-chal-1" :hop-id 0}}
        r1 (replay/process-step sew/protocol ctx w0 c1)
        r2 (replay/process-step sew/protocol ctx (:world r1) c2)]
    (is (= :ok (get-in r1 [:trace-entry :result])))
    (is (= 1 (t/dispute-level (:world r1) 0)))
    (is (= :ok (get-in r2 [:trace-entry :result])))
    (is (= :no-op-duplicate (get-in r2 [:trace-entry :extra :idempotency])))
    (is (= 1 (t/dispute-level (:world r2) 0)))))

(deftest same-event-id-across-escalation-hops-allowed
  (let [agents [{:id "buyer" :address "0xBuyer" :type "honest"}
                {:id "seller" :address "0xSeller" :type "honest"}
                {:id "resolver0" :address "0xResolver0" :type "resolver"}
                {:id "resolver1" :address "0xResolver1" :type "resolver"}]
        ctx (proto/build-execution-context
             sew/protocol
             agents
             {:resolver-fee-bps 50
              :resolver-bond-bps 0
              :appeal-window-duration 200
              :escalation-resolvers {:1 "0xResolver1" :2 "0xResolver2"}})
        w0 (disputed-world-with-pending ctx)
        ;; Hop 0 -> 1 at T=1030 (within appeal window; no prior escalation)
        e-hop1 {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                :params {:workflow-id 0 :event-id "evt-hop-shared"}}
        r-hop1 (replay/process-step sew/protocol ctx w0 e-hop1)
        w1 (:world r-hop1)
        ;; Seed a new pending at level 1 to model the next hop being appealable.
        ;; Appeal deadline must exceed the 1-day cooldown so the L1→L2 hop
        ;; can succeed after advancing time past cooldown.
        w2 (assoc-in w1 [:pending-settlements 0]
                     (t/make-pending-settlement {:exists true
                                                 :is-release true
                                                 :appeal-deadline 100000
                                                 :resolution-hash "0xhash-l1"}))
        ;; Hop 1 -> 2: must advance past the 86400s cooldown (since T=1030)
        ;; and stay within the appeal window (deadline 100000).
        e-hop2 {:seq 5 :time 87431 :agent "buyer" :action "escalate_dispute"
                :params {:workflow-id 0 :event-id "evt-hop-shared"}}
        r-hop2 (replay/process-step sew/protocol ctx w2 e-hop2)]
    (is (= :ok (get-in r-hop1 [:trace-entry :result])))
    (is (= 1 (t/dispute-level w1 0)))
    (is (= :ok (get-in r-hop2 [:trace-entry :result])))
    (is (= 2 (t/dispute-level (:world r-hop2) 0)))
    (is (not= :no-op-duplicate (get-in r-hop2 [:trace-entry :extra :idempotency]))
        "same event-id on a different hop level must not be deduped")))

(deftest configured-escalation-successor-is-frozen-per-escrow
  (let [ctx (context-with-escalation)
        world (disputed-world-with-pending ctx)
        frozen-snapshot (t/get-snapshot world 0)
        result (replay/process-step sew/protocol ctx world
                                    {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                                     :params {:workflow-id 0}})]
    (is (= "0xResolver1" (get-in result [:world :escrow-transfers 0 :dispute-resolver])))
    (is (= (:resolver-ladder/root frozen-snapshot)
           (get-in result [:world :module-snapshots 0 :resolver-ladder/root])))
    (is (= (:resolver-ladder frozen-snapshot)
           (get-in result [:world :module-snapshots 0 :resolver-ladder])))))

(deftest ordinary-escalation-rejects-callback-successor-not-in-frozen-ladder
  (let [ctx (assoc (context-with-escalation)
                   :escalation-fn (fn [_ _ _ _] {:ok true :new-resolver "0xOtherResolver"}))
        result (replay/process-step sew/protocol ctx (disputed-world-with-pending ctx)
                                    {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                                     :params {:workflow-id 0}})]
    (is (= :escalation-successor-mismatch (get-in result [:trace-entry :error])))
    (is (= 0 (t/dispute-level (:world result) 0)))))

(deftest ordinary-escalation-fails-closed-when-frozen-ladder-has-no-successor
  (let [agents [{:id "buyer" :address "0xBuyer" :type "honest"}
                {:id "seller" :address "0xSeller" :type "honest"}
                {:id "resolver0" :address "0xResolver0" :type "resolver"}]
        ctx (proto/build-execution-context sew/protocol agents
                                           {:resolver-fee-bps 50
                                            :resolver-bond-bps 0
                                            :appeal-window-duration 200
                                            :escalation-resolvers {:2 "0xResolver2"}})
        result (replay/process-step sew/protocol ctx (disputed-world-with-pending ctx)
                                    {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                                     :params {:workflow-id 0}})]
    (is (= :escalation-not-allowed (get-in result [:trace-entry :error])))
    (is (= 0 (t/dispute-level (:world result) 0)))))

(deftest later-live-escalation-config-cannot-change-the-frozen-ladder
  (let [ctx (context-with-escalation)
        world (assoc-in (disputed-world-with-pending ctx)
                        [:params :escalation-resolvers]
                        {:1 "0xLaterResolver"})
        result (replay/process-step sew/protocol ctx world
                                    {:seq 3 :time 1030 :agent "buyer" :action "escalate_dispute"
                                     :params {:workflow-id 0}})]
    (is (= :ok (get-in result [:trace-entry :result])))
    (is (= "0xResolver1" (get-in result [:world :escrow-transfers 0 :dispute-resolver])))
    (is (not= "0xLaterResolver" (get-in result [:world :escrow-transfers 0 :dispute-resolver])))))
