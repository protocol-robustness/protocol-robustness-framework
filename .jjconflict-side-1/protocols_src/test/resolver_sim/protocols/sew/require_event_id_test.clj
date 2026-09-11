(ns resolver-sim.protocols.sew.require-event-id-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.flags :as replay-flags]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.compat :as compat]
            [resolver-sim.io.scenarios :as scen-io]))

(deftest require-event-id-rejects-sensitive-action-without-id
  (let [scenario (scen-io/load-scenario-file "scenarios/edn/S02_dr3-dispute-release.edn")
        result   (replay/replay-with-protocol sew/protocol scenario
                                              {:flags {:require-event-id? true}})
        entry    (nth (:trace result) 2)]
    (is (= "execute_resolution" (:action entry)))
    (is (= :rejected (:result entry)))
    (is (= :missing-event-id (:error entry)))))

(deftest require-event-id-allows-s64-with-ids
  (let [scenario (scen-io/load-scenario-file "scenarios/edn/S64_replay-event-id-dedupe.edn")
        result   (replay/replay-with-protocol sew/protocol scenario
                                              {:flags replay-flags/external-log-replay-flags})]
    (is (= :pass (:outcome result)))
    (is (pos? (count (:trace result))))))

(deftest external-log-replay-flags-enables-require-event-id
  (is (true? (:require-event-id? replay-flags/external-log-replay-flags))))

(deftest non-sensitive-actions-unaffected-by-require-event-id
  (let [scenario (assoc {:scenario-id "require-id-non-sensitive"
                         :schema-version "1.0"
                         :initial-block-time 1000
                         :agents [{:id "buyer" :address "0xbuyer" :role "buyer" :strategy "honest"}
                                  {:id "seller" :address "0xseller" :role "seller" :strategy "honest"}
                                  {:id "resolver" :address "0xresolver" :role "resolver" :strategy "honest"}]
                         :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                                   :params {:token "USDC" :to "0xseller" :amount 1000 :custom-resolver "0xresolver"}}
                                  {:seq 1 :time 1100 :agent "buyer" :action "raise_dispute"
                                   :params {:workflow-id 0}}]
                         :protocol-params {:resolver-fee-bps 0}}
                        :allow-open-disputes? true)
        result (replay/replay-with-protocol sew/protocol scenario
                                            {:flags {:require-event-id? true}})]
    (is (= :pass (:outcome result)))
    (is (= :ok (:result (nth (:trace result) 1))))))
