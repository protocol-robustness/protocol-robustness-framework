(ns resolver-sim.contract-model.replay-fork-checkpoints-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.io.scenarios :as scen-io]))

(deftest world-checkpoints-include-replay-complete-state
  (let [scenario (scen-io/load-scenario-file "scenarios/edn/S64_replay-event-id-dedupe.edn")
        result   (replay/replay-with-protocol sew/protocol scenario
                                              {:flags {:world-checkpoint-policy :retain-all}})
        cp       (get-in result [:world-checkpoints 3])]
    (is (= :pass (:outcome result)))
    (is (map? cp))
    (is (contains? cp :pending-settlements))
    (is (contains? cp :module-snapshots))
    (is (true? (get-in cp [:pending-settlements 0 :exists])))))

(deftest default-checkpoint-policy-retains-decision-nodes-only
  (let [scenario (scen-io/load-scenario-file "scenarios/edn/S64_replay-event-id-dedupe.edn")
        result   (replay/replay-with-protocol sew/protocol scenario)
        cps      (:world-checkpoints result)]
    (is (= :pass (:outcome result)))
    (is (= #{1 2 3} (set (keys cps))))
    (is (not (contains? cps 0)))
    (is (not (contains? cps 4)))
    (is (not (contains? cps 5)))))

(deftest trace-entry->replay-event-strips-trace-metadata
  (let [entry {:seq 3 :time 1120 :agent "buyer" :action "challenge_resolution"
               :params {:workflow-id 0 :event-id "evt-1"}
               :world {:block-time 1120}
               :result :ok
               :error nil}
        event  (replay/trace-entry->replay-event entry)]
    (is (= {:seq 3 :time 1120 :agent "buyer" :action "challenge_resolution"
            :params {:workflow-id 0 :event-id "evt-1"}}
           event))
    (is (not (contains? event :world)))
    (is (not (contains? event :result)))))
