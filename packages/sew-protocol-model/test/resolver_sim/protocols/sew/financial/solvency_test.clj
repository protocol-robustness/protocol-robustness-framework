(ns resolver-sim.protocols.sew.financial.solvency-test
  "Tests for the canonical solvency assessment.
   Covers the assessment vocabulary (:assessment/status), the orthogonal
   evidence/verification dimensions, the three separate guarantees
   (accounting conservation / economic solvency / observed coverage), and the
   live SHA-256 commitment layer (compute-state-commitment, with-commitment).

   The deprecated five-tier taxonomy is only asserted via :assessment/legacy-tier."
  (:require [clojure.test :refer :all]
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.financial.liabilities :as liab]
            [resolver-sim.protocols.sew.invariants.solvency :as solv-inv]
            [resolver-sim.protocols.sew.types :as t]))

;; ── Classification defaults ──────────────────────────────────────────────────

(deftest empty-world-is-solvent-unproven
  (let [result (solv/classify-solvency (t/empty-world 1000))]
    (is (= :solvent (:assessment/status result))
        "trivially solvent: no liabilities, no assets")
    (is (= :unproven (:assessment/legacy-tier result))
        "legacy tier derived, never authoritative")
    (is (= :unavailable (:evidence/status result))
        "absence of external evidence is explicit, not a silent pass")
    (is (= :unverified (:verification/status result)))))

(deftest reconciliation-failure-is-assessment-invalid-not-insolvent
  (testing "An inconsistent custody ledger is NOT evidence of insolvency"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:total-held :USDC] 500)
                    (assoc-in [:claimable :USDC] 200)
                    (assoc-in [:bond-balances 0 "0xRes0"] 10000))
          result (solv/classify-solvency world)]
      (is (= :assessment-invalid (:assessment/status result)))
      (is (contains? (:assessment/reasons result) :accounting-inconsistent))
      (is (= :insolvent (:assessment/legacy-tier result))))))

(deftest coherent-insolvency-is-insolvent
  (testing "Coherent accounting with assets < liabilities is genuinely insolvent"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:total-held :USDC] 1000)
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 1000
                               :escrow-state :pending})
                    (assoc-in [:slash-credit-liabilities "0xRes0"] 500))
          result (solv/classify-solvency world)]
      (is (= :insolvent (:assessment/status result))
          "slash-credit liability (500) exceeds backing custody (1000 vs 1500 owed)")
      (is (false? (:holds? (solv/economic-solvency? world)))))))

(deftest realized-loss-with-covering-assets-is-impaired
  (testing "A realized haircut with coherent accounting is :impaired, not :insolvent"
    (let [pos {:token :USDC :principal 10000 :realized-yield 0 :unrealized-yield 0
               :status :unwinding
               :shortfall {:fulfilled-amount 8000 :deferred-amount 0
                           :haircut-amount 2000 :reason :principal-loss}}
          ;; settlement: 8000 sub-held out of custody into claimable-v2; the
          ;; 2000 haircut is written off — liability reduced, assets reduced
          world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 0})
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 10000 :escrow-state :released})
                    (assoc-in [:claimable-v2 0 :settlement/principal "recipient"] 8000)
                    (assoc-in [:yield/positions "owner1"] pos))
          result (solv/classify-solvency world)]
      (is (= :impaired (:assessment/status result)))
      (is (contains? (:assessment/reasons result) :realized-loss))
      (is (contains? (:assessment/reasons result) :obligation-haircut))
      (is (= :solvent (get-in result [:assessment/dimensions :economic-solvency :status]))
          "impaired still means obligations covered — never assets < liabilities"))))

;; ── Proof-status mapping ─────────────────────────────────────────────────────

(deftest proof-status-maps-to-verification-and-legacy
  (let [cases [[nil        :unverified :unproven]
               [:unproven  :unverified :unproven]
               [:valid     :invalid    :proof-invalid]   ;; no stored commitment
               [:invalid   :invalid    :proof-invalid]
               [:mismatch  :invalid    :proof-invalid]]]
    (doseq [[proof-status exp-verification exp-legacy] cases]
      (let [result (solv/classify-solvency (t/empty-world 1000) nil
                                           {:proof-status proof-status})]
        (is (= exp-verification (:verification/status result))
            (str "verification/status for proof-status " proof-status))
        (is (= exp-legacy (:assessment/legacy-tier result))
            (str "legacy-tier for proof-status " proof-status))))))

