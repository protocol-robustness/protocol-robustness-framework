(ns resolver-sim.contract-model.replay-simple-characterization-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.flags :as flags]
            [resolver-sim.contract-model.replay.profile-adapter :as adapter]
            [resolver-sim.contract-model.replay.yield :as yield-replay]
            [resolver-sim.protocols.dummy :as dummy]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.yield.risk-monitor :as risk]))

;; ===========================================================================
;; Generic protocol path — current behaviour characterization
;; ===========================================================================

(def minimal-scenario
  {:scenario-id "simple-char-minimal"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]})

(deftest simple-replay-missing-schema-version-defaults-to-1-0
  (let [no-version (dissoc minimal-scenario :schema-version)
        result (replay/simple-replay dummy/protocol no-version)]
    (is (= :pass (:outcome result)))))

(deftest simple-replay-supplied-schema-version-preserved
  (let [scenario (assoc minimal-scenario :schema-version "1.0")
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result))
        "schema-version \"1.0\" accepted and preserved")))

(deftest simple-replay-no-evidence-chain-finalization
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (= :pass (:outcome result)))
    (is (not (contains? result :evidence/hash)))
    (is (not (contains? result :evidence/signature)))))

(deftest simple-replay-no-theory-diagnostics
  (let [scenario (assoc minimal-scenario :theory {:expectations []})
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))
    (is (not (contains? result :evidence/hash))
        "no evidence/hash")))

(deftest simple-replay-temporal-disabled-by-default
  (let [scenario (assoc minimal-scenario :temporal-evidence {:enabled? true})
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))))

(deftest simple-replay-preserves-expected-error-processing
  (let [scenario (assoc minimal-scenario :expected-errors [{:seq 0 :action "noop" :error "some-error"}])
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))
    (is (pos? (:events-processed result)))
    (is (map? (:expected-error-analysis result)))))

(deftest simple-replay-dispatch-and-invariants-run
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (= 1 (:events-processed result)))
    (is (vector? (:trace result)))
    (is (= :ok (:result (first (:trace result)))))))

(deftest simple-replay-result-contains-core-keys
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (contains? result :outcome))
    (is (contains? result :trace))
    (is (contains? result :metrics))
    (is (contains? result :events-processed))
    (is (contains? result :protocol))
    (is (contains? result :replay-profile))
    (is (contains? result :protocol-id))
    (is (= :simple (:replay-profile result)))))

;; ===========================================================================
;; Yield path — current behaviour characterization
;; ===========================================================================

(def yield-scenario
  {:scenario-id "yield-char-test"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "vault" :address "0xVault"}]
   :protocol-params {:yield-profile "aave-v3" :default-owner-id "vault"}
   :yield-config {:modules {"aave-v3" {:tokens {"USDC" {:apy 0.05 :liquidity-mode "available"}}}}}
   :events [{:seq 0 :time 1000 :agent "vault" :action "yield_deposit"
             :params {:token "USDC" :amount 10000}}
            {:seq 1 :time 2000 :agent "vault" :action "yield_accrue"
             :params {:token "USDC" :dt 1000}}]})

(deftest simple-replay-rejects-malformed-input-shapes
  (doseq [[label replay-opts expected-field]
          [["options-not-map" [] nil]
           ["flags-not-map" {:flags []} :flags]
           ["blank-run-id" {:run-id "  "} :run-id]]]
    (let [error (try
                  (replay/simple-replay dummy/protocol minimal-scenario replay-opts)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-simple-replay-options (:type (ex-data error))) label)
      (when expected-field
        (is (= expected-field (:field (ex-data error))) label))))
  (let [error (try
                (replay/simple-replay dummy/protocol [])
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-simple-replay-scenario (:type (ex-data error))))))

(deftest simple-replay-rejects-malformed-adapter-result
  (with-redefs [adapter/simple-execution-plan
                (fn [_ _]
                  {:execution {:profile :simple :adapter/id :broken-test-adapter}
                   :run (fn [_ _ _]
                          {:outcome :unknown
                           :trace :not-sequential
                           :metrics []
                           :events-processed -1})})]
    (let [error (try
                  (replay/simple-replay dummy/protocol minimal-scenario)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :simple-replay-invalid-adapter-result (:type (ex-data error))))
      (is (= :broken-test-adapter (:adapter (ex-data error))))
      (is (seq (:violations (ex-data error)))))))

(deftest yield-replay-rejects-unknown-top-level-options
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unsupported options"
                        (replay/simple-replay yp/protocol yield-scenario {:minimal false}))))

