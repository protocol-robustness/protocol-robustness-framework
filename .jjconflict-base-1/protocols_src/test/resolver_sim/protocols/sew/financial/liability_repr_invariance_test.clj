(ns resolver-sim.protocols.sew.financial.liability-repr-invariance-test
  "Metamorphic representation-invariance tests for the canonical economic
   liability universe.

   Core property: moving an economically IDENTICAL obligation through lifecycle
   representations (pending escrow → settled/claimable-v2 → partially withdrawn
   → fully withdrawn) must NOT alter the economic assessment merely because its
   bookkeeping representation changed. Economic net worth (assets − canonical
   liabilities) changes only when something economically real changes: a real
   loss, a haircut, a new liability, an extinguishment, or a slash credit
   becoming realizable.

   These tests protect economic-liability-set.v1 against double-counting across
   :held / :claimable-v2 / liabilities / settled-but-unwithdrawn claims /
   haircuts / slash credits."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.financial.liabilities :as liab]
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.types :as t]))

(defn- surplus-of
  "Per-token economic surplus (assets − canonical liabilities) for a token."
  [world token]
  (:surplus (get (:per-token (solv/economic-solvency? world)) token)))

;; ── Settlement representation invariance ────────────────────────────────────

(deftest settlement-representation-does-not-change-solvency
  (testing "pending escrow → settled claimable-v2 → withdrawn: economic solvency invariant"
    (let [pending (-> (t/empty-world 1000)
                      (assoc :total-held {:USDC 1000})
                      (assoc-in [:escrow-transfers 0]
                                {:token :USDC :amount-after-fee 1000 :escrow-state :pending}))
          ;; settlement: custody moved out of :total-held into :claimable-v2
          settled (-> (t/empty-world 1000)
                      (assoc :total-held {:USDC 0})
                      (assoc-in [:escrow-transfers 0]
                                {:token :USDC :amount-after-fee 1000 :escrow-state :released})
                      (assoc-in [:claimable-v2 0 :settlement/principal "recipient"] 1000))
          ;; partial withdrawal: 400 of 1000 withdrawn
          partial (-> settled
                      (assoc-in [:claimable-v2 0 :settlement/principal "recipient"] 600)
                      (assoc :total-withdrawn {:USDC 400}))
          ;; full withdrawal
          full (-> settled
                   (assoc :total-withdrawn {:USDC 1000}))]
      (doseq [[label w] {:pending pending :settled settled :partial partial :full full}]
        (testing label
          (is (:holds? (solv/economic-solvency? w))
              "obligation covered at every representation stage")
          (is (zero? (surplus-of w :USDC))
              "net worth identical — bookkeeping representation is not an economic event"))))))

(deftest no-double-count-across-held-and-claimable-v2
  (testing "claimable-v2 appears exactly once as a liability and once as an asset"
    (let [settled (-> (t/empty-world 1000)
                      (assoc :total-held {:USDC 0})
                      (assoc-in [:escrow-transfers 0]
                                {:token :USDC :amount-after-fee 1000 :escrow-state :released})
                      (assoc-in [:claimable-v2 0 :settlement/principal "recipient"] 1000))
          {:keys [per-token]} (liab/economic-liability-set settled)
          assets (liab/custody-assets settled)]
      (is (= 1000 (get per-token :USDC 0)) "liability counted once")
      (is (= 1000 (get assets :USDC 0)) "asset counted once")
      (is (= 1000 (get-in (solv/economic-solvency? settled)
                          [:per-token :USDC :assets])))
      (is (zero? (surplus-of settled :USDC)) "no double-counting drift"))))

;; ── Haircut: a flow, not a stock ────────────────────────────────────────────