;; ── SHA-256 commitment layer ─────────────────────────────────────────────────

(deftest commitment-deterministic
  (testing "Same world + same prev-commitment produces identical hash"
    (let [world (t/empty-world 1000)
          h1    (solv/compute-state-commitment world nil)
          h2    (solv/compute-state-commitment world nil)]
      (is (string? h1))
      (is (= 64 (count h1)) "SHA-256 hex = 64 chars")
      (is (= h1 h2) "deterministic — same inputs → same hash"))))

(deftest commitment-changes-with-state
  (testing "Different world states produce different commitments"
    (let [w1  (t/empty-world 1000)
          w2  (assoc-in (t/empty-world 1000) [:escrow-transfers 0 :escrow-state] :pending)
          h1  (solv/compute-state-commitment w1 nil)
          h2  (solv/compute-state-commitment w2 nil)]
      (is (not= h1 h2) "different escrow state → different hash"))))

(deftest commitment-binds-liability-and-asset-roots
  (testing "The commitment binds the canonical liability and asset roots"
    (let [base (t/empty-world 1000)
          w1   (assoc-in base [:total-held :USDC] 1000)
          w2   (assoc-in (t/empty-world 1000) [:total-held :DAI] 1000)
          h1   (solv/compute-state-commitment w1 nil)
          h2   (solv/compute-state-commitment w2 nil)]
      (is (not= h1 h2) "different asset composition → different liability/asset roots"))))

(deftest commitment-chains
  (testing "Previous commitment is included in the preimage"
    (let [world (t/empty-world 1000)
          h1    (solv/compute-state-commitment world nil)
          h2    (solv/compute-state-commitment world h1)]
      (is (not= h1 h2) "prev-commitment changes the hash"))))

(deftest with-commitment-stores-hash
  (testing "with-commitment stores commitment-root in world"
    (let [world (solv/with-commitment (t/empty-world 1000))
          sol   (:solvency world)]
      (is (map? sol))
      (is (string? (:commitment-root sol)))
      (is (= 64 (count (:commitment-root sol))))
      (is (nil? (:prev-commitment sol)) "first commitment has no prev"))))

(deftest with-commitment-chains-properly
  (testing "Second call uses first commitment as prev"
    (let [w1 (solv/with-commitment (t/empty-world 1000))
          w2 (solv/with-commitment w1)
          c1 (get-in w1 [:solvency :commitment-root])
          c2 (get-in w2 [:solvency :commitment-root])
          p2 (get-in w2 [:solvency :prev-commitment])]
      (is (= c1 p2) "second commitment's prev = first commitment's root")
      (is (not= c1 c2) "second hash differs from first"))))

(deftest with-commitment-valid-proof
  (testing "After with-commitment, :proof-status :valid produces :solvent + :verified"
    (let [world (solv/with-commitment (t/empty-world 1000))
          result (solv/classify-solvency world nil {:proof-status :valid})]
      (is (= :solvent (:assessment/status result)))
      (is (= :verified (:verification/status result)))
      (is (= :solvent (:assessment/legacy-tier result)))
      (is (string? (:assessment/commitment result))))))

(deftest tampered-world-commitment-mismatch
  (testing "Tampering state after with-commitment produces different hash and invalid verification"
    (let [base    (t/empty-world 1000)
          world   (solv/with-commitment base)
          stored  (get-in world [:solvency :commitment-root])
          tampered (assoc-in world [:escrow-transfers 0 :escrow-state] :disputed)
          computed (solv/compute-state-commitment tampered
                                                  (get-in tampered [:solvency :prev-commitment]))
          result  (solv/classify-solvency tampered nil {:proof-status :valid})]
      (is (string? stored))
      (is (string? computed))
      (is (not= stored computed) "tampered state → different commitment hash")
      (is (= :invalid (:verification/status result))
          "verification fails on tampered state")
      (is (not= :verified (:verification/status result))))))

;; ── Guarantee separation ─────────────────────────────────────────────────────

