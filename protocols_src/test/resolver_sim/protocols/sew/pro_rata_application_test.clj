(ns resolver-sim.protocols.sew.pro-rata-application-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.protocols.sew.pro-rata-application :as sut]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.evidence :as evidence]
            [resolver-sim.pro-rata.refinement :as refinement]
            [resolver-sim.pro-rata.application :as application]))

(deftest full-pro-rata-held-credit-chain
  (let [allocation (allocation/allocate {:allocation/id :credit :available 10
                                         :rows [{:row/id :a :obligation/id :a :requested 10 :weight 1 :cap 10}]})
        proposal (evidence/proposed-effects allocation)
        source-id (get-in proposal [:effects 0 :effect/id])
        refinement (refinement/sew-add-held-refinement allocation proposal
                                                        {source-id {:effect/token :USDC :effect/account :escrow :held/kind :credit}})
        before (types/empty-world)
        after (accounting/add-held before :USDC 10 {:reason :credit :account :escrow})
        roots (sut/application-roots before after (:held-adjustments after))
        authorization (application/authorize {:allocation-root (:allocation/hash allocation)
                                               :proposed-effects-root (:proposed-effects/root proposal)
                                               :protocol-effect-set-root (:protocol-effect-set/root refinement)
                                               :state-before-root (:state-before/root roots)
                                               :policy-root "policy" :authorization-root "auth" :consumption-key "once"})
        result (sut/apply-pro-rata-held-credit before allocation proposal refinement authorization roots)]
    (is (= 1 (count (:adjustments result))))
    (is (application/receipt-valid? (:receipt result)))))

(deftest roots-bind-a-real-new-held-adjustment
  (let [before (types/empty-world)
        after (accounting/add-held before :USDC 10 {:reason :test :extra {:held/workflow-id 1}})
        adjustments (:held-adjustments after)
        roots (sut/application-roots before after adjustments)]
    (is (every? string? (vals roots)))
    (is (not= (:state-before/root roots) (:state-after/root roots)))
    (is (not= (:ledger-before/root roots) (:ledger-after/root roots)))))