(deftest haircut-is-a-flow-not-a-stock
  (testing "a realized haircut changes impairment, never flips to :insolvent"
    (let [before (-> (t/empty-world 1000)
                     (assoc :total-held {:USDC 10000})
                     (assoc-in [:escrow-transfers 0]
                               {:token :USDC :amount-after-fee 10000 :escrow-state :pending}))
          ;; haircut 2000: entitlement and custody both reduced; obligations covered
          after (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 0})
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 10000 :escrow-state :released})
                    (assoc-in [:claimable-v2 0 :settlement/principal "r"] 8000)
                    (assoc-in [:yield/positions "o1"]
                              {:token :USDC :principal 10000 :status :unwinding
                               :shortfall {:fulfilled-amount 8000 :deferred-amount 0
                                           :haircut-amount 2000 :reason :principal-loss}}))]
      (is (zero? (surplus-of before :USDC)))
      (is (zero? (surplus-of after :USDC))
          "surplus invariant — loss was already netted out of both sides")
      (is (= :solvent (:assessment/status (solv/classify-solvency before))))
      (is (= :impaired (:assessment/status (solv/classify-solvency after)))
          "haircut → :impaired, never :insolvent")
      (is (= 8000 (:liabilities (get-in (solv/economic-solvency? after)
                                        [:per-token :USDC])))
          "the 2000 extinguished portion is NOT a remaining liability"))))

;; ── Slash credits: real obligations, assessed only when present ─────────────

(deftest slash-credit-changes-assessment-only-when-real
  (testing "an unbacked slash-credit obligation is a real economic change"
    (let [base (-> (t/empty-world 1000)
                   (assoc :total-held {:USDC 1000})
                   (assoc-in [:escrow-transfers 0]
                             {:token :USDC :amount-after-fee 1000 :escrow-state :pending}))
          unbacked (assoc-in base [:slash-credit-liabilities "0xRes0"] 300)
          backed (-> base
                     (assoc :total-held {:USDC 1300})
                     (assoc-in [:slash-credit-liabilities "0xRes0"] 300))]
      (is (zero? (surplus-of base :USDC)))
      (is (= -300 (surplus-of unbacked :USDC))
          "slash-credit creates a real, unbacked obligation")
      (is (false? (:holds? (solv/economic-solvency? unbacked))))
      (is (= :insolvent (:assessment/status (solv/classify-solvency unbacked))))
      (is (zero? (surplus-of backed :USDC))
          "slash-credit backed by custody is neutral")
      (is (:holds? (solv/economic-solvency? backed))))))

;; ── Cross-token isolation ────────────────────────────────────────────────────

(deftest cross-token-isolation-no-synthetic-aggregation
  (testing "solvency is per-token; a surplus token does not mask a deficit token"
    (let [world (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 1000})
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 1000 :escrow-state :pending})
                    (assoc-in [:escrow-transfers 1]
                              {:token :DAI :amount-after-fee 2000 :escrow-state :pending}))
          econ (solv/economic-solvency? world)]
      (is (false? (:holds? econ))
          "DAI deficit fails the assessment even though USDC is solvent")
      (is (zero? (get-in (:per-token econ) [:USDC :surplus]))
          "USDC is balanced")
      (is (= -2000 (get-in (:per-token econ) [:DAI :surplus]))
          "DAI deficit is per-token; no synthetic cross-token aggregation masks it"))))

;; ── Ratio edge semantics ─────────────────────────────────────────────────────

