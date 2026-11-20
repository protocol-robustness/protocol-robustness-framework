(ns resolver-sim.protocols.sew.alias-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.contract-model.replay :as replay]))

(def ^:private alice {:id "alice" :address "0xA"})
(def ^:private bob   {:id "bob"   :address "0xB"})

(deftest test-agent-alias-integration
  (testing "Batch integration: captured aliases available to events in same batch"
    (let [protocol (sew/->SewProtocol)
          scenario {:schema-version "1.0"
                    :scenario-id "alias-batch-test"
                    :title "Alias batch test"
                    :scenario-author "@test"
                    :purpose :regression
                    :agents [alice bob]
                     :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                               :save-agent-as "creator"
                               :params {:token "USDC" :to "0xB" :amount 1000}}
                              {:seq 1 :time 1001 :agent "creator" :action "raise_dispute"
                               :params {:workflow-id 0}}]
                     :execution-mode :deterministic-batch}
           result (replay/replay-with-protocol protocol scenario)]
      (is (= :pass (:outcome result)) (str "Batch execution failed: " (:halt-reason result)))
      (is (= :ok (get-in (first (filter #(= 1 (:seq %)) (:trace result))) [:result])) "Event using alias failed")))

  (testing "Alias failure handling: failed actions do not capture aliases"
    (let [protocol (sew/->SewProtocol)
          scenario {:schema-version "1.0"
                    :scenario-id "alias-fail-test"
                    :title "Alias failure test"
                    :scenario-author "@test"
                    :purpose :regression
                    :agents [alice bob]
                    :events [{:seq 0 :time 1000 :agent "alice" :action "raise_dispute"
                              :save-agent-as "bad-alias"
                              :params {:workflow-id 999}}] ;; Non-existent WF
                    :expected-errors [{:seq 0 :error :invalid-workflow-id}]
                    :execution-mode :deterministic-batch}
          result (replay/replay-with-protocol protocol scenario)
          final-alias-map (:id-alias-map result)]
      (is (= :pass (:outcome result)))
      (is (nil? (get final-alias-map "bad-alias"))
          "Failed action should not register alias")))

  (testing "Alias collision: subsequent captures overwrite previous"
    (let [protocol (sew/->SewProtocol)
          scenario {:schema-version "1.0"
                    :scenario-id "alias-collision-test"
                    :title "Alias collision test"
                    :scenario-author "@test"
                    :purpose :regression
                    :agents [alice bob]
                    :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                              :save-agent-as "my-alias"
                              :params {:token "USDC" :to "0xB" :amount 1000}}
                             {:seq 1 :time 1010 :agent "bob" :action "create_escrow"
                              :save-agent-as "my-alias"
                              :params {:token "USDC" :to "0xA" :amount 1000}}]
                    :execution-mode :deterministic-batch}
          result (replay/replay-with-protocol protocol scenario)
          final-alias-map (:id-alias-map result)]
      (is (= "bob" (get final-alias-map "my-alias"))
          "Alias 'my-alias' should be updated to 'bob'"))))
