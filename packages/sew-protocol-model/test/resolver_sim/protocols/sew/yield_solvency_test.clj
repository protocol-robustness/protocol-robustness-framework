(ns resolver-sim.protocols.sew.yield-solvency-test
  "Solvency accounting for yield-bearing resolver stakes vs escrow shortfall deferrals."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.types :as t]))

(deftest solvency-still-counts-escrow-yield-deferred
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:total-held :USDC] 500)
                  (assoc-in [:escrow-transfers 0]
                            {:token :USDC
                             :amount-after-fee 1000
                             :escrow-state :released
                             :from "0xA"
                             :to "0xB"})
                  (assoc-in [:yield/positions [:sew/escrow 0]]
                            {:token :USDC
                             :status :unwinding
                             :unrealized-yield 0
                             :realized-yield 0
                             :shortfall {:deferred-amount 500}}))]
    (is (:holds? (inv/solvency-holds? world nil))
        "escrow deferred remainder remains a yield-held liability after finalize")))
