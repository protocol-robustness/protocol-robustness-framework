(ns resolver-sim.sim.phase-e-evidence-integrity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.research.sew.analytic.phase-e-evidence-integrity :as phase-e]))

(deftest e5-yield-accrual-during-dispute-sweep
  (testing "Simulator-backed dispute yield accrual sweep"
    (let [{:keys [passed? summary]} (phase-e/run-e5-yield-accrual-during-dispute)]
      (is passed?)
      (is (= 11 (:total-trials summary)))
      (is (= 11 (:correctly-credited summary))))))
