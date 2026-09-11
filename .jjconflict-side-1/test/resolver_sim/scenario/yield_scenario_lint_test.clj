(ns resolver-sim.scenario.yield-scenario-lint-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.yield-scenario-lint :as lint]))

(deftest detects-missing-failure-modes
  (let [scenario {:threat-tags ["negative-yield"]
                  :events [{:action "set-yield-risk"
                            :params {:apy -0.01}}]
                  :expectations {:metrics [{:name "yield/escrow-gross" :op ":<" :value 100}]}}]
    (is (seq (lint/lint-negative-yield-scenario scenario)))))

(deftest accepts-configured-and-asserted
  (let [scenario {:threat-tags ["negative-yield"]
                  :events [{:action "set-yield-risk"
                            :params {:failure-modes ["negative-yield"] :apy -0.01}}]
                  :expectations {:step-terminal [{:seq 1
                                                  :path ["yield-positions" ["sew/escrow" 0] "unrealized-yield"]
                                                  :op ":>"
                                                  :value 0}]}}]
    (is (empty? (lint/lint-negative-yield-scenario scenario)))))
