(require '[resolver-sim.sim.fixtures :as fix]
         '[resolver-sim.protocols.sew :as sew])

(defn replay-scenario [scenario]
  (sew/replay-with-sew-protocol (fix/normalize-scenario scenario)))

(defn steps [scenario]
  (let [r (replay-scenario scenario)]
    (mapv (fn [e]
            {:seq (get-in e [:event :seq])
             :act (:action (:event e))
             :unreal (get-in e [:world :yield-positions [:sew/escrow 0] :unrealized-yield])
             :gross (when-let [p (get-in e [:world :yield-positions [:sew/escrow 0]])]
                      (+ (:principal p 0) (:unrealized-yield p 0)))
             :claim (get-in e [:world :claimable 0])})
          (:trace r))))

(defn metrics [scenario]
  (let [r (replay-scenario scenario)]
    (select-keys (:metrics r)
                 [:yield/escrow-principal :yield/escrow-unrealized :yield/escrow-gross
                  :yield/escrow-realized :yield/escrow-reclaimed :yield/escrow-deferred
                  :yield/escrow-haircut :buyer/claimable :protocol/fees-usdc])))

(def base-buyer
  {:scenario-id "t" :schema-version "1.0" :initial-block-time 1000
   :agents [{:id "buyer" :address "0xbuyer"} {:id "seller" :address "0xseller"}]
   :protocol-params {:yield-generation-module "aave-v3"}
   :yield-config {:modules {:aave-v3 {:tokens {:USDC {:apy 0.05 :liquidity-mode :available}}}}}})

(def base-sender
  {:scenario-id "t" :schema-version "1.0" :initial-block-time 1000
   :agents [{:id "sender" :address "sender"} {:id "recipient" :address "recipient"}]
   :protocol-params {:yield-generation-module "aave-v3"
                     :yield-protocol-fee-bps 1000
                     :resolver-fee-bps 150}
   :yield-config {:modules {:aave-v3 {:tokens {:USDC {:apy 0.05 :liquidity-mode :available}}}}}})

(println "S108 v2 steps" (steps (assoc base-buyer :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 7776000 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 7776001 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :apy -0.01 :failure-modes ["negative-yield"]}}
   {:seq 3 :time 15552001 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 4 :time 15552002 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 5 :time 15552002 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}])))

(println "S108 v2 metrics" (metrics (assoc base-buyer :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 7776000 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 7776001 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :apy -0.01 :failure-modes ["negative-yield"]}}
   {:seq 3 :time 15552001 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 4 :time 15552002 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 5 :time 15552002 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}])))

(println "S78 steps" (steps (assoc base-sender :events
  [{:seq 0 :time 1000 :agent "sender" :action "create_escrow"
    :params {:token "USDC" :to "recipient" :amount 100000 :yield-preset "to-recipient"}}
   {:seq 1 :time 31536000 :agent "sender" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 31536001 :agent "sender" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :apy -0.03 :failure-modes ["negative-yield"]}}
   {:seq 3 :time 63072001 :agent "sender" :action "release" :params {:workflow-id 0}}])))

(println "S78 metrics" (metrics (assoc base-sender :events
  [{:seq 0 :time 1000 :agent "sender" :action "create_escrow"
    :params {:token "USDC" :to "recipient" :amount 100000 :yield-preset "to-recipient"}}
   {:seq 1 :time 31536000 :agent "sender" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 31536001 :agent "sender" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :apy -0.03 :failure-modes ["negative-yield"]}}
   {:seq 3 :time 63072001 :agent "sender" :action "release" :params {:workflow-id 0}}])))

(def base-s109
  (assoc base-buyer :protocol-params {:yield-generation-module "aave-v3" :resolver-fee-bps 150}))

(println "S109 long steps" (steps (assoc base-s109 :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
             :failure-modes ["negative-yield"] :apy -0.05
             :shortfall {:available-ratio 0.5}}}
   {:seq 3 :time 7776000 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
   {:seq 4 :time 7776000 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 5 :time 7776001 :agent "buyer" :action "withdraw-escrow" :params {:workflow-id 0}}
   {:seq 6 :time 7777000 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "available" :failure-modes []}}
   {:seq 7 :time 7777001 :agent "buyer" :action "claim-deferred-yield"
    :params {:owner-id "escrow:0"}}])))

(defn last-world-info [scenario]
  (let [r (replay-scenario scenario)
        w (get-in (last (:trace r)) [:world])]
    {:outcome (:outcome r)
     :live (get-in w [:live-states 0])
     :state (get-in w [:escrow-transfers 0 :escrow-state])
     :pos (get-in w [:yield-positions [:sew/escrow 0]])}))

(println "S109 long world" (last-world-info (assoc base-s109 :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
             :failure-modes ["negative-yield"] :apy -0.05
             :shortfall {:available-ratio 0.5}}}
   {:seq 3 :time 7776000 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
   {:seq 4 :time 7776000 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 5 :time 7776001 :agent "buyer" :action "withdraw-escrow" :params {:workflow-id 0}}
   {:seq 6 :time 7777000 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "available" :failure-modes []}}
   {:seq 7 :time 7777001 :agent "buyer" :action "claim-deferred-yield"
    :params {:owner-id "escrow:0"}}])))

(def base-s79
  {:scenario-id "t" :schema-version "1.0" :initial-block-time 1000
   :agents [{:id "sender" :address "sender"} {:id "recipient" :address "recipient"}
            {:id "resolver" :address "resolver"}]
   :protocol-params {:yield-generation-module "aave-v3"
                      :yield-protocol-fee-bps 1000
                      :resolver-fee-bps 150
                      :resolver-bond-bps 0
                      :appeal-window-duration 0
                      :max-dispute-duration 2592000}
   :yield-config {:modules {:aave-v3 {:tokens {:USDC {:apy 0.05}}}}}})

