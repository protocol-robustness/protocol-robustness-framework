(ns resolver-sim.protocols.sew.financial.concentration-test
  "Concentration (structural risk) in relation to insolvency.

   Concentration asks HOW solvency/headroom exposure is distributed, not
   whether assets cover liabilities. A portfolio concentrated in one token, one
   workflow, or one custody contract is structurally brittle: a single failure
   can tip it into insolvency even when the aggregate ratio is comfortable.

   Also covers CONCATENATION correctness: the canonical liability buckets
   (:escrow, :bonds, :appeal-bonds, :yield, :claimable-v2, :slash-credits) are
   pairwise disjoint — no obligation is counted twice when per-token totals are
   concatenated."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.financial.liabilities :as liab]
            [resolver-sim.protocols.sew.financial.concentration :as conc]
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.types :as t]))

;; ── HHI semantics ────────────────────────────────────────────────────────────

(deftest hhi-basics
  (is (= 1.0 (conc/normalized-hhi [100])))
  (is (= 1.0 (conc/normalized-hhi [10 0 0])))
  (is (zero? (conc/normalized-hhi [])))
  (is (zero? (conc/normalized-hhi [0 0])))
  (is (< (conc/normalized-hhi [50 50]) (conc/normalized-hhi [90 10]))
      "more equal → lower concentration")
  (is (< (conc/hhi [33 33 34]) (conc/hhi [50 50]))
      "more parts → lower RAW HHI; normalized HHI is ~0 for any equal split"))

(deftest concentration-bands
  (is (= :diversified (conc/classify 0.05)))
  (is (= :moderately-concentrated (conc/classify 0.2)))
  (is (= :highly-concentrated (conc/classify 0.5))))

;; ── Concentration measures ───────────────────────────────────────────────────

(deftest single-token-portfolio-is-highly-concentrated
  (let [world (-> (t/empty-world 1000)
                  (assoc :total-held {"USDC" 1000})
                  (assoc-in [:escrow-transfers 0]
                            {:token "USDC" :amount-after-fee 1000 :escrow-state :pending}))
        profile (conc/concentration-profile world)]
    (is (= :highly-concentrated (get-in profile [:liabilities :band]))
        "all obligations in one token → single-asset exposure")
    (is (= :highly-concentrated (get-in profile [:assets :band])))
    (is (conc/concentration-risk? profile))
    (is (= :unavailable (get-in profile [:custodians :status]))
        "no external snapshot → fail-closed unavailable, not 'diversified'")))

(deftest multi-token-portfolio-is-more-diversified
  (let [world (-> (t/empty-world 1000)
                  (assoc :total-held {"USDC" 1000 "DAI" 1000})
                  (assoc-in [:escrow-transfers 0]
                            {:token "USDC" :amount-after-fee 1000 :escrow-state :pending})
                  (assoc-in [:escrow-transfers 1]
                            {:token "DAI" :amount-after-fee 1000 :escrow-state :pending}))
        profile (conc/concentration-profile world)]
    (is (< (get-in profile [:liabilities :normalized-hhi]) 0.01)
        "two equal tokens → near-zero normalized HHI")
    (is (= :diversified (get-in profile [:liabilities :band])))
    (is (not (conc/concentration-risk? profile)))))

(deftest obligation-concentration-sees-a-single-workflow
  (let [world (-> (t/empty-world 1000)
                  (assoc :total-held {"USDC" 10000})
                  (assoc-in [:escrow-transfers 0]
                            {:token "USDC" :amount-after-fee 9000 :escrow-state :pending})
                  (assoc-in [:escrow-transfers 1]
                            {:token "USDC" :amount-after-fee 1000 :escrow-state :pending}))
        ob (conc/obligation-concentration world)]
    (is (= :highly-concentrated (:band ob))
        "one workflow holds 90% of obligations — visible even within one token")))

(deftest custodian-concentration-reads-external-snapshot
  (let [world (assoc (t/empty-world 1000) :solvency/contract-balances
                     {[:escrow-vault "USDC"] 5000
                      [:secondary-vault "USDC"] 5000})
        c (conc/custodian-concentration world)]
    (is (= :evaluated (:status c)))
    (is (= 2 (:count c)))
    (is (< (get-in c [:normalized-hhi]) 0.01) "two equal contracts → diversified")
    (is (= :diversified (:band c)))))

(deftest concentration-is-reported-in-the-assessment
  (testing "concentration is a committed assessment dimension (structural, not a verdict)"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {"USDC" 1000})
                    (assoc-in [:escrow-transfers 0]
                              {:token "USDC" :amount-after-fee 1000 :escrow-state :pending}))
          result (solv/classify-solvency world)]
      (is (= :highly-concentrated (get-in result [:assessment/dimensions :concentration :liabilities :band])))
      (is (contains? (:assessment/reasons result) :concentration-risk))
      (is (= :solvent (:assessment/status result))
          "concentration qualifies solvency; it does not by itself declare insolvency"))))

;; ── Concatenation correctness: liability buckets are pairwise disjoint ───────

(deftest liability-buckets-are-pairwise-disjoint
  (testing "no obligation is double-counted when per-token totals concatenate buckets.
            The six buckets read PAIRWISE-DISJOINT world sources, so the canonical
            liability total is the DISJOINT UNION of the included buckets (identity
            preserved), not merely their arithmetic sum."
    (let [world (-> (t/empty-world 1000)
                    ;; 1. live escrow on workflow 0
                    (assoc-in [:escrow-transfers 0]
                              {:token "USDC" :amount-after-fee 500 :escrow-state :pending})
                    ;; 2. active bond on workflow 0
                    (assoc-in [:bond-balances 0] {"0xRes" 100})
                    ;; 3. slash-appeal bond held pending
                    (assoc-in [:pending-fraud-slashes "s1"] {:appeal-bond-held 200})
                    (assoc-in [:appeal-bond-custody "s1"] {:token "USDC"})
                    ;; 4. live yield on workflow 0 (realized + unrealized)
                    (assoc-in [:yield/positions [:sew/escrow 0]]
                              {:token "USDC" :status :active
                               :realized-yield 30 :unrealized-yield 20})
                    ;; 5. settled claimable-v2 on workflow 1 (released escrow, not live)
                    (assoc-in [:escrow-transfers 1]
                              {:token "USDC" :amount-after-fee 700 :escrow-state :released})
                    (assoc-in [:claimable-v2 1 :settlement/principal] {"r" 200})
                    ;; 6. slash-credit obligation
                    (assoc-in [:slash-credit-liabilities "0xRes"] 50))
          {:keys [per-token buckets]} (liab/economic-liability-set world)
          expected 1100  ; 500 + 100 + 200 + 50 + 200 + 50
          sum-of-buckets (reduce + 0
                                 (for [[_ m] buckets] (get m :USDC 0)))]
      (is (= expected (get per-token :USDC))
          "per-token total is the disjoint union of all six buckets")
      (is (= sum-of-buckets (get per-token :USDC))
          "concatenating the disjoint buckets reproduces the per-token total exactly"))))
