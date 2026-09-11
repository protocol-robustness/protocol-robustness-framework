(ns resolver-sim.yield.ops-liveness-test
  "Regression tests for the yield-op dispatch liveness contract.

   Guard: a missing or unavailable yield module must never permanently block a
   caller (e.g. settlement). Missing/unavailable modules degrade to a
   liveness-preserving no-op (world returned unchanged, skip recorded) instead of
   throwing, while a present module that lacks the requested op is still a
   genuine error."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.ops :as ops]
            [resolver-sim.yield.registry :as registry]
            [resolver-sim.yield.exact-math :as m]))

(def liquid-mid :yield.provider/liquid-lending)

(defn- test-world
  "A world with a registered liquid-lending module and one active position."
  []
  (-> {:block-time 1000
       :yield/held-balances {"USDC" 1000000}}
      registry/init-yield-modules
      (assoc-in [:yield/rates liquid-mid "USDC"] 0.05)
      (assoc-in [:yield/indices liquid-mid "USDC"] 1)
      (assoc-in [:yield/risk liquid-mid "USDC"]
                {:liquidity-mode :available :loss-mode :none})
      (assoc-in [:yield/positions "vault"]
                {:owner/id "vault"
                 :module/id liquid-mid
                 :token "USDC"
                 :principal 10000
                 :shares (m/ratio 10000)
                 :entry-index (m/ratio 1)
                 :status :active
                 :realized-yield 0
                 :unrealized-yield 0
                 :deferred-yield 0
                 :haircut-yield 0
                 :principal-impairment 0
                 :accrual-dust-remainder 0
                 :shortfall-affected? false
                 :oracle-stale-affected? false
                 :partial-fill-affected? false
                 :capital-event-affected? false
                 :last-accrual-time nil
                 :last-accrual-index nil})))

(deftest stale-yield-direct-execution-preserves-liveness
  (testing "Direct accrual against a stale-yield module must proceed, not block"
    (let [world (-> (test-world)
                    (assoc-in [:yield/risk liquid-mid "USDC" :failure-modes]
                              #{:oracle-stale})
                    (assoc-in [:yield/risk liquid-mid "USDC" :oracle-stale-seconds]
                              43200))
          world' (ops/accrue-module world liquid-mid {:token "USDC" :dt 31536000})
          pos    (get-in world' [:yield/positions "vault"])
          ;; 5% APY over one year on 10000 principal = 500; stale degradation
          ;; floors it below that while keeping it positive.
          full-rate (long (* 10000 0.05))]
      (is (map? world') "stale-yield direct execution must return a world, not throw")
      (is (pos? (:unrealized-yield pos 0))
          "stale accrual must still recognize (degraded) yield")
      (is (< (:unrealized-yield pos 0) full-rate)
          "the stale-oracle degradation path must be exercised (yield below full rate)")
      (is (empty? (:yield/skipped-ops world'))
          "an available (stale) module must not be treated as unavailable"))))

(deftest unavailable-module-keeper-execution-preserves-liveness
  (testing "Missing module: keeper-driven op must not throw; world returned with skip recorded"
    (let [world   (test-world)
          world'  (ops/apply-yield-op world {:op/type :yield/accrue
                                             :module/id :missing-module
                                             :token "USDC" :dt 1})
          skipped (:yield/skipped-ops world')]
      (is (map? world') "missing module must not block (world returned, not throw)")
      (is (= 1 (count skipped)) "the skipped op must be recorded")
      (is (= :missing-module (get-in (first skipped) [:module/id])))
      (is (= :yield/accrue (get-in (first skipped) [:op/type])))))

  (testing "Unavailable module status: keeper-driven op must not throw; skip recorded"
    (let [world   (-> (test-world)
                      (assoc-in [:yield/module-status liquid-mid] :unavailable))
          world'  (ops/apply-yield-op world {:op/type :yield/withdraw
                                             :module/id liquid-mid
                                             :owner/id "vault" :token "USDC"})
          skipped (:yield/skipped-ops world')]
      (is (map? world') "unavailable module must not block (world returned, not throw)")
      (is (= 1 (count skipped)))
      (is (= liquid-mid (get-in (first skipped) [:module/id])))
      (is (= :yield/withdraw (get-in (first skipped) [:op/type]))))))

(deftest present-module-without-op-still-throws
  (testing "A present module that lacks the requested op is a genuine error"
    (let [world (test-world)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ops/apply-yield-op world {:op/type :yield/does-not-exist
                                              :module/id liquid-mid}))
          "only module availability degrades to a no-op, not an unsupported op"))))
