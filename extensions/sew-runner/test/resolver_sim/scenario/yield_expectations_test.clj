(ns resolver-sim.scenario.yield-expectations-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.sim.fixtures :as fix]
            [resolver-sim.scenario.yield-scenario-lint :as lint]))

(def ^:private negative-yield-scenarios
  ["scenarios/edn/S108_negative-yield-mild.edn"
   "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
   "scenarios/edn/S78_yield-negative-yield-release-path.edn"
   "scenarios/edn/S79_yield-negative-yield-dispute-refund-path.edn"
   "scenarios/edn/S109_negative-yield-severe-repair.edn"])

(defn- load-normalized [path]
  (-> path sc/load-scenario-file fix/normalize-scenario))

(deftest negative-yield-scenario-structure
  (doseq [path negative-yield-scenarios]
    (testing path
      (let [scenario (load-normalized path)
            issues   (lint/lint-negative-yield-scenario scenario)]
        (is (empty? issues)
            (str "lint for " path ": " (pr-str issues)))))))
