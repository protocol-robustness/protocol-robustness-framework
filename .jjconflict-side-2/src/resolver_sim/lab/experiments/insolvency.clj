(ns resolver-sim.lab.experiments.insolvency
  "Insolvency / impairment experiment.

   Constructs a minimal, accounting-coherent world (a live escrow custody
   obligation backed exactly by :total-held) and runs the Sew canonical
   solvency classifier
   (resolver-sim.protocols.sew.financial.solvency/classify-solvency) over the
   canonical economic-liability-set.v1 universe.

   The assessment vocabulary (:solvent :impaired :insolvent :unassessable
   :assessment-invalid) and every dimension are produced by the classifier
   itself — the lab only renders them. Accounting reconciliation and economic
   solvency are deliberately kept as separate dimensions, exactly as the
   classifier defines them.

   A recognized (haircut) loss is modeled as a yield position shortfall
   (:principal-loss) so that :impaired is reachable while the ledger remains
   coherent. Insolvency is reached through the observed-balance authority:
   an external custody snapshot below the modeled obligation makes economic
   solvency fail while the internal ledger still reconciles. Requiring
   external coverage with no snapshot reaches :unassessable."
  (:require [resolver-sim.protocols.sew.financial.solvency :as solvency]
            [resolver-sim.protocols.sew.financial.liabilities :as liab]))

(defn- build-world
  [{:keys [custody recognized-loss observed-balances require-external-coverage]}]
  (cond-> {:total-held {:USDC (long custody)}
           :escrow-transfers
           {"lab-wf" {:token :USDC
                      :escrow-state :pending
                      :amount-after-fee (long custody)}}
           :yield/positions
           (if (and recognized-loss (pos? (long recognized-loss)))
             {:lab-loss-position
              {:token :USDC
               :status :active
               :realized-yield 0
               :unrealized-yield 0
               :shortfall {:reason :principal-loss
                           :haircut-amount (long recognized-loss)}}}
             {})
           :claimable-v2 {}
           :claimable {}
           :withdrawn {}
           :fees {}
           :total-fees {}
           :bond-fees {}
           :bond-balances {}
           :pending-fraud-slashes {}
           :slash-credit-liabilities {}
           :senior-bonds {}
           :params {:solvency/token-custody-contracts {:USDC :escrow-vault}}
           :block-time 0}
    observed-balances
    (assoc :solvency/contract-balances
           {[:escrow-vault :USDC] (long observed-balances)})
    require-external-coverage
    (assoc :lab/require-external-coverage true)))

(defn- findings-from-assessment
  [assessment]
  (let [dims (:assessment/dimensions assessment)
        accounting (:accounting dims)
        econ (:economic-solvency dims)
        reserved (:reserved-coverage dims)
        liq (:liquidity dims)]
    [{:findings/id :assessment/accounting
      :findings/status (if (:holds? accounting) :pass :fail)
      :findings/origin :prf
      :findings/label "Accounting ledger reconciles"
      :findings/detail (:status accounting)}
     {:findings/id :assessment/economic-solvency
      :findings/status (if (:holds? econ) :pass :fail)
      :findings/origin :prf
      :findings/label "Assets cover economic liabilities"
      :findings/detail {:assets (:assets econ)
                        :liabilities (:liabilities econ)
                        :ratio (:ratio econ)
                        :observed-vs-ledger (:observed-vs-ledger econ)}}
     {:findings/id :assessment/liquidity
      :findings/status (if (true? (:holds? liq)) :pass :fail)
      :findings/origin :prf
      :findings/label "Liquid assets cover due liabilities"
      :findings/detail {:liquid-assets (:liquid-assets liq)
                        :due-liabilities (:due-liabilities liq)}}
     {:findings/id :assessment/reserved-coverage
      :findings/status (if (true? (:holds? reserved)) :pass :fail)
      :findings/origin :prf
      :findings/label "Reserved coverage within policy bound"
      :findings/detail {:total-reserved (:total-reserved reserved)}}
     {:findings/id :assessment/observed-coverage
      :findings/status (case (:evidence/status assessment)
                         :verified :pass
                         :insufficient :fail
                         :unavailable :inconclusive
                         :stale :inconclusive
                         :invalid-evidence :fail
                         :inconclusive)
      :findings/origin :prf
      :findings/label "External coverage evidence status"
      :findings/detail (:evidence/status assessment)}
     {:findings/id :assessment/commitment
      :findings/status (case (:verification/status assessment)
                         :verified :pass
                         :invalid :fail
                         :inconclusive)
      :findings/origin :prf
      :findings/label "State commitment verifies"
      :findings/detail (:verification/status assessment)}]))

(defn run
  "Run the insolvency/impairment experiment.
   parameters := validated registry parameters."
  [parameters]
  (let [world (build-world parameters)
        committed (solvency/with-commitment world)
        opts (merge {:proof-status :valid}
                    (when (:lab/require-external-coverage committed)
                      {:require-external-coverage? true}))
        assessment (solvency/classify-solvency committed nil opts)
        liability-set (:liability-set assessment)
        findings (findings-from-assessment assessment)
        reasons (sort (map (comp name) (:assessment/reasons assessment)))]
    {:outcome
     {:assessment/status (:assessment/status assessment)
      :assessment/reasons reasons
      :assessment/reason (:assessment/reason assessment)
      :assessment/ratio (:assessment/ratio assessment)
      :evidence/status (:evidence/status assessment)
      :verification/status (:verification/status assessment)
      :assessment/dimensions (:assessment/dimensions assessment)
      :liability-set/entries (:liability-set/entries liability-set)
      :liability-set/exclusions (:liability-set/exclusions liability-set)}
     :assessment
     {:assessment/status (:assessment/status assessment)
      :assessment/label (:assessment/reason assessment)
      :assessment/ratio (:assessment/ratio assessment)}
     :findings findings
     :evidence
     {:roots
      {:liability-set-root (:liability-set/root liability-set)
       :assessment-commitment (:assessment/commitment assessment)}
      :artifacts
      [{:artifact/id :economic-liability-set
        :artifact/ref (:liability-set/root liability-set)}
       {:artifact/id :solvency-commitment
        :artifact/ref (:assessment/commitment assessment)}]}}))
