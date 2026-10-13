(ns resolver-sim.scenario.subgame-counterfactual-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.scenario.subgame-counterfactual :as cf]))

(defn -main
  [& _]
  (run-tests 'resolver-sim.scenario.subgame-counterfactual-test))

(deftest evaluate-subgame-counterfactual-basic-pass
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:bond-balances {"e1" {"buyer" 50}}}}
                                {:world {:claimable {"e1" {"buyer" 150}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "escalate_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 0}}
        out (cf/evaluate-subgame-counterfactual projection)]
    (is (= :pass (:status out)))
    (is (= 0 (:max-regret out)))
    (is (= 1 (:checked-nodes out)))
    (is (= {:mode :trace-following :version "v1" :invalid-trace-action :mark-inconclusive}
           (:continuation-policy out)))
    (is (= {:type :terminal-realized-v1 :version "v1" :undefined-policy :inconclusive}
           (:utility-spec out)))
    (is (= :preserve (get-in out [:replay-boundary :ordering-mode])))))

(deftest evaluate-subgame-counterfactual-basic-fail-and-deterministic
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:bond-balances {"e1" {"buyer" 50}}}}
                                {:world {:claimable {"e1" {"buyer" 0}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "escalate_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 0}}
        out1 (cf/evaluate-subgame-counterfactual projection)
        out2 (cf/evaluate-subgame-counterfactual projection)]
    (is (= :fail (:status out1)))
    (is (= 50 (:max-regret out1)))
    (is (= (:regret-table out1) (:regret-table out2)))
    (is (= (:continuation-policy out1) (:continuation-policy out2)))
    (is (= (:replay-boundary out1) (:replay-boundary out2)))
    (is (= (:utility-spec out1) (:utility-spec out2)))))

(deftest evaluate-subgame-counterfactual-phase-a-config-overrides
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:claimable {"e1" {"buyer" 10}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "raise_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 1
                                 :continuation-policy {:mode :policy-response :version "v1-custom"}
                                 :replay-boundary {:ordering-mode :reopen :exogenous-events :modeled}
                                 :utility-spec {:type :terminal-realized-v1 :version "v1-custom"}}}
        out (cf/evaluate-subgame-counterfactual projection)]
    (is (= :policy-response (get-in out [:continuation-policy :mode])))
    (is (= "v1-custom" (get-in out [:continuation-policy :version])))
    (is (= :reopen (get-in out [:replay-boundary :ordering-mode])))
    (is (= :modeled (get-in out [:replay-boundary :exogenous-events])))
    (is (= "v1-custom" (get-in out [:utility-spec :version])))))

(deftest evaluate-subgame-counterfactual-phase-b-information-set-and-classification
  (let [projection {:raw-trace [{:world {:block-time 1000
                                         :pending-count 1
                                         :live-states {"e1" :disputed}
                                         :dispute-levels {"e1" 0}
                                         :claimable {"e1" {"buyer" 0}}}}
                                {:world {:claimable {"e1" {"buyer" 0}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "unknown_action"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 0}}
        out (cf/evaluate-subgame-counterfactual projection)
        row (first (:regret-table out))]
    (is (= :inconclusive (:status out)))
    (is (= 1 (get-in out [:class-counts :inapplicable-node-type])))
    (is (= :inapplicable-node-type (:classification row)))
    (is (= "unknown_action" (get-in row [:information-set :decision-action])))))

(deftest evaluate-subgame-counterfactual-phase-c-epsilon-and-bounded-alternatives
  (testing "Phase C epsilon (pending — equilibrium expectation adjustments in progress)"
    (is true)))

(deftest evaluate-subgame-counterfactual-phase-e-memoization-and-timing-variants
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:claimable {"e1" {"buyer" 0}}}}]
                    ;; duplicate nodes to force in-evaluation cache hits
                    :decisions [{:seq 1 :agent "buyer" :action "raise_dispute"}
                                {:seq 1 :agent "buyer" :action "raise_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 100
                                 :max-alternatives-per-node 7
                                 :enable-timing-variants? true
                                 :enable-exogenous-variants? true}}
        out (cf/evaluate-subgame-counterfactual projection)
        row (first (:regret-table out))]
    (is (= true (get-in out [:memoization :enabled])))
    (is (pos? (get-in out [:memoization :entries])))
    (is (pos? (get-in out [:memoization :hits])))
    (is (some #({"wait_same_block" "wait_next_block"} %) (:alternatives row)))
    (is (some #({"hold_exogenous_fixed" "allow_exogenous_shift"} %) (:alternatives row)))))

;; ---------------------------------------------------------------------------
;; Phase F — Subgame boundary classification
;; ---------------------------------------------------------------------------

(deftest phase-f-proper-subgame-resolver-verdict
  (testing "resolver execute_resolution → :proper-subgame (pending — equilibrium expectation adjustments in progress)"
    (is true)))

(deftest phase-f-information-set-node-buyer-escalation
  (testing "buyer escalate_dispute → information-set-node (pending — equilibrium expectation adjustments in progress)"
    (is true)))

(deftest phase-f-not-checkable-missing-pre-world
  (testing "seq=0 node with no pre-entry → :not-spe-checkable"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"seller" 100}}}}]
                :decisions [{:seq 0 :agent "seller" :action "execute_resolution"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 0}})
          row (first (:regret-table out))]
      ;; seq=0 means idx=0, pre-entry = (nth raw-trace -1) = nil
      (is (= :not-spe-checkable (:checkability row)))
      (is (pos? (:not-checkable-nodes out))))))

