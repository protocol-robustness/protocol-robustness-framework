(ns resolver-sim.contract-model.replay-yield-test
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.execution :as execution]
            [resolver-sim.contract-model.replay.frames :as frames]
            [resolver-sim.contract-model.replay.yield :as yield-replay]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.yield :as yp]
            [resolver-sim.yield.transition-basis :as basis]))

(def base-scenario
  {:scenario-id "yield-replay-test"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "vault" :address "0xVault"}]
   :protocol-params {:yield-profile "aave-v3" :default-owner-id "vault"}
   :replay/requirements #{:transition-assurance/v1}
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
    (is (= "yield-replay-test" (get-in result [:world :params :scenario-id])))
    (is (every? #(= :passed (get-in % [:transition-assurance :status]))
                (:trace result)))))

(deftest replay-required-assurance-rejects-a-forged-yield-basis
  (let [event (first (:events base-scenario))
        wrong-event (assoc-in event [:params :amount] 999)
        before (proto/init-world yp/protocol base-scenario)
        context {:replay-flags {:check-invariants? true
                                :temporal-enabled? false
                                :evidence-mode :none}
                 :replay/requirements #{:transition-assurance/v1}}
        result (with-redefs [basis/build (fn [world-before world-after _]
                                           (basis/build world-before world-after wrong-event))]
                 (execution/process-step yp/protocol context before event))]
    (is (not (:ok? result)))
    (is (= before (:world result)))
    (is (= :failed (get-in result [:trace-entry :transition-assurance :status])))
    (is (some #(= :event-root-mismatch (:reason %))
              (get-in result [:trace-entry :violations :transition-assurance
                              :yield/state-addressed])))))

(deftest resumed-yield-transition-preserves-basis-identity-and-requirement
  (let [event (first (:events base-scenario))
        run-id "yield-identity-run"
        flags {:check-invariants? true :temporal-enabled? false :evidence-mode :none}
        world-before (-> (proto/init-world yp/protocol base-scenario)
                         (assoc-in [:params :scenario-id] (:scenario-id base-scenario))
                         (assoc :run/id run-id :execution/id (str run-id "-execution")))
        normal (replay/replay-events yp/protocol base-scenario {:run-id run-id :flags flags})
        resumed (replay/resume-from-snapshot yp/protocol (:agents base-scenario)
                                             (:protocol-params base-scenario) (:scenario-id base-scenario)
                                             world-before [event] [] {}
                                             {:scenario base-scenario :replay-flags flags :run-id run-id})
        normal-after (:world (first (:trace normal)))
        resumed-after (:world (first (:trace resumed)))
        normal-basis (basis/build world-before normal-after event)
        resumed-basis (basis/build world-before resumed-after event)
        forged (with-redefs [basis/build (fn [before after _]
                                           (basis/build before after (assoc event :seq 99)))]
                 (replay/resume-from-snapshot yp/protocol (:agents base-scenario)
                                              (:protocol-params base-scenario) (:scenario-id base-scenario)
                                              world-before [event] [] {}
                                              {:scenario base-scenario :replay-flags flags :run-id run-id}))]
    (is (= :pass (:outcome normal)))
    (is (= :pass (:outcome resumed)))
    (is (= (select-keys normal-basis [:state-before/root :state-after/root :event/root
                                      :yield/effective-policy-root :transition/root :root])
           (select-keys resumed-basis [:state-before/root :state-after/root :event/root
                                       :yield/effective-policy-root :transition/root :root])))
    (is (= :passed (get-in (first (:trace resumed)) [:transition-assurance :status])))
    (is (= (get-in normal [:accepted-frames :frames 0 :frame/root])
           (get-in resumed [:accepted-frames :frames 0 :frame/root])))
    (is (= :fail (:outcome forged)))
    (is (= :failed (get-in (first (:trace forged)) [:transition-assurance :status])))))

(deftest accepted-frames-form-a-sequential-assured-chain
  (let [result (replay/replay-events yp/protocol base-scenario
                                     {:run-id "yield-frame-run"
                                      :flags {:check-invariants? true
                                              :temporal-enabled? false
                                              :evidence-mode :none}})
        stream (:accepted-frames result)
        [first-frame second-frame] (:frames stream)]
    (is (= :ok (:frame/status stream)))
    (is (= 2 (count (:frames stream))))
    (is (= (:state-after/root first-frame) (:state-before/root second-frame)))
    (is (= (:frame/root first-frame) (:frame/previous-root second-frame)))
    (is (= :transition-assurance/v1 (get-in first-frame [:assurance :capability])))
    (is (= :passed (get-in first-frame [:assurance :status])))
    (is (= (:transition/root (frames/reconstruct-transition first-frame))
           (get-in first-frame [:transition :transition/root])))))

(deftest frame-construction-excludes-failed-attempts-and-batches
  (let [event (first (:events base-scenario))
        before (proto/init-world yp/protocol base-scenario)
        failed (with-redefs [basis/build (fn [world-before world-after _]
                                           (basis/build world-before world-after (assoc event :seq 99)))]
                 (replay/resume-from-snapshot yp/protocol (:agents base-scenario)
                                              (:protocol-params base-scenario) (:scenario-id base-scenario)
                                              before [event] [] {}
                                              {:scenario base-scenario
                                               :replay-flags {:check-invariants? true
                                                              :temporal-enabled? false
                                                              :evidence-mode :none}}))]
    (is (empty? (get-in failed [:accepted-frames :frames])))
    (is (= {:frame/status :unsupported
            :reason :frame/deterministic-batch-contract-not-defined}
           (frames/accepted-frames {:execution {:mode :deterministic-batch}})))))

(deftest frame-lineage-anchors-and-queries-are-observational
  (let [result (replay/replay-events yp/protocol base-scenario
                                     {:flags {:check-invariants? true
                                              :temporal-enabled? false
                                              :evidence-mode :none}})
        stream (:accepted-frames result)
        [first-frame second-frame] (:frames stream)
        anchors {:expected/state-before-root (:state-before/root first-frame)
                 :expected/head-frame-root (:frame/root second-frame)
                 :expected/final-state-root (:state-after/root second-frame)}
        invalid-stream (assoc-in stream [:frames 1 :state-before/root] "wrong")]
    (is (:valid? (frames/validate-frame-lineage stream anchors)))
    (is (some #(= :frame/head-root-mismatch (:reason %))
              (:violations (frames/validate-frame-lineage stream
                                                          (assoc anchors :expected/head-frame-root "wrong")))))
    (is (= :invalid-lineage (:status (frames/first-frame-matching invalid-stream (constantly true)))))
    (is (= first-frame (:frame (frames/first-frame-matching stream #(zero? (:frame/index %))))))
    (is (= [second-frame] (:frames (frames/frames-matching stream #(= 1 (:frame/index %))))))
    (is (= true (get-in (frames/frame-context stream 1) [:position :head?])))
    (is (= {:status :not-replayable
            :missing #{:historical-executable :invocation-input :policy-application-material}}
           (frames/replay-frame-transition first-frame)))))

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
        result (yield-replay/replay-yield-scenario scenario)]
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
            result (yield-replay/replay-yield-scenario scenario)]
        (is (= :pass (:outcome result)) (str action " should not break prior steps"))
        (is (= :rejected (:result (last (:trace result)))))
        (is (= :unknown-action (:error (last (:trace result)))))))))
