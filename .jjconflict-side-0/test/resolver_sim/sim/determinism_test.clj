(ns resolver-sim.sim.determinism-test
  "Proves that run-suite produces bit-identical results across two consecutive runs.

   A determinism failure here means the replay engine has a non-deterministic
   code path (e.g. unordered map iteration, mutable state, time-dependent logic).
   All golden reports depend on this property holding."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.scenario.normalize :as norm]
            [resolver-sim.io.fixtures :as fixtures]))

(deftest all-invariants-suite-is-deterministic
  (let [result-1 (fixtures/run-suite-from-key :suites/all-invariants nil nil {:silent? true})
        result-2 (fixtures/run-suite-from-key :suites/all-invariants nil nil {:silent? true})]
    (is (= (dissoc result-1 :elapsed-ms)
           (dissoc result-2 :elapsed-ms))
        "Suite results differ between runs — replay engine has a non-deterministic code path")))

(deftest replay-idempotent-same-trace-works
  (testing "replay-idempotent-same-trace? confirms determinism for a single scenario"
    (binding [chain/*allow-dirty* true]
      (let [scenario (-> "scenarios/edn/S63_replay-idempotence-same-trace-double-run.edn"
                         sc/load-scenario-file
                         norm/normalize-scenario)
            protocol (preg/get-protocol preg/default-protocol-id)
            result (replay/replay-idempotent-same-trace? protocol scenario)]
        (is (true? (:idempotent? result))
            (str "Scenario replays are not idempotent — replay engine has a non-deterministic code path"))
        (is (= (:outcome (:first result)) (:outcome (:second result)))
            "Outcomes match")
        (is (= (:halt-reason (:first result)) (:halt-reason (:second result)))
            "Halt reasons match")
        (is (= (:events-processed (:first result)) (:events-processed (:second result)))
            "Events processed counts match")))))
