(ns resolver-sim.contract-model.replay-temporal-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.temporal :as temporal]
            [resolver-sim.contract-model.replay.analysis :as analysis]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.time.context :as time-ctx]
            [resolver-sim.protocols.sew.io.trace-export :as trace-export]))

(defn- temporal-step-context
  "Execution context with temporal enforcement enabled (direct process-step tests)."
  [scenario]
  (assoc (proto/build-execution-context sew/protocol (:agents scenario) {})
         :replay-flags {:temporal-enabled? true}))

(deftest advance-world-time-helper
  (testing "advances only when event-time is in the future"
    (let [w (time-ctx/with-temporal-context {} {:block-ts 1000})
          f #'resolver-sim.contract-model.replay/advance-world-time
          same (f w 1000)
          fut  (f w 1015)]
      (is (= 1000 (time-ctx/block-ts (:world same))))
      (is (false? (:advanced? same)))
      (is (= 0 (:delta-ms same)))
      (is (= 1015 (time-ctx/block-ts (:world fut))))
      (is (true? (:advanced? fut)))
      (is (= 15000 (:delta-ms fut))))))

(deftest advance-world-time-rejects-regression
  (testing "advance-world-time throws a structured :time-regression on regressive event time"
    (let [w (time-ctx/with-temporal-context {} {:block-ts 2000})
          ex (try
               (temporal/advance-world-time w 1000)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :time-regression (:type (ex-data ex))))
      (is (= 2000 (:now-ts (ex-data ex))))
      (is (= 1000 (:event-ts (ex-data ex)))))))

(deftest clock-guard-rejects-when-temporal-disabled
  (testing "regressive / malformed event times reject as trace entries (never crash) when temporal rules are off"
    (let [world  (time-ctx/with-temporal-context {} {:block-ts 2000})
          ctx    {:replay-flags {:temporal-enabled? false :check-invariants? false}}
          reg    (replay/process-step sew/protocol ctx world
                                      {:seq 0 :time 1000 :agent "alice"
                                       :action "set-paused" :params {:paused? true}})
          bad    (replay/process-step sew/protocol ctx world
                                      {:seq 0 :time (java.util.Date.) :agent "alice"
                                       :action "set-paused" :params {:paused? true}})]
      (is (= :rejected (get-in reg [:trace-entry :result])))
      (is (= :time-regression (get-in reg [:trace-entry :error])))
      (is (= :non-regressive-time (get-in reg [:trace-entry :temporal-rule-id])))
      (is (= 2000 (get-in reg [:trace-entry :time-after :block-ts]))
          "the clock must not move on a rejected step")
      (is (= :rejected (get-in bad [:trace-entry :result])))
      (is (= :invalid-event-time (get-in bad [:trace-entry :error])))
      (is (= :missing-event-time (get-in bad [:trace-entry :temporal-rule-id]))))))

(deftest epoch-second-rejects-unsupported-type
  (testing "epoch-second raises a structured :invalid-event-time for unsupported types"
    (let [w (time-ctx/with-temporal-context {} {:block-ts 1000})
          ex (try (temporal/advance-world-time w (java.util.Date.)) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :invalid-event-time (:type (ex-data ex))))
      (is (= "class java.util.Date" (:actual-type (ex-data ex)))))))

(deftest time-evidence-schema-version-reflects-terminal-context
  (testing "finalize-scenario-result labels :time-evidence with the actual terminal context version"
    (let [v1-world {:context/time {:schema-version "temporal-context.v1"
                                   :block-ts 5000 :step 3
                                   :instant (java.time.Instant/ofEpochSecond 5000)}}
          finalized (analysis/finalize-scenario-result
                     {:scenario-id "x"}
                     {:outcome :pass :world v1-world :trace [] :metrics {}})
          evidence  (:time-evidence finalized)]
      (is (= "temporal-context.v1" (:schema-version evidence)))
      (is (= "temporal-context.v1" (get-in evidence [:terminal-time :schema-version]))))))

(deftest temporal-rule-regression-rejected
  (testing "process-step rejects regressive time via temporal rule evaluation"
    (let [scenario {:scenario-id "time-regression-check"
                    :schema-version "1.1"
                    :scenario-author "@test"
                    :title "Time regression"
                    :purpose :adversarial-robustness
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}
                             {:id "bob" :type "honest" :address "0xBob"}]
                    :events []}
          world    (time-ctx/with-temporal-context
                     (proto/init-world sew/protocol scenario)
                     {:block-ts 2000})
          context  (temporal-step-context scenario)
          event    {:seq 0 :time 1999 :agent "alice" :action "set-paused" :params {:paused? true}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :rejected (get-in step [:trace-entry :result])))
      (is (= :time-regression (get-in step [:trace-entry :error])))
      (is (= :non-regressive-time (get-in step [:trace-entry :temporal-rule-id])))
      (is (= 2000 (get-in step [:world :context/time :block-ts]))))))

