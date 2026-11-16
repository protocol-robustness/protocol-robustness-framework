(ns resolver-sim.protocols.sew.temporal-generator-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]))

(defn- base-appeal-boundary-scenario
  [offset]
  (let [t0 1000
        t-dispute 1060
        t-rule 1120
        appeal-window 60
        deadline (+ t-rule appeal-window)
        t-exec (+ deadline offset)]
    {:scenario-id (str "generated-appeal-boundary-offset-" offset)
     :schema-version "1.0"
     :initial-block-time t0
     :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
              {:id "seller" :address "0xseller" :strategy "honest"}
              {:id "resolver" :address "0xresolver" :role "resolver"}
              {:id "keeper" :address "0xkeeper" :role "keeper"}]
     :protocol-params {:resolver-fee-bps 150
                       :appeal-window-duration appeal-window
                       :max-dispute-duration 2592000}
     :events [{:seq 0 :time t0 :agent "buyer" :action "create_escrow"
               :params {:token "USDC" :to "0xseller" :amount 7000 :custom-resolver "0xresolver"}}
              {:seq 1 :time t-dispute :agent "buyer" :action "raise_dispute"
               :params {:workflow-id 0}}
              {:seq 2 :time t-rule :agent "resolver" :action "execute_resolution"
               :params {:workflow-id 0 :is-release true :resolution-hash "0xedge"}}
              {:seq 3 :time t-exec :agent "keeper" :action "execute_pending_settlement"
               :params {:workflow-id 0}}]}))

(deftest generated-boundary-offsets-behave-as-expected
  (testing "Boundary-biased generated offsets enforce t-1 reject, t and t+1 success"
    (doseq [[offset expected-outcome expected-result expected-state]
            [[-1 :fail :rejected :disputed]
             [0  :pass :ok       :released]
             [1  :pass :ok       :released]]]
      (let [scenario (base-appeal-boundary-scenario offset)
            r (replay/replay-with-protocol sew/protocol scenario)
            trace (:trace r)
            projection (-> r :trace last :projection)]
        (is (= expected-outcome (:outcome r)))
        (is (= expected-result (get-in trace [3 :result]))
            (str "unexpected result for offset " offset))
        (is (= expected-state (get-in projection [:escrow-transfers 0 :escrow-state]))
            (str "unexpected state for offset " offset))))))

(deftest generated-boundary-determinism-and-sensitivity
  (testing "Same generated schedule is deterministic; different boundary schedules diverge meaningfully"
    (let [s-minus1 (base-appeal-boundary-scenario -1)
          s-zero   (base-appeal-boundary-scenario 0)
          r1a (replay/replay-with-protocol sew/protocol s-minus1)
          r1b (replay/replay-with-protocol sew/protocol s-minus1)
          r2  (replay/replay-with-protocol sew/protocol s-zero)
          p1a (-> r1a :trace last :projection)
          p1b (-> r1b :trace last :projection)
          p2  (-> r2  :trace last :projection)]
      ;; determinism
      (is (= p1a p1b))
      ;; sensitivity to schedule change around deadline
      (is (not= (get-in p1a [:escrow-transfers 0 :escrow-state])
                (get-in p2  [:escrow-transfers 0 :escrow-state])))
      (is (= :disputed (get-in p1a [:escrow-transfers 0 :escrow-state])))
      (is (= :released (get-in p2  [:escrow-transfers 0 :escrow-state]))))))

(deftest generated-same-block-compression-race
  (testing "Same-block compression run remains deterministic and invariant-safe"
    (let [scenario {:scenario-id "generated-same-block-compression"
                    :schema-version "1.0"
                    :initial-block-time 1000
                    :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                             {:id "seller" :address "0xseller" :strategy "honest"}
                             {:id "keeper" :address "0xkeeper" :role "keeper"}]
                    :protocol-params {:resolver-fee-bps 150
                                      :appeal-window-duration 0
                                      :max-dispute-duration 2592000}
                    :events [{:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
                              :params {:token "USDC" :to "0xseller" :amount 8000 :auto-release-time 1300}}
                             {:seq 1 :time 1299 :agent "buyer" :action "raise_dispute"
                              :params {:workflow-id 0}}
                             {:seq 2 :time 1300 :agent "keeper" :action "automate_timed_actions"
                              :params {:workflow-id 0}}
                             {:seq 3 :time 1300 :agent "buyer" :action "release"
                              :params {:workflow-id 0}}]}
          r1 (replay/replay-with-protocol sew/protocol scenario)
          r2 (replay/replay-with-protocol sew/protocol scenario)]
      (is (= :fail (:outcome r1)))
      (is (= :fail (:outcome r2)))
      (is (= 0 (get-in r1 [:metrics :invariant-violations])))
      (is (= (-> r1 :trace last :projection)
             (-> r2 :trace last :projection))))))
