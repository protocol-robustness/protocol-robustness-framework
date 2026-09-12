(ns resolver-sim.generators.yield-test
  (:require [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.generators.yield.core :as ycore]
            [resolver-sim.generators.yield.scenario :as ysc]
            [resolver-sim.yield.registry :as yreg]))

(deftest generators-produce-valid-scenario-maps
  (is (vector? (:events (ysc/build-yield-scenario {:seed 1}))))
  (is (= "vault" (get-in (ysc/build-yield-scenario {}) [:protocol-params :default-owner-id])))
  (is (contains? (set (:threat-tags (ysc/build-shortfall-scenario)))
                 "shortfall-affected")))

(deftest gen-yield-scenario-replays-without-invalid
  (let [prop (prop/for-all [scenario ysc/gen-yield-scenario]
                           (not= :invalid
                                 (:outcome (replay/replay-yield-scenario scenario))))]
    (is (:pass? (tc/quick-check 25 prop)))))

(deftest shortfall-affected-generated-scenario-passes
  (let [scenario (ysc/build-shortfall-scenario {:seed 99})
        result   (replay/replay-yield-scenario scenario)]
    (is (= :pass (:outcome result)))))

(deftest liquidity-shortage-scenario-rejects-deposit
  (let [scenario (ysc/build-liquidity-shortage-scenario)
        result   (replay/replay-yield-scenario scenario)]
    (is (= :pass (:outcome result)))
    (is (pos? (get-in result [:metrics :reverts])))))

(deftest gen-yield-profile-covers-registry
  (doseq [p ycore/known-profile-ids]
    (is (some? (:module-id (yreg/resolve-yield-profile p))) (str p))))
