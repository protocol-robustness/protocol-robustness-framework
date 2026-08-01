(ns resolver-sim.protocols.sew.governance-gates-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]))

(def alice {:id "alice" :type "honest" :address "0xAlice"})
(def resolver {:id "resolver" :type "honest" :address "0xResolver"})

(def base-scenario
  {:scenario-id "gov-gates-test"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [alice resolver]
   :protocol-params {:resolver-fee-bps 50}
   :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
             :params {:token "0xUSDC" :to "0xBob" :amount 10000}}]})

(deftest test-governance-full-mode
  (testing "Governance actions proceed normally in :full mode"
    (let [scenario (-> base-scenario
                       (assoc-in [:protocol-params :governance-mode] :full)
                       (assoc :events
                              [{:seq 0 :time 1000 :agent "resolver" :action "set-paused" :params {:paused? true}}]))
          result (sew/replay-with-sew-protocol scenario)]
      (is (= :pass (:outcome result)))
      (is (true? (get-in result [:world :paused?]))))))

(deftest governance-envelope-is-normalized-for-set-paused
  (let [ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
             :governance-identity "0xGov"}
        event {:seq 0 :time 1000 :agent "gov" :action "set_paused" :params {:paused? true}}
        result (sew/apply-action ctx (t/empty-world 1000) event)]
    (is (:ok result))
    (is (true? (get-in result [:world :paused?])))
    (is (= :governance
           (get-in result [:extra :authorization/provenance :authorization/type])))
    (is (= :with-governance-actor
           (get-in result [:extra :authorization/provenance :authorization/check])))
    (is (= :scenario-configured-address-binding
           (get-in result [:extra :authorization/provenance :authorization/basis])))
    (is (= :address-bound
           (get-in result [:extra :authorization/provenance :authorization/authentication-mode])))
    (is (= true
           (get-in result [:extra :authorization/provenance :authorization/address-bound?])))
    (is (= false
           (get-in result [:extra :authorization/provenance :authorization/registry-verified?])))
    (is (nil? (get-in result [:extra :authorization/provenance :authorization/class]))))
  (testing "non-governance agent is rejected"
    (let [ctx {:agent-index {"alice" {:id "alice" :address "0xAlice" :type "honest"}}
               :governance-identity "0xGov"}
          event {:seq 0 :time 1000 :agent "alice" :action "set_paused" :params {:paused? true}}
          result (sew/apply-action ctx (t/empty-world 1000) event)]
      (is (= :not-governance (:error result))))))

(deftest activate-resolver-overflow-record-carries-normalized-envelope
  (let [ctx {:agent-index {"gov" {:id "gov" :address "0xGov" :role "governance"}}
             :governance-identity "0xGov"
             :resolver-overflow-policy {:allowed-reasons #{:resolver-overcapacity}
                                        :default-max-workflows 3
                                        :default-duration 3600
                                        :failover-resolvers ["0xOverflow"]}}
        world (assoc-in (t/empty-world 1000)
                        [:resolver-capacities "0xResolver"]
                        {:current-active 1 :max-concurrent 1})
        event {:seq 0
               :time 1000
               :agent "gov"
               :action "activate_resolver_overflow"
               :params {:resolver "0xResolver"
                        :reason :resolver-overcapacity}}
        result (sew/apply-action ctx world event)
        record (get-in result [:world :resolver-overflows 0])]
    (is (:ok result))
    (is (= "0xGov" (:authorized-by record)))
    (is (= :active (:status record)))
    (is (= #{"0xOverflow"} (:failover-resolvers record)))
    (is (= :governance
           (get-in record [:authorization/provenance :authorization/type])))
    (is (= :capacity-failover
           (get-in record [:authorization/provenance :authorization/class])))
    (is (= :capacity-failover
           (get-in record [:authorization/provenance :authorization/path])))
    (is (= :resolver-overcapacity
           (get-in record [:authorization/provenance :authorization/reason])))
    (is (= :with-governance-actor
           (get-in record [:authorization/provenance :authorization/check])))))

(deftest force-authorisation-policy-rejects-non-allowlisted-action
  (let [ctx {:governance-mode :restricted}
        event {:agent "gov" :action "set_paused"}
        thrown (try
                 (sew/build-force-authorisation-provenance
                  ctx event "0xGov"
                  {:reason :resolver-overcapacity
                   :capacity-context {:resolver "0xResolver"}})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
    (is thrown)
    (is (= :invalid-force-authorisation
           (:type (ex-data thrown))))
    (is (= "set-paused"
           (:action (ex-data thrown))))))
