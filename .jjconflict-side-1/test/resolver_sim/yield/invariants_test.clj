(ns resolver-sim.yield.invariants-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.scenario.expectations :as expectations]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.yield.accrual-properties :as accrual-props]
            [resolver-sim.yield.invariant-catalog :as cat]
            [resolver-sim.yield.invariants :as inv]
            [resolver-sim.yield.invariants-transition :as inv-trans]))

(deftest default-runtime-ids-are-registered
  (let [registered (set (inv/registered-ids))]
    (doseq [id cat/default-runtime-invariant-ids]
      (is (contains? registered id)
          (str id " from default-runtime-invariant-ids is NOT in check-fns. Add it to check-fns.")))))

(deftest all-check-fns-are-in-default-runtime
  (let [default-ids (set cat/default-runtime-invariant-ids)]
    (doseq [id (inv/registered-ids)]
      (is (contains? default-ids id)
          (str id " is in check-fns but NOT in default-runtime-invariant-ids. Add it there or remove from check-fns.")))))

(deftest shortfall-splits-holds-on-valid-shortfall
  (let [world {:yield/positions {"v" {:shortfall {:fulfilled-amount 400
                                                  :deferred-amount 400
                                                  :basis-amount 800}}}}]
    (is (inv/holds? :yield/shortfall-splits world))))

(deftest shortfall-splits-fails-when-split-wrong
  (let [world {:yield/positions {"v" {:shortfall {:fulfilled-amount 100
                                                  :deferred-amount 400
                                                  :basis-amount 800}}}}]
    (is (not (inv/holds? :yield/shortfall-splits world)))))

(deftest enrich-expectations-adds-default-invariants
  (let [s (cat/enrich-expectations {:scenario-id "y01-deposit-accrue-positive"
                                    :expectations {:metrics []}})]
    (is (contains? (set (get-in s [:expectations :invariants]))
                   :yield/exposure))
    (is (contains? (set (get-in s [:expectations :invariants]))
                   :yield/index-monotone))))

