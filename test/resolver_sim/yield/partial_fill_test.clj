(ns resolver-sim.yield.partial-fill-test
  "Tests for partial-fill settlement decisions: pro-rata, principal-first,
   waterfall modes, recovery, haircut, and multi-escrow isolation."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.yield.partial-fill :as pf]
            [resolver-sim.yield.position :as pos]))

(defn- closed-form-checks
  "Call closed-form checks, returning results even when checks fail."
  [decision]
  (try
    (pf/partial-fill-closed-form-checks decision)
    (catch clojure.lang.ExceptionInfo e
      (:check-results (ex-data e)))))

(deftest capped-shared-withdrawal-decision-and-mechanism-evidence-are-parallel-invariant
  (let [rows (mapv (fn [i]
                     {:key (keyword (str "claim-" i))
                      :obligation-id (keyword (str "obligation-" i))
                      :source-position-id (keyword (str "position-" i))
                      :owed 10 :weight 1
                      :cap (if (< i 4) 2 10)})
                   (range 16))
        policy {:mode :pro-rata :rounding-policy :largest-remainder}
        run (fn [parallelism]
              (binding [payoffs/*pro-rata-parallel-threshold* 1]
                (pf/calculate-fulfillment-pro-rata
                 101 {} policy
                 {:rows rows :execution/claimant-parallelism parallelism})))
        serial (run 1)
        parallel (run 2)
        artifact-position {:owner/id "parallel-test-owner"
                           :module/id :parallel-test-module
                           :token "USDC"}
        serial-artifact (pf/decision-artifact artifact-position serial)
        parallel-artifact (pf/decision-artifact artifact-position parallel)]
    (is (= serial parallel))
    (is (= (get-in serial [:evidence :allocation-rows])
           (get-in parallel [:evidence :allocation-rows])))
    (is (= (get-in serial [:evidence :allocation-passes])
           (get-in parallel [:evidence :allocation-passes])))
    (is (= (get-in serial [:evidence :redistribution])
           (get-in parallel [:evidence :redistribution])))
    (is (= (get-in serial [:evidence :allocation-mechanism-evidence])
           (get-in parallel [:evidence :allocation-mechanism-evidence])))
    (is (= serial-artifact parallel-artifact))
    (is (pf/decision-hash-valid? parallel-artifact))))

(def base-position
  (pos/normalize-position
   {:owner/id "user1"
    :module/id :test-mod
    :token "USDC"
    :principal 10000
    :shares 10000
    :entry-index 1
    :realized-yield 500
    :unrealized-yield 300
    :deferred-yield 200
    :haircut-yield 0
    :status :active}))

(deftest test-full-fill-when-sufficient-liquidity
  (testing "Full fill when liquidity covers all claims"
    (let [decision (pf/calculate-fulfillment 20000 base-position)]
      (is (= :full-fill (:settlement-mode decision)))
      (is (= 10000 (get-in decision [:filled :principal])))
      (is (= 500 (get-in decision [:filled :realized-yield])))
      (is (= 200 (get-in decision [:filled :deferred-yield])))
      (is (= 0 (get-in decision [:deferred :principal] 0)))
      (is (= 0 (get-in decision [:deferred :realized-yield] 0))))))

(deftest test-partial-fill-pro-rata
  (testing "Pro-rata partial fill distributes proportionally"
    (let [policy {:mode :pro-rata
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 5350 base-position policy)
          filled (:filled decision)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (> (get filled :principal 0) 0) "Principal gets some")
      (is (> (get filled :realized-yield 0) 0) "Realized yield gets some")
      (is (> (get filled :deferred-yield 0) 0) "Deferred yield gets some"))))

(deftest test-pro-rata-row-allocation-reports-progress
  (let [progress (payoffs/make-pro-rata-progress-atom)
        decision (pf/calculate-fulfillment-pro-rata
                  50
                  {:a 40 :b 60}
                  {:mode :pro-rata :rounding-policy :largest-remainder}
                  {:rows [{:key :a :owed 40 :weight 40 :cap 40}
                          {:key :b :owed 60 :weight 60 :cap 60}]
                   :progress-atom progress})]
    (is (= :partial-fill (:settlement-mode decision)))
    (is (= {:a 20 :b 30} (:filled decision)))
    (is (= "pro-rata-allocation-result.v1"
           (get-in decision [:evidence :allocation-mechanism :schema-version])))
    (is (= {:id :mechanism/pro-rata-allocation :version 1}
           (get-in decision [:evidence :allocation-mechanism :mechanism])))
    (is (string? (get-in decision [:evidence :allocation-mechanism :allocation/hash])))
    (is (= {:status :completed
            :phase :completed
            :current 2
            :total 2}
           (select-keys @progress [:status :phase :current :total])))))

(deftest test-partial-fill-principal-first
  (testing "Principal-first fill protects principal"
    (let [policy {:mode :principal-first
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 8000 base-position policy)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 8000 (get-in decision [:filled :principal])))
      (is (= 2000 (get-in decision [:deferred :principal])))
      (is (= 0 (get-in decision [:filled :realized-yield] 0))
          "Yield should get nothing when principal not fully covered"))))

(deftest test-partial-fill-principal-first-with-remainder
  (testing "Principal-first fill with liquidity exceeding principal"
    (let [policy {:mode :principal-first
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 10300 base-position policy)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 10000 (get-in decision [:filled :principal])))
      (is (= 0 (get-in decision [:deferred :principal] 0)))
      (is (>= (get-in decision [:filled :realized-yield] 0) 0)))))

(deftest test-partial-fill-waterfall
  (testing "Waterfall fill respects fill order"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 10200 base-position policy)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 10000 (get-in decision [:filled :principal])))
      (is (= 200 (get-in decision [:filled :realized-yield])))
      (is (= 0 (get-in decision [:filled :deferred-yield] 0))))))

(deftest test-waterfall-custom-fill-order
  (testing "Waterfall respects custom fill-order"
    (let [policy {:mode :waterfall
                  :fill-order [:realized-yield :principal :deferred-yield]
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 400 base-position policy)]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 400 (get-in decision [:filled :realized-yield])))
      (is (zero? (get-in decision [:filled :principal] 0))))))

(deftest test-partial-fill-followed-by-recovery
  (testing "Post-partial-fill recovery restores claimable status"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]
                  :unrealized-yield-treatment :not-claimable
                  :post-partial-fill-accrual :accrue-residual-as-unrealized}
          decision (pf/calculate-fulfillment 10200 base-position policy)
          updated-pos (pf/post-partial-fill-position base-position decision)]
      (is (:partial-fill-affected? updated-pos))
      (is (= :unwinding (:status updated-pos)))
      (is (< (:principal updated-pos 0) 10000) "Principal reduced by filled amount")
      (is (> (:unrealized-yield updated-pos 0) (:unrealized-yield base-position 0))
          "Deferred amounts accrued as unrealized"))))

(deftest test-partial-fill-followed-by-haircut
  (testing "Post-partial-fill position can track haircut"
    (let [decision {:settlement-mode :partial-fill
                    :filled {:principal 5000}
                    :deferred {:principal 3000}
                    :haircut {:principal 2000}
                    :requested {:principal 10000}
                    :policy {:unrealized-yield-treatment :not-claimable
                             :post-partial-fill-accrual :accrue-residual-as-unrealized}
                    :evidence {}}
          updated-pos (pf/post-partial-fill-position base-position decision)]
      (is (:partial-fill-affected? updated-pos))
      ;; After 5000 fill, the 5000 residual is reclassified: 3000 to unrealized, 2000 to haircut.
      ;; Principal bucket should be 0 to avoid double counting.
      (is (= 0 (:principal updated-pos)))
      (is (= 3300 (:unrealized-yield updated-pos)) "Base 300 + 3000 deferred")
      (is (= 2000 (:haircut-yield updated-pos))))))

(deftest test-multi-escrow-same-module-shortfall-isolation
  (testing "Shortfall in one escrow does not affect another in same module"
    (let [pos1 (assoc base-position :owner/id "user1")
          pos2 (assoc base-position :owner/id "user2")
          decision1 (pf/calculate-fulfillment 5350 pos1)
          decision2 (pf/calculate-fulfillment 20000 pos2)]
      (is (= :partial-fill (:settlement-mode decision1)))
      (is (= :full-fill (:settlement-mode decision2))
          "Position 2 with sufficient liquidity should fill completely"))))

(deftest test-separate-module-unaffected
  (testing "Shortfall in one module does not affect a different module"
    (let [pos-mod1 (assoc base-position :module/id :mod1)
          pos-mod2 (assoc base-position :module/id :mod2)
          decision1 (pf/calculate-fulfillment 5350 pos-mod1)
          decision2 (pf/calculate-fulfillment 20000 pos-mod2)]
      (is (= :partial-fill (:settlement-mode decision1)))
      (is (= :full-fill (:settlement-mode decision2))))))

(deftest test-unrealized-yield-not-claimable-by-default
  (testing "Unrealized yield is excluded from requested by default"
    (let [decision (pf/calculate-fulfillment 20000 base-position)]
      (is (not (contains? (:requested decision) :unrealized-yield))
          "Unrealized yield should not be in requested by default"))))

(deftest test-unrealized-yield-claimable-when-configured
  (testing "Unrealized yield can be included when configured"
    (let [policy {:mode :waterfall
                  :unrealized-yield-treatment :claimable}
          decision (pf/calculate-fulfillment 20000 base-position policy)]
      (is (contains? (:requested decision) :unrealized-yield)
          "Unrealized yield should be in requested when claimable"))))

(deftest test-zero-requested-buckets-excluded
  (testing "Buckets with zero value are excluded from requested"
    (let [pos (assoc base-position :realized-yield 0 :deferred-yield 0)
          decision (pf/calculate-fulfillment 20000 pos)]
      (is (not (contains? (:requested decision) :realized-yield)))
      (is (not (contains? (:requested decision) :deferred-yield))))))

(deftest test-partial-fill-preserves-total-accounting
  (testing "Filled + deferred = requested for each bucket under waterfall"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 10200 base-position policy)]
      (doseq [k (keys (:requested decision))]
        (is (= (long (get-in decision [:requested k] 0))
               (+ (long (get-in decision [:filled k] 0))
                  (long (get-in decision [:deferred k] 0))))
            (str "Bucket " k " should conserve: filled + deferred = requested"))))))

(deftest test-partial-fill-closed-form-checks-pro-rata
  (testing "closed-form partial-fill checks pass on a valid pro-rata decision"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)]
      (is (= #{:pass :not-applicable}
             (set (map :status checks)))))))

(deftest largest-remainder-dust-is-not-a-strict-pro-rata-violation
  (let [decision {:settlement-mode :partial-fill
                  :requested {:a 100 :b 100 :c 100}
                  :filled {:a 4 :b 3 :c 3}
                  :deferred {:a 96 :b 97 :c 97}
                  :haircut {}
                  :policy {:mode :pro-rata :rounding-policy :largest-remainder}
                  :evidence {:available-liquidity 10}}
        checks (closed-form-checks decision)
        by-id (into {} (map (juxt :check/id identity) checks))]
    (is (= :not-applicable (:status (get by-id :partial-fill/exact-pro-rata))))
    (is (= :pass (:status (get by-id :partial-fill/rounding-fairness))))
    (is (= :pass (:status (get by-id :partial-fill/rounding-fairness-ideal))))
    (is (= :pass (:status (get by-id :partial-fill/rounding-fairness-remainder-ranking))))))

(deftest test-partial-fill-closed-form-checks-detect-fairness-failure
  (testing "cross-product check catches non-pro-rata allocation"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 10 :b 40}
                    :deferred {:a 30 :b 20}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          fairness (first (filter #(= :partial-fill/exact-pro-rata
                                      (:check/id %))
                                  checks))]
      (is (= :fail (:status fairness)))
      (is (seq (get-in fairness [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-fail-action-fairness
  (testing "fail-action fairness check passes when the deferred shortfall is pro-rata"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 200 :c 300}
                    :filled {:a 10 :b 20 :c 30}
                    :deferred {:a 90 :b 180 :c 270}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :largest-remainder}
                    :evidence {:available-liquidity 60 :total-requested 600 :shortage 540}}
          checks (closed-form-checks decision)
          fail-action (first (filter #(= :partial-fill/fail-action-fairness
                                         (:check/id %))
                                     checks))]
      (is (= :pass (:status fail-action)))
      (is (= [] (get-in fail-action [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-fail-action-catches-unfair-split
  (testing "fail-action fairness catches a deferred/haircut split concentrated on one claimant"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 200 :c 300}
                    :filled {:a 10 :b 20 :c 30}
                    :deferred {:a 90 :b 180 :c 0}
                    :haircut {:a 0 :b 0 :c 270}
                    :policy {:mode :pro-rata :rounding-policy :largest-remainder}
                    :evidence {:available-liquidity 60 :total-requested 600 :shortage 540}}
          checks (closed-form-checks decision)
          fail-action (first (filter #(= :partial-fill/fail-action-fairness
                                         (:check/id %))
                                     checks))
          buckets (set (map :bucket (get-in fail-action [:details :violations])))]
      (is (= :fail (:status fail-action)))
      (is (seq (get-in fail-action [:details :violations])))
      (is (contains? buckets :deferred))
      (is (contains? buckets :haircut)))))

(deftest test-partial-fill-closed-form-checks-fail-action-honors-declared-policy
  (testing "fail-action fairness conforms to a declared :contractual policy rather than enforcing same-ratio"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 200 :c 300}
                    :filled {:a 10 :b 20 :c 30}
                    :deferred {:a 90 :b 180 :c 0}
                    :haircut {:a 0 :b 0 :c 270}
                    :policy {:mode :pro-rata :rounding-policy :largest-remainder
                             :fail-action-policy {:mode :pro-rata-treatment
                                                  :deferred-policy :contractual
                                                  :haircut-policy :contractual}}
                    :evidence {:available-liquidity 60 :total-requested 600 :shortage 540}}
          checks (closed-form-checks decision)
          fail-action (first (filter #(= :partial-fill/fail-action-fairness
                                         (:check/id %))
                                     checks))]
      (is (= :pass (:status fail-action)))
      (is (= [] (get-in fail-action [:details :violations])))
      (is (= :contractual (get-in fail-action [:details :fail-action-policy :deferred-policy])))
      (is (some? (get-in fail-action [:details :fail-action-policy-root]))))))

(deftest test-partial-fill-closed-form-checks-fail-action-not-applicable-without-fail
  (testing "fail-action fairness is not-applicable when nothing is deferred or haircut"
    (let [decision {:settlement-mode :full-fill
                    :requested {:a 100 :b 200 :c 300}
                    :filled {:a 100 :b 200 :c 300}
                    :deferred {}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :largest-remainder}
                    :evidence {:available-liquidity 600 :total-requested 600 :shortage 0}}
          checks (closed-form-checks decision)
          fail-action (first (filter #(= :partial-fill/fail-action-fairness
                                         (:check/id %))
                                     checks))]
      (is (= :not-applicable (:status fail-action)))
      (is (= "no deferred or haircut amounts (no fail action exercised)"
             (get-in fail-action [:details :reason]))))))

(deftest test-partial-fill-closed-form-checks-fail-action-passes-under-lr-dust
  (testing "fail-action fairness passes under largest-remainder dust: deferred is the fill complement"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 100 :c 100}
                    :filled {:a 4 :b 3 :c 3}
                    :deferred {:a 96 :b 97 :c 97}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :largest-remainder}
                    :evidence {:available-liquidity 10}}
          checks (closed-form-checks decision)
          fail-action (first (filter #(= :partial-fill/fail-action-fairness
                                         (:check/id %))
                                     checks))]
      (is (= :pass (:status fail-action))))))
(deftest test-partial-fill-closed-form-checks-per-claim-conservation-violation
  (testing "per-claim-conservation catches filled+deferred != requested"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 25}
                    :deferred {:a 15 :b 35}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          per-claim (first (filter #(= :partial-fill/per-claim-conservation
                                       (:check/id %))
                                   checks))]
      (is (= :fail (:status per-claim)))
      (is (seq (get-in per-claim [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-claim-key-consistency-violation
  (testing "claim-key-consistency catches phantom keys in filled/deferred/haircut"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30 :c 10}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          integrity (first (filter #(= :partial-fill/claim-key-consistency
                                       (:check/id %))
                                   checks))]
      (is (= :fail (:status integrity)))
      (is (seq (get-in integrity [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-principal-first-priority-pass
  (testing "principal-first priority passes when correctly ordered"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 50}
                    :filled {:principal 100 :realized-yield 30}
                    :deferred {:realized-yield 20}
                    :haircut {}
                    :policy {:mode :principal-first}
                    :evidence {:available-liquidity 130}}
          checks (closed-form-checks decision)
          priority (first (filter #(= :partial-fill/principal-first-priority
                                      (:check/id %))
                                  checks))]
      (is (= :pass (:status priority))))))

(deftest test-partial-fill-closed-form-checks-principal-first-priority-violation
  (testing "principal-first catches yield filled before principal fully satisfied"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 50}
                    :filled {:principal 80 :realized-yield 20}
                    :deferred {:principal 20 :realized-yield 30}
                    :haircut {}
                    :policy {:mode :principal-first}
                    :evidence {:available-liquidity 100}}
          checks (closed-form-checks decision)
          priority (first (filter #(= :partial-fill/principal-first-priority
                                      (:check/id %))
                                  checks))]
      (is (= :fail (:status priority)))
      (is (seq (get-in priority [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-principal-first-not-applicable
  (testing "principal-first priority is not-applicable outside principal-first mode"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 50}
                    :filled {:principal 80 :realized-yield 20}
                    :deferred {:principal 20 :realized-yield 30}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 100}}
          checks (closed-form-checks decision)
          priority (first (filter #(= :partial-fill/principal-first-priority
                                      (:check/id %))
                                  checks))]
      (is (= :not-applicable (:status priority))))))

(deftest test-partial-fill-closed-form-checks-waterfall-priority-pass
  (testing "waterfall priority passes when fill-order is respected"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 50 :deferred-yield 30}
                    :filled {:principal 100 :realized-yield 50}
                    :deferred {:deferred-yield 30}
                    :haircut {}
                    :policy {:mode :waterfall
                             :fill-order [:principal :realized-yield :deferred-yield]}
                    :evidence {:available-liquidity 150}}
          checks (closed-form-checks decision)
          priority (first (filter #(= :partial-fill/waterfall-priority
                                      (:check/id %))
                                  checks))]
      (is (= :pass (:status priority))))))

(deftest test-partial-fill-closed-form-checks-waterfall-priority-violation
  (testing "waterfall catches lower bucket filled before higher is fully satisfied"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 50 :deferred-yield 30}
                    :filled {:principal 80 :deferred-yield 10}
                    :deferred {:principal 20 :realized-yield 50 :deferred-yield 20}
                    :haircut {}
                    :policy {:mode :waterfall
                             :fill-order [:principal :realized-yield :deferred-yield]}
                    :evidence {:available-liquidity 90}}
          checks (closed-form-checks decision)
          priority (first (filter #(= :partial-fill/waterfall-priority
                                      (:check/id %))
                                  checks))]
      (is (= :fail (:status priority)))
      (is (seq (get-in priority [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-waterfall-priority-not-applicable
  (testing "waterfall priority is not-applicable outside waterfall mode"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 50}
                    :filled {:principal 80 :realized-yield 20}
                    :deferred {:principal 20 :realized-yield 30}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 100}}
          checks (closed-form-checks decision)
          priority (first (filter #(= :partial-fill/waterfall-priority
                                      (:check/id %))
                                  checks))]
      (is (= :not-applicable (:status priority))))))

(deftest test-partial-fill-closed-form-checks-waterfall-not-applicable
  (testing "pro-rata fairness is explicitly not-applicable outside pro-rata mode"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 20}
                    :filled {:principal 80}
                    :deferred {:principal 20 :realized-yield 20}
                    :haircut {}
                    :policy {:mode :waterfall}
                    :evidence {:available-liquidity 80}}
          checks (closed-form-checks decision)
          fairness (first (filter #(= :partial-fill/exact-pro-rata
                                      (:check/id %))
                                  checks))]
      (is (= :not-applicable (:status fairness))))))

(deftest test-partial-fill-closed-form-checks-non-negative-violation
  (testing "non-negative amounts catches negative values"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {:a -5}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          neg (first (filter #(= :partial-fill/non-negative-amounts
                                 (:check/id %))
                             checks))]
      (is (= :fail (:status neg)))
      (is (seq (get-in neg [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-settlement-mode-consistency
  (testing "settlement-mode-consistency catches full-fill with deferred"
    (let [decision {:settlement-mode :full-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          sm (first (filter #(= :partial-fill/settlement-mode-consistency
                                (:check/id %))
                            checks))]
      (is (= :fail (:status sm)))
      (is (seq (get-in sm [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-full-fill-passes-clean
  (testing "settlement-mode-consistency passes on clean full-fill"
    (let [decision {:settlement-mode :full-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 40 :b 60}
                    :deferred {}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 100}}
          checks (closed-form-checks decision)
          sm (first (filter #(= :partial-fill/settlement-mode-consistency
                                (:check/id %))
                            checks))]
      (is (= :pass (:status sm))))))

(deftest test-partial-fill-closed-form-checks-mode-valid-violation
  (testing "mode-valid catches invalid mode"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40}
                    :filled {:a 20}
                    :deferred {:a 20}
                    :haircut {}
                    :policy {:mode :unknown-mode}
                    :evidence {:available-liquidity 20}}
          checks (closed-form-checks decision)
          mv (first (filter #(= :partial-fill/mode-valid
                                (:check/id %))
                            checks))]
      (is (= :fail (:status mv)))
      (is (seq (get-in mv [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-per-claim-deferred-bound
  (testing "per-claim-bound catches deferred exceeding requested"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 70}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          pc (first (filter #(= :partial-fill/per-claim-bound
                                (:check/id %))
                            checks))]
      (is (= :fail (:status pc)))
      (is (seq (get-in pc [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-per-claim-haircut-bound
  (testing "per-claim-bound catches haircut exceeding requested"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {:b 70}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          pc (first (filter #(= :partial-fill/per-claim-bound
                                (:check/id %))
                            checks))]
      (is (= :fail (:status pc)))
      (is (seq (get-in pc [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-deferred-haircut-overlap
  (testing "deferred-haircut-overlap catches same key in both buckets"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 10}
                    :haircut {:b 50}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          overlap (first (filter #(= :partial-fill/deferred-haircut-overlap
                                     (:check/id %))
                                 checks))]
      (is (= :fail (:status overlap)))
      (is (seq (get-in overlap [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-deferred-haircut-overlap-passes
  (testing "deferred-haircut-overlap passes when keys do not overlap"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20}
                    :haircut {:b 30}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          overlap (first (filter #(= :partial-fill/deferred-haircut-overlap
                                     (:check/id %))
                                 checks))]
      (is (= :pass (:status overlap))))))

(deftest test-partial-fill-closed-form-checks-evidence-shortage-mismatch
  (testing "evidence-self-consistency catches shortage mismatch"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50
                               :shortage 0}}
          checks (closed-form-checks decision)
          ec (first (filter #(= :partial-fill/evidence-self-consistency
                                (:check/id %))
                            checks))]
      (is (= :fail (:status ec)))
      (is (seq (get-in ec [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-evidence-fill-mode-mismatch
  (testing "evidence-self-consistency catches fill-mode mismatch"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50
                               :shortage 50
                               :fill-mode :waterfall}}
          checks (closed-form-checks decision)
          ec (first (filter #(= :partial-fill/evidence-self-consistency
                                (:check/id %))
                            checks))]
      (is (= :fail (:status ec)))
      (is (seq (get-in ec [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-settlement-mode-valid-violation
  (testing "settlement-mode-valid catches invalid settlement-mode"
    (let [decision {:settlement-mode :invalid-mode
                    :requested {:a 40}
                    :filled {:a 20}
                    :deferred {:a 20}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 20}}
          checks (closed-form-checks decision)
          smv (first (filter #(= :partial-fill/settlement-mode-valid
                                 (:check/id %))
                             checks))]
      (is (= :fail (:status smv)))
      (is (seq (get-in smv [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-unrealized-phantom-key
  (testing "unrealized-bucket-valid catches phantom key in unrealized"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {}
                    :unrealized {:c 10}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          ub (first (filter #(= :partial-fill/unrealized-bucket-valid
                                (:check/id %))
                            checks))]
      (is (= :fail (:status ub)))
      (is (seq (get-in ub [:details :violations]))))))

(deftest test-largest-remainder-policy
  (testing "Largest-remainder rounding policy for partial fill"
    (let [policy {:mode :pro-rata
                  :rounding-policy :largest-remainder
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 100 [(assoc base-position
                                                         :principal 33 :realized-yield 33 :deferred-yield 34)] policy)
          total-filled (reduce + 0 (vals (:filled decision)))]
      (is (<= total-filled 100)
          "Never exceeds available liquidity"))))

(deftest test-principal-protective-floor-policy
  (testing "Principal-protective-floor rounding preserves principal"
    (let [policy {:mode :principal-first
                  :rounding-policy :principal-protective-floor
                  :unrealized-yield-treatment :not-claimable}
          decision (pf/calculate-fulfillment 10000 base-position policy)]
      (is (= 10000 (get-in decision [:filled :principal])))
      (is (= 0 (get-in decision [:deferred :principal] 0))))))

(deftest test-partial-fill-predicate
  (testing "partial-fill? predicate"
    (is (pf/partial-fill? {:settlement-mode :partial-fill}))
    (is (not (pf/partial-fill? {:settlement-mode :full-fill})))))

(deftest test-apply-partial-fill-world-mutation
  (testing "apply-partial-fill updates world state correctly"
    (let [world {:yield/positions {"user1" base-position}
                 :total-held {:USDC 100000}}
          decision (pf/calculate-fulfillment 10200 base-position)
          world' (pf/apply-partial-fill world base-position decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (:partial-fill-affected? pos'))
      (is (= 100000 (get-in world' [:total-held :USDC]))
          "Generic yield application must not mutate protocol custody."))))

(deftest test-batch-partial-fill-rejects-shared-liquidity-domain
  (let [input {:available-liquidity 100
               :position base-position
               :liquidity-domain [:aave-v3 :USDC]}
        error (try
                (pf/batch-partial-fill {:yield/positions {"user1" base-position}}
                                       [input input])
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :batch-partial-fill-shared-liquidity (:type (ex-data error))))))

(deftest test-empty-position-full-fill
  (testing "Empty position always full-fills"
    (let [pos (assoc base-position :principal 0 :realized-yield 0 :deferred-yield 0)
          decision (pf/calculate-fulfillment 0 pos)]
      (is (= :full-fill (:settlement-mode decision))))))

;; ── Decoupled rows (weight/cap) tests ─────────────────────────────────

(deftest test-pro-rata-rows-backward-compatible
  (testing "no explicit rows produces same result as current behavior"
    (let [policy {:mode :pro-rata}
          without-rows (pf/calculate-fulfillment 5350 base-position policy)
          ;; same call but explicitly passing rows derived from requested
          rows [{:key :principal :owed 10000 :weight 10000 :cap 10000}
                {:key :realized-yield :owed 500 :weight 500 :cap 500}
                {:key :deferred-yield :owed 200 :weight 200 :cap 200}]
          with-rows (pf/calculate-fulfillment 5350 base-position policy
                                              {:rows rows})]
      (is (= (:settlement-mode without-rows) (:settlement-mode with-rows)))
      (is (= (:filled without-rows) (:filled with-rows)))
      (is (= (:deferred without-rows) (:deferred with-rows))))))

(deftest test-pro-rata-rows-owed-exceeds-cap
  (testing "owed > cap: unfilled owed goes to deferred"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 10000 :weight 10000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 1000 (get-in decision [:filled :principal]))
          "principal capped at 1000")
      (is (= 9000 (get-in decision [:deferred :principal]))
          "remaining 9000 deferred"))))

(deftest test-pro-rata-rows-cap-exceeds-owed
  (testing "cap > owed: allocation does not overpay beyond owed"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 5000 :weight 10000 :cap 10000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (<= (get-in decision [:filled :principal]) 5000)
          "principal capped at owed")
      (is (<= (get-in decision [:filled :realized-yield]) 500)
          "realized-yield capped at owed")
      (is (<= (get-in decision [:filled :deferred-yield]) 200)
          "deferred-yield capped at owed"))))

(deftest test-pro-rata-rows-weight-exceeds-cap-redistributes
  (testing "weight > cap: excess redistributes to uncapped rows"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 60 base-position policy
                                             {:rows [{:key :a :owed 50 :weight 100 :cap 10}
                                                     {:key :b :owed 50 :weight 100 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 10 (get-in decision [:filled :a]))
          "a capped at 10")
      (is (pos? (get-in decision [:filled :b]))
          "b receives remaining")
      (is (pos? (get-in decision [:deferred :a]))
          "a has deferred (owed 50, filled 10)")
      (is (>= (get-in decision [:deferred :a])
              (- 50 (get-in decision [:filled :a])))
          "deferred accounts for remaining owed"))))

(deftest test-pro-rata-rows-weight-below-cap
  (testing "weight < cap: row receives more after capped peers release excess"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 100 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 60 :cap 60}
                                                     {:key :b :owed 100 :weight 2 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (<= (get-in decision [:filled :a]) 60)
          "a does not exceed cap")
      (is (<= (get-in decision [:filled :b]) 100)
          "b does not exceed owed")
      (is (< 0 (get-in decision [:filled :b]))
          "b receives some amount"))))

(deftest test-pro-rata-rows-zero-weight
  (testing "zero weight with positive cap gets nothing"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 100 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 0 :cap 50}
                                                     {:key :b :owed 100 :weight 100 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (zero? (get-in decision [:filled :a]))
          "zero-weight row gets nothing")
      (is (pos? (get-in decision [:filled :b]))
          "b gets allocation"))))

(deftest test-pro-rata-rows-zero-cap
  (testing "positive weight with zero cap gets nothing"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 100 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 100 :cap 0}
                                                     {:key :b :owed 100 :weight 100 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (zero? (get-in decision [:filled :a]))
          "zero-cap row gets nothing")
      (is (pos? (get-in decision [:filled :b]))
          "b gets allocation"))))

(deftest test-pro-rata-rows-excess-liquidity
  (testing "available liquidity exceeding total cap does not overpay"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 100 base-position policy
                                             {:rows [{:key :a :owed 50 :weight 50 :cap 20}
                                                     {:key :b :owed 30 :weight 30 :cap 30}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 20 (get-in decision [:filled :a])))
      (is (= 30 (get-in decision [:filled :b])))
      (is (zero? (get-in decision [:evidence :shortage]))
          "shortage is 0 when rows total owed <= available"))))

(deftest test-pro-rata-rows-conservation
  (testing "conservation: filled + deferred = owed for each row"
    (let [policy {:mode :pro-rata}
          rows [{:key :a :owed 100 :weight 100 :cap 30}
                {:key :b :owed 100 :weight 100 :cap nil}]
          decision (pf/calculate-fulfillment 80 base-position policy {:rows rows})]
      (doseq [row rows]
        (let [k (:key row)
              f (long (get-in decision [:filled k] 0))
              d (long (get-in decision [:deferred k] 0))]
          (is (<= f (long (:owed row)))
              (str "row " k " filled <= owed"))
          (is (>= d 0)
              (str "row " k " deferred >= 0")))))))

(deftest test-pro-rata-rows-evidence
  (testing "row-level evidence includes allocation-rows with cap-hit? flag"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 40 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 100 :cap 10}
                                                     {:key :b :owed 100 :weight 100 :cap nil}]})
          rows-evidence (get-in decision [:evidence :allocation-rows])]
      (is (some? rows-evidence) "allocation-rows in evidence")
      (is (= 2 (count rows-evidence)))
      (is (true? (get-in decision [:evidence :allocation-rows 0 :cap-hit?]))
          "a hit its cap")
      (is (false? (get-in decision [:evidence :allocation-rows 1 :cap-hit?]))
          "b did not hit cap (nil cap)")
      (is (every? #(contains? % :filled) rows-evidence)
          "each row has filled")
      (is (every? #(contains? % :deferred) rows-evidence)
          "each row has deferred"))))

;; ── Principal-first with decoupled rows ───────────────────────────────

(deftest test-principal-first-rows-backward-compatible
  (testing "no explicit rows produces same result as current principal-first behavior"
    (let [policy {:mode :principal-first}
          without-rows (pf/calculate-fulfillment 8000 base-position policy)
          rows [{:key :principal :owed 10000 :weight 10000 :cap 10000}
                {:key :realized-yield :owed 500 :weight 500 :cap 500}
                {:key :deferred-yield :owed 200 :weight 200 :cap 200}]
          with-rows (pf/calculate-fulfillment 8000 base-position policy
                                              {:rows rows})]
      (is (= (:settlement-mode without-rows) (:settlement-mode with-rows)))
      (is (= (:filled without-rows) (:filled with-rows)))
      (is (= (:deferred without-rows) (:deferred with-rows))))))

(deftest test-principal-first-rows-principal-capped
  (testing "principal owed > cap: principal is capped, yield shares remaining"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 10000 :weight 10000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 1000 (get-in decision [:filled :principal]))
          "principal capped at 1000")
      (is (some? (get-in decision [:filled :realized-yield]))
          "yield receives from remaining after principal cap"))))

(deftest test-principal-first-rows-yield-weight-exceeds-cap
  (testing "yield row weight > cap: excess redistributed among yield rows"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 3000 base-position policy
                                             {:rows [{:key :principal :owed 2000 :weight 2000 :cap 2000}
                                                     {:key :realized-yield :owed 2000 :weight 1000 :cap 100}
                                                     {:key :deferred-yield :owed 2000 :weight 1000 :cap nil}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 2000 (get-in decision [:filled :principal]))
          "principal fills fully")
      (is (<= (get-in decision [:filled :realized-yield]) 100)
          "realized-yield capped at 100")
      (is (pos? (get-in decision [:filled :deferred-yield]))
          "deferred-yield gets remaining after cap redistribution"))))

(deftest test-principal-first-rows-evidence
  (testing "principal-first with rows produces allocation-rows evidence"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 10000 :weight 10000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap nil}]})
          rows-evidence (get-in decision [:evidence :allocation-rows])]
      (is (some? rows-evidence) "allocation-rows in evidence")
      (is (= 2 (count rows-evidence)))
      (is (true? (:cap-hit? (first rows-evidence)))
          "principal hit its cap")
      (is (false? (:cap-hit? (second rows-evidence)))
          "yield row did not hit cap (nil cap)")
      (is (every? #(contains? % :filled) rows-evidence))
      (is (every? #(contains? % :deferred) rows-evidence)))))

;; Additional tests for decoupled principal-first edge cases
(deftest test-principal-first-rows-zero-liquidity
  (testing "Zero liquidity: nothing allocated, everything deferred"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 0 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 0 (get-in decision [:filled :principal] 0)) "No principal filled")
      (is (= 0 (get-in decision [:filled :realized-yield] 0)) "No yield filled")
      (is (= 1000 (get-in decision [:deferred :principal] 0)) "All principal deferred")
      (is (= 500 (get-in decision [:deferred :realized-yield] 0)) "All yield deferred"))))

(deftest test-principal-first-rows-weight-zero
  (testing "Zero weight on principal row: principal-first ignores weight, fills to cap first"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 1000 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 0 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
      (is (= 1000 (get-in decision [:filled :principal] 0)) "Principal fills to cap regardless of weight")
      (is (= 0 (get-in decision [:filled :realized-yield] 0)) "Nothing remaining for yield"))))

(deftest test-principal-first-rows-cap-zero
  (testing "Zero cap: receives nothing regardless of weight"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 1000 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 0}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
      (is (= 0 (get-in decision [:filled :principal] 0)) "Principal with zero cap gets nothing")
      (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Yield gets available liquidity"))))

(deftest test-principal-first-rows-weight-less-than-cap
  (testing "Weight < cap on principal: principal-first fills principal to cap, weight is for yield rows only"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 1000 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 500 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
      (is (= 1000 (get-in decision [:filled :principal] 0)) "Principal fills to cap, ignoring weight")
      (is (= 0 (get-in decision [:filled :realized-yield] 0)) "Nothing remaining for yield"))))

(deftest test-principal-first-rows-weight-greater-than-cap
  (testing "Weight > cap: allocation limited by cap"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 1000 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 500}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
      (is (= 500 (get-in decision [:filled :principal] 0)) "Principal limited by cap")
      (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Yield gets remaining"))))

(deftest test-principal-first-rows-all-capped
  (testing "All rows capped: excess liquidity remains unallocated"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 1000 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 300}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 200}]})]
      (is (= 300 (get-in decision [:filled :principal] 0)) "Principal capped at 300")
      (is (= 200 (get-in decision [:filled :realized-yield] 0)) "Yield capped at 200")
      (is (= 700 (get-in decision [:deferred :principal] 0)) "Principal deferred = owed - filled")
      (is (= 300 (get-in decision [:deferred :realized-yield] 0)) "Yield deferred amount"))))

(deftest test-principal-first-rows-conservation
  (testing "Conservation: filled + deferred = requested for each row"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 1000 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 600}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 300}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 100}]})
          rows-evidence (get-in decision [:evidence :allocation-rows])]
      (doseq [row rows-evidence]
        (let [key (:key row)
              requested (:owed row)
              filled (:filled row)
              deferred (:deferred row)]
          (is (= requested (+ filled deferred))
              (str "Row " key " conservation: filled + deferred = requested")))))))

(deftest test-principal-first-rows-only-principal
  (testing "Only principal row: yield rows empty"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 500 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 1000}]})]
      (is (= 500 (get-in decision [:filled :principal] 0)) "Principal gets available liquidity")
      (is (nil? (get-in decision [:filled :realized-yield])) "No yield allocation")
      (is (nil? (get-in decision [:filled :deferred-yield])) "No deferred yield allocation"))))

(deftest test-principal-first-rows-no-principal
  (testing "No principal row: yield rows filled pro-rata with caps"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 500 base-position policy
                                             {:rows [{:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})]
      (is (= 0 (get-in decision [:filled :principal] 0)) "No principal allocation")
      ;; pro-rata with floor-with-largest-remainder:
      ;; realized-yield: floor(500 * 500/700) = 357, deferred-yield: floor(500 * 200/700) = 142
      ;; remainder = 1, largest remainder to deferred-yield (600 > 100)
      (is (= 357 (get-in decision [:filled :realized-yield] 0)) "Realized yield gets pro-rata share")
      (is (= 143 (get-in decision [:filled :deferred-yield] 0)) "Deferred yield gets remainder + pro-rata share"))))

(deftest test-principal-first-rows-full-fill
  (testing "Full fill when liquidity covers all capped amounts"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 1500 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
      (is (= :partial-fill (:settlement-mode decision))
          "Rows path currently returns :partial-fill even when shortage is 0 (pre-existing)")
      (is (= 1000 (get-in decision [:filled :principal] 0)) "Principal fully filled")
      (is (= 500 (get-in decision [:filled :realized-yield] 0)) "Yield fully filled")
      (is (= 0 (get-in decision [:deferred :principal] 0)) "No principal deferred")
      (is (= 0 (get-in decision [:deferred :realized-yield] 0)) "No yield deferred"))))

;; ── New tests for improvements ───────────────────────────────────────────

(deftest test-row-evidence-includes-fill-ratio
  (testing "allocation-rows evidence includes fill-ratio for each row"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 200 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 100 :cap 30}
                                                     {:key :b :owed 100 :weight 100 :cap nil}]})
          rows-evidence (get-in decision [:evidence :allocation-rows])]
      (is (every? #(contains? % :fill-ratio) rows-evidence)
          "each row has fill-ratio")
      (is (double? (:fill-ratio (first rows-evidence)))
          "fill-ratio is a double")
      (is (= 0.3 (:fill-ratio (first rows-evidence)))
          "capped row a: fill-ratio = 30/100")
      (is (= 1.0 (:fill-ratio (second rows-evidence)))
          "uncapped row b gets remaining = 170, fill-ratio = 170/100 > 1.0...")
      (is (pos? (:fill-ratio (second rows-evidence)))
          "fill-ratio is positive for uncapped row"))))

(deftest test-principal-first-evidence-has-allocation-detail
  (testing "principal-first rows path includes allocation-detail for evidence consistency"
    (let [policy {:mode :principal-first}
          decision (pf/calculate-fulfillment 5000 base-position policy
                                             {:rows [{:key :principal :owed 10000 :weight 10000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap nil}]})
          detail (get-in decision [:evidence :allocation-detail])]
      (is (some? detail) "allocation-detail present in principal-first evidence"))))

(deftest test-redistribution-pass-2-allocations
  (testing "redistribution metadata includes per-pass records for traceability"
    (let [policy {:mode :pro-rata}
          decision (pf/calculate-fulfillment 80 base-position policy
                                             {:rows [{:key :a :owed 100 :weight 100 :cap 30}
                                                     {:key :b :owed 100 :weight 100 :cap nil}]})
          redist (get-in decision [:evidence :redistribution])]
      (is (contains? redist :passes)
          "redistribution includes :passes")
      (is (vector? (:passes redist))
          ":passes is a vector")
      (is (every? #(contains? % :pass) (:passes redist))
          "each pass record has :pass")
      (is (every? #(contains? % :capped-ids) (:passes redist))
          "each pass record has :capped-ids")
      (is (every? #(contains? % :excess) (:passes redist))
          "each pass record has :excess"))))

(deftest test-waterfall-rows-produces-evidence
  (testing "waterfall with rows produces allocation-rows and bucket-redistributions evidence"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]}
          decision (pf/calculate-fulfillment 10200 base-position policy
                                             {:rows [{:key :principal :owed 10000 :weight 10000 :cap 10000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}
                                                     {:key :deferred-yield :owed 200 :weight 200 :cap 200}]})
          rows-evidence (get-in decision [:evidence :allocation-rows])]
      (is (some? rows-evidence) "allocation-rows present")
      (is (some? (get-in decision [:evidence :bucket-redistributions]))
          "bucket-redistributions present")
      (is (= 3 (count rows-evidence)) "all rows have evidence"))))

(deftest test-waterfall-rows-all-capped
  (testing "waterfall with all rows capped: excess liquidity remains unallocated"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield]}
          decision (pf/calculate-fulfillment 1000 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 300}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 200}]})]
      (is (= :partial-fill (:settlement-mode decision)))
      (is (= 300 (get-in decision [:filled :principal] 0)) "Principal capped at 300")
      (is (= 200 (get-in decision [:filled :realized-yield] 0)) "Yield capped at 200")
      (is (= 700 (get-in decision [:deferred :principal] 0)) "Principal deferred = owed - filled"))))

(deftest test-waterfall-rows-full-fill-with-caps
  (testing "waterfall with rows: sufficient liquidity produces full-fill with cap evidence"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield]}
          decision (pf/calculate-fulfillment 1500 base-position policy
                                             {:rows [{:key :principal :owed 1000 :weight 1000 :cap 1000}
                                                     {:key :realized-yield :owed 500 :weight 500 :cap 500}]})]
      (is (some? (get-in decision [:evidence :allocation-rows]))
          "allocation-rows present even on full-fill with caps"))))

;; ── Batch and artifact validation tests ──────────────────────────────────

(deftest test-validate-batch-decisions
  (testing "validate-batch-decisions runs checks on each decision"
    (let [d1 {:settlement-mode :full-fill
              :requested {:a 40}
              :filled {:a 40}
              :deferred {}
              :haircut {}
              :policy {:mode :pro-rata}
              :evidence {:available-liquidity 40}}
          d2 {:settlement-mode :partial-fill
              :requested {:a 40}
              :filled {:a 20}
              :deferred {:a 20}
              :haircut {}
              :policy {:mode :pro-rata}
              :evidence {:available-liquidity 20}}
          result (pf/validate-batch-decisions [d1 d2])]
      (is (true? (:batch/valid? result)))
      (is (= 2 (get-in result [:batch/summary :total-decisions])))
      (is (= 2 (get-in result [:batch/summary :passed-count])))
      (is (empty? (get-in result [:batch/summary :failed-decisions]))))))

(deftest test-validate-batch-decisions-detects-failure
  (testing "validate-batch-decisions detects failing decisions"
    (let [d1 {:settlement-mode :full-fill
              :requested {:a 40}
              :filled {:a 40}
              :deferred {:a 10}
              :haircut {}
              :policy {:mode :pro-rata}
              :evidence {:available-liquidity 40}}
          result (pf/validate-batch-decisions [d1])]
      (is (false? (:batch/valid? result)))
      (is (= 0 (get-in result [:batch/summary :failed-decisions 0 :decision-index]))))))

(deftest test-decision-artifact-format-valid
  (testing "decision-artifact-format passes for well-formed artifact"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40}
                    :filled {:a 20}
                    :deferred {:a 20}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 20}
                    :decision/hash (str "sha256:" (apply str (repeat 64 "a")))
                    :decision/id "partial-fill-aaaa"}
          checks (closed-form-checks decision)
          fmt (first (filter #(= :partial-fill/decision-artifact-format (:check/id %)) checks))]
      (is (= :pass (:status fmt))))))

(deftest test-decision-artifact-format-invalid-hash
  (testing "decision-artifact-format catches malformed hash"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40}
                    :filled {:a 20}
                    :deferred {:a 20}
                    :haircut {}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 20}
                    :decision/hash "not-a-valid-hash"}
          checks (closed-form-checks decision)
          fmt (first (filter #(= :partial-fill/decision-artifact-format (:check/id %)) checks))]
      (is (= :fail (:status fmt)))
      (is (seq (get-in fmt [:details :violations]))))))

(deftest test-validate-decision-artifact
  (testing "validate-decision-artifact verifies content-addressed hash"
    (let [position (pos/normalize-position
                    {:owner/id "user1" :module/id :test-mod :token "USDC"
                     :principal 10000 :shares 10000 :entry-index 1
                     :realized-yield 500 :unrealized-yield 300
                     :deferred-yield 200 :haircut-yield 0 :status :active})
          decision (pf/calculate-fulfillment 20000 position)
          artifact (pf/decision-artifact position decision)
          result (pf/validate-decision-artifact position artifact)]
      (is (= :pass (:status result))))))

(deftest test-validate-decision-artifact-tampered
  (testing "validate-decision-artifact detects tampered hash"
    (let [position (pos/normalize-position
                    {:owner/id "user1" :module/id :test-mod :token "USDC"
                     :principal 10000 :shares 10000 :entry-index 1
                     :realized-yield 500 :unrealized-yield 300
                     :deferred-yield 200 :haircut-yield 0 :status :active})
          decision (pf/calculate-fulfillment 20000 position)
          artifact (assoc (pf/decision-artifact position decision)
                          :decision/hash "sha256:0000000000000000000000000000000000000000000000000000000000000000")
          result (pf/validate-decision-artifact position artifact)]
      (is (= :fail (:status result))))))

(deftest test-partial-fill-closed-form-checks-deferred-haircut-sum-bound-catches
  (testing "deferred-haircut-sum-bound catches deferred+haircut > requested"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 25}
                    :haircut {:a 20}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          sc (first (filter #(= :partial-fill/deferred-haircut-sum-bound
                                (:check/id %))
                            checks))]
      (is (= :fail (:status sc)))
      (is (some #(= :a (:claim %)) (get-in sc [:details :violations]))))))

(deftest test-partial-fill-closed-form-checks-deferred-haircut-sum-bound-passes
  (testing "deferred-haircut-sum-bound passes when deferred+haircut <= requested"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 15}
                    :haircut {:a 10}
                    :policy {:mode :pro-rata}
                    :evidence {:available-liquidity 50}}
          checks (closed-form-checks decision)
          sc (first (filter #(= :partial-fill/deferred-haircut-sum-bound
                                (:check/id %))
                            checks))]
      (is (= :pass (:status sc))))))

;; ── Shared-pool batch tests (Phase 3) ──────────────────────────────────

(deftest test-batch-partial-fill-different-domains-succeed
  (testing "Inputs with different liquidity domains succeed independently"
    (let [pos1 (assoc base-position :owner/id "user1")
          pos2 (assoc base-position :owner/id "user2")
          input1 {:available-liquidity 5350
                  :position pos1
                  :liquidity-domain [:pool-a :USDC]}
          input2 {:available-liquidity 20000
                  :position pos2
                  :liquidity-domain [:pool-b :USDC]}
          world (pf/batch-partial-fill
                 {:yield/positions {"user1" pos1 "user2" pos2}}
                 [input1 input2])]
      (is (some? (get-in world [:yield/positions "user1"])))
      (is (some? (get-in world [:yield/positions "user2"]))))))

(deftest test-batch-partial-fill-missing-domain-rejected
  (testing "Multi-input batches require an explicit liquidity domain"
    (let [pos1 (assoc base-position :owner/id "user1")
          pos2 (assoc base-position :owner/id "user2")
          error (try
                  (pf/batch-partial-fill
                   {:yield/positions {"user1" pos1 "user2" pos2}}
                   [{:available-liquidity 5000 :position pos1
                     :liquidity-domain [:pool-a :USDC]}
                    {:available-liquidity 5000 :position pos2}])
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :batch-partial-fill-missing-liquidity-domain
             (:type (ex-data error)))))))

(deftest test-batch-partial-fill-same-domain-rejected
  (testing "Two withdrawals declaring the same liquidity domain are rejected"
    (let [pos1 (assoc base-position :owner/id "user1")
          pos2 (assoc base-position :owner/id "user2")
          input1 {:available-liquidity 5000 :position pos1
                  :liquidity-domain [:same-pool :USDC]}
          input2 {:available-liquidity 5000 :position pos2
                  :liquidity-domain [:same-pool :USDC]}
          error (try
                  (pf/batch-partial-fill
                   {:yield/positions {"user1" pos1 "user2" pos2}}
                   [input1 input2])
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :batch-partial-fill-shared-liquidity (:type (ex-data error)))))))

(deftest test-batch-partial-fill-deterministic-ordering
  (testing "Batch decisions applied in deterministic input order"
    (let [pos1 (assoc base-position :owner/id "user1")
          pos2 (assoc base-position :owner/id "user2")
          input1 {:available-liquidity 20000 :position pos1
                  :liquidity-domain [:pool-a :USDC]}
          input2 {:available-liquidity 0 :position pos2
                  :liquidity-domain [:pool-b :USDC]}
          world (pf/batch-partial-fill
                 {:yield/positions {"user1" pos1 "user2" pos2}}
                 [input1 input2])
          pos1' (get-in world [:yield/positions "user1"])
          pos2' (get-in world [:yield/positions "user2"])]
      (is (not (:partial-fill-affected? pos1'))
          "Full-fill (full liquidity) is NOT a partial-fill event - flag stays false")
      (is (= :withdrawn (:status pos1'))
          "Full-fill fully resolves the position to :withdrawn, not :unwinding")
      (is (:partial-fill-affected? pos2')
          "Zero-liquidity defers the full entitlement - a genuine partial fill sets the flag")
      (is (= :unwinding (:status pos2'))
          "Zero-liquidity position remains :unwinding with deferred entitlement"))))

(deftest test-partial-fill-affected-sticky-through-later-full-resolution
  (testing ":partial-fill-affected? is sticky-historical - never cleared by a later full-fill"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]
                  :unrealized-yield-treatment :not-claimable
                  :post-partial-fill-accrual :accrue-residual-as-unrealized}
          partial-decision (pf/calculate-fulfillment 10200 base-position policy)
          after-partial (pf/post-partial-fill-position base-position partial-decision)
          full-decision (pf/calculate-fulfillment
                         (+ (:principal after-partial 0)
                            (:unrealized-yield after-partial 0)
                            (:haircut-yield after-partial 0))
                         after-partial
                         policy)
          resolved (pf/post-partial-fill-position after-partial full-decision)]
      (is (:partial-fill-affected? after-partial) "Genuine partial fill sets the flag")
      (is (= :unwinding (:status after-partial)) "Partial fill leaves position unwinding")
      (is (:partial-fill-affected? resolved)
          "Flag stays true even after the residual is fully resolved/settled")
      (is (= :withdrawn (:status resolved))
          "Fully-resolved residual settles the position to :withdrawn"))))

(deftest test-full-fill-not-a-partial-fill-event
  (testing "A full-fill decision marks no partial-fill event and resolves to :withdrawn"
    (let [policy {:mode :waterfall
                  :fill-order [:principal :realized-yield :deferred-yield]
                  :unrealized-yield-treatment :not-claimable
                  :post-partial-fill-accrual :accrue-residual-as-unrealized}
          requested (+ (:principal base-position) (:realized-yield base-position)
                       (:unrealized-yield base-position))
          decision (pf/calculate-fulfillment requested base-position policy)
          resolved (pf/post-partial-fill-position base-position decision)]
      (is (not (pf/partial-fill? decision)) "Full entitlement -> :settlement-mode :full-fill")
      (is (not (:partial-fill-affected? resolved))
          "Full-fill is not a partial-fill event; flag stays false on a fresh position")
      (is (= :withdrawn (:status resolved))
          "Full-fill fully resolves the position"))))

(deftest test-partial-fill-affected-haircut-only
  (testing "Haircut-only outcome (no deferred) is still a partial-fill event"
    (let [position (assoc base-position :principal 10000 :realized-yield 0 :unrealized-yield 0)
          liquidity-needed (+ (:principal position) 1)
          decision (pf/calculate-fulfillment liquidity-needed position
                                             {:mode :pro-rata
                                              :fill-order [:principal]
                                              :unrealized-yield-treatment :not-claimable
                                              :post-partial-fill-accrual :accrue-residual-as-unrealized})
          updated-pos (pf/post-partial-fill-position position decision)]
      (is (:partial-fill-affected? updated-pos) "Haircut-only (deferred 0) is a genuine partial fill")
      (is (= :unwinding (:status updated-pos))))))

(deftest test-partial-fill-affected-zero-liquidity
  (testing "Zero-fill defers the entire entitlement - flag set, status unwinding"
    (let [decision (pf/calculate-fulfillment 0 base-position)
          updated-pos (pf/post-partial-fill-position base-position decision)]
      (is (pf/partial-fill? decision) "Zero liquidity -> :settlement-mode :partial-fill")
      (is (:partial-fill-affected? updated-pos))
      (is (= :unwinding (:status updated-pos))))))

(deftest test-partial-fill-affected-defaults-false
  (testing "Fresh/normalized position starts with :partial-fill-affected? false"
    (is (false? (:partial-fill-affected? (pos/normalize-position base-position))))))

(deftest test-batch-partial-fill-total-filled-never-exceeds-liquidity
  (testing "Total filled across batch respects available liquidity per position"
    (let [pos (assoc base-position :owner/id "user1")
          d1 (pf/calculate-fulfillment 5000 pos)
          d2 (pf/calculate-fulfillment 8000 pos)]
      (is (<= (pf/filled-total d1) 5000)
          "Decision with 5000 liquidity: filled <= 5000")
      (is (<= (pf/filled-total d2) 8000)
          "Decision with 8000 liquidity: filled <= 8000")
      (is (zero? (pf/filled-total (pf/calculate-fulfillment 0 pos)))
          "Zero liquidity produces zero fill"))))

(deftest test-batch-partial-fill-held-ledger-not-modified
  (testing "batch-partial-fill does not modify total-held"
    (let [pos (assoc base-position :owner/id "user1")
          world-before {:yield/positions {"user1" pos}
                        :total-held {:USDC 100000}}
          input {:available-liquidity 20000 :position pos}
          world-after (pf/batch-partial-fill world-before [input])]
      (is (= 100000 (get-in world-after [:total-held :USDC]))
          "total-held unchanged by generic partial-fill batch"))))

;; ── Rounding-semantics consistency (P0) ───────────────────────────────────
;;
;; A canonical producer using an admitted/default policy must not produce an
;; artifact its corresponding verifier rejects. These regressions lock the
;; centralized rounding classification: :floor = strict floor (no carry),
;; :floor-and-carry / :largest-remainder = bounded +1 carry.

(deftest floor-and-carry-indivisible-passes-its-own-closed-form-verification
  (testing "A non-divisible :floor-and-carry pro-rata fill passes complete closed-form checks"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 150 :deferred-yield 200}
                    :filled {:principal 27 :realized-yield 40 :deferred-yield 54}
                    :deferred {:principal 73 :realized-yield 110 :deferred-yield 146}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :floor-and-carry}
                    :evidence {:available-liquidity 121 :total-requested 450 :shortage 329
                               :fill-mode :pro-rata :rounding-policy :floor-and-carry}}
          checks (closed-form-checks decision)]
      (is (= #{:pass :not-applicable} (set (map :status checks))))))
  (testing "The public default producer's own output is accepted end-to-end"
    (let [pos (pos/normalize-position
               {:owner/id "u1" :module/id :m :token "USDC"
                :principal 100 :realized-yield 150 :deferred-yield 200
                :shares 100 :entry-index 1 :status :active})
          checks (closed-form-checks (pf/calculate-fulfillment 121 pos {:mode :pro-rata}))]
      (is (= #{:pass :not-applicable} (set (map :status checks)))))))

(deftest floor-and-carry-divisible-stays-exactly-proportional
  (testing "When the fill divides evenly, :floor-and-carry is exactly pro-rata"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :floor-and-carry}
                    :evidence {:available-liquidity 50 :fill-mode :pro-rata}}
          checks (closed-form-checks decision)
          by-id (into {} (map (juxt :check/id identity) checks))]
      (is (= :pass (:status (get by-id :partial-fill/exact-pro-rata))))
      (is (= :pass (:status (get by-id :partial-fill/rounding-fairness)))))))

(deftest carry-unit-on-wrong-claimant-fails-rounding-fairness
  (testing "Moving a +1 carry to a non-top-remainder claimant is rejected"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 100 :c 100}
                    :filled {:a 3 :b 4 :c 3}
                    :deferred {:a 97 :b 96 :c 97}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :largest-remainder}
                    :evidence {:available-liquidity 10}}
          checks (closed-form-checks decision)
          rf (first (filter #(= :partial-fill/rounding-fairness (:check/id %)) checks))]
      (is (= :fail (:status rf)))
      (is (some #(= :unexpected-extra-unit (:kind %)) (get-in rf [:details :violations]))))))

(deftest two-excess-units-fails-the-carry-bound
  (testing "A +2 deviation exceeds the ≤1 rounding bound and is rejected"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 100 :c 100}
                    :filled {:a 5 :b 3 :c 3}
                    :deferred {:a 95 :b 97 :c 97}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :floor-and-carry}
                    :evidence {:available-liquidity 10}}
          checks (closed-form-checks decision)
          rf (first (filter #(= :partial-fill/rounding-fairness (:check/id %)) checks))]
      (is (= :fail (:status rf)))
      (is (some #(and (= :a (:claim %)) (= 2 (:error %)))
                (get-in rf [:details :violations]))))))

(deftest fail-action-deferred-complement-passes-under-carry
  (testing "The deferred complement of an integer-rounded fill passes fail-action fairness"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 200 :c 300}
                    :filled {:a 27 :b 54 :c 82}
                    :deferred {:a 73 :b 146 :c 218}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :floor-and-carry}
                    :evidence {:available-liquidity 163 :total-requested 600 :shortage 437}}
          checks (closed-form-checks decision)
          fa (first (filter #(= :partial-fill/fail-action-fairness (:check/id %)) checks))]
      (is (= :pass (:status fa)))
      (is (empty? (get-in fa [:details :violations]))))))

(deftest tampered-deferred-complement-fails-fail-action
  (testing "An independently-tampered (non-same-ratio) deferred bucket is rejected"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 200 :c 300}
                    :filled {:a 30 :b 51 :c 82}
                    :deferred {:a 70 :b 149 :c 218}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :floor-and-carry}
                    :evidence {:available-liquidity 163 :total-requested 600 :shortage 437}}
          checks (closed-form-checks decision)
          fa (first (filter #(= :partial-fill/fail-action-fairness (:check/id %)) checks))]
      (is (= :fail (:status fa)))
      (is (seq (get-in fa [:details :violations]))))))

(deftest strict-floor-does-not-acquire-carry-tolerance
  (testing ":floor rejects an upward +1 carry unit"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 100 :c 100}
                    :filled {:a 3 :b 4 :c 3}
                    :deferred {:a 97 :b 96 :c 97}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :floor}
                    :evidence {:available-liquidity 10}}
          checks (closed-form-checks decision)
          rf (first (filter #(= :partial-fill/rounding-fairness (:check/id %)) checks))]
      (is (= :fail (:status rf)))))
  (testing "A strict floor leaving the unit residual unallocated is accepted"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:a 100 :b 100 :c 100}
                    :filled {:a 3 :b 3 :c 3}
                    :deferred {:a 97 :b 97 :c 97}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :floor}
                    :evidence {:available-liquidity 10}}
          checks (closed-form-checks decision)
          rf (first (filter #(= :partial-fill/rounding-fairness (:check/id %)) checks))]
      (is (= :pass (:status rf))))))

(deftest floor-producer-leaves-residual-undistributed
  (testing "The non-rows :floor producer leaves the rounding residual unallocated (no carry)"
    (let [pos (pos/normalize-position
               {:owner/id "u1" :module/id :m :token "USDC"
                :principal 100 :realized-yield 150 :deferred-yield 200
                :shares 100 :entry-index 1 :status :active})
          d (pf/calculate-fulfillment 121 pos {:mode :pro-rata :rounding-policy :floor})
          filled (reduce + 0 (vals (:filled d)))]
      (is (pos? (- 121 filled))
          "A non-divisible :floor fill does not consume all available units")
      (is (= #{:pass :not-applicable} (set (map :status (closed-form-checks d))))))))

(deftest rows-and-non-rows-agree-on-floor-and-carry-semantics
  (testing "Rows and non-rows paths agree on :floor-and-carry bounded-carry invariants"
    (let [pos (pos/normalize-position
               {:owner/id "u1" :module/id :m :token "USDC"
                :principal 100 :realized-yield 150 :deferred-yield 200
                :shares 100 :entry-index 1 :status :active})
          policy {:mode :pro-rata :rounding-policy :floor-and-carry}
          rows-opts {:rows [{:key :principal :owed 100 :weight 100 :cap nil}
                            {:key :realized-yield :owed 150 :weight 150 :cap nil}
                            {:key :deferred-yield :owed 200 :weight 200 :cap nil}]}
          non-rows (pf/calculate-fulfillment 121 pos policy)
          rows (pf/calculate-fulfillment 121 pos policy rows-opts)
          statuses (fn [d] (set (map :status (closed-form-checks d))))
          check-of (fn [d id]
                     (first (filter #(= id (:check/id %)) (closed-form-checks d))))]
      (is (= #{:pass :not-applicable} (statuses non-rows)))
      (is (= #{:pass :not-applicable} (statuses rows)))
      (is (= :pass (:status (check-of non-rows :partial-fill/rounding-fairness))))
      (is (= :pass (:status (check-of rows :partial-fill/rounding-fairness))))
      (is (= :pass (:status (check-of non-rows :partial-fill/rounding-residual-bounded))))
      (is (= :pass (:status (check-of rows :partial-fill/rounding-residual-bounded)))))))

(deftest effective-rounding-consistency-rows-mechanism
  (testing "Rows :floor-and-carry requests derive effective :largest-remainder, matching the mechanism"
    (let [pos (pos/normalize-position
               {:owner/id "u1" :module/id :m :token "USDC"
                :principal 100 :realized-yield 150 :deferred-yield 200
                :shares 100 :entry-index 1 :status :active})
          d (pf/calculate-fulfillment 121 pos {:mode :pro-rata :rounding-policy :floor-and-carry}
                                      {:rows [{:key :principal :owed 100 :weight 100 :cap nil}
                                              {:key :realized-yield :owed 150 :weight 150 :cap nil}
                                              {:key :deferred-yield :owed 200 :weight 200 :cap nil}]})
          disclosed (get-in d [:evidence :allocation-mechanism-evidence :mechanism/result :rounding-policy])
          check (closed-form-checks d)
          eff (first (filter #(= :partial-fill/effective-rounding-consistency (:check/id %)) check))]
      (is (= :largest-remainder disclosed))
      (is (= :pass (:status eff)))
      (is (= :largest-remainder (get-in eff [:details :derived-effective])))
      (is (= :largest-remainder (get-in eff [:details :declared-effective]))))))

(deftest effective-rounding-consistency-rejects-disclosed-mismatch
  (testing "Tampering the mechanism's declared effective algorithm away from the derived value fails the check"
    (let [pos (pos/normalize-position
               {:owner/id "u1" :module/id :m :token "USDC"
                :principal 100 :realized-yield 150 :deferred-yield 200
                :shares 100 :entry-index 1 :status :active})
          d (pf/calculate-fulfillment 121 pos {:mode :pro-rata :rounding-policy :floor-and-carry}
                                      {:rows [{:key :principal :owed 100 :weight 100 :cap nil}
                                              {:key :realized-yield :owed 150 :weight 150 :cap nil}
                                              {:key :deferred-yield :owed 200 :weight 200 :cap nil}]})
          tampered (assoc-in d [:evidence :allocation-mechanism-evidence
                                :mechanism/result :rounding-policy] :floor)
          eff (first (filter #(= :partial-fill/effective-rounding-consistency (:check/id %))
                             (closed-form-checks tampered)))]
      (is (= :fail (:status eff)))
      (is (= :floor (get-in eff [:details :declared-effective])))
      (is (= :largest-remainder (get-in eff [:details :derived-effective]))))))

(deftest effective-rounding-consistency-not-applicable-on-single-shape
  (testing "Single-shape decisions disclose no effective algorithm; the check is not-applicable"
    (let [pos (pos/normalize-position
               {:owner/id "u1" :module/id :m :token "USDC"
                :principal 100 :realized-yield 150 :deferred-yield 200
                :shares 100 :entry-index 1 :status :active})
          d (pf/calculate-fulfillment 121 pos {:mode :pro-rata :rounding-policy :floor-and-carry})
          check (closed-form-checks d)
          eff (first (filter #(= :partial-fill/effective-rounding-consistency (:check/id %)) check))]
      (is (= :not-applicable (:status eff)))
      (is (= :single (get-in eff [:details :execution-shape]))))))

(deftest exact-tie-carry-goes-to-lowest-canonical-id-both-shapes
  (testing "A three-way exact remainder tie resolves the carry identically (canonical id) on rows and non-rows"
    (let [pos (pos/normalize-position
               {:owner/id "u" :module/id :m :token "USDC"
                :principal 100 :realized-yield 100 :deferred-yield 100
                :shares 100 :entry-index 1 :status :active})
          non-rows (pf/calculate-fulfillment 10 pos {:mode :pro-rata :rounding-policy :floor-and-carry})
          rows (pf/calculate-fulfillment 10 pos {:mode :pro-rata :rounding-policy :floor-and-carry}
                                         {:rows [{:key :principal :owed 100 :weight 1 :cap nil}
                                                 {:key :realized-yield :owed 100 :weight 1 :cap nil}
                                                 {:key :deferred-yield :owed 100 :weight 1 :cap nil}]})
          carry-recipient (fn [d] (first (first (filter (fn [[_ amt]] (> amt 3)) (:filled d)))))]
      (is (= :deferred-yield (carry-recipient non-rows))
          "lowest canonical id (:deferred-yield) receives the carry")
      (is (= :deferred-yield (carry-recipient rows)))
      (is (= (:filled non-rows)
             (into {} (map (fn [[k v]] [k (long v)]) (:filled rows))))
          "rows and non-rows agree on the same carry recipient and amounts")
      (is (= #{:pass :not-applicable} (set (map :status (closed-form-checks non-rows)))))
      (is (= #{:pass :not-applicable} (set (map :status (closed-form-checks rows))))))))

(deftest tie-carry-recipient-is-insertion-order-invariant
  (testing "Same stable identities, permuted input order, receive the same carry recipient"
    (let [pos (pos/normalize-position
               {:owner/id "u" :module/id :m :token "USDC"
                :principal 100 :realized-yield 100 :deferred-yield 100
                :shares 100 :entry-index 1 :status :active})
          rows-producer (fn [order]
                          (:filled (pf/calculate-fulfillment 10 pos {:mode :pro-rata :rounding-policy :floor-and-carry}
                                                             {:rows (map (fn [k] {:key k :owed 100 :weight 1 :cap nil}) order)})))
          a (rows-producer [:principal :realized-yield :deferred-yield])
          b (rows-producer [:deferred-yield :realized-yield :principal])
          c (rows-producer [:realized-yield :deferred-yield :principal])
          recipient (fn [filled] (first (first (filter (fn [[_ amt]] (> amt 3)) filled))))]
      (is (= :deferred-yield (recipient a)))
      (is (= :deferred-yield (recipient b)))
      (is (= :deferred-yield (recipient c))))))

(deftest carry-on-wrong-tie-member-fails-remainder-ranking
  (testing "Awarding the tie's single carry to canonical higher id (:principal) is rejected by rounding-fairness"
    (let [decision {:settlement-mode :partial-fill
                    :requested {:principal 100 :realized-yield 100 :deferred-yield 100}
                    :filled {:principal 4 :realized-yield 3 :deferred-yield 3}
                    :deferred {:principal 96 :realized-yield 97 :deferred-yield 97}
                    :haircut {}
                    :policy {:mode :pro-rata :rounding-policy :floor-and-carry}
                    :evidence {:available-liquidity 10}}
          check (closed-form-checks decision)
          rf (first (filter #(= :partial-fill/rounding-fairness (:check/id %)) check))]
      (is (= :fail (:status rf)))
      (is (some #(and (= :principal (:claim %))
                      (= :unexpected-extra-unit (:kind %)))
                (get-in rf [:details :violations]))))))

(deftest strict-floor-leaves-equal-tie-residual-unallocated
  (testing "Same exact-tie quota under :floor grants no carry; the unit stays residual"
    (let [pos (pos/normalize-position
               {:owner/id "u" :module/id :m :token "USDC"
                :principal 100 :realized-yield 100 :deferred-yield 100
                :shares 100 :entry-index 1 :status :active})
          d (pf/calculate-fulfillment 10 pos {:mode :pro-rata :rounding-policy :floor})
          filled (reduce + 0 (vals (:filled d)))]
      (is (= {:principal 3 :realized-yield 3 :deferred-yield 3} (:filled d)))
      (is (= 1 (- 10 filled)))
      (is (= #{:pass :not-applicable} (set (map :status (closed-form-checks d))))))))
