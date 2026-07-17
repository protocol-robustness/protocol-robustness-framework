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
