(ns prf.extensions.sew-core-model.adapter-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.protocol :as proto]
            [resolver-sim.protocols.registry :as registry]
            [resolver-sim.time.context :as time-context]
            [prf.extensions.sew-core-model.adapter :as core]))

(def solidity-vectors
  (edn/read-string (slurp (io/resource "prf/extensions/sew_core_model/solidity_vectors.edn"))))

(def valid-scenario
  {:protocol-params {:governance-mode :restricted
                     :governance/identity "0xGovernance"
                     :guardian/identity "0xGuardian"
                     :dispute-resolver "0xResolver"
                     :resolution-module "0xResolutionModule"
                     :release-strategy "0xReleaseStrategy"
                     :sew-core/module-identities core/solidity-module-identities
                     :max-dispute-level 0
                     :resolver-bond-bps 0}
   :events []})

(defn- error-code [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e
         (:error (ex-data e)))))

(deftest event-boundary
  (testing "the extension admits the named Sew Core operation surface"
    (doseq [action core/supported-actions]
      (is (:ok (core/validate-event! {:action action :params {}})) action)))
  (testing "operations outside that surface fail closed"
    (doseq [action ["register-stake" "appeal" "execute-appeal" "slash"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/validate-event! {:action action :params {}})) action)))
  (testing "yield options are rejected on otherwise supported actions"
    (is (= :sew-core/feature-not-supported
           (error-code #(core/validate-event!
                         {:action "create-escrow" :params {:yield-enabled? true}})))))
  (testing "parameters outside each operation's input contract are rejected"
    (is (= :sew-core/event-parameters-not-supported
           (error-code #(core/validate-event!
                         {:action "create-escrow"
                          :params {:token "USDC" :to "0xBob" :amount 10
                                   :ignored-prf-option true}})))))
  (testing "automatic release and cancellation settings remain disabled"
    (is (= :sew-core/feature-not-supported
           (error-code #(core/validate-event!
                         {:action "create-escrow"
                          :params {:auto-release-time 5}}))))))

(deftest scenario-boundary
  (testing "a scenario explicitly binds governance, guardian, resolver, and modules"
    (is (:ok (core/validate-scenario! (core/prepare-scenario valid-scenario)))))
  (testing "nested lookalike governance identity does not satisfy Sew's contract"
    (is (= :sew-core/governance-identity-required
           (error-code #(core/validate-scenario!
                         (core/prepare-scenario
                          {:protocol-params
                           {:governance {:governance/identity "0xGovernance"}
                            :guardian/identity "0xGuardian"}}))))))
  (testing "yield and appeal configuration are rejected"
    (doseq [params [{:yield-enabled? true}
                    {:appeal-window-duration 60}]]
      (is (= :sew-core/feature-not-supported
             (error-code #(core/validate-scenario!
                           (core/prepare-scenario
                            (assoc valid-scenario :protocol-params
                                   (merge (:protocol-params valid-scenario) params)))))))))
  (testing "escalation, cancellation strategies, and resolver bonds are excluded"
    (doseq [params [{:max-dispute-level 1}
                    {:escalation-resolvers {1 "0xNextResolver"}}
                    {:cancellation-strategy "0xCancelStrategy"}
                    {:resolver-bond-bps 1000}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/validate-scenario!
                    (core/prepare-scenario
                     (assoc valid-scenario :protocol-params
                            (merge (:protocol-params valid-scenario) params))))))))
  (testing "core requires exactly one resolver and configured modules"
    (doseq [params [{:max-dispute-level nil}
                    {:dispute-resolver nil}
                    {:resolution-module nil}
                    {:release-strategy nil}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/validate-scenario!
                    (core/prepare-scenario
                     (assoc valid-scenario :protocol-params
                            (merge (:protocol-params valid-scenario) params))))))))
  (testing "arbitrary configured addresses cannot stand in for Solidity module identities"
    (is (= :sew-core/module-identity-mismatch
           (error-code #(core/validate-scenario!
                         (core/prepare-scenario
                          (update-in valid-scenario
                                     [:protocol-params :sew-core/module-identities]
                                     assoc :resolution-module :custom-module))))))))