(deftest economic-solvency-consumes-canonical-liability-set
  (testing "economic-solvency? uses the canonical liability universe incl. slash-credits"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:total-held :USDC] 1000)
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 800 :escrow-state :pending})
                    (assoc-in [:slash-credit-liabilities "0xRes0"] 300))
          result (solv/economic-solvency? world)
          {:keys [per-token]} (liab/economic-liability-set world)]
      (is (= 1100 (get per-token :USDC))
          "escrow 800 + slash-credits 300")
      (is (false? (:holds? result))
          "assets 1000 < liabilities 1100"))))

(deftest reserved-coverage-is-separate-from-economic-solvency
  (testing "Reserved senior coverage is not a base liability"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 1000})
                    (assoc-in [:senior-bonds "0xSenior"]
                              {:coverage-max 500 :reserved-coverage 400}))
          econ (solv/economic-solvency? world)
          cover (solv/reserved-coverage-sufficient? world)]
      (is (:holds? econ) "reserved coverage does not inflate economic liabilities")
      (is (= 400 (:total-reserved cover))))))

(deftest liquidity-sufficiency-measures-due-vs-liquid
  (testing "liquidity-sufficient? checks liquid assets against currently-due liabilities"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 500})
                    (assoc :total-fees {:USDC 100})
                    (assoc-in [:escrow-transfers 7] {:token :USDC})
                    (assoc-in [:claimable-v2 7 :settlement/principal "0xB"] 800))
          result (solv/liquidity-sufficient? world)]
      (is (false? (:holds? result))
          "liquid 600 < due 800"))))

(deftest observed-coverage-is-not-a-silent-pass
  (testing "Missing external evidence is :unavailable with :status :not-evaluated"
    (let [world (t/empty-world 1000)
          result (solv/observed-coverage? world)]
      (is (= :unavailable (:coverage result)))
      (is (= :not-evaluated (:status result)))
      (is (= :unavailable (:evidence/status (solv/classify-solvency world)))
          "the assessment surfaces unavailable evidence")))

  (testing "Sufficient external evidence verifies coverage"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 100})
                    (assoc :solvency/contract-balances {[:escrow-vault :USDC] 150}))
          result (solv/observed-coverage? world)]
      (is (= :verified (:coverage result)))
      (is (:holds? result))))

  (testing "Insufficient external evidence downgrades to :insufficient"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 100})
                    (assoc :solvency/contract-balances {[:escrow-vault :USDC] 50}))
          result (solv/observed-coverage? world)]
      (is (= :insufficient (:coverage result)))
      (is (false? (:holds? result))))))

(deftest require-external-coverage-downgrades-to-unassessable
  (testing "When external coverage is required, missing evidence → :unassessable"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 100})
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 100 :escrow-state :pending}))
          result (solv/classify-solvency world nil {:require-external-coverage? true})]
      (is (= :unassessable (:assessment/status result))))))

(deftest contract-payout-covers-claimable-v2-and-fees
  (testing "contract-payout-solvency? conservatively sums held + claimable-v2 + fees"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 100})
                    (assoc :total-fees {:USDC 20})
                    (assoc-in [:escrow-transfers 7] {:token :USDC})
                    (assoc-in [:claimable-v2 7 :settlement/principal "bob"] 30)
                    (assoc :solvency/contract-balances {[:escrow-vault :USDC] 149}))
          result (solv-inv/contract-payout-solvency? world)
          violation (first (:violations result))]
      (is (false? (:holds? result)))
      (is (= :insufficient (:coverage result)))
      (is (= :contract-payout-shortfall (:type violation)))
      (is (= 1 (:shortfall violation))))))

;; ── Status precedence and semantics ──────────────────────────────────────────

(deftest assessment-invalid-beats-insolvent
  (testing "An inconsistent ledger is :assessment-invalid even when assets < liabilities"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:total-held :USDC] 500)
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 1000 :escrow-state :pending}))
          result (solv/classify-solvency world)]
      (is (= :assessment-invalid (:assessment/status result))
          "precedence: assessment-invalid > insolvent; cannot trust the numbers"))))

