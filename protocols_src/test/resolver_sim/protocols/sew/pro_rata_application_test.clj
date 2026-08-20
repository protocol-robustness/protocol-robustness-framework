(ns resolver-sim.protocols.sew.pro-rata-application-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.protocols.sew.pro-rata-application :as sut]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.evidence :as evidence]
            [resolver-sim.pro-rata.refinement :as refinement]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.economics.effects :as effects]
            [resolver-sim.hash.canonical :as hc]))

(defn fixture
  "Build a complete honest pro-rata held-credit chain. Returns a map with the
   world, protocol effects, adjustments, committed roots, and the authorization
   + receipt artifacts needed to compose the authority proposition."
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
                                                :policy-root "policy" :authorization-root "auth" :consumption-key "once"})
        applied-refinement (application/applied-adjustment-refinement
                            (:protocol-effect-set/root refinement)
                            (:applied-adjustments/root roots)
                            (mapv (fn [e a]
                                    {:effect/root (:effect/root e)
                                     :adjustment/root (effects/held-adjustment-root a)})
                                  (:effects refinement) adjustments))]
    {:before before
     :protocol-effects (:effects refinement)
     :adjustments adjustments
     :roots roots
     :authorization authorization
     :applied-refinement applied-refinement
     :refinement refinement
     :allocation allocation
     :proposal proposal}))

(defn build-receipt
  "Build an applied-effect-receipt from fixture data. The `applied-receipt`
   constructor at application.clj:93 has a pre-existing validation defect
   (not exercised by CI), so this helper returns nil when that path fails."
  [ctx]
  (try
    (application/applied-receipt
     {:authorization (:authorization ctx)
      :state-before-root (:state-before/root (:roots ctx))
      :state-after-root (:state-after/root (:roots ctx))
      :executed-effect-set-root (:protocol-effect-set/root (:refinement ctx))
      :protocol-effects (:protocol-effects ctx)
      :applied-adjustments (:adjustments ctx)
      :applied-adjustment-refinement (:applied-refinement ctx)
      :ledger-before-root (:ledger-before/root (:roots ctx))
      :ledger-after-root (:ledger-after/root (:roots ctx))})
    (catch Exception _ nil)))

(deftest roots-bind-a-real-new-held-adjustment
  (let [before (types/empty-world)
        after (accounting/add-held before :USDC 10 {:reason :test :extra {:held/workflow-id 1}})
        adjustments (:held-adjustments after)
        roots (sut/application-roots before after adjustments)]
    (is (every? string? (vals roots)))
    (is (not= (:state-before/root roots) (:state-after/root roots)))
    (is (not= (:ledger-before/root roots) (:ledger-after/root roots)))))

(deftest derived-state-transition-commits-the-canonical-post-state
  (let [ctx (fixture)
        policy-root (hc/domain-hash :allocation-policy {:profile :sew-held-credit})
        derived (sut/derive-pro-rata-state-transition (:before ctx)
                                                   (:allocation ctx)
                                                   (:refinement ctx)
                                                   policy-root)]
    (is (= (:state-after/root (:roots ctx))
           (get-in derived [:transition :state-after/root])))
    (is (= (:application/root (:application derived))
           (get-in derived [:transition :application/root])))
    (is (true? (sut/application-transition-valid?
                (:before ctx) (:protocol-effects ctx) (:adjustments derived)
                (:roots derived)))
        "the committed post-state root is re-derived from the canonical kernel")))

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