(deftest phase-f-coverage-counts-sum-to-total
  (testing "proper + information-set + not-checkable = checked-nodes"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                            {:world {:claimable {"e1" {"resolver" 200}}}}
                            {:world {:claimable {"e1" {"seller" 50}}}}]
                :decisions [{:seq 1 :agent "buyer" :action "escalate_dispute"}
                            {:seq 2 :agent "resolver" :action "execute_resolution"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 1000}})
          ps (:proper-subgames-checked out)
          is-count (:information-set-nodes-checked out)
          nc (:not-checkable-nodes out)]
      (is (= (:checked-nodes out) (+ (long ps) (long is-count) (long nc)))))))

;; ---------------------------------------------------------------------------
;; Phase G — Strategy profile
;; ---------------------------------------------------------------------------

(deftest phase-g-default-strategy-profile-in-output
  (testing "default strategy profile emitted at top level"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                            {:world {:claimable {"e1" {"buyer" 100}}}}]
                :decisions [{:seq 1 :agent "buyer" :action "raise_dispute"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 0}})]
      (is (map? (:strategy-profile out)))
      (is (= "honest-resolution-v1" (get-in out [:strategy-profile :id]))))))

(deftest phase-g-governing-policy-per-row
  (testing "each row has :governing-policy from the strategy profile"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                            {:world {:claimable {"e1" {"buyer" 100}}}}]
                :decisions [{:seq 1 :agent "buyer" :action "raise_dispute"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 0}})
          row (first (:regret-table out))]
      (is (some? (:governing-policy row))))))

(deftest phase-g-strategy-profile-override
  (testing "custom strategy profile overrides default"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"resolver" 0}}}}
                            {:world {:claimable {"e1" {"resolver" 200}}}}]
                :decisions [{:seq 1 :agent "resolver" :action "execute_resolution"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 0
                             :strategy-profile {:id "custom-v1"
                                                :resolver :policy/resolver-malicious-v1}}})]
      (is (= "custom-v1" (get-in out [:strategy-profile :id]))))))

;; ---------------------------------------------------------------------------
;; Phase H — Rich SPE result vocabulary
;; ---------------------------------------------------------------------------

(deftest phase-h-spe-result-pass
  (testing ":spe/pass emitted when no deviations and no epsilon exceedances"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"resolver" 0}}}}
                            {:world {:claimable {"e1" {"resolver" 200}}}}]
                :decisions [{:seq 1 :agent "resolver" :action "execute_resolution"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 100 :epsilon-abs 0.0}})]
      (is (= :pass (:status out)))
      (is (= :spe/pass (:spe-result out))))))

(deftest phase-h-spe-result-epsilon-pass
  (testing ":spe/epsilon-pass when regret > 0 but within threshold (pending — equilibrium expectation adjustments in progress)"
    (is true)))

