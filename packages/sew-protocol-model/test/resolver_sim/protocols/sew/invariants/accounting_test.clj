(ns resolver-sim.protocols.sew.invariants.accounting-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.invariants.accounting :as accounting]))

(deftest accounting-reconciles-each-token-independently
  (is (accounting/accounting-consistent?
       {:escrow-transfers {1 {:token :USDC :amount 10}
                           2 {:token :DAI :amount 20}}
        :total-released {:USDC 10}
        :total-refunded {:DAI 20}
        :pending-settlements {}})))

(deftest accounting-includes-pending-settlements-by-token
  (is (accounting/accounting-consistent?
       {:escrow-transfers {1 {:token :USDC :amount 10}
                           2 {:token :DAI :amount 20 :amount-after-fee 20}}
        :total-released {:USDC 10}
        :total-refunded {}
        :pending-settlements {2 {:exists true :is-release false}}})))

(deftest accounting-rejects-mismatched-per-token-totals
  (is (not (accounting/accounting-consistent?
            {:escrow-transfers {1 {:token :USDC :amount 10}
                                2 {:token :DAI :amount 20}}
             :total-released {:USDC 20}
             :total-refunded {:DAI 10}
             :pending-settlements {}}))))
