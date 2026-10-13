(ns resolver-sim.scenario.yield-metrics-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.scenario.yield-metrics :as ym]))

(deftest compute-yield-metrics-reads-yield-positions
  (let [world {:yield/positions {[:sew/escrow 0] {:token :USDC
                                                  :principal 1000
                                                  :unrealized-yield 50
                                                  :realized-yield 10
                                                  :status :active}}
               :escrow-transfers {0 {:amount-after-fee 1000
                                     :escrow-state :pending}}}
        m (ym/compute-yield-metrics {:trace [{:world world}]} :workflow-id 0)]
    (is (= 1000 (:yield/escrow-principal m)))
    (is (= 50 (:yield/escrow-unrealized m)))
    (is (= 10 (:yield/escrow-realized m)))))

(deftest yield-metric-key-handles-keyword-and-string-forms
  (is (ym/yield-metric-key? :yield/escrow-principal))
  (is (ym/yield-metric-key? "yield/escrow-principal"))
  (is (ym/yield-metric-key? ":yield/escrow-principal"))
  (is (not (ym/yield-metric-key? :escrow-principal))
      "bare name without yield namespace is not a yield metric")
  (is (not (ym/yield-metric-key? "escrow-principal"))))