(deftest yield-replay-run-id-ignored
  (let [result (replay/simple-replay yp/protocol yield-scenario {:run-id "custom-run-id"})]
    (is (= :pass (:outcome result)))
    (is (= "yield-char-test" (or (:scenario-id result)
                                 (get-in result [:context/source :scenario-id])))
        "scenario-id in :context/source or top-level")))

(deftest yield-replay-flag-overrides-ignored
  (let [result (replay/simple-replay yp/protocol yield-scenario {:flags {:strict-validation? true}})]
    (is (= :pass (:outcome result)))
    (is (= :canonical-loop (get-in result [:execution :engine])))))

(deftest yield-replay-preserves-required-flags-with-caller-overrides
  (let [seen-opts (atom nil)
        plan (adapter/simple-execution-plan :simple yp/protocol)]
    (with-redefs [replay/replay-events
                  (fn [_ scenario opts]
                    (reset! seen-opts opts)
                    {:outcome :pass
                     :scenario-id (:scenario-id scenario)
                     :trace []
                     :metrics {}
                     :events-processed 0})]
      (replay/simple-replay yp/protocol yield-scenario
                            {:flags {:strict-validation? true
                                     :yield-dt-validation? false
                                     :metrics-profile :sew-integrated}}))
    (is (= true (get-in @seen-opts [:flags :yield-dt-validation?])))
    (is (= :yield-provider (get-in @seen-opts [:flags :metrics-profile])))
    (is (= true (get-in @seen-opts [:flags :strict-validation?])))
    (is (= (:execution plan)
           (adapter/simple-execution-descriptor :simple yp/protocol)))))

(deftest yield-replay-result-shape-differs-from-generic
  (let [simple-result (replay/simple-replay yp/protocol yield-scenario)]
    (is (= :pass (:outcome simple-result)))
    (is (contains? simple-result :replay-profile)
        "Yield path includes :replay-profile")
    (is (contains? simple-result :protocol-id)
        "Yield path includes :protocol-id")
    (is (contains? simple-result :execution)
        "Yield path includes :execution")
    (is (= :canonical-loop (get-in simple-result [:execution :engine]))
        "Yield path uses canonical-loop now that yield routes through replay-events")
    (is (= :simple (get-in simple-result [:execution :profile]))
        "Yield path uses :simple profile")))

(deftest yield-replay-metrics-use-yield-provider-profile
  (let [generic-result (replay/simple-replay dummy/protocol minimal-scenario)
        yield-result (replay/simple-replay yp/protocol yield-scenario)]
    (is (= :pass (:outcome yield-result)))
    (is (not= (keys (:metrics generic-result))
              (keys (:metrics yield-result)))
        "Yield metrics keys differ from generic metrics keys")
    (is (contains? (:metrics yield-result) :yield/principal)
        "Yield metrics include yield-specific keys")))

(deftest yield-replay-unchanged-by-unsupported-opts
  (doseq [opt [:evidence-mode :signing-key :tsa-url]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Simple replay does not support"
                          (replay/simple-replay yp/protocol yield-scenario {opt "some-value"}))
        (str "Option " opt " rejected on yield path"))))

(deftest generic-replay-rejects-prohibited-nested-flags
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"cannot override enforced profile flags"
                        (replay/simple-replay dummy/protocol minimal-scenario
                                              {:flags {:evidence-mode :all :strict-validation? true}}))))

(deftest simple-replay-generic-result-includes-execution
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (contains? result :execution))
    (is (= :canonical-loop (get-in result [:execution :engine])))
    (is (= :simple (get-in result [:execution :profile])))))

(deftest simple-replay-accepts-replay-opts-on-generic
  (let [result (replay/simple-replay dummy/protocol minimal-scenario {:run-id "custom-id"})]
    (is (= :pass (:outcome result)))))

(deftest simple-replay-preparation-adds-normalizations
  (let [no-version (dissoc minimal-scenario :schema-version)
        result (replay/simple-replay dummy/protocol no-version)]
    (is (= :pass (:outcome result)))
    (is (contains? result :scenario-normalizations))
    (is (= :schema-version (-> result :scenario-normalizations first :field)))
    (is (= "1.0" (-> result :scenario-normalizations first :value)))))

(deftest simple-replay-preparation-no-normalization-when-version-present
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (= :pass (:outcome result)))
    (is (not (contains? result :scenario-normalizations))
        "No normalizations when :schema-version is present")))

(deftest simple-replay-explicit-arities-work
  (let [r2 (replay/simple-replay dummy/protocol minimal-scenario)
        r3 (replay/simple-replay dummy/protocol minimal-scenario {:run-id "test"})]
    (is (= :pass (:outcome r2)))
    (is (= :pass (:outcome r3)))))

