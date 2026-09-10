(ns resolver-sim.pro-rata.application-receipt-test
  "Regression test for the workbench pro-rata full-application receipt example
   (notebooks/state_after_assurance_workbench.clj section 3).

   The example must produce a valid held account, a refinement account that
   matches the applied adjustment, and a successful applied-receipt — otherwise
   the workbench's \"receipt integrity\" assurance row cannot render :verified."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.effects :as effects]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.protocols.sew.pro-rata-application :as pro-rata]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.pro-rata.evidence :as evidence]
            [resolver-sim.pro-rata.refinement :as refinement]))

(defn- workbench-example
  "Replicates the state-after assurance workbench section 3 example."
  []
  (let [allocation (allocation/allocate {:allocation/id :workbench :available 10
                                         :rows [{:row/id :a :obligation/id :a
                                                 :requested 10 :weight 1 :cap 10}]})
        proposal (evidence/proposed-effects allocation)
        source-id (get-in proposal [:effects 0 :effect/id])
        refinement (refinement/sew-add-held-refinement
                    allocation proposal
                    {source-id {:effect/token :USDC
                                :effect/account :escrow
                                :held/kind :credit}})
        before (types/empty-world)
        ;; `:held/account` must be supplied via the supported `:extra` path; the
        ;; unsupported `:account` opt is silently ignored and yields a nil
        ;; `:held/account`, which then fails the refinement account match.
        after (accounting/add-held before :USDC 10
                                   {:reason :credit :extra {:held/account :escrow}})
        adjustments (:held-adjustments after)
        roots (pro-rata/application-roots before after adjustments)
        authorization (application/authorize
                       {:allocation-root (:allocation/hash allocation)
                        :proposed-effects-root (:proposed-effects/root proposal)
                        :protocol-effect-set-root (:protocol-effect-set/root refinement)
                        :state-before-root (:state-before/root roots)
                        :policy-root "policy" :authorization-root "auth"
                        :consumption-key "once"})
        pairs (mapv (fn [effect adjustment]
                      {:effect/root (:effect/root effect)
                       :adjustment/root (effects/held-adjustment-root adjustment)})
                    (:effects refinement) adjustments)
        applied-refinement (application/applied-adjustment-refinement
                            (:protocol-effect-set/root refinement)
                            (:applied-adjustments/root roots)
                            pairs)
        receipt (application/applied-receipt
                 {:authorization authorization
                  :state-before-root (:state-before/root roots)
                  :state-after-root (:state-after/root roots)
                  :executed-effect-set-root (:protocol-effect-set/root refinement)
                  :protocol-effects (:effects refinement)
                  :applied-adjustments adjustments
                  :applied-adjustment-refinement applied-refinement
                  :ledger-before-root (:ledger-before/root roots)
                  :ledger-after-root (:ledger-after/root roots)})]
    {:refinement refinement
     :adjustments adjustments
     :applied-refinement applied-refinement
     :receipt receipt}))

(deftest workbench-pro-rata-receipt-example-succeeds
  (testing "the workbench example produces a valid held account"
    (let [{:keys [adjustments]} (workbench-example)]
      (is (= 1 (count adjustments)))
      (is (= :escrow (:held/account (first adjustments)))
          "the refinement's :effect/account must surface on the applied adjustment")
      (is (= :credit (:held/reason (first adjustments))))))
  (testing "the refinement account matches the applied adjustment"
    (let [{:keys [refinement adjustments applied-refinement]} (workbench-example)]
      (is (= (get-in (first (:effects refinement)) [:effect :effect/account])
             (:held/account (first adjustments))))
      (is (empty? (application/applied-adjustment-refinement-violations
                   (:effects refinement) adjustments applied-refinement)))))
  (testing "the full-application receipt succeeds"
    (let [{:keys [receipt]} (workbench-example)]
      (is (some? (:applied-effect-receipt/root receipt)))
      (is (application/receipt-valid? receipt))))
  (testing "the unsupported :account opt is not the supported account path"
    (let [world (accounting/add-held (types/empty-world) :USDC 10
                                     {:reason :credit :account :escrow})]
      (is (nil? (:held/account (first (:held-adjustments world))))
          "the :account opt is ignored; use :extra {:held/account ...}")))
  (testing "authorization and transition derivation hold for the example"
    (let [{:keys [refinement adjustments]} (workbench-example)
          before (types/empty-world)
          after (accounting/add-held before :USDC 10
                                     {:reason :credit :extra {:held/account :escrow}})
          roots (pro-rata/application-roots before after adjustments)]
      (is (pro-rata/application-transition-valid?
           before (:effects refinement) adjustments
           (select-keys roots [:state-before/root :state-after/root
                               :ledger-before/root :ledger-after/root]))))))