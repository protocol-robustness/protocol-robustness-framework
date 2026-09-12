(ns resolver-sim.scenario.projection-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.protocols.sew.projection :as proj]))

(defn -main
  "Allow direct execution via: clojure -M:test -m resolver-sim.scenario.projection-test"
  [& _]
  (run-tests 'resolver-sim.scenario.projection-test))

(defn- replay-result
  [{:keys [world metrics trace halt-reason agents]
    :or   {metrics {}
           trace   []
           agents  []}}]
  {:trace       (if (seq trace) trace [{:world world}])
   :metrics     metrics
   :halt-reason halt-reason
   :agents      agents})

(deftest test-trace-end-projection-terminal-world
  (testing "terminal world produces terminal=true and stable terminal-state set"
    (let [result (replay-result
                  {:world {:live-states {0 :released 1 :refunded}
                           :total-held {"USDC" 0}
                           :total-fees {"USDC" 50}
                           :escrow-amounts {0 1000 1 2000}
                           :dispute-resolvers {0 "0xR" 1 "0xR"}
                           :dispute-levels {0 0 1 1}
                           :pending-count 0
                           :resolver-stakes {"0xR" 1000}
                           :block-time 1300}
                   :metrics {:disputes-triggered 1
                             :resolutions-executed 2}})
          p      (proj/trace-end-projection result)]
      (is (= true (get-in p [:terminal-world :terminal?])))
      (is (= #{:released :refunded}
             (get-in p [:terminal-world :all-terminal-states])))
      (is (= {"USDC" 0} (get-in p [:terminal-world :total-held-by-token])))
      (is (= 1300 (get-in p [:trace-summary :terminal-time]))))))

(deftest test-trace-end-projection-open-dispute
  (testing "open entity keeps terminal=false and preserves halt reason"
    (let [result (replay-result
                  {:world {:live-states {0 :disputed}
                           :total-held {"USDC" 9950}
                           :total-fees {"USDC" 50}
                           :pending-count 0
                           :block-time 1060}
                   :halt-reason :open-entities-at-end
                   :metrics {:disputes-triggered 1}})
          p      (proj/trace-end-projection result)]
      (is (= false (get-in p [:terminal-world :terminal?])))
      (is (= :open-entities-at-end (get-in p [:trace-summary :halt-reason])))
      (is (= 1 (get-in p [:trace-summary :dispute-count]))))))

(deftest test-trace-end-projection-multi-token
  (testing "multi-token accounting maps are preserved with defaults"
    (let [result (replay-result
                  {:world {:live-states {0 :released 1 :refunded}
                           :total-held {"USDC" 0 "DAI" 0}
                           :total-fees {"USDC" 25 "DAI" 40}
                           :block-time 1400}
                   :metrics {}})
          p      (proj/trace-end-projection result)]
      (is (= {"USDC" 0 "DAI" 0} (get-in p [:terminal-world :total-held-by-token])))
      (is (= {"USDC" 25 "DAI" 40} (get-in p [:terminal-world :total-fees-by-token])))
      (is (= 0 (get-in p [:metrics :attack-successes])))
      (is (nil? (get-in p [:metrics :coalition-net-profit]))))))

(deftest test-trace-end-projection-includes-yield-routing-topology
  (testing "terminal-world includes snapshot module topology and yield profile->archetype routing"
    (let [result (replay-result
                  {:world {:live-states {0 :released}
                           :total-held {"USDC" 0}
                           :total-fees {"USDC" 10}
                           :module-snapshots
                           {0 {:resolution-module :module/decentralized-dispute-resolution
                               :release-strategy :module/release-default
                               :cancellation-strategy :module/cancellation-default
                               :yield-generation-module :yield.provider/liquid-lending
                               :yield-distribution-module :module/yield-distribution-default
                               :incentive-module :module/incentive-default
                               :escrow/modules {:resolution :module/decentralized-dispute-resolution
                                                :yield :aave-v3
                                                :release :module/release-default
                                                :cancel :module/cancellation-default}
                               :yield-module-id :module/aave-yield
                               :yield-profile :aave-v3
                               :yield-archetype :yield.provider/liquid-lending}}
                           :yield/risk {:aave-v3 {:USDC {:failure-modes #{:partial-liquidity}}}}
                           :block-time 1400}
                   :metrics {}})
          p      (proj/trace-end-projection result)]
      (is (= :module/aave-yield
             (get-in p [:terminal-world :yield-routing-by-workflow 0 :yield-module-id])))
      (is (= :aave-v3
             (get-in p [:terminal-world :yield-routing-by-workflow 0 :yield-profile])))
      (is (= :yield.provider/liquid-lending
             (get-in p [:terminal-world :yield-routing-by-workflow 0 :yield-archetype])))
      (is (= :aave-v3
             (get-in p [:yield-evidence :routing-by-workflow 0 :yield-profile])))
      (is (contains? (get-in p [:yield-evidence :supported-failure-modes]) :partial-liquidity))
      (is (= :module/decentralized-dispute-resolution
             (get-in p [:terminal-world :module-topology-by-workflow 0 :resolution-module]))))))

(deftest test-trace-end-projection-escalation-summary
  (testing "escalation levels are derived from escalation actions and actors are distinct"
    (let [trace [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                  :world {:live-states {0 :pending}
                          :total-held {"USDC" 5000}
                          :total-fees {"USDC" 0}
                          :block-time 1000}}
                 {:seq 1 :time 1060 :agent "buyer" :action "raise_dispute"
                  :world {:live-states {0 :disputed}
                          :total-held {"USDC" 5000}
                          :total-fees {"USDC" 0}
                          :block-time 1060}}
                 {:seq 2 :time 1120 :agent "buyer" :action "escalate_dispute"
                  :extra {:level 1}
                  :world {:live-states {0 :disputed}
                          :total-held {"USDC" 5000}
                          :total-fees {"USDC" 0}
                          :block-time 1120}}
                 {:seq 3 :time 1180 :agent "buyer" :action "escalate_dispute"
                  :extra {:level 2}
                  :world {:live-states {0 :released}
                          :total-held {"USDC" 0}
                          :total-fees {"USDC" 0}
                          :block-time 1180}}]
          p (proj/trace-end-projection (replay-result {:trace trace :metrics {:disputes-triggered 1}}))]
      (is (= #{1 2} (get-in p [:trace-summary :escalation-levels])))
      (is (= #{"buyer"} (get-in p [:trace-summary :actors])))
      (is (= 4 (get-in p [:trace-summary :events-count]))))))

(deftest test-trace-end-projection-money-movement-summary
  (testing "money movement summary captures token deltas and pending lifecycle"
    (let [trace [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                  :world {:live-states {0 :disputed}
                          :pending-count 0
                          :total-held {"USDC" 1000}
                          :total-fees {"USDC" 0}
                          :resolver-stakes {"0xR" 100}
                          :block-time 1000}}
                 {:seq 1 :time 1010 :agent "resolver" :action "execute_resolution"
                  :world {:live-states {0 :pending}
                          :pending-count 1
                          :total-held {"USDC" 1000}
                          :total-fees {"USDC" 10}
                          :resolver-stakes {"0xR" 100}
                          :block-time 1010}}
                 {:seq 2 :time 1020 :agent "buyer" :action "escalate_dispute"
                  :world {:live-states {0 :disputed}
                          :pending-count 0
                          :total-held {"USDC" 1000}
                          :total-fees {"USDC" 10}
                          :resolver-stakes {"0xR" 100}
                          :block-time 1020}}
                 {:seq 3 :time 1030 :agent "resolver" :action "withdraw_stake"
                  :world {:live-states {0 :released}
                          :pending-count 0
                          :total-held {"USDC" 0}
                          :total-fees {"USDC" 10}
                          :resolver-stakes {"0xR" 70}
                          :block-time 1030}}]
          p (proj/trace-end-projection (replay-result {:trace trace :metrics {}}))]
      (is (= -1000 (get-in p [:money-movement-summary :token-deltas "USDC" :held-delta])))
      (is (= 10 (get-in p [:money-movement-summary :token-deltas "USDC" :fee-delta])))
      (is (= {:created 1 :cleared 1 :superseded 1}
             (get-in p [:money-movement-summary :pending-lifecycle :unknown])))
      (is (= 30 (get-in p [:stake-flow-summary "0xR" :withdrawn]))))))

(deftest test-trace-end-projection-payoff-ledger-v1
  (testing "payoff-ledger summary exposes per-actor deltas and aggregate metrics"
    (let [trace [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                  :world {:live-states {0 :disputed}
                          :pending-count 0
                          :total-held {"USDC" 1000}
                          :total-fees {"USDC" 0}
                          :resolver-stakes {"0xR" 100}
                          :claimable {0 {"0xbuyer" 0}}
                          :bond-balances {0 {"0xbuyer" 0}}
                          :block-time 1000}}
                 {:seq 1 :time 1010 :agent "resolver" :action "execute_resolution"
                  :world {:live-states {0 :released}
                          :pending-count 0
                          :total-held {"USDC" 0}
                          :total-fees {"USDC" 10}
                          :resolver-stakes {"0xR" 90}
                          :claimable {0 {"0xbuyer" 990}}
                          :bond-balances {0 {"0xbuyer" 0}}
                          :block-time 1010}}]
          p (proj/trace-end-projection
             (replay-result {:trace trace
                             :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                                      {:id "resolver" :address "0xR" :type "resolver"}]
                             :metrics {}}))]
      ;; Ledger is event-actor-attributed in v1, so resolver row is guaranteed
      ;; to exist for this synthetic trace.
      (is (number? (get-in p [:payoff-ledger-summary :per-actor "0xR" :net-payoff])))
      (is (number? (get-in p [:payoff-ledger-summary :negative-payoff-count])))
      (is (= (get-in p [:payoff-ledger-summary :negative-payoff-count])
             (get-in p [:metrics :negative-payoff-count]))))))

(deftest test-negative-payoff-count-ignores-replay-metric-override
  (testing "projection always uses the derived terminal payoff-ledger count"
    (let [trace [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                  :world {:live-states {0 :disputed}
                          :pending-count 0
                          :total-held {"USDC" 1000}
                          :total-fees {"USDC" 0}
                          :resolver-stakes {"0xR" 100}
                          :claimable {0 {"0xbuyer" 0}}
                          :bond-balances {0 {"0xbuyer" 0}}
                          :block-time 1000}}
                 {:seq 1 :time 1010 :agent "resolver" :action "execute_resolution"
                  :world {:live-states {0 :released}
                          :pending-count 0
                          :total-held {"USDC" 0}
                          :total-fees {"USDC" 10}
                          :resolver-stakes {"0xR" 90}
                          :claimable {0 {"0xbuyer" 990}}
                          :bond-balances {0 {"0xbuyer" 0}}
                          :block-time 1010}}]
          p (proj/trace-end-projection
             (replay-result {:trace trace
                             :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                                      {:id "resolver" :address "0xR" :type "resolver"}]
                             ;; Deliberately set to a sentinel value different from
                             ;; the derived ledger count to verify preference ordering.
                             :metrics {:negative-payoff-count 7}}))]
      (is (= (get-in p [:payoff-ledger-summary :negative-payoff-count])
             (get-in p [:metrics :negative-payoff-count]))
          "sentinel replay metric must not override the derived count")
      (is (not= 7 (get-in p [:metrics :negative-payoff-count]))))))

(deftest test-negative-payoff-count-ignores-nil-replay-metric
  (testing "projection uses the derived payoff-ledger count when replay metric is nil"
    (let [trace [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                  :world {:live-states {0 :disputed}
                          :pending-count 0
                          :total-held {"USDC" 1000}
                          :total-fees {"USDC" 0}
                          :resolver-stakes {"0xR" 100}
                          :claimable {0 {"0xbuyer" 0}}
                          :bond-balances {0 {"0xbuyer" 0}}
                          :block-time 1000}}
                 {:seq 1 :time 1010 :agent "resolver" :action "execute_resolution"
                  :world {:live-states {0 :released}
                          :pending-count 0
                          :total-held {"USDC" 0}
                          :total-fees {"USDC" 10}
                          :resolver-stakes {"0xR" 90}
                          :claimable {0 {"0xbuyer" 990}}
                          :bond-balances {0 {"0xbuyer" 0}}
                          :block-time 1010}}]
          p (proj/trace-end-projection
             (replay-result {:trace trace
                             :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                                      {:id "resolver" :address "0xR" :type "resolver"}]
                             :metrics {:negative-payoff-count nil}}))]
      (is (= (get-in p [:payoff-ledger-summary :negative-payoff-count])
             (get-in p [:metrics :negative-payoff-count]))
          "nil replay metric should trigger projection fallback to derived value"))))

(deftest test-trace-end-projection-coalition-net-profit
  (testing "coalition-net-profit is derived when coalition tags exist"
    (let [trace [{:seq 0 :time 1000 :agent "colluder" :action "create_escrow"
                  :world {:live-states {0 :disputed}
                          :total-held {"USDC" 100}
                          :total-fees {"USDC" 0}
                          :resolver-stakes {}
                          :claimable {0 {"0xC" 0}}
                          :bond-balances {}
                          :pending-count 0
                          :block-time 1000}}
                 {:seq 1 :time 1010 :agent "colluder" :action "release"
                  :world {:live-states {0 :released}
                          :total-held {"USDC" 0}
                          :total-fees {"USDC" 0}
                          :resolver-stakes {}
                          :claimable {0 {"0xC" 100}}
                          :bond-balances {}
                          :pending-count 0
                          :block-time 1010}}]
          p (proj/trace-end-projection
             (replay-result {:trace trace
                             :agents [{:id "colluder" :address "0xC" :strategy "collusive"}]
                             :metrics {}}))]
      (is (number? (get-in p [:metrics :coalition-net-profit])))
      (is (= (get-in p [:metrics :coalition-net-profit])
             (get-in p [:payoff-ledger-summary :coalition-net-profit]))))))

(deftest test-trace-end-projection-includes-financial-loss
  (testing "projection includes :financial-loss with loss status keys and prorata user-loss-realised"
    (let [result (replay-result
                  {:world {:escrow-transfers {0 {:escrow-state :refunded}}
                           :yield/positions {"pos1" {:token :USDC
                                                     :shortfall {:fulfilled-amount 800
                                                                 :deferred-amount 0
                                                                 :haircut-amount 200}}}
                           :total-held {:USDC 500}
                           :total-fees {:USDC 0}
                           :claimable {0 {"0xAnyone" 200}}
                           :resolver-stakes {}
                           :bond-balances {}
                           :pending-count 0
                           :block-time 1000}
                   :metrics {}})
          p (proj/trace-end-projection result)
          fl (:financial-loss p)]
      (is (map? fl) ":financial-loss should be a map")
      (is (= :loss-realized (:loss/status fl)))
      (is (= 0.2 (:loss/user-realized? fl)))
      (is (true? (:loss/protocol-realized? fl)))
      (is (= :haircut-loss (:loss/reason fl)))
      (is (map? (:loss/shortfall fl))))))