(deftest selected-extension-capabilities-bootstrap
  (testing "the model's lazy profile hook can restore the selected extension"
    (registry/unregister-extension! "sew-v1")
    (is (ifn? (registry/get-capability "sew-v1" :sew-core/validate-scenario)))
    (is (ifn? (registry/get-capability "sew-v1" :sew-core/dispatch-action)))))

(deftest adapter-initialization-and-snapshot
  (let [scenario (core/prepare-scenario valid-scenario)
        world (proto/init-world core/protocol scenario)]
    (is (= core/protocol-profile (:protocol-profile world)))
    (is (= core/protocol-profile
           (:protocol-profile (proto/world-snapshot core/protocol world))))))

(deftest guardian-pause-and-governance-recovery
  (let [scenario (core/prepare-scenario valid-scenario)
        world (proto/init-world core/protocol scenario)
        agents [{:id "guardian" :address "0xGuardian" :role "guardian"}
                {:id "wrong-guardian" :address "0xAttacker" :role "guardian"}
                {:id "governance" :address "0xGovernance" :role "governance"}]
        context (proto/build-execution-context
                 core/protocol agents (:protocol-params valid-scenario))
        pause-event {:agent "guardian" :action "set-paused" :params {:paused? true}}
        pause-result (proto/dispatch-action core/protocol context world pause-event)
        unpause-result (proto/dispatch-action
                        core/protocol context (:world pause-result)
                        {:agent "governance" :action "set-paused" :params {:paused? false}})
        denied-result (proto/dispatch-action
                       core/protocol context world
                       {:agent "wrong-guardian" :action "set-paused" :params {:paused? true}})]
    (is (:ok pause-result))
    (is (true? (get-in pause-result [:world :paused?])))
    (is (= core/protocol-profile (get-in pause-result [:world :protocol-profile])))
    (is (:ok unpause-result))
    (is (false? (get-in unpause-result [:world :paused?])))
    (is (false? (:ok denied-result)))
    (is (= :sew-core/not-guardian (:error denied-result)))))

(deftest create-and-native-bilateral-cancellation
  (let [scenario (core/prepare-scenario valid-scenario)
        world (proto/init-world core/protocol scenario)
        agents [{:id "sender" :address "0xSender" :role "participant"}
                {:id "recipient" :address "0xRecipient" :role "participant"}
                {:id "resolver" :address "0xResolver" :role "resolver"}]
        context (proto/build-execution-context
                 core/protocol agents (:protocol-params valid-scenario))
        created (proto/dispatch-action
                 core/protocol context world
                 {:agent "sender" :action "create-escrow"
                  :params {:token "USDC" :to "0xRecipient" :amount 1000}})
        recipient-approval (proto/dispatch-action
                            core/protocol context (:world created)
                            {:agent "recipient" :action "recipient-cancel"
                             :params {:workflow-id 0}})
        sender-approval (proto/dispatch-action
                         core/protocol context (:world recipient-approval)
                         {:agent "sender" :action "sender-cancel"
                          :params {:workflow-id 0}})]
    (is (:ok created))
    (is (= core/protocol-profile (get-in created [:world :protocol-profile])))
    (is (:ok recipient-approval))
    (is (= :pending
           (get-in recipient-approval [:world :escrow-transfers 0 :escrow-state])))
    (is (:ok sender-approval))
    (is (= :refunded
           (get-in sender-approval [:world :escrow-transfers 0 :escrow-state])))))

