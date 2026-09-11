(ns resolver-sim.protocols.sew.financial.liabilities-test
  "Tests for the canonical economic liability universe (economic-liability-set.v1)
   and its per-state classification. The liability set is the single source of
   truth consumed by every solvency predicate."
  (:require [clojure.test :refer :all]
            [resolver-sim.protocols.sew.financial.liabilities :as liab]
            [resolver-sim.protocols.sew.types :as t]))

(defn- base-world []
  (t/empty-world 1000))

(deftest empty-world-has-no-liabilities
  (let [{:keys [per-token total]} (liab/economic-liability-set (base-world))]
    (is (= {} per-token))
    (is (zero? total))))

(deftest live-escrow-counted-terminal-not
  (let [world (-> (base-world)
                  (assoc-in [:escrow-transfers 0] {:token :USDC :amount-after-fee 500
                                                   :escrow-state :pending})
                  (assoc-in [:escrow-transfers 1] {:token :USDC :amount-after-fee 700
                                                   :escrow-state :released}))]
    (is (= {:USDC 500} (liab/escrow-liability-by-token world))
        "only live escrow AFAs are liabilities")))

(deftest bonds-and-appeal-bonds-counted
  (let [world (-> (base-world)
                  (assoc-in [:escrow-transfers 0] {:token :USDC})
                  (assoc-in [:bond-balances 0] {"0xRes0" 1000})
                  (assoc-in [:pending-fraud-slashes "s1"]
                            {:appeal-bond-held 200})
                  (assoc-in [:appeal-bond-custody "s1"] {:token :USDC}))]
    (is (= {:USDC 1000} (liab/bond-liability-by-token world)))
    (is (= {:USDC 200} (liab/slash-appeal-bond-liability-by-token world)))))

(deftest yield-liability-includes-accrued-and-deferred
  (let [world (-> (base-world)
                  ;; active position in live escrow → realized + unrealized
                  (assoc-in [:escrow-transfers 0] {:token :USDC :escrow-state :pending})
                  (assoc-in [:yield/positions [:sew/escrow 0]]
                            {:token :USDC :status :active
                             :realized-yield 50 :unrealized-yield 30})
                  ;; unwinding non-resolver position → deferred residue still owed
                  (assoc-in [:yield/positions [:sew/escrow 1]]
                            {:token :USDC :status :unwinding
                             :shortfall {:deferred-amount 200}}))]
    (is (= {:USDC 280} (liab/yield-liability-by-token world))
        "80 accrued + 200 deferred")))

(deftest claimable-v2-counted-by-workflow-token
  (let [world (-> (base-world)
                  (assoc-in [:escrow-transfers 0] {:token :USDC})
                  (assoc-in [:escrow-transfers 1] {:token :DAI})
                  (assoc-in [:claimable-v2 0 :settlement/principal] {"a" 100 "b" 200})
                  (assoc-in [:claimable-v2 0 :settlement/yield] {"a" 50})
                  (assoc-in [:claimable-v2 1 :bond/refund] {"r" 25}))]
    (is (= {:USDC 350 :DAI 25} (liab/claimable-v2-liability-by-token world))
        "settlement domains by workflow token; un-attributable entries skipped")))

(deftest slash-credits-included-and-attributed
  (let [world (-> (base-world)
                  (assoc-in [:slash-credit-liabilities "0xRes0"] 500)
                  (assoc-in [:slash-credit-liabilities "0xRes1"] 250))]
    (is (= {:USDC 750} (liab/slash-credit-liability-by-token world))
        "slash credits are stable-denominated protocol obligations")))

(deftest recognized-loss-excluded-from-liabilities
  (testing "A haircut is a flow, not a stock: the extinguished portion is not owed"
    (let [pos {:token :USDC :status :unwinding
               :shortfall {:fulfilled-amount 8000 :deferred-amount 0
                           :haircut-amount 2000 :reason :principal-loss}}
          world (-> (base-world)
                    (assoc-in [:yield/positions "owner1"] pos))
          {:keys [per-token total]} (liab/economic-liability-set world)]
      (is (zero? (get per-token :USDC 0)))
      (is (zero? total))
      (is (= {:USDC 2000} (liab/recognized-loss-by-token world))
          "loss tracked separately for conservation/impaired status"))))

(deftest reserved-coverage-excluded-from-base-liabilities
  (let [world (-> (base-world)
                  (assoc-in [:senior-bonds "0xSenior"]
                            {:coverage-max 500 :reserved-coverage 400}))
          {:keys [per-token]} (liab/economic-liability-set world)]
    (is (empty? per-token)
        "reserved senior coverage is capital, not a second liability")))