(deftest temporal-rule-invalid-time-rejected
  (testing "process-step rejects missing/invalid event-time with explicit rule metadata"
    (let [scenario {:scenario-id "invalid-time-check"
                    :schema-version "1.1"
                    :scenario-author "@test"
                    :title "Invalid time"
                    :purpose :adversarial-robustness
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}
                             {:id "bob" :type "honest" :address "0xBob"}]
                    :events []}
          world    (time-ctx/with-temporal-context
                     (proto/init-world sew/protocol scenario)
                     {:block-ts 2000})
          context  (temporal-step-context scenario)
          event    {:seq 0 :agent "alice" :action "set-paused" :params {:paused? true}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :rejected (get-in step [:trace-entry :result])))
      (is (= :invalid-event-time (get-in step [:trace-entry :error])))
      (is (= :missing-event-time (get-in step [:trace-entry :temporal-rule-id])))
      (is (= 2000 (get-in step [:world :context/time :block-ts]))))))

(deftest temporal-rule-context-extension
  (testing "process-step applies optional context-provided temporal rules"
    (let [scenario {:scenario-id "context-rule-check"
                    :schema-version "1.1"
                    :scenario-author "@test"
                    :title "Context rule"
                    :purpose :adversarial-robustness
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}
                             {:id "bob" :type "honest" :address "0xBob"}]
                    :events []}
          world    (time-ctx/with-temporal-context
                     (proto/init-world sew/protocol scenario)
                     {:block-ts 2000})
          context  (assoc (temporal-step-context scenario)
                          :temporal-rules
                          [{:id :custom-no-set-paused
                            :check (fn [{:keys [event]}]
                                     (if (= "set-paused" (:action event))
                                       {:ok? false :error :custom-time-rule}
                                       {:ok? true}))}])
          event    {:seq 0 :time 2001 :agent "alice" :action "set-paused" :params {:paused? true}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :rejected (get-in step [:trace-entry :result])))
      (is (= :custom-time-rule (get-in step [:trace-entry :error])))
      (is (= :custom-no-set-paused (get-in step [:trace-entry :temporal-rule-id]))))))

(deftest temporal-rule-instant-support
  (testing "process-step accepts java.time.Instant as valid event-time"
    (let [scenario {:scenario-id "instant-time-check"
                    :schema-version "1.1"
                    :scenario-author "@test"
                    :agents [{:id "buyer" :type "honest" :address "0xBuyer"}
                             {:id "seller" :type "honest" :address "0xSeller"}]
                    :events []}
          world    (time-ctx/with-temporal-context
                     (proto/init-world sew/protocol scenario)
                     {:block-ts 1000})
          context  (temporal-step-context scenario)
          inst     (java.time.Instant/ofEpochSecond 1005)
          event    {:seq 0 :time inst :agent "buyer" :action "create_escrow"
                    :params {:token "USDC" :to "0xSeller" :amount 6000}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :ok (get-in step [:trace-entry :result])))
      (is (= 1005 (get-in step [:trace-entry :time-after :block-ts])))))

  (testing "process-step rejects regressive Instant time"
    (let [scenario {:scenario-id "instant-regression-check"
                    :schema-version "1.1"
                    :scenario-author "@test"
                    :agents [{:id "alice" :type "honest" :address "0xAlice"}]
                    :events []}
          world    (time-ctx/with-temporal-context
                     (proto/init-world sew/protocol scenario)
                     {:block-ts 2000})
          context  (temporal-step-context scenario)
          inst     (java.time.Instant/ofEpochSecond 1999)
          event    {:seq 0 :time inst :agent "alice" :action "set-paused" :params {:paused? true}}
          step     (replay/process-step sew/protocol context world event)]
      (is (= :rejected (get-in step [:trace-entry :result])))
      (is (= :time-regression (get-in step [:trace-entry :error])))
      (is (= 2000 (get-in step [:trace-entry :time-after :block-ts]))))))

