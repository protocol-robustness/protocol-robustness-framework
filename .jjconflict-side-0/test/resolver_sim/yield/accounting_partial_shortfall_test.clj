(ns resolver-sim.yield.accounting-partial-shortfall-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.accounting :as acct]))

(deftest partial-yield-shortfall-predicate
  (testing "basis below principal identifies yield-leg-only shortfall"
    (is (acct/partial-yield-shortfall?
         {:principal 9850}
         {:basis-amount 788 :fulfilled-amount 394 :deferred-amount 394}))
    (is (not (acct/partial-yield-shortfall?
              {:principal 9850}
              {:basis-amount 9850 :fulfilled-amount 7880 :deferred-amount 1970})))))