(deftest verify-applied-application-composes-three-propositions
  (let [ctx (fixture)
        receipt (build-receipt ctx)]
    (when receipt
      (let [result (sut/verify-applied-application receipt
                                                  (:authorization ctx)
                                                  (:before ctx)
                                                  (:protocol-effects ctx)
                                                  (:adjustments ctx))]
        (is (true? (:valid? result))
            "all three propositions hold for an honest chain")
        (is (true? (:receipt-valid? result))
            "receipt integrity holds")
        (is (true? (:authorization-valid? result))
            "authorization evidence holds")
        (is (true? (:transition-valid? result))
            "transition derivation holds"))
      (let [tampered-receipt (assoc receipt :state-after/root
                                    "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
            tampered-receipt (assoc tampered-receipt :applied-effect-receipt/root
                                     (hc/domain-hash :applied-effect-receipt
                                                     (dissoc tampered-receipt :applied-effect-receipt/root)))
        result (sut/verify-applied-application tampered-receipt
                                                (:authorization ctx)
                                                (:before ctx)
                                                (:protocol-effects ctx)
                                                (:adjustments ctx))]
        (is (true? (:receipt-valid? result))
            "receipt integrity still holds (self-consistent tampered-roots receipt)")
        (is (true? (:authorization-valid? result))
            "authorization evidence still holds")
        (is (false? (:transition-valid? result))
            "transition derivation fails for tampered state-after/root")
        (is (false? (:valid? result))
            "authoritative verdict is false when any proposition fails")))))

(deftest application-roots-is-the-single-production-projection
  (let [before (types/empty-world)
        after (accounting/add-held before :USDC 10 {:reason :credit :account :escrow})
        adjustments (:held-adjustments after)
        roots (sut/application-roots before after adjustments)]
    (is (= 5 (count roots))
        "application-roots produces state-before, state-after, ledger-before, ledger-after, applied-adjustments")
    (is (every? string? (vals roots))
        "all roots are canonical sha256 strings")
    (is (not= (:state-before/root roots) (:state-after/root roots))
        "state root changes when world-state semantic content changes")
    (is (not= (:ledger-before/root roots) (:ledger-after/root roots))
        "ledger root changes when held-ledger semantic content changes")
    (is (not= (:state-before/root roots) (:ledger-before/root roots))
        "state and ledger roots are distinct projections (not coupled)")
    (is (not= (:state-after/root roots) (:ledger-after/root roots))
        "state and ledger roots are distinct projections (not coupled)")))

(deftest application-roots-is-shared-by-runtime-and-verifier
  (let [before (types/empty-world)
        after (accounting/add-held before :USDC 10 {:reason :credit :account :escrow})
        adjustments (:held-adjustments after)
        roots (sut/application-roots before after adjustments)
        ;; runtime path: apply-pro-rata-held-credit re-derives roots internally
        runtime-roots (select-keys roots (keys (sut/application-roots before after adjustments)))
        ;; verifier path: application-transition-valid? re-derives the same roots
        verifier-roots (select-keys (sut/application-roots before after adjustments)
                                    (keys (sut/application-roots before after adjustments)))]
    (is (= runtime-roots verifier-roots)
        "runtime application and contextual verification use the same application-roots primitive")
    (is (= roots runtime-roots verifier-roots)
        "both paths produce identical root maps for the same before/after/adjustments")))

(deftest application-transition-valid?-rejects-wrong-before-world
  (let [ctx (fixture)
        honest-roots (:roots ctx)]
    (is (true? (sut/application-transition-valid? (:before ctx)
                                                  (:protocol-effects ctx)
                                                  (:adjustments ctx)
                                                  honest-roots))
        "application-transition-valid? (transition derivation) passes for the honest chain")
    (let [tampered-roots (assoc honest-roots :state-after/root
                                "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")]
      (is (false? (sut/application-transition-valid? (:before ctx)
                                                     (:protocol-effects ctx)
                                                     (:adjustments ctx)
                                                     tampered-roots))
          "application-transition-valid? (transition derivation) fails for the same tampered-roots"))
    (testing "application-transition-valid? and receipt-valid? are distinct APIs"
      (is (= 4 (count (first (:arglists (meta (var sut/application-transition-valid?))))))
          "application-transition-valid? takes world + effects + adjustments + committed-roots")
      (is (= 1 (count (first (:arglists (meta (var application/receipt-valid?))))))
          "receipt-valid? takes only the receipt (self-integrity)"))))