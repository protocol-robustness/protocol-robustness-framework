(ns resolver-sim.protocols.yield-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.protocols.registry :as preg]
                        [resolver-sim.yield.invariants :as yield-invariants]))

(def base-scenario
  {:scenario-id "yield-test-inline"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "vault" :address "0xVault"}]
   :protocol-params {:yield-profile "aave-v3" :default-owner-id "vault"}
   :yield-config {:modules {"aave-v3" {:tokens {"USDC" {:apy 0.05 :liquidity-mode "available"}}}}}
   :options {:minimal true}
   :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
             :params {:token "USDC" :amount 10000}}
            {:seq 1 :time 87400 :agent "vault" :action "yield_accrue"
             :params {:token "USDC" :dt 86400}}]})

(deftest yield-protocol-satisfies-adapter
  (is (satisfies? proto/SimulationAdapter yp/protocol))
  (is (not (satisfies? proto/EconomicModel yp/protocol))))

(deftest registry-resolves-yield-v1
  (is (= yp/protocol (preg/get-protocol "yield-v1"))))

(deftest simple-replay-deposit-accrue
  (let [result (replay/replay-yield-scenario base-scenario)]
    (is (= :pass (:outcome result)))
    (is (pos? (get-in result [:metrics :yield/position-unrealized])))))

(deftest replay-shortfall-withdraw-exposes-partial-fill-decision
  (let [scenario (assoc base-scenario
                        :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
                                  :params {:token "USDC" :amount 10000}}
                                 {:seq 1 :time 2000 :agent "vault" :action "set-yield-risk"
                                  :params {:token "USDC"
                                           :shortfall {:available-ratio 0.5
                                                       :reason "liquidity-shortfall"}}}
                                 {:seq 2 :time 3000 :agent "vault" :action "yield_withdraw"
                                  :params {:token "USDC"}}]
                        :protocol-params {:yield-profile "aave-v3"
                                          :default-owner-id "vault"})
        result (replay/replay-yield-scenario scenario)
        decisions (get-in result [:world :yield/partial-fill-decisions])
        snapshot-decisions (get-in (proto/world-snapshot yp/protocol (:world result))
                                   [:yield-evidence :partial-fill-decisions])]
    (is (= :pass (:outcome result)))
    (is (= 1 (count decisions)))
    (is (= decisions snapshot-decisions))))

(deftest replay-shared-withdraw-is-atomic-pro-rata
  (let [scenario (assoc base-scenario
                        :agents [{:id "alice" :address "alice"} {:id "bob" :address "bob"} {:id "governance" :address "governance"}]
                        :events [{:seq 0 :time 1000 :agent "alice" :action "yield_deposit"
                                  :params {:token "USDC" :owner-id "alice" :amount 1000}}
                                 {:seq 1 :time 1000 :agent "bob" :action "yield_deposit"
                                  :params {:token "USDC" :owner-id "bob" :amount 2000}}
                                 {:seq 2 :time 1100 :agent "governance" :action "set-yield-risk"
                                  :params {:token "USDC" :shortfall {:available-ratio 0.6 :reason "test"}}}
                                 {:seq 3 :time 1200 :agent "governance" :action "yield_withdraw_shared"
                                  :params {:token "USDC" :module-id "aave-v3"
                                           :owner-ids ["bob" "alice"] :allocation-mode "pro-rata"}}])
        result (replay/replay-yield-scenario scenario)
        decisions (vals (get-in result [:world :yield/partial-fill-decisions]))
        decision (first decisions)]
    (is (= :pass (:outcome result)))
    (is (= 1 (count decisions)))
    (is (= :yield-withdraw-shared (:decision/source decision)))
    (is (= {"alice" 600 "bob" 1200} (:filled decision)))
    (is (= {"alice" 400 "bob" 800} (:deferred decision)))
    (is (= :pro-rata (get-in decision [:policy :mode])))
    (is (= ["alice" "bob"] (:participants decision)))
    (let [propagation (first (vals (get-in result [:world :yield/pro-rata-propagations])))]
      (is (= "pro-rata-propagation.v1" (:schema-version propagation)))
      (is (= 1800 (get-in propagation [:summary :allocated])))
      (is (= 1200 (get-in propagation [:summary :deferred])))
      (is (= :committed (:status propagation)))
      (let [accounting (get (yield-invariants/check-all (:world result))
                            :yield/pro-rata-accounting-reconciles)]
        (is (true? (:holds? accounting)))
        (doseq [check [:policy-reference-valid
                       :source-account-policy-compliant
                       :participant-account-policy-compliant
                       :deferred-position-policy-compliant
                       :shortfall-policy-compliant
                       :residual-policy-compliant
                       :idempotency-policy-compliant]]
          (is (= :pass (get-in accounting [:checks check]))))))))

(deftest y01-long-accrue-expectations
  (let [scenario (assoc base-scenario
                        :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
                                  :params {:token "USDC" :amount 10000}}
                                 {:seq 1 :time 31537000 :agent "vault" :action "yield_accrue"
                                  :params {:token "USDC" :dt 31536000}}]
                        :expectations {:metrics [{:name :yield/position-principal :op := :value 10000}
                                                 {:name :yield/position-unrealized :op :> :value 400}]})
        result (replay/replay-yield-scenario scenario)]
    (is (= :pass (:outcome result)))))

(deftest y02-negative-yield-step
  (let [scenario (assoc base-scenario
                        :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
                                  :params {:token "USDC" :amount 10000}}
                                 {:seq 1 :time 31537000 :agent "vault" :action "yield_accrue"
                                  :params {:token "USDC" :dt 31536000}}
                                 {:seq 2 :time 31537001 :agent "vault" :action "set-yield-risk"
                                  :params {:module-id "aave-v3" :token "USDC" :apy -0.05
                                           :failure-modes ["negative-yield"]}}
                                 {:seq 3 :time 63073001 :agent "vault" :action "yield_accrue"
                                  :params {:token "USDC" :dt 31536000}}]
                        :expectations {:step-terminal [{:seq 3
                                                        :path ["yield-positions" "vault" "unrealized-yield"]
                                                        :op :<
                                                        :value 0}]})
        result (replay/replay-yield-scenario scenario)]
    (is (= :pass (:outcome result)))))
