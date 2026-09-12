(ns resolver-sim.protocols.sew.yield.failure-test
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.modules.liquid-lending :as liquid]
            [resolver-sim.yield.presets :as yield-presets]
            [resolver-sim.yield.registry :as yield-reg]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]))

(deftest test-yield-config-behavior-adapter
  (let [world (-> (proto/init-world sew/protocol {:scenario-id "x"})
                  (yield-reg/apply-yield-config
                   {:modules {:aave-v3 {:behavior {:yield/provider-kind :immediate-withdrawal-lending}
                                        :tokens {"USDC" {:initial-index 1.0
                                                         :apy 0.05
                                                         :failure-modes #{:withdraw-fails}}}}}}))]
    (is (= :yield.provider/liquid-lending
           (get-in world [:yield/module-aliases :aave-v3])))
    (is (= :immediate-withdrawal-lending
           (get-in world [:yield/behavior :aave-v3 :yield/provider-kind])))
    (is (= #{:withdraw-fails}
           (get-in world [:yield/risk :yield.provider/liquid-lending :USDC :failure-modes])))))

(deftest test-liquid-lending-emergency-unwind-marks-active-positions
  (testing "Emergency unwind marks matching active positions as :unwinding"
    (let [module {:module/id :aave-v3}
          world {:yield/positions {"u1" {:owner/id "u1" :module/id :aave-v3 :token "USDC" :status :active}
                                   "u2" {:owner/id "u2" :module/id :aave-v3 :token "USDC" :status :withdrawn}
                                   "u3" {:owner/id "u3" :module/id :aave-v3 :token "DAI"  :status :active}}}
          world' (liquid/emergency-unwind world module {:token "USDC"})]
      (is (= :unwinding (get-in world' [:yield/positions "u1" :status])))
      (is (= :withdrawn (get-in world' [:yield/positions "u2" :status])))
      (is (= :active (get-in world' [:yield/positions "u3" :status]))))))

(deftest test-replay-yield-config-sets-module-status
  (testing "Scenario yield-config can set module status and still replay non-yield actions"
    (let [scenario {:scenario-id "yield-module-status-config"
                    :schema-version "1.0"
                    :initial-block-time 1000
                    :yield-config {:modules {:aave-v3 {:module-status :disabled-for-new-deposits
                                                       :tokens {"USDC" {:initial-index 1.0 :apy 0.05}}}}}
                    :protocol-params {}
                    :agents [{:id "sender" :address "sender"}
                             {:id "recipient" :address "recipient"}]
                    :events [{:seq 0 :time 1000 :agent "sender" :action "create_escrow"
                              :params {:token "USDC" :to "recipient" :amount 10000}}]}
          result (replay/replay-with-protocol sew/protocol scenario)
          world  (-> (proto/init-world sew/protocol scenario)
                     (yield-reg/apply-yield-config (:yield-config scenario)))]
      ;; create_escrow in replay path does not auto-deposit yield unless module is in snapshot,
      ;; and replay trace projection intentionally omits non-EVM world fields,
      ;; so validate status on init-world directly.
      (is (= :pass (:outcome result)))
      (is (= :disabled-for-new-deposits
             (get-in world [:yield/module-status :yield.provider/liquid-lending]))))))

(deftest test-yield-preset-negative-yield-mild
  (let [world (-> (proto/init-world sew/protocol {:scenario-id "preset-test"})
                  yield-reg/init-yield-modules
                  (yield-presets/apply-preset :yield.preset/negative-yield-mild))]
    (is (= #{:negative-yield}
           (get-in world [:yield/risk :yield.provider/liquid-lending :USDC :failure-modes])))
    (is (= -0.01 (get-in world [:yield/rates :yield.provider/liquid-lending :USDC])))))