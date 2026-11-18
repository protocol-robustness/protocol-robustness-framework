(ns resolver-sim.protocols.sew.reclaimed-reconstruction-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.protocols.sew.lifecycle :as lifecycle]
            [resolver-sim.protocols.sew.reclaimed-reconstruction :as reconstruction]
            [resolver-sim.protocols.sew.types :as types]))

(def token :USDC)
(def workflow-id 7)
(def recipient "0xAlice")
(def owner-id "yield:7")

(defn- before-world []
  (assoc (accounting/add-held
          (types/empty-world) token 25
          {:action "reserve-deferred-yield"
           :reason :deferred-yield-reserved
           :extra {:held/workflow-id workflow-id
                   :owner/address recipient}})
         :escrow-transfers {workflow-id {:token token}}
         :yield/positions {owner-id {:token token}}))

(def operation {:workflow-id workflow-id
                :owner-id owner-id
                :recipient recipient
                :reclaimed 25})

(deftest reclaimed-transition-reconstructs-custody-and-claimable-effects
  (let [before (before-world)
        after (lifecycle/apply-deferred-yield-claim-settlement
               before workflow-id owner-id recipient 25)
        result (reconstruction/verify-transition
                {:state-before before :operation operation :state-after after})]
    (is (:valid? result) (pr-str (:mismatches result)))
    (is (= 3 (count (:effects result))))
    (is (= 25 (get-in after [:claimable-v2 workflow-id :settlement/yield recipient])))
    (is (= 25 (get-in after [:claimable workflow-id recipient])))))

(deftest same-net-value-wrong-effect-decomposition-rejects
  (let [before (before-world)
        after (lifecycle/apply-deferred-yield-claim-settlement
               before workflow-id owner-id recipient 25)
        wrong (-> after
                  (assoc-in [:claimable-v2 workflow-id :settlement/yield recipient] 20)
                  (assoc-in [:claimable-v2 workflow-id :settlement/principal recipient] 5))
        result (reconstruction/verify-transition
                {:state-before before :operation operation :state-after wrong})]
    (testing "net claimable value is unchanged"
      (is (= 25 (reduce + 0 (mapcat vals (vals (get-in wrong [:claimable-v2 workflow-id])))))))
    (is (false? (:valid? result)))
    (is (some #{:claimable-v2} (:mismatches result)))))

(deftest reclaimed-transition-rejects-substituted-history
  (let [before (before-world)
        after (lifecycle/apply-deferred-yield-claim-settlement
               before workflow-id owner-id recipient 25)
        tampered (assoc-in after [:held-adjustments 0 :amount] 20)
        result (reconstruction/verify-transition
                {:state-before before :operation operation :state-after tampered})]
    (is (false? (:valid? result)))))