(deftest precedence-rule-test
  (testing "insolvent beats impaired; impaired beats solvent"
    (let [insolvent-world (-> (t/empty-world 1000)
                              (assoc-in [:total-held :USDC] 1000)
                              (assoc-in [:escrow-transfers 0]
                                        {:token :USDC :amount-after-fee 1000 :escrow-state :pending})
                              (assoc-in [:slash-credit-liabilities "0xRes0"] 500))
          impaired-world (-> (t/empty-world 1000)
                             (assoc :total-held {:USDC 0})
                             (assoc-in [:escrow-transfers 0]
                                       {:token :USDC :amount-after-fee 10000 :escrow-state :released})
                             (assoc-in [:claimable-v2 0 :settlement/principal "r"] 8000)
                             (assoc-in [:yield/positions "o1"]
                                       {:token :USDC :principal 10000 :status :unwinding
                                        :shortfall {:fulfilled-amount 8000 :deferred-amount 0
                                                    :haircut-amount 2000 :reason :principal-loss}}))]
      (is (= :insolvent (:assessment/status (solv/classify-solvency insolvent-world))))
      (is (= :impaired (:assessment/status (solv/classify-solvency impaired-world)))))))

(deftest four-dimensional-result-carries-dimensions
  (testing "The headline does not carry all meaning; dimensions are explicit"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 1000})
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 1000 :escrow-state :pending}))
          result (solv/classify-solvency world)
          dims (:assessment/dimensions result)]
      (is (= :consistent (get-in dims [:accounting :status])))
      (is (= :solvent (get-in dims [:economic-solvency :status])))
      (is (= 1000 (get-in dims [:economic-solvency :liabilities])))
      (is (= 1000 (get-in dims [:economic-solvency :assets])))
      (is (= :sufficient (get-in dims [:reserved-coverage :status]))))))

(deftest observed-balance-is-authoritative-with-explicit-finding
  (testing "Observed balances replace ledger assets; divergence is an explicit finding"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 1000})
                    (assoc :solvency/contract-balances {[:escrow-vault :USDC] 800})
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 900 :escrow-state :pending}))
          result (solv/classify-solvency world)]
      (is (contains? (:assessment/reasons result) :observed-ledger-mismatch))
      (is (= [{:token :USDC :observed 800 :ledger 1000 :delta -200}]
             (get-in result [:assessment/dimensions :economic-solvency :observed-vs-ledger])))
      (is (= 800 (get-in result [:assessment/dimensions :economic-solvency :assets]))
          "observed balance is authoritative for economic assets"))))

;; ── Liability-set reproducibility artifact ──────────────────────────────────

(deftest liability-artifact-reproducible
  (testing "The liability artifact commits version + policy + entries + exclusions"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 1000})
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 800 :escrow-state :pending})
                    (assoc-in [:slash-credit-liabilities "0xRes0"] 300)
                    (assoc :claimable {0 {"a" 100}}))
          a1 (liab/liability-artifact world)
          a2 (liab/liability-artifact world)]
      (is (= (:liability-set/root a1) (:liability-set/root a2))
          "deterministic — same world → same committed root")
      (is (= "v1" (:liability-set/version a1)))
      (is (seq (:liability-set/entries a1)) "entries recorded")
      (is (some #(= :legacy-claimable (:exclusion/bucket %))
                (:exclusion/decisions (:liability-set/exclusions a1)))
          "exclusion decisions are explicit")
      (is (string? (:liability-set/root a1)))
      (is (= 64 (count (:liability-set/root a1))) "sha-256 hex"))))

(deftest liability-artifact-root-changes-when-selection-changes
  (testing "A different liability universe produces a different committed root"
    (let [w-base (-> (t/empty-world 1000)
                     (assoc-in [:escrow-transfers 0]
                               {:token :USDC :amount-after-fee 800 :escrow-state :pending}))
          w-slash (assoc-in w-base [:slash-credit-liabilities "0xRes0"] 300)
          r-base (:liability-set/root (liab/liability-artifact w-base))
          r-slash (:liability-set/root (liab/liability-artifact w-slash))]
      (is (not= r-base r-slash) "adding a slash-credit obligation changes the root"))))

(deftest commitment-binds-liability-artifact-root
  (testing "The state commitment preimage binds the artifact root"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 800 :escrow-state :pending}))
          with-slash (assoc-in world [:slash-credit-liabilities "0xRes0"] 300)
          h1 (solv/compute-state-commitment world nil)
          h2 (solv/compute-state-commitment with-slash nil)]
      (is (not= h1 h2) "liability selection change is committed"))))
