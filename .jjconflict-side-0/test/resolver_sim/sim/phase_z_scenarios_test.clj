(ns resolver-sim.sim.phase-z-scenarios-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.sim.adversarial.phase-z-scenarios :as z]))

(deftest phase-z-5-cascade-liveness-auto-cancels-all-three
  (testing "auto_cancel_disputed eligibility is per-workflow timestamp"
    (let [r (replay/replay-with-protocol sew/protocol z/scenario-z5-cascade-liveness)
          results (mapv (juxt :seq :action :result :error) (:trace r))]
      (is (= :pass (:outcome r)) (pr-str results))
      (is (every? (fn [e] (not= :dispute-timeout-not-exceeded (:error e))) (:trace r))
          "No workflow should revert due to timeout not exceeded"))))

