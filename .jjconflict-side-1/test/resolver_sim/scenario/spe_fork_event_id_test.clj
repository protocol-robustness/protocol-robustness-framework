(ns resolver-sim.scenario.spe-fork-event-id-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.compat :as compat]
            [resolver-sim.scenario.subgame-counterfactual :as cf]
            [resolver-sim.io.scenarios :as scen-io]))

(defn- fork-options [scenario]
  {:scenario scenario
   :agents (:agents scenario)})

(deftest fork-continuation-inherits-event-id-from-main-trace
  (testing "SPE fork replay preserves event-id on continuation events"
    (let [scenario   (scen-io/load-scenario-file "scenarios/edn/S65_spe-fork-event-id-inheritance.edn")
          result     (replay/replay-with-protocol sew/protocol scenario
                                                  {:flags {:world-checkpoint-policy :retain-all}})
          trace      (:trace result)
          fork-world (get-in result [:world-checkpoints 2])
          res-entry  (nth trace 2)
          cont-events (->> (drop 3 trace)
                           (map replay/trace-entry->replay-event)
                           (map-indexed (fn [i e] (assoc e :seq (inc i)))))
          fork-result (replay/resume-from-snapshot
                       sew/protocol (:agents scenario) (:protocol-params scenario)
                       (:scenario-id scenario) fork-world
                       (into [{:seq 0 :time 1120 :agent "resolver" :action "execute_resolution"
                               :params (:params res-entry)}]
                             cont-events)
                       [] {} (fork-options scenario))
          settle-entry (first (filter #(= "execute_pending_settlement" (:action %))
                                      (:trace fork-result)))]
      (is (= :pass (:outcome result)))
      (is (= :pass (:outcome fork-result)))
      (is (= "evt-settle-main" (compat/event-id {:params (:params settle-entry)}))))))

(deftest fork-continuation-dedupes-duplicate-settlement-event-id
  (testing "Inherited event-id activates replay dedupe in fork continuations"
    (let [scenario   (scen-io/load-scenario-file "scenarios/edn/S65_spe-fork-event-id-inheritance.edn")
          result     (replay/replay-with-protocol sew/protocol scenario
                                                  {:flags {:world-checkpoint-policy :retain-all}})
          trace      (:trace result)
          fork-world (get-in result [:world-checkpoints 2])
          res-entry  (nth trace 2)
          settle     (replay/trace-entry->replay-event (nth trace 3))
          dup-settle (assoc settle :seq 2 :time 1200)
          fork-result (replay/resume-from-snapshot
                       sew/protocol (:agents scenario) (:protocol-params scenario)
                       (:scenario-id scenario) fork-world
                       [{:seq 0 :time 1120 :agent "resolver" :action "execute_resolution"
                         :params (:params res-entry)}
                        (assoc settle :seq 1)
                        dup-settle]
                       [] {} (fork-options scenario))
          fork-trace (:trace fork-result)]
      (is (= :pass (:outcome fork-result)))
      (is (= :no-op-duplicate (get-in (nth fork-trace 2) [:extra :idempotency]))))))

(deftest spe-tree-expansion-passes-with-event-id-scenario
  (testing "SPE evaluation with tree expansion on S65"
    (let [scenario    (scen-io/load-scenario-file "scenarios/edn/S65_spe-fork-event-id-inheritance.edn")
          result      (replay/replay-with-protocol sew/protocol scenario)
          projection  (assoc (proto/trace-projection sew/protocol result)
                             :spe-config {:regret-threshold 1000
                                          :enable-tree-expansion? true
                                          :utility-spec {:type :terminal-realized-v1 :version "v1"}})
          eval-result (cf/evaluate-subgame-counterfactual projection)
          row         (first (filter #(= "execute_resolution" (:chosen-action %))
                                     (:regret-table eval-result)))]
      (is (= :pass (:outcome result)))
      (is (= :pass (:status eval-result)))
      (is (some? row))
      (is (pos? (count (:alternatives row)))))))