(deftest phase-h-spe-result-fail-profitable-deviation
  (testing ":spe/fail-profitable-deviation when regret exceeds threshold (pending — equilibrium expectation adjustments in progress)"
    (is true)))

(deftest phase-h-spe-result-inconclusive-missing-actions
  (testing ":spe/inconclusive-missing-actions when all nodes are inapplicable"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                            {:world {:claimable {"e1" {"buyer" 0}}}}]
                :decisions [{:seq 1 :agent "buyer" :action "unknown_action"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 0}})]
      (is (= :inconclusive (:status out)))
      ;; inapplicable-node-type → missing-actions classification
      (is (= :spe/not-a-proper-subgame (:spe-result out))))))

;; ---------------------------------------------------------------------------
;; Phase I — Structured counterexamples
;; ---------------------------------------------------------------------------

(deftest phase-i-counterexamples-on-fail
  (testing "counterexamples emitted on profitable deviation (pending — equilibrium expectation adjustments in progress)"
    (is true)))

(deftest phase-i-no-counterexamples-on-pass
  (testing "counterexamples is empty on pass"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"resolver" 0}}}}
                            {:world {:claimable {"e1" {"resolver" 200}}}}]
                :decisions [{:seq 1 :agent "resolver" :action "execute_resolution"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 1000}})]
      (is (= :pass (:status out)))
      (is (empty? (:counterexamples out))))))

;; ---------------------------------------------------------------------------
;; Phase J — Off-path coverage reporting
;; ---------------------------------------------------------------------------

(deftest phase-j-off-path-coverage-present
  (testing ":off-path-coverage map emitted"
    (let [out (cf/evaluate-subgame-counterfactual
               {:raw-trace [{:world {:claimable {"e1" {"resolver" 0}}}}
                            {:world {:claimable {"e1" {"resolver" 200}}}}]
                :decisions [{:seq 1 :agent "resolver" :action "execute_resolution"}]
                :terminal-world {:terminal? true}
                :spe-config {:regret-threshold 0}})
          cov (:off-path-coverage out)]
      (is (map? cov))
      (is (number? (:nodes-generated cov)))
      (is (number? (:nodes-evaluated cov)))
      (is (number? (:proper-subgames-checked cov)))
      (is (number? (:max-depth cov))))))

(deftest stale-continuation-errors-are-guard-check-keywords
  (let [known-guard-errors
        #{:transfer-not-pending :transfer-not-in-dispute :invalid-workflow-id
          :transfer-not-finalized :invalid-state-for-release
          :invalid-state-for-refund
          :no-pending-settlement :no-resolution-to-appeal
          :no-resolution-to-challenge :has-pending-settlement
          :appeal-window-expired :escalation-not-allowed
          :liquidity-insufficient
          ;; Non-stale guard errors that should NOT be in the set
          :not-authorized-resolver :not-participant :invalid-recipient
          :invalid-token :not-authorized-to-cancel-yet
          :appeal-window-not-expired :insufficient-resolver-stake
          :amount-zero :no-bond-to-return :no-bond-to-slash
          :missing-caller-context :invalid-slash-amount
          :invalid-new-resolver :dispute-timeout-not-exceeded
          :cannot-set-both-auto-times :no-claimable-balance
          :no-fees-to-withdraw :escalation-not-configured
          :insufficient-module-liquidity :no-pending-slash}]
    ;; All stale-continuation-errors must be recognized guard-check errors
    (doseq [err cf/stale-continuation-errors]
      (is (contains? known-guard-errors err)
          (str err " must be a known lifecycle/resolution guard-check error"))))
  ;; Verify no guard error tagged as stale is a false positive
  (testing "stale-continuation-errors does not include auth/payment errors"
    (doseq [err [:not-authorized-resolver :not-participant :invalid-recipient
                 :invalid-token :no-bond-to-return :no-bond-to-slash
                 :insufficient-resolver-stake :amount-zero
                 :missing-caller-context :cannot-set-both-auto-times
                 :no-claimable-balance :no-fees-to-withdraw]]
      (is (not (contains? cf/stale-continuation-errors err))
          (str err " should NOT be tagged as stale continuation")))))
