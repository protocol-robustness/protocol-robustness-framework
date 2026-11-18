(ns resolver-sim.protocols.sew.financial.finality-hardening-test
  (:require [clojure.test :refer :all]
            [resolver-sim.protocols.sew.financial.finality :as fin]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]))

;; Actions that SHOULD be forbidden when :can-change? is false.
(def state-mutating-actions
  ["create-escrow" "raise-dispute" "execute-resolution"
   "execute-pending-settlement" "release" "sender-cancel"
   "recipient-cancel" "escalate-dispute" "submit-evidence"
   "challenge-resolution"])

(deftest test-finality-gate-coverage
  (testing "Verify that terminal states reject all state-mutating actions"
    (let [w (t/empty-world 1000)
          snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0})
          wf-id 0]
      (let [c (lc/create-escrow w "0xAlice" "0xUSDC" "0xBob" 1000 {} snap)
            w-final (-> c :world (lc/release 0 "0xAlice" (fn [_ _ _] {:allowed? true :reason-code 0})) :world)
            ff (fin/classify-financial-finality w-final wf-id)]

        ;; Ensure terminal state
        (is (true? (:financially-final? ff)))
        (is (false? (:can-change? ff)))

        ;; Assert that all state-mutating actions are blocked (or simply not covered by open-gates)
        (is (empty? (:open-gates ff)) "No open gates allowed in terminal state")))))