(deftest instant-deadline-policy
  (testing "Instant deadlines use the replay clock's epoch-second precision"
    (let [rules    (temporal/effective-temporal-rules {})
          protocol (reify proto/TemporalDeadlines
                     (deadline-for [_ _ _ _ _] 1180))
          ctx      {:event {:action "execute_pending_settlement"
                            :params {:workflow-id 0}}
                    :context {}
                    :protocol protocol
                    :world {}
                    :now 0}
          before   (temporal/evaluate-temporal-rules
                    rules
                    (assoc ctx :event-time (java.time.Instant/ofEpochSecond 1179 999999999)))
          at       (temporal/evaluate-temporal-rules
                    rules
                    (assoc ctx :event-time (java.time.Instant/ofEpochSecond 1180)))]
      (is (= :sew/appeal-window-open (:rule-id before)))
      (is (= 1179 (get-in before [:guard-context :temporal/event-time])))
      (is (nil? at)))))

(deftest sew-appeal-window-rule-triggers-via-replay
  (testing "Sew temporal rule rejects execute_pending_settlement before appeal deadline"
    (let [scenario {:scenario-id "sew-appeal-window-rule"
                    :id "S-Temporal-Sew-Appeal-Window"
                    :schema-version "1.1"
                    :scenario-author "@test"
                    :title "Sew appeal window temporal rule"
                    :purpose :adversarial-robustness
                    :expectations {:terminal [{:name :trace-produced :equals true}]}
                    :initial-block-time 1000
                    :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                             {:id "seller" :address "0xseller" :strategy "honest"}
                             {:id "l0resolver" :address "0xl0" :role "resolver"}
                             {:id "keeper" :address "0xkeeper" :role "keeper"}]
                    :protocol-params {:resolver-fee-bps 150
                                      :resolution-module "0xkleros-proxy"
                                      :escalation-resolvers {"0" "0xl0"}
                                      :appeal-window-duration 60
                                      :max-dispute-duration 2592000}
                    :options {:flags {:temporal-enabled? true}}
                    :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                              :params {:token "USDC" :to "0xseller" :amount 6000}}
                             {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                              :params {:workflow-id 0}}
                             {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
                              :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
                             ;; before appeal window expires (1120 + 60 = 1180)
                             {:seq 3 :time 1130 :agent "keeper" :action "execute_pending_settlement"
                              :params {:workflow-id 0}}]}
          result      (sew/replay-with-sew-protocol scenario)
          entry       (some #(when (= 3 (:seq %)) %) (:trace result))
          at-deadline (sew/replay-with-sew-protocol
                       (assoc-in scenario [:events 3 :time] 1180))
          boundary    (some #(when (= 3 (:seq %)) %) (:trace at-deadline))]
      (is (= :rejected (:result entry)))
      (is (= :appeal-window-not-expired (:error entry)))
      (is (= :sew/appeal-window-open (:temporal-rule-id entry)))
      (testing "the settlement deadline is inclusive"
        (is (= :ok (:result boundary)))
        (is (= 1180 (get-in boundary [:time-after :block-ts])))))))

(deftest temporal-rule-metadata-propagates-to-trace-artifact
  (testing "exported trace artifact preserves temporal rule id on rejected step"
    (let [scenario {:scenario-id "sew-appeal-window-artifact"
                    :id "S-Temporal-Sew-Appeal-Artifact"
                    :schema-version "1.1"
                    :scenario-author "@test"
                    :title "Sew appeal window artifact propagation"
                    :purpose :adversarial-robustness
                    :expectations {:terminal [{:name :trace-produced :equals true}]}
                    :initial-block-time 1000
                    :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                             {:id "seller" :address "0xseller" :strategy "honest"}
                             {:id "l0resolver" :address "0xl0" :role "resolver"}
                             {:id "keeper" :address "0xkeeper" :role "keeper"}]
                    :protocol-params {:resolver-fee-bps 150
                                      :resolution-module "0xkleros-proxy"
                                      :escalation-resolvers {"0" "0xl0"}
                                      :appeal-window-duration 60
                                      :max-dispute-duration 2592000}
                    :options {:flags {:temporal-enabled? true}}
                    :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                              :params {:token "USDC" :to "0xseller" :amount 6000}}
                             {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                              :params {:workflow-id 0}}
                             {:seq 2 :time 1120 :agent "l0resolver" :action "execute_resolution"
                              :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
                             {:seq 3 :time 1130 :agent "keeper" :action "execute_pending_settlement"
                              :params {:workflow-id 0}}]}
          result  (sew/replay-with-sew-protocol scenario)
          fixture (trace-export/export-trace-fixture result scenario)
          step    (some #(when (= 3 (:seq %)) %) (:steps fixture))]
      (is (= true (get-in step [:expected :reverted])))
      (is (= "appeal-window-not-expired" (get-in step [:expected :error])))
      (is (= "appeal-window-open" (get-in step [:attributes :temporal_rule_id]))))))
