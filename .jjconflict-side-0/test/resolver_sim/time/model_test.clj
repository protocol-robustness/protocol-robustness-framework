(ns resolver-sim.time.model-test
  (:require [clojure.test :refer :all]
            [resolver-sim.time.model :as tm]
            [resolver-sim.time.deadlines :as td]
            [resolver-sim.time.invariants :as tinv]))

(deftest test-time-now-and-advance
  (let [w0 {:block-time 1000}
        n0 (tm/now w0)
        w1 (tm/advance w0 {:seconds 60 :steps 5})
        n1 (tm/now w1)]
    (is (= 1000 (:block-ts n0)))
    (is (= 1060 (:block-ts n1)))
    (is (= 5 (:scenario-step n1)))))

(deftest test-time-advance-rejects-negative
  (is (thrown? clojure.lang.ExceptionInfo
               (tm/advance {:block-time 1000} {:seconds -1}))))

(deftest test-deadline-helpers
  (let [dl (td/deadline 1000 3600)
        b  (td/boundary-times dl)]
    (is (= 4600 dl))
    (is (td/before-deadline? 4599 dl))
    (is (td/at-deadline? 4600 dl))
    (is (td/deadline-passed? 4601 dl))
    (is (= 4599 (:t-1 b)))
    (is (= 4600 (:t b)))
    (is (= 4601 (:t+1 b)))))

(deftest test-time-invariants
  (let [wb {:block-time 1000 :escrow-transfers {0 {:escrow-state :released}}}
        wa-good {:block-time 1001 :escrow-transfers {0 {:escrow-state :released}}}
        wa-bad-time {:block-time 999 :escrow-transfers {0 {:escrow-state :released}}}
        wa-bad-finality {:block-time 1001 :escrow-transfers {0 {:escrow-state :disputed}}}]
    (is (:holds? (tinv/non-decreasing-time? wb wa-good)))
    (is (not (:holds? (tinv/non-decreasing-time? wb wa-bad-time))))
    (is (:holds? (tinv/no-action-after-finality? wb wa-good
                                                 :entities-before-fn (fn [w] (:escrow-transfers w {}))
                                                 :state-after-fn     (fn [w entity-id]
                                                                       (get-in w [:escrow-transfers entity-id :escrow-state]))
                                                 :terminal-states    #{:released :refunded})))
    (is (not (:holds? (tinv/no-action-after-finality? wb wa-bad-finality
                                                      :entities-before-fn (fn [w] (:escrow-transfers w {}))
                                                      :state-after-fn     (fn [w entity-id]
                                                                            (get-in w [:escrow-transfers entity-id :escrow-state]))
                                                      :terminal-states    #{:released :refunded}))))))
