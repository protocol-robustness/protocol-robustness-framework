(ns resolver-sim.contract-model.replay-batch-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]))

(defrecord BatchTestProtocol []
  proto/SimulationAdapter
  (protocol-id [_] "batch-test")
  (init-world [_ _] {:block-time 1000 :flag false :applied []})
  (build-execution-context [_ agents _] {:agent-index (into {} (map (juxt :id identity) agents))})
  (dispatch-action [_ _ world event]
    (case (:action event)
      "set-flag" {:ok true :world (-> world (assoc :flag true) (update :applied conj (:seq event)))}
      "guarded-write" (if (:flag world)
                        {:ok false :error :guard-failed}
                        {:ok true :world (-> world (assoc :flag true) (update :applied conj (:seq event)))})
      "touch" {:ok true :world (update world :applied conj (:seq event))}
      {:ok false :error :unknown-action}))
  (check-invariants-single [_ _] {:ok? true})
  (check-invariants-transition [_ _ _] {:ok? true})
  (world-snapshot [_ world] (select-keys world [:block-time :flag :applied]))
  (available-actions [_ _ _] [])
  (resolve-id-alias [_ event _] {:ok true :event event})
  (created-id [_ _ _] nil)
  (open-entities [_ _] [])
  (project-state [_ _ _] nil)

  proto/BatchConflictModel
  (event-conflict-domains [_ _ event _]
    (set (get-in event [:params :domains] #{[:global :unknown]}))))

(def ^:private protocol (->BatchTestProtocol))

;; Protocol without BatchConflictModel — tests the fallback to [:global :unknown]
(defrecord SimpleProtocol []
  proto/SimulationAdapter
  (protocol-id [_] "simple")
  (init-world [_ _] {:block-time 1000 :applied []})
  (build-execution-context [_ agents _] {:agent-index (into {} (map (juxt :id identity) agents))})
  (dispatch-action [_ _ world event]
    (case (:action event)
      "touch" {:ok true :world (update world :applied conj (:seq event))}
      {:ok false :error :unknown-action}))
  (check-invariants-single [_ _] {:ok? true})
  (check-invariants-transition [_ _ _] {:ok? true})
  (world-snapshot [_ world] (select-keys world [:block-time :applied]))
  (available-actions [_ _ _] [])
  (resolve-id-alias [_ event _] {:ok true :event event})
  (created-id [_ _ _] nil)
  (open-entities [_ _] [])
  (project-state [_ _ _] nil))

(def ^:private simple-protocol (->SimpleProtocol))

(defn- base-scenario
  [events]
  {:schema-version "1.1"
   :id "batch-test"
   :scenario-id "batch-test"
   :title "Batch test"
   :scenario-author "@tests"
   :purpose :regression
   :agents [{:id "a" :address "0xa" :role "actor"}
            {:id "b" :address "0xb" :role "actor"}]
   :events events
   :execution-mode :deterministic-batch
   :theory {:claim-id :test/batch :assumptions [] :falsifies-if [{:metric :reverts :op :>= :value 0}]}})

(deftest preflight-eligible-but-commit-guard-fails
  (let [result (replay/replay-with-protocol
                protocol
                (base-scenario
                 [{:seq 0 :time 1000 :agent "a" :action "set-flag"
                   :params {:domains #{[:workflow 1]}}}
                  {:seq 1 :time 1000 :agent "b" :action "guarded-write"
                   :params {:domains #{[:workflow 2]}}}]))
        e1 (some #(when (= 1 (:seq %)) %) (:trace result))
        checkpoint (some #(when (= :post-batch (:invariant-phase %)) %) (:trace result))]
    (is (= :pass (:outcome result)))
    (is (= :eligible (:preflight-status e1)))
    (is (= :rejected (:result e1)))
    (is (= :guard-failed (:error e1)))
    (is (= :batch-commit (:reject-phase e1)))
    (is (= :ok (:result checkpoint)))
    (is (= 1 (:batch-buckets (:metrics result))))
    (is (= 2 (:batch-events (:metrics result))))))

(deftest multi-domain-intersection-rejects-as-batch-conflict
  (let [result (replay/replay-with-protocol
                protocol
                (base-scenario
                 [{:seq 0 :time 1000 :agent "a" :action "touch"
                   :params {:domains #{[:workflow 1] [:resolver "r1"]}}}
                  {:seq 1 :time 1000 :agent "b" :action "touch"
                   :params {:domains #{[:resolver "r1"]}}}]))
        e1 (some #(when (= 1 (:seq %)) %) (:trace result))]
    (testing "any shared domain intersects and rejects second event"
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:resolver "r1"] (:conflict-domain e1)))
      (is (= 0 (:conflict-with-seq e1)))
      (is (= :eligible (:preflight-status e1)))
      (is (= 1 (:batch-conflicts (:metrics result)))))))

(deftest global-protocol-domain-conflicts-with-itself
  (testing "Two [:global :protocol] events at same timestamp: second rejected"
    (let [result (replay/replay-with-protocol
                  protocol
                  (base-scenario
                   [{:seq 0 :time 1000 :agent "a" :action "touch"
                     :params {:domains #{[:global :protocol]}}}
                    {:seq 1 :time 1000 :agent "b" :action "touch"
                     :params {:domains #{[:global :protocol]}}}]))
          e0 (some #(when (= 0 (:seq %)) %) (:trace result))
          e1 (some #(when (= 1 (:seq %)) %) (:trace result))]
      (is (= :pass (:outcome result)))
      (is (= :ok (:result e0)))
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:global :protocol] (:conflict-domain e1)))
      (is (= 0 (:conflict-with-seq e1)))
      (is (= 1 (:batch-conflicts (:metrics result)))))))

(deftest global-unknown-domain-conflicts-with-itself
  (testing "Two [:global :unknown] events at same timestamp: second rejected
            (protocol without BatchConflictModel)"
    (let [result (replay/replay-with-protocol
                  simple-protocol
                  (assoc (base-scenario
                          [{:seq 0 :time 1000 :agent "a" :action "touch"}
                           {:seq 1 :time 1000 :agent "b" :action "touch"}])
                         :scenario-id "batch-test-simple"
                         :theory {:claim-id :test/batch-simple
                                  :assumptions []
                                  :falsifies-if [{:metric :reverts :op :>= :value 0}]}))
          e1 (some #(when (= 1 (:seq %)) %) (:trace result))]
      (is (= :rejected (:result e1)))
      (is (= :batch-conflict (:error e1)))
      (is (= [:global :unknown] (:conflict-domain e1))))))

(deftest sew-auto-cancel-batch-no-conflict
  (testing "Two auto-cancel-disputed for different workflows at same timestamp:
            both accepted ([:workflow wf-id] domains are distinct)"
    (let [scenario {:schema-version "1.0"
                    :id "batch-auto-cancel"
                    :scenario-id "batch-auto-cancel"
                    :title "Batch auto-cancel test"
                    :scenario-author "@test"
                    :purpose :regression
                    :initial-block-time 1000
                    :execution-mode :deterministic-batch
                    :agents [{:id "buyer0"  :address "0xb0" :strategy "honest"}
                             {:id "buyer1"  :address "0xb1" :strategy "honest"}
                             {:id "seller0" :address "0xs0" :strategy "honest"}
                             {:id "seller1" :address "0xs1" :strategy "honest"}
                             {:id "keeper"  :address "0xk"  :role "keeper"}]
                    :protocol-params {:resolver-fee-bps 0
                                      :appeal-window-duration 0
                                      :max-dispute-duration 300}
                    :events [{:seq 0  :time 1000 :agent "buyer0" :action "create_escrow"
                              :params {:token "USDC" :to "0xseller0" :amount 1000}}
                             {:seq 1  :time 1010 :agent "buyer1" :action "create_escrow"
                              :params {:token "DAI" :to "0xseller1" :amount 1000}}
                             {:seq 2  :time 1060 :agent "buyer0" :action "raise_dispute"
                              :params {:workflow-id 0}}
                             {:seq 3  :time 1070 :agent "buyer1" :action "raise_dispute"
                              :params {:workflow-id 1}}
                             ;; Both auto-cancel at the same timestamp (batch bucket)
                             ;; dispute-timeout = 1060 + 300 = 1360 (wf0), 1070 + 300 = 1370 (wf1)
                             {:seq 4  :time 1370 :agent "keeper" :action "auto_cancel_disputed"
                              :params {:workflow-id 0}}
                             {:seq 5  :time 1370 :agent "keeper" :action "auto_cancel_disputed"
                              :params {:workflow-id 1}}]
                    :theory {:claim-id :test/batch-auto-cancel
                             :assumptions []
                             :falsifies-if [{:metric :reverts :op :>= :value 0}]}}
          result (sew/replay-with-sew-protocol scenario)
          e0 (some #(when (= 0 (:seq %)) %) (:trace result))
          e4 (some #(when (= 4 (:seq %)) %) (:trace result))
          e5 (some #(when (= 5 (:seq %)) %) (:trace result))
          batch-checkpoint (some #(when (= :post-batch (:invariant-phase %)) %) (:trace result))]
      (is (= :pass (:outcome result)) "scenario should pass")
      (is (= :ok (:result e0)) "escrow 0 created")
      (is (= :ok (:result e4)) "auto-cancel wf 0 accepted")
      (is (= :ok (:result e5)) "auto-cancel wf 1 accepted (no batch conflict)")
      (is (= :ok (:result batch-checkpoint)) "post-batch invariants pass")
      (is (= 0 (:batch-conflicts (:metrics result))) "no batch conflicts across all buckets"))))