(deftest ratio-edge-semantics-defined
  (testing "L = 0 semantics are explicit, not incidental"
    (let [empty (assoc (t/empty-world 1000) :total-held {:USDC 0})
          surplus (assoc (t/empty-world 1000) :total-held {:USDC 100})
          rows-empty (:per-token (solv/economic-solvency? empty))
          rows-surplus (:per-token (solv/economic-solvency? surplus))]
      (is (= 1.0 (get-in rows-empty [:USDC :coverage-ratio]))
          "assets=0, liabilities=0 → ratio 1.0 by COVERAGE CONVENTION (no obligations fully covered), not division")
      (is (= ##Inf (get-in rows-surplus [:USDC :coverage-ratio]))
          "assets>0, liabilities=0 → ratio +inf (surplus with no obligations)"))))

;; ── Directionality: the invariance property is NOT too strong ───────────────

(deftest directionality-same-content-different-representation-is-invariant
  (testing "SAME economic content, DIFFERENT bookkeeping representation → identical assessment"
    (let [;; live escrow: obligation backed by custody
          live (-> (t/empty-world 1000)
                   (assoc :total-held {:USDC 1000})
                   (assoc-in [:escrow-transfers 0]
                             {:token :USDC :amount-after-fee 1000 :escrow-state :pending}))
          ;; settled: the same 1000 obligation, now a claimable-v2 payable
          settled (-> (t/empty-world 1000)
                      (assoc :total-held {:USDC 0})
                      (assoc-in [:escrow-transfers 0]
                                {:token :USDC :amount-after-fee 1000 :escrow-state :released})
                      (assoc-in [:claimable-v2 0 :settlement/principal "recipient"] 1000))
          a (solv/classify-solvency live)
          b (solv/classify-solvency settled)]
      (is (= (:assessment/status a) (:assessment/status b))
          "status invariant across representation")
      (is (= :solvent (:assessment/status a)))
      (is (zero? (surplus-of live :USDC)))
      (is (zero? (surplus-of settled :USDC))))))

(deftest directionality-newly-imposed-haircut-changes-assessment
  (testing "A NEWLY imposed haircut (realized asset loss) legitimately changes the assessment"
    (let [before (-> (t/empty-world 1000)
                     (assoc :total-held {:USDC 10000})
                     (assoc-in [:escrow-transfers 0]
                               {:token :USDC :amount-after-fee 10000 :escrow-state :pending}))
          ;; the same 10000 principal, but 2000 of it is now a recognized loss:
          ;; custody and entitlement both reduced to 8000, obligations covered
          after (-> (t/empty-world 1000)
                    (assoc :total-held {:USDC 0})
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 10000 :escrow-state :released})
                    (assoc-in [:claimable-v2 0 :settlement/principal "r"] 8000)
                    (assoc-in [:yield/positions "o1"]
                              {:token :USDC :principal 10000 :status :unwinding
                               :shortfall {:fulfilled-amount 8000 :deferred-amount 0
                                           :haircut-amount 2000 :reason :principal-loss}}))]
      (is (= :solvent (:assessment/status (solv/classify-solvency before))))
      (is (= :impaired (:assessment/status (solv/classify-solvency after)))
          "a real economic change is reflected — the invariance is not too strong"))))

(deftest directionality-extinguished-liability-is-not-counted
  (testing "Settled/extinguished obligations are historical, not outstanding"
    (let [live (-> (t/empty-world 1000)
                   (assoc :total-held {:USDC 1000})
                   (assoc-in [:escrow-transfers 0]
                             {:token :USDC :amount-after-fee 1000 :escrow-state :pending}))
          extinguished (-> (t/empty-world 1000)
                           (assoc :total-held {:USDC 0})
                           (assoc :total-withdrawn {:USDC 1000}))
          {:keys [per-token]} (liab/economic-liability-set extinguished)]
      (is (empty? per-token) "extinguished liability carries no present obligation")
      (is (:holds? (solv/economic-solvency? extinguished)) "trivially covered, nothing owed"))))

;; ── Artifact derivation boundary ─────────────────────────────────────────────

(deftest artifact-root-recomputable-from-entries-and-exclusions
  (testing "A verifier can recompute the committed root from the artifact's own fields"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:escrow-transfers 0]
                              {:token :USDC :amount-after-fee 500 :escrow-state :pending})
                    (assoc-in [:slash-credit-liabilities "0xRes0"] 250)
                    (assoc :claimable {0 {"a" 100}}))
          art (liab/liability-artifact world)
          expected (:liability-set/root art)
          ;; re-derive exactly as liabilities.clj does, from entries + exclusions
          entries (:liability-set/entries art)
          exclusions (get-in art [:liability-set/exclusions :exclusion/decisions])
          policy (:liability-set/policy art)
          sha (fn [lines]
                (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                  (doseq [line lines] (.update md (.getBytes (str line "\n") "UTF-8")))
                  (let [sb (StringBuilder.)]
                    (doseq [b (.digest md)] (.append sb (format "%02x" (bit-and b 0xff))))
                    (.toString sb))))
          recomputed (sha (concat
                           [(str "liability-set/version:v1")
                            (str "policy-root:" (sha (map pr-str (sort-by pr-str (:policy/included policy)))))
                            (str "policy-exclusions-root:" (sha (map pr-str (sort-by pr-str (:policy/excluded policy)))))
                            (str "stable-token:" (pr-str (sort-by pr-str (seq (:liability-set/stable-token art)))))]
                           (map pr-str entries)
                           (map pr-str exclusions)))]
      (is (= expected recomputed)
          "source → policy → stable-token → included+excluded → normalized entries → root is fully derivable"))))
