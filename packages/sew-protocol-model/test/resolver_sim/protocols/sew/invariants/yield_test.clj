(ns resolver-sim.protocols.sew.invariants.yield-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.invariants.yield :as invariants]))

(deftest yield-position-consistency-reports-invalid-position-states
  (let [result (invariants/yield-position-consistency?
                {:yield/positions
                 {:active {:status :active :principal 10 :unrealized-yield 2}
                  :unwinding {:status :unwinding :principal 2
                              :shortfall {:deferred-amount 1}}
                  :withdrawn {:status :withdrawn :principal 0
                              :realized-yield 0 :unrealized-yield 0}}})]
    (is (:holds? result))
    (is (empty? (:violations result))))
  (let [result (invariants/yield-position-consistency?
                {:yield/positions
                 {:negative {:status :active :principal -1}
                  :missing-shortfall {:status :unwinding}
                  :not-zeroed {:status :withdrawn :principal 1}
                  :unknown {:status :settled}}})]
    (is (false? (:holds? result)))
    (is (= 4 (count (:violations result))))))

(deftest yield-exposure-is-bounded-by-workflow-resolver-stake
  (let [owner-id [:sew/escrow 7]
        result (invariants/yield-exposure-ok?
                {:params {:resolver-stakes {7 5}}
                 :yield/positions {owner-id {:status :active
                                             :realized-yield 2
                                             :unrealized-yield 4}}})]
    (is (false? (:holds? result)))
    (is (= owner-id (get-in result [:violations 0 :owner-id])))
    (is (= 7 (get-in result [:violations 0 :workflow-id])))
    (is (= 6 (get-in result [:violations 0 :yield-exposure])))))

(deftest realized-yield-must-be-nonnegative
  (is (:holds? (invariants/realized-non-negative?
                {:yield/positions {:positive {:realized-yield 0}}})))
  (is (false? (:holds? (invariants/realized-non-negative?
                        {:yield/positions {:negative {:realized-yield -1}}})))))