(println "S79 res 172k" (steps (assoc base-s79 :events
  [{:seq 0 :time 1000 :agent "resolver" :action "register_stake" :params {:amount 200000}}
   {:seq 1 :time 1000 :agent "sender" :action "create_escrow"
    :params {:token "USDC" :to "recipient" :amount 100000 :yield-preset "to-recipient"
             :custom-resolver "resolver"}}
   {:seq 2 :time 86400 :agent "sender" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 3 :time 86401 :agent "sender" :action "raise_dispute" :params {:workflow-id 0}}
   {:seq 4 :time 86402 :agent "sender" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :apy -0.05 :failure-modes ["negative-yield"]}}
   {:seq 5 :time 172000 :agent "resolver" :action "execute_resolution"
    :params {:workflow-id 0 :is-release false :resolution-hash "0xneg79"}}])))

(println "S109 apy-20 full metrics" (metrics (assoc base-s109 :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
             :failure-modes ["negative-yield"] :apy -0.20
             :shortfall {:available-ratio 0.5}}}
   {:seq 3 :time 100000 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
   {:seq 4 :time 100000 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 5 :time 100001 :agent "buyer" :action "withdraw-escrow" :params {:workflow-id 0}}
   {:seq 6 :time 100100 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "available" :failure-modes []}}
   {:seq 7 :time 100101 :agent "buyer" :action "claim-deferred-yield"
    :params {:owner-id "escrow:0"}}])))

(println "S109 apy-20 cancel 100k" (last-world-info (assoc base-s109 :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
             :failure-modes ["negative-yield"] :apy -0.20
             :shortfall {:available-ratio 0.5}}}
   {:seq 3 :time 100000 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
   {:seq 4 :time 100000 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}])))

(doseq [cancel-at [95000 100000 150000 200000 300000]]
  (println "S109 cancel-at" cancel-at (last-world-info (assoc base-s109 :events
    [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
      :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
     {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
     {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
      :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
               :failure-modes ["negative-yield"] :apy -0.05
               :shortfall {:available-ratio 0.5}}}
     {:seq 3 :time cancel-at :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
     {:seq 4 :time cancel-at :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}]))))

(println "S109 60d world" (last-world-info (assoc base-s109 :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
             :failure-modes ["negative-yield"] :apy -0.05
             :shortfall {:available-ratio 0.5}}}
   {:seq 3 :time 5184000 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
   {:seq 4 :time 5184000 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 5 :time 5184001 :agent "buyer" :action "withdraw-escrow" :params {:workflow-id 0}}
   {:seq 6 :time 5185000 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "available" :failure-modes []}}
   {:seq 7 :time 5185001 :agent "buyer" :action "claim-deferred-yield"
    :params {:owner-id "escrow:0"}}])))

(println "S109 orig world" (last-world-info (assoc base-s109 :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
             :failure-modes ["negative-yield"] :apy -0.05
             :shortfall {:available-ratio 0.5}}}
   {:seq 3 :time 90000 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
   {:seq 4 :time 90000 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 5 :time 91000 :agent "buyer" :action "withdraw-escrow" :params {:workflow-id 0}}
   {:seq 6 :time 92000 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "available" :failure-modes []}}
   {:seq 7 :time 92001 :agent "buyer" :action "claim-deferred-yield"
    :params {:owner-id "escrow:0"}}])))

(println "S109 long outcome"
         (let [r (replay-scenario (assoc base-s109 :events
                                [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                                  :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
                                 {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
                                 {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
                                  :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
                                           :failure-modes ["negative-yield"] :apy -0.05
                                           :shortfall {:available-ratio 0.5}}}
                                 {:seq 3 :time 7776000 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
                                 {:seq 4 :time 7776000 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
                                 {:seq 5 :time 7776001 :agent "buyer" :action "withdraw-escrow" :params {:workflow-id 0}}
                                 {:seq 6 :time 7777000 :agent "buyer" :action "set-yield-risk"
                                  :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "available" :failure-modes []}}
                                 {:seq 7 :time 7777001 :agent "buyer" :action "claim-deferred-yield"
                                  :params {:owner-id "escrow:0"}}]))]
           {:outcome (:outcome r) :halt (:halt-reason r)}))

(println "S108 cancel-only metrics" (metrics (assoc base-buyer :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 7776000 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 7776001 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :apy -0.01 :failure-modes ["negative-yield"]}}
   {:seq 3 :time 15552001 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 4 :time 15552001 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}])))

(println "S109 long metrics" (metrics (assoc base-s109 :events
  [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 10000 :yield-preset "to-sender"}}
   {:seq 1 :time 86400 :agent "buyer" :action "trigger-accrue" :params {:workflow-id 0}}
   {:seq 2 :time 86401 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "shortfall"
             :failure-modes ["negative-yield"] :apy -0.05
             :shortfall {:available-ratio 0.5}}}
   {:seq 3 :time 7776000 :agent "seller" :action "recipient-cancel" :params {:workflow-id 0}}
   {:seq 4 :time 7776000 :agent "buyer" :action "sender-cancel" :params {:workflow-id 0}}
   {:seq 5 :time 7776001 :agent "buyer" :action "withdraw-escrow" :params {:workflow-id 0}}
   {:seq 6 :time 7777000 :agent "buyer" :action "set-yield-risk"
    :params {:module-id "aave-v3" :token "USDC" :liquidity-mode "available" :failure-modes []}}
   {:seq 7 :time 7777001 :agent "buyer" :action "claim-deferred-yield"
    :params {:owner-id "escrow:0"}}])))
