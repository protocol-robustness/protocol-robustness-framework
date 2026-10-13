(ns resolver-sim.protocols.sew.held-reconstruction-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.accounting.held-position-policy :as held-policy]
            [resolver-sim.assurance.force-authorisation :as force-authorisation]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.protocols.sew.held-reconstruction :as reconstruction]
            [resolver-sim.protocols.sew.types :as types]))

(def token :USDC)
(def owner "0xAlice")

(def ordinary-operation
  {:token token
   :amount 100
   :action "create-escrow"
   :reason :escrow-principal-deposited
   :extra {:held/workflow-id 7 :owner/address owner}})

(defn- successor [world operation]
  (accounting/add-held world (:token operation) (:amount operation)
                       (dissoc operation :token :amount)))

(defn- input [world operation state-after]
  {:state-before world :operation operation :state-after state-after})

(deftest ordinary-add-held-reconstructs-exact-effects
  (let [before (types/empty-world)
        after (successor before ordinary-operation)
        result (reconstruction/verify-transition (input before ordinary-operation after))]
    (is (:valid? result))
    (is (= 2 (count (:effects result))))
    (is (= (:held-adjustments after) (get-in result [:after :held-adjustments])))
    (is (= (:held-artifacts after) (get-in result [:after :held-artifacts])))))

(deftest reconstruction-rejects-semantic-and-effect-substitution
  (let [before (types/empty-world)
        after (successor before ordinary-operation)
        adjustment (first (:held-adjustments after))
        artifact (get-in after [:held-artifacts (:held-adjustment/id adjustment)])
        alternate-history (-> (types/empty-world)
                              (successor (assoc ordinary-operation :amount 40))
                              (successor (assoc ordinary-operation :amount 60)))]
    (doseq [operation [(assoc ordinary-operation :amount 101)
                       (assoc ordinary-operation :token :DAI)
                       (assoc ordinary-operation :reason :appeal-bond-posted)
                       (assoc-in ordinary-operation [:extra :owner/address] "0xMallory")]]
      (is (false? (:valid? (reconstruction/verify-transition (input before operation after))))))
    (doseq [state-after [(assoc after :held-adjustments [])
                         (update after :held-adjustments conj adjustment)
                         (update-in after [:held-adjustments 0 :amount] inc)
                         (assoc-in after [:held-artifacts (:held-adjustment/id adjustment)]
                                   (assoc artifact :amount 99))
                         (assoc-in after [:held-ledger/index :by-token token] 99)
                         alternate-history]]
      (is (false? (:valid? (reconstruction/verify-transition
                            (input before ordinary-operation state-after))))))
    (testing "same final quantity with a different adjustment history is rejected"
      (is (= 100 (get-in alternate-history [:total-held token])))
      (is (false? (:valid? (reconstruction/verify-transition
                            (input before ordinary-operation alternate-history))))))))

(deftest force-authorised-add-held-reconstructs-consumption
  (let [reason :governance-authorised-correction
        extra {:held/workflow-id 7 :owner/address owner}
        auth-id "force-add-1"
        scope (held-adjustment/project-held-adjustment-scope
               (merge {:authorization/id auth-id
                       :authorization/type :force-authorisation
                       :held/direction :in
                       :token token
                       :amount 100
                       :held/reason reason}
                      (held-policy/position-components token reason extra)
                      extra))
        permit {:authorization/id auth-id
                :authorization/type :force-authorisation
                :authorization/status :active
                :consumed? false
                :authorization/scope scope
                :authorization/scope-hash (force-authorisation/force-authorisation-scope-hash scope)
                :starts-at 0}
        before (assoc (types/empty-world) :force-authorisations {auth-id permit})
        operation {:token token :amount 100 :action "correction" :reason reason :extra extra
                   :authorization-provenance {:authorization/id auth-id
                                               :authorization/type :force-authorisation
                                               :authorization/scope-hash (:authorization/scope-hash permit)}}
        after (successor before operation)
        result (reconstruction/verify-transition (input before operation after))]
    (is (:valid? result))
    (is (= 3 (count (:effects result))))
    (doseq [state-after [(update after :force-authorisations/consumed dissoc auth-id)
                         (assoc-in after [:force-authorisations/consumed auth-id :authorization/id] "wrong")
                         (assoc-in after [:force-authorisations auth-id :authorization/status] :active)]]
      (is (false? (:valid? (reconstruction/verify-transition
                            (input before operation state-after))))))))