(deftest legacy-claimable-excluded-dual-write
  (let [world (-> (base-world)
                  (assoc-in [:escrow-transfers 0] {:token :USDC})
                  (assoc :claimable {0 {"a" 100}})
                  (assoc-in [:claimable-v2 0 :settlement/principal] {"a" 100}))]
    (is (= {:USDC 100} (liab/claimable-v2-liability-by-token world))
        "legacy claimable dual-writes settlement and must not be added")))

(deftest custody-assets-are-held-plus-claimable
  (let [world (-> (base-world)
                  (assoc :total-held {:USDC 1000})
                  (assoc-in [:escrow-transfers 0] {:token :USDC})
                  (assoc-in [:claimable-v2 0 :settlement/principal] {"a" 300}))]
    (is (= {:USDC 1300} (liab/custody-assets world))
        "settled-but-unwithdrawn claims remain physically in custody")))

(deftest version-is-v1
  (is (= "v1" (:version (liab/economic-liability-set (base-world))))))

(deftest string-and-keyword-tokens-concatenate-into-one-bucket
  (testing "REGRESSION: \"USDC\" and :USDC must unify — otherwise the liability
            map splits and solvency is understated (a slash-credit under :USDC
            would not be matched against \"USDC\" assets)"
    (let [world (-> (base-world)
                    (assoc :total-held {"USDC" 1000})
                    (assoc-in [:escrow-transfers 0]
                              {:token "USDC" :amount-after-fee 500 :escrow-state :pending})
                    (assoc-in [:slash-credit-liabilities "0xRes"] 300))
          {:keys [per-token]} (liab/economic-liability-set world)
          rows (liab/asset-liability-rows world nil)]
      (is (= 800 (get per-token :USDC))
          "escrow (string) + slash-credit (keyword) concatenate under one :USDC bucket")
      (is (nil? (get per-token "USDC"))
          "no residual string key")
      (is (= 1000 (get-in rows [:USDC :assets]))
          "assets lookup sees the same unified token")
      (is (= 800 (get-in rows [:USDC :liabilities]))))))

;; ── Assessment reproducibility artifact ──────────────────────────────────────

(deftest artifact-records-entries-and-exclusions
  (let [world (-> (base-world)
                  (assoc-in [:escrow-transfers 0] {:token :USDC :amount-after-fee 500
                                                   :escrow-state :pending})
                  (assoc :claimable {0 {"a" 100}})
                  (assoc-in [:slash-credit-liabilities "0xRes0"] 250))
        art (liab/liability-artifact world)
        exclusions (get-in art [:liability-set/exclusions :exclusion/decisions])]
    (is (= "v1" (:liability-set/version art)))
    (is (string? (:liability-set/root art)))
    (is (= 64 (count (:liability-set/root art))))
    (is (= 750 (get-in art [:liability-set/entries 0 :amount]))
        "entry amount aggregates the canonical set (500 escrow + 250 slash-credit)")
    (is (= {:escrow 500 :slash-credits 250} (get-in art [:liability-set/entries 0 :buckets]))
        "entry carries per-bucket attribution")
    (is (= 100 (->> exclusions
                    (filter #(= :legacy-claimable (:exclusion/bucket %)))
                    first :excluded-amount))
        "legacy claimable exclusion is explicit with its amount")
    (is (some #(= :reserved-senior-coverage (:exclusion/bucket %)) exclusions)
        "reserved senior coverage exclusion decision is recorded")
    (is (some #(= :haircutted (:exclusion/bucket %)) exclusions)
        "haircut exclusion decision is recorded")))

(deftest artifact-deterministic-and-sensitive
  (let [w1 (-> (base-world)
               (assoc-in [:escrow-transfers 0] {:token :USDC :amount-after-fee 500
                                                :escrow-state :pending}))
        w2 (assoc-in w1 [:slash-credit-liabilities "0xRes0"] 100)
        r1 (:liability-set/root (liab/liability-artifact w1))
        r1' (:liability-set/root (liab/liability-artifact w1))
        r2 (:liability-set/root (liab/liability-artifact w2))]
    (is (= r1 r1') "deterministic — same world → same root")
    (is (not= r1 r2) "selection change → different root")))

(deftest artifact-source-roots-present
  (let [art (liab/liability-artifact (base-world))]
    (is (contains? (:liability-set/source art) :escrow-transfers))
    (is (contains? (:liability-set/source art) :claimable-v2))
    (is (contains? (:liability-set/source art) :slash-credit-liabilities))
    (is (contains? (:liability-set/source art) :senior-bonds))
    (is (string? (get-in art [:liability-set/source :escrow-transfers]))
        "source roots are hashes a verifier can recompute")))