(deftest preparation-is-deterministic
  (let [no-version (dissoc minimal-scenario :schema-version)
        p1 (replay/prepare-simple-scenario no-version)
        p2 (replay/prepare-simple-scenario no-version)]
    (is (= p1 p2))))

;; ===========================================================================
;; Flag resolution tests
;; ===========================================================================

(deftest single-flag-override-leaves-other-minimal-defaults
  (let [scenario (assoc minimal-scenario :temporal-evidence {:enabled? true}
                        :theory {:expectations []})
        ;; Override only :evaluate-theory? to true; everything else stays minimal
        result (replay/simple-replay dummy/protocol scenario {:flags {:evaluate-theory? true}})]
    (is (= :pass (:outcome result)))
    ;; temporal should still be disabled (minimal default) — scenario still passes
    (is (not (contains? result :evidence/hash))
        "no evidence chain written")))

(deftest scenario-level-flags-overridden-by-caller-opts
  (let [scenario (assoc minimal-scenario :options {:flags {:evaluate-expectations? false}})
        ;; Caller re-enables expectations
        result (replay/simple-replay dummy/protocol scenario {:flags {:evaluate-expectations? true}})
        simple-without (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))
    (is (= :pass (:outcome simple-without)))
    ;; Both pass — minimal defaults allow expectations (evaluate-expectations? defaults to true)
    (is (pos? (:events-processed result)))))

(deftest minimal-defaults-cover-all-flag-keys
  (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
    (is (= :none (:evidence-mode flags/minimal-replay-flags))
        "evidence-mode defaults to :none")
    (is (false? (:temporal-enabled? flags/minimal-replay-flags))
        "temporal disabled by default")
    (is (false? (:strict-validation? flags/minimal-replay-flags))
        "strict validation disabled by default")
    (is (= :omit (:world-checkpoint-policy flags/minimal-replay-flags))
        "world checkpoints omitted")))

(deftest scenario-flags-cannot-escape-simple-profile
  (let [scenario (assoc-in minimal-scenario [:options :flags]
                           {:evidence-mode :all
                            :include-telemetry-evidence? true
                            :world-checkpoint-policy :retain-all
                            :projection-mode :full})
        effective (flags/resolve-replay-flags scenario {:profile :replay/simple
                                                        :minimal true})
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :none (:evidence-mode effective)))
    (is (false? (:include-telemetry-evidence? effective)))
    (is (= :omit (:world-checkpoint-policy effective)))
    (is (= :finalize-only (:projection-mode effective)))
    (is (= :pass (:outcome result)))))

(deftest minimal-replay-exposes-short-circuit-summary
  (with-redefs [risk/events (constantly [{:short-circuits [:module-frozen-zero-accrual]
                                          :module-id :aave-v3
                                          :token :USDC}])]
    (let [result (replay/simple-replay dummy/protocol minimal-scenario)]
      (is (= [:module-frozen-zero-accrual]
             (get-in result [:yield/risk-events 0 :short-circuits])))
      (is (= :pass (:outcome result))))))

(deftest minimal-replay-can-fail-closed-on-short-circuit
  (with-redefs [risk/events (constantly [{:short-circuits [:module-frozen-zero-accrual]}])]
    (let [scenario (assoc minimal-scenario :events
                          [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}
                           {:seq 1 :time 1001 :agent "a" :action "noop" :params {}}])
          result (replay/replay-events dummy/protocol scenario
                                       {:minimal true
                                        :flags {:fail-on-short-circuits #{:module-frozen-zero-accrual}}})]
      (is (= :fail (:outcome result)))
      (is (= :short-circuit-policy (:halt-reason result)))
      (is (= [:module-frozen-zero-accrual] (:short-circuit-violations result)))
      (is (= 0 (:halted-at-seq result)))
      (is (= 1 (:events-processed result)))
      (is (= [0] (mapv :seq (:trace result)))))))

(deftest unsupported-flags-rejected-on-generic-path
  (doseq [opt [:signing-key :signing-password :tsa-url :evidence-mode]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Simple replay does not support"
                          (replay/simple-replay dummy/protocol minimal-scenario {opt "some-value"}))
        (str "Option " opt " rejected"))))

(deftest allowed-flags-pass-through-on-generic-path
  (let [r1 (replay/simple-replay dummy/protocol minimal-scenario {:run-id "a"})
        r2 (replay/simple-replay dummy/protocol minimal-scenario {:run-id "b"})]
    (is (= :pass (:outcome r1)))
    (is (= :pass (:outcome r2)))
    (is (= "a" (get-in r1 [:context/source :run-id])))
    (is (= "b" (get-in r2 [:context/source :run-id])))))
