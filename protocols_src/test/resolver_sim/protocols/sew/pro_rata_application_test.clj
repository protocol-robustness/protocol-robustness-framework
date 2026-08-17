(ns resolver-sim.protocols.sew.pro-rata-application-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.protocols.sew.pro-rata-application :as sut]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.evidence :as evidence]
            [resolver-sim.pro-rata.refinement :as refinement]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.hash.canonical :as hc]))

(defn fixture
  "Build a complete honest pro-rata held-credit chain. Returns a map with the
   world, protocol effects, adjustments, committed roots, and the full
   authorization + refinement artifacts needed to build a receipt."
  []
  (let [allocation (allocation/allocate {:allocation/id :credit :available 10
                                          :rows [{:row/id :a :obligation/id :a :requested 10 :weight 1 :cap 10}]})
        proposal (evidence/proposed-effects allocation)
        source-id (get-in proposal [:effects 0 :effect/id])
        refinement (refinement/sew-add-held-refinement allocation proposal
                                                        {source-id {:effect/token :USDC :effect/account :escrow :held/kind :credit}})
        before (types/empty-world)
        after (accounting/add-held before :USDC 10 {:reason :credit :account :escrow})
        adjustments (:held-adjustments after)
        roots (sut/application-roots before after adjustments)
        authorization (application/authorize {:allocation-root (:allocation/hash allocation)
                                                :proposed-effects-root (:proposed-effects/root proposal)
                                                :protocol-effect-set-root (:protocol-effect-set/root refinement)
                                                :state-before-root (:state-before/root roots)
                                                :policy-root "policy" :authorization-root "auth" :consumption-key "once"})]
    {:before before
     :protocol-effects (:effects refinement)
     :adjustments adjustments
     :roots roots
     :authorization authorization
     :refinement refinement
     :allocation allocation
     :proposal proposal}))

(deftest full-pro-rata-held-credit-chain
  (let [ctx (fixture)
        result (sut/apply-pro-rata-held-credit (:before ctx) (:allocation ctx) (:proposal ctx)
                                               (:refinement ctx) (:authorization ctx) (:roots ctx))]
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

(deftest application-transition-valid?-matches-committed-roots
  (let [ctx (fixture)
        result (sut/application-transition-valid? (:before ctx)
                                                  (:protocol-effects ctx)
                                                  (:adjustments ctx)
                                                  (:roots ctx))]
    (is (true? result)
        "re-derived roots match the committed roots for an honest chain")))

(deftest application-transition-valid?-rejects-tampered-state-after-root
  (let [ctx (fixture)
        tampered-roots (assoc (:roots ctx) :state-after/root
                              "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        result (sut/application-transition-valid? (:before ctx)
                                                  (:protocol-effects ctx)
                                                  (:adjustments ctx)
                                                  tampered-roots)]
    (is (false? result)
        "re-derived state-after-root does not match the tampered committed root")))

(deftest application-transition-valid?-rejects-tampered-ledger-after-root
  (let [ctx (fixture)
        tampered-roots (assoc (:roots ctx) :ledger-after/root
                              "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        result (sut/application-transition-valid? (:before ctx)
                                                  (:protocol-effects ctx)
                                                  (:adjustments ctx)
                                                  tampered-roots)]
    (is (false? result)
        "re-derived ledger-after-root does not match the tampered committed root")))

(deftest application-transition-valid?-rejects-wrong-before-world
  (let [ctx (fixture)
        wrong-before (accounting/add-held (:before ctx) :USDC 10 {:reason :wrong :account :escrow})
        result (sut/application-transition-valid? wrong-before
                                                  (:protocol-effects ctx)
                                                  (:adjustments ctx)
                                                  (:roots ctx))]
    (is (false? result)
        "re-derived state-before-root does not match when the wrong before-world is supplied")))

(deftest application-transition-valid?-separates-from-receipt-valid?
  (let [ctx (fixture)
        honest-result (sut/apply-pro-rata-held-credit (:before ctx) (:allocation ctx) (:proposal ctx)
                                                       (:refinement ctx) (:authorization ctx) (:roots ctx))
        honest-receipt (:receipt honest-result)]
    (is (application/receipt-valid? honest-receipt)
        "receipt-valid? (self-integrity) passes for the honest receipt")
    (is (true? (sut/application-transition-valid? (:before ctx)
                                                  (:protocol-effects ctx)
                                                  (:adjustments ctx)
                                                  (:roots ctx)))
        "application-transition-valid? (transition derivation) passes for the honest chain")
    (let [tampered-receipt (assoc honest-receipt :state-after/root
                                  "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
          tampered-receipt (assoc tampered-receipt :applied-effect-receipt/root
                                   (hc/domain-hash :applied-effect-receipt
                                                   (dissoc tampered-receipt :applied-effect-receipt/root)))]
      (is (application/receipt-valid? tampered-receipt)
          "receipt-valid? (self-integrity) still passes for a self-consistent tampered-roots receipt")
      (is (false? (sut/application-transition-valid? (:before ctx)
                                                     (:protocol-effects ctx)
                                                     (:adjustments ctx)
                                                     tampered-receipt))
          "application-transition-valid? (transition derivation) fails for the same tampered-roots receipt"))))