(defn- conformance-context [fee-bps]
  (let [scenario (core/prepare-scenario
                  (assoc valid-scenario :protocol-params
                         (assoc (:protocol-params valid-scenario) :resolver-fee-bps fee-bps)))
        agents [{:id "buyer" :address "0xBuyer" :role "participant"}
                {:id "seller" :address "0xSeller" :role "participant"}
                {:id "resolver" :address "0xResolver" :role "resolver"}]
        world (proto/init-world core/protocol scenario)
        context (proto/build-execution-context core/protocol agents (:protocol-params scenario))]
    {:world world :context context}))

(defn- conformance-step [context world agent action params]
  (let [result (proto/dispatch-action core/protocol context world
                                      {:agent agent :action action :params params})]
    (when-not (:ok result)
      (throw (ex-info "Sew Core conformance action failed"
                      {:action action :error (:error result) :detail (:detail result)})))
    (:world result)))

(defn- conformance-create [context world gross]
  (conformance-step context world "buyer" "create-escrow"
                    {:token "USDC" :to "0xSeller" :amount gross}))

(defn- assert-solidity-settlement [world vector]
  (let [{:keys [net state claimant fee]} vector
        escrow (get-in world [:escrow-transfers 0])]
    (is (= state (:escrow-state escrow)))
    (is (= net (get-in world [:claimable 0 claimant] 0)))
    (is (= (or fee 0) (get-in world [:total-fees :USDC] 0)))))

(deftest pinned-solidity-release-and-resolution-conformance
  (let [release (:release (:cases solidity-vectors))
        resolution (:dispute-resolution (:cases solidity-vectors))
        {:keys [context world]} (conformance-context (:fee-bps release))
        funded (conformance-create context world (:gross release))
        released (conformance-step context funded "buyer" "release" {:workflow-id 0})]
    (testing (str "matches pinned Solidity " (:source/test-function release))
      (assert-solidity-settlement released release))
    (let [{resolution-context :context resolution-world :world}
          (conformance-context (:fee-bps resolution))
          funded (conformance-create resolution-context resolution-world (:gross resolution))
          disputed (conformance-step resolution-context funded "buyer" "raise-dispute"
                                     {:workflow-id 0})
          decided (conformance-step resolution-context disputed "resolver" "execute-resolution"
                                    {:workflow-id 0 :is-release true
                                     :resolution-hash "0xresolution"})]
      (testing (str "matches pinned Solidity " (:source/test-function resolution))
        (assert-solidity-settlement decided resolution)))))

(deftest pinned-solidity-timeout-conformance
  (let [vector (:timeout (:cases solidity-vectors))
        {:keys [context world]} (conformance-context (:fee-bps vector))
        funded (conformance-create context world (:gross vector))
        disputed (conformance-step context funded "buyer" "raise-dispute" {:workflow-id 0})
        expired (time-context/advance-time disputed {:seconds (:elapsed-seconds vector)})
        refunded (conformance-step context expired "buyer" "auto-cancel-disputed"
                                   {:workflow-id 0})]
    (testing (str "matches pinned Solidity " (:source/test-function vector))
      (assert-solidity-settlement refunded vector))))

(deftest pinned-solidity-pull-settlement-conformance
  (let [vector (:pull-settlement (:cases solidity-vectors))
        {:keys [context world]} (conformance-context (:fee-bps vector))
        funded (conformance-create context world (:gross vector))
        released (conformance-step context funded "buyer" "release" {:workflow-id 0})
        before-withdrawal (get-in released [:claimable 0 (:claimant vector)])
        withdrawn (conformance-step context released "seller" "withdraw-escrow"
                                    {:workflow-id 0})]
    (testing (str "matches pinned Solidity " (:source/test-function vector))
      (is (= (:net vector) before-withdrawal))
      (is (zero? (get-in withdrawn [:claimable 0 (:claimant vector)] 0)))
      (is (= (:net vector) (get-in withdrawn [:total-withdrawn :USDC] 0)))
      (is (= :released (get-in withdrawn [:escrow-transfers 0 :escrow-state]))))))