(deftest index-monotone-transition-holds-on-accrue
  (let [token "USDC"
        world {:yield/indices {:aave-v3 {token 1.0}}
               :yield/positions {"vault" {:module/id :aave-v3 :token token :status :active}}}
        world' (assoc-in world [:yield/indices :aave-v3 token] 1.01)]
    (is (:holds? (:yield/index-monotone (inv-trans/check-all-transitions world world'))))))

(deftest index-monotone-transition-fails-when-index-drops
  (let [token "USDC"
        world {:yield/indices {:aave-v3 {token 1.05}}
               :yield/positions {"vault" {:module/id :aave-v3 :token token :status :active}}}
        world' (assoc-in world [:yield/indices :aave-v3 token] 1.04)]
    (is (not (:holds? (:yield/index-monotone (inv-trans/check-all-transitions world world')))))))

(deftest liquid-lending-partition-drift-within-budget
  (let [token "USDC"
        module-id :aave-v3
        dt 31536000
        steps 365
        world {:yield/indices {module-id {token 1.0}}
               :yield/rates   {module-id {token 0.10}}
               :yield/positions {"user1" {:owner/id "user1" :module/id module-id :token token
                                          :principal 1000 :shares 1000 :entry-index 1.0
                                          :status :active :unrealized-yield 0 :realized-yield 0}}}]
    (is (accrual-props/partition-drift-within-budget?
         world module-id token dt steps accrual-props/default-liquid-lending-partition-budget))))

(deftest y03-replay-passes-invariant-expectations
  (let [raw  (io-sc/load-scenario-file
              "scenarios/edn/yield/Y03_partial-liquidity-shortfall-affected.edn")
        scenario (cat/enrich-expectations (normalize/normalize-scenario raw))
        result (replay/replay-yield-scenario scenario)
        inv-check (expectations/evaluate-invariants
                   result (get-in scenario [:expectations :invariants]))]
    (is (= :pass (:outcome result)) (:outcome result))
    (is (:ok? inv-check) (pr-str (:violations inv-check)))))

(deftest y07-replay-passes-metrics-and-invariants
  (let [raw  (io-sc/load-scenario-file "scenarios/edn/yield/Y07_monthly-accrual-one-year.edn")
        scenario (cat/enrich-expectations (normalize/normalize-scenario raw))
        result (replay/replay-yield-scenario scenario)
        inv-check (expectations/evaluate-invariants
                   result (get-in scenario [:expectations :invariants]))]
    (is (= :pass (:outcome result)))
    (is (:ok? inv-check) (pr-str (:violations inv-check)))
    (is (> (get-in result [:metrics :yield/liquidity-index] 0) 1.04))))

(deftest provider-suite-scenarios-pass-final-invariants
  (doseq [path ["scenarios/edn/yield/Y01_deposit-accrue-positive.edn"
                "scenarios/edn/yield/Y06_liquidity-shortage-deposit-blocked.edn"
                "scenarios/edn/yield/Y07_monthly-accrual-one-year.edn"]]
    (let [scenario (-> path io-sc/load-scenario-file normalize/normalize-scenario
                       cat/enrich-expectations)
          result (replay/replay-yield-scenario scenario)
          inv-check (expectations/evaluate-invariants
                     result (get-in scenario [:expectations :invariants]))]
      (is (= :pass (:outcome result)) path)
      (is (:ok? inv-check) (str path " " (:violations inv-check))))))

(deftest shortfall-detected-passes-on-valid-shortfall
  (testing "shortfall-detected passes when basis does not exceed position value"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 800
                                                     :fulfilled-amount 400
                                                     :deferred-amount 400
                                                     :haircut-amount 0}}}
                 :yield/risk {:m {:t {:liquidity-mode :shortfall}}}
                 :yield/market-state {:m {:t {:available-ratio 0.5}}}}]
      (is (inv/holds? :yield/shortfall-detected world)))))

(deftest shortfall-detected-fails-on-over-detection
  (testing "shortfall-detected fails when basis exceeds position value (including deferred)"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 100 :realized-yield 0 :unrealized-yield 0
                                         :shortfall {:basis-amount 500
                                                     :fulfilled-amount 200
                                                     :deferred-amount 200
                                                     :haircut-amount 100}}}
                 :yield/risk {:m {:t {:liquidity-mode :shortfall}}}
                 :yield/market-state {:m {:t {:available-ratio 0.5}}}}]
      (is (not (inv/holds? :yield/shortfall-detected world))))))

(deftest shortfall-detected-fails-on-under-detection
  (testing "shortfall-detected fails when unwinding position has no shortfall during shortfall"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0}}
                 :yield/risk {:m {:t {:liquidity-mode :shortfall}}}
                 :yield/market-state {:m {:t {:available-ratio 0.5}}}}]
      (is (not (inv/holds? :yield/shortfall-detected world))))))

(deftest shortfall-detected-passes-when-no-shortfall-mode
  (testing "shortfall-detected passes when module is not in shortfall mode"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :unwinding
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0}}
                 :yield/risk {:m {:t {:liquidity-mode :available}}}
                 :yield/market-state {:m {:t {:available-ratio 1.0}}}}]
      (is (inv/holds? :yield/shortfall-detected world)))))

(deftest shortfall-detected-passes-on-active-position-during-shortfall
  (testing "shortfall-detected passes for active positions during shortfall (no withdrawal yet)"
    (let [world {:yield/positions {"u1" {:module/id :m :token :t :status :active
                                         :principal 1000 :realized-yield 0 :unrealized-yield 0}}
                 :yield/risk {:m {:t {:liquidity-mode :shortfall}}}
                 :yield/market-state {:m {:t {:available-ratio 0.5}}}}]
      (is (inv/holds? :yield/shortfall-detected world)))))
