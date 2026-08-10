(ns resolver-sim.contract-model.replay-yield-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.yield :as yield-replay]
            [resolver-sim.protocols.yield :as yp]))

(def base-scenario
  {:scenario-id "yield-replay-test"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "vault" :address "0xVault"}]
   :protocol-params {:yield-profile "aave-v3" :default-owner-id "vault"}
   :yield-config {:modules {"aave-v3" {:tokens {"USDC" {:apy 0.05 :liquidity-mode "available"}}}}}
   :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
             :params {:token "USDC" :amount 10000}}
            {:seq 1 :time 2000 :agent "vault" :action "yield_accrue"
             :params {:token "USDC" :dt 1000}}]})

(deftest replay-yield-scenario-passes-aligned-dt
  (let [result (yield-replay/replay-yield-scenario base-scenario)]
    (is (= :pass (:outcome result)))
    (is (= :yield-sequential (get-in result [:execution :mode])))))

(deftest canonical-yield-replay-preserves-input-scenario-identity
  (let [result (replay/replay-events yp/protocol base-scenario
                                     {:flags {:yield-dt-validation? true
                                              :metrics-profile :yield-provider}})]
    (is (= :pass (:outcome result)))
    (is (= "yield-replay-test" (:scenario-id result)))
    (is (= "yield-replay-test" (get-in result [:world :params :scenario-id])))))

(def shared-scenario
  {:scenario-id "yield-shared-replay-test"
   :id "yield-shared-replay-test"
   :schema-version "1.1"
   :title "Shared withdrawal canonical replay"
   :purpose "functional-test"
   :scenario-author "agent-c"
   :initial-block-time 1000
   :agents [{:id "alice" :address "0xAlice" :role "provider"}
            {:id "bob" :address "0xBob" :role "provider"}
            {:id "governance" :address "governance" :role "governance"}]
   :protocol-params {:yield-profile "aave-v3" :token "USDC"}
   :events [{:seq 0 :time 1000 :agent "alice" :action "yield_deposit"
             :params {:amount 100 :token "USDC" :owner-id "alice"}}
            {:seq 1 :time 1000 :agent "bob" :action "yield_deposit"
             :params {:amount 100 :token "USDC" :owner-id "bob"}}
            {:seq 2 :time 2000 :agent "governance" :action "set-yield-risk"
             :params {:token "USDC" :shortfall {:available-ratio 0.5 :reason "liquidity-shortfall"}}}
            {:seq 3 :time 3000 :agent "governance" :action "yield_withdraw_shared"
             :params {:token "USDC" :module-id "aave-v3" :owner-ids ["alice" "bob"]
                      :allocation-mode "pro-rata"}}]})

(deftest canonical-replay-executes-shared-withdrawal-not-vacuous
  (let [result (replay/replay-with-protocol yp/protocol shared-scenario
                                            {:allow-dirty? true :skip-finalize true
                                             :flags {:yield-dt-validation? true
                                                     :metrics-profile :yield-provider}})
        shared-step (first (filter #(= 3 (:seq %)) (:trace result)))]
    (is (= :pass (:outcome result)))
    (is (= :ok (:result shared-step))
        "shared withdrawal must be applied through the canonical loop, not silently rejected")
    (is (some? (get-in result [:world :run/id]))
        "world carries run identity for application-order commitments")
    (is (= 1 (count (filter #(= :yield-withdraw-shared (:decision/source %))
                            (vals (get-in result [:world :yield/partial-fill-decisions])))))
        "shared decision artifact persisted in the world")))

(deftest replay-yield-scenario-rejects-dt-time-mismatch
  (let [scenario (assoc-in base-scenario [:events 1 :params :dt] 999)
        result   (yield-replay/replay-yield-scenario scenario)]
    (is (= :invalid (:outcome result)))
    (is (= :dt-time-mismatch (:halt-reason result))))

  (deftest simple-replay-uses-canonical-yield-contract
    (let [result (replay/simple-replay yp/protocol base-scenario)]
      (is (= :pass (:outcome result)))
      (is (= "yield-replay-test" (:scenario-id result)))))

  (deftest unknown-time-advance-actions-rejected
    (doseq [action ["advance_time" "time_advance"]]
      (let [scenario (update base-scenario :events conj
                             {:seq 2 :time 3000 :agent "vault" :action action :params {}})
            result   (yield-replay/replay-yield-scenario scenario)]
        (is (= :pass (:outcome result)) (str action " should not break prior steps"))
        (is (= :rejected (:result (last (:trace result)))))
        (is (= :unknown-action (:error (last (:trace result)))))))))
