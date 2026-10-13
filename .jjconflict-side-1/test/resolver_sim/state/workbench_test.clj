(ns resolver-sim.state.workbench-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as sew-types]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.resubmission.transition :as resubmission]
            [resolver-sim.protocols.sew.terminal-state-snapshot :as terminal]
            [resolver-sim.state :as state]))

(defn- custody-world []
  (accounting/add-held (sew-types/empty-world 100) :USDC 25
                       {:reason :escrow-principal
                        :extra {:held/workflow-id 1
                                :owner/address "0xowner"}}))

(deftest classification-preserves-state-model-boundaries
  (testing "the passive catalogue declares fixture and analytical authority explicitly"
    (is (= :fixture (:state/authority (state/model-descriptor :lab/solvency-fixture))))
    (is (= :analytical (:state/authority (state/model-descriptor :sim/adversarial-ring)))))
  (testing "operational states are distinct models"
    (is (= :sew/runtime (get-in (state/describe-state (sew-types/empty-world))
                                [:state/model :state/model-id])))
    (is (= :resubmission/chain
           (get-in (state/describe-state (resubmission/empty-state "family"))
                   [:state/model :state/model-id]))))
  (testing "representations do not become runtime worlds"
    (let [world (custody-world)
          reconstruction (select-keys world [:held-ledger/index :total-held :held/positions])
          snapshot (terminal/build-terminal-state-snapshot world)]
      (is (= :assurance/held-ledger-reconstruction
             (get-in (state/describe-state reconstruction) [:state/model :state/model-id])))
      (is (= :sew/terminal-custody-snapshot
             (get-in (state/describe-state snapshot) [:state/model :state/model-id])))
      (is (= :projection (get-in (state/describe-state snapshot)
                                 [:state/model :representation/completeness]))))))

(deftest custody-authority-and-independent-assurance
  (let [world (custody-world)
        canonical (state/explain-path world [:held-adjustments])
        materialized (state/explain-path world [:total-held])
        assurance (state/state-assurance world)]
    (is (= :canonical (:authority canonical)))
    (is (= :materialized (:authority materialized)))
    (is (= :ok (:status (first assurance))))
    (is (true? (:independent? (first assurance))))
    (let [corrupt (assoc-in world [:total-held :USDC] 999)]
      (is (= :failed (:status (first (state/state-assurance corrupt))))))))

(deftest semantic-diff-is-append-aware-and-region-aware
  (let [before (custody-world)
        after (accounting/add-held before :USDC 5
                                   {:reason :escrow-principal
                                    :extra {:held/workflow-id 2
                                            :owner/address "0xowner"}})
        diff (state/diff-state before after)
        appends (filter #(= :append (:change %)) (:changes diff))]
    (is (some #(= [:held-adjustments] (:path %)) appends))
    (is (contains? (:changed-regions diff) :custody))
    (is (some #(and (= [:total-held :USDC] (:path %)) (= 5 (:delta %))
                    (= :materialized (:authority %))) (:changes diff)))
    (is (empty? (:unclassified-paths diff)))))

(deftest transition-lineage-keeps-time-and-representations-separate
  (let [trace [{:seq 1 :action "create_escrow" :agent "buyer"
                :time-before {:block-ts 10} :time-after {:block-ts 10} :result :ok}]
        lineage (state/transition-lineage trace)
        representations (state/representation-lineage (custody-world) {})]
    (is (= :trace (:lineage/kind (first lineage))))
    (is (= 1 (:sequence (first lineage))))
    (is (some #(= :materialized-from (:relationship/type %)) representations))))
