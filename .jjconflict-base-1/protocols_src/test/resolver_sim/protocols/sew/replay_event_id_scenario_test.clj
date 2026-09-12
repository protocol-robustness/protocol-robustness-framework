(ns resolver-sim.protocols.sew.replay-event-id-scenario-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.io.scenarios :as scen-io]))

(deftest s64-duplicate-events-with-event-id-noop
  (let [scenario (scen-io/load-scenario-file "scenarios/edn/S64_replay-event-id-dedupe.edn")
        result   (replay/replay-with-protocol sew/protocol scenario)
        trace    (:trace result)]
    (is (= :pass (:outcome result)))
    (is (= :no-op-duplicate (get-in (nth trace 3) [:extra :idempotency])))
    (is (= :no-op-duplicate (get-in (nth trace 5) [:extra :idempotency])))))
