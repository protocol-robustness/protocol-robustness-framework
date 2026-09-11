(ns resolver-sim.yield.risk-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.yield.risk :as risk]
            [resolver-sim.yield.accounting :as acct]
            [resolver-sim.yield.model :as model]
            [resolver-sim.yield.modules.liquid-lending :as liquid]))

(deftest effective-loss-mode-defaults
  (is (= :mark-to-market
         (risk/effective-loss-mode {:loss-mode :none
                                    :failure-modes #{:negative-yield}})))
  (is (= :none
         (risk/effective-loss-mode {:loss-mode :none
                                    :failure-modes #{}})))
  (is (= :mark-to-market
         (risk/effective-loss-mode {:loss-mode :mark-to-market
                                    :failure-modes #{}}))))

(deftest apply-shocks-composable
  (let [world {:yield/risk {:mod {:USDC {:failure-modes #{}}}}
               :yield/rates {:mod {:USDC 0.05}}}
        world' (risk/apply-shocks world :mod :USDC
                                  [{:type :failure-mode :mode :negative-yield}
                                   {:type :apy :value 0.03}
                                   {:type :liquidity-mode :mode :shortfall}
                                   {:type :shortfall :available-ratio 0.8}])]
    (is (contains? (get-in world' [:yield/risk :mod :USDC :failure-modes])
                   :negative-yield))
    (is (= 0.03 (get-in world' [:yield/rates :mod :USDC])))
    (is (= :shortfall (get-in world' [:yield/risk :mod :USDC :liquidity-mode])))
    (is (= 0.8 (get-in world' [:yield/risk :mod :USDC :shortfall :available-ratio])))))

(deftest accrual-loss-annotation
  (let [world {:yield/risk {:mod {:USDC {:failure-modes #{:negative-yield}}}}
               :yield/rates {:mod {:USDC -0.05}}
               :yield/indices {:mod {:USDC 1.0}}
               :yield/positions {"o" {:owner/id "o" :module/id :mod :token :USDC
                                      :principal 10000 :shares 10000 :entry-index 1.0
                                      :status :active :unrealized-yield 0 :realized-yield 0}}}
        module {:module/id :mod}
        w' (liquid/accrue world module {:token :USDC :dt 31536000})
        pos (get-in w' [:yield/positions "o"])]
    (is (= :negative-accrual-yield (get-in pos [:yield-loss :reason])))
    (is (pos? (:amount (:yield-loss pos))))))

(deftest mark-to-market-negative-unrealized
  (let [world {:yield/risk {:mod {:USDC {:loss-mode :none
                                         :failure-modes #{:negative-yield}}}}
               :yield/indices {:mod {:USDC 0.98}}}
        pos (model/make-position {:owner/id :o
                                  :module/id :mod
                                  :token :USDC
                                  :principal 10000
                                  :shares 10000
                                  :entry-index 1.0})
        pos' (acct/update-position-yield world pos 0.98)]
    (is (< (:unrealized-yield pos' 0) 0))))

(deftest apply-liquidity-stress-basis-amount
  (let [{:keys [shortfall]} (acct/apply-liquidity-stress
                             {:yield/risk {:mod {:USDC {:liquidity-mode :shortfall
                                                        :shortfall {:available-ratio 0.8}}}}}
                             :mod :USDC 10000)]
    (is (= 10000 (:basis-amount shortfall)))
    (is (= 8000 (:fulfilled-amount shortfall)))
    (is (= 2000 (:deferred-amount shortfall)))))
