(ns resolver-sim.protocols.sew.reclaimed-reconstruction
  "Independent reconstruction of one reclaimed deferred-yield settlement.

  This is an application contract, not a replay through the lifecycle or
  accounting mutation path. It binds the custody debit and claimable credit as
  separate effects, plus the settlement identity that attributes them."
  (:require [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.accounting.held-position-policy :as held-policy]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.hash.canonical :as hash]))

(def ^:private operation-domain "sew.reclaimed-yield.operation.v1")
(def ^:private before-domain "sew.reclaimed-yield.before.v1")
(def ^:private effects-domain "sew.reclaimed-yield.effects.v1")
(def ^:private after-domain "sew.reclaimed-yield.after.v1")
(def ^:private settlement-domain "sew.reclaimed-yield.settlement.v1")
(def ^:private claimable-domain "sew.reclaimed-yield.claimable.v1")

(defn operation-basis
  [{:keys [workflow-id owner-id recipient reclaimed]}]
  {:workflow-id workflow-id
   :owner-id owner-id
   :recipient recipient
   :reclaimed reclaimed
   :operation :claim-deferred-yield})

(defn operation-root [operation]
  (hash/domain-hash operation-domain (operation-basis operation)))

(defn- before-projection [{:keys [held-adjustments held-artifacts claimable-v2 claimable]}]
  {:held-adjustments (vec (or held-adjustments []))
   :held-artifacts (or held-artifacts {})
   :claimable-v2 (or claimable-v2 {})
   :claimable (or claimable {})})

(defn before-root [state-before]
  (hash/domain-hash before-domain (before-projection state-before)))

(defn- token-from-state [state-before workflow-id]
  (get-in state-before [:escrow-transfers workflow-id :token]))

(defn settlement-root [{:keys [workflow-id token reclaimed recipient]}]
  (held-adjustment/settlement-identity
   {:workflow-id workflow-id
    :token token
    :direction :released
    :filled reclaimed
    :recipient recipient}))

(defn derive-adjustment
  [{:keys [state-before operation]}]
  (let [{:keys [workflow-id owner-id recipient reclaimed]} operation
        token (token-from-state state-before workflow-id)
        prior (vec (:held-adjustments state-before []))
        replayed (custody/replay-held-adjustment-state {} prior)
        before (get-in replayed [:total-held token] 0)
        root (settlement-root {:workflow-id workflow-id
                               :token token
                               :reclaimed reclaimed
                               :recipient recipient})
        extra {:held/action "claim-deferred-yield"
               :held/workflow-id workflow-id
               :held/owner-id owner-id
               :owner/address recipient
               :held/recipient recipient
               :held-adjustment/settlement-root root}
        position (held-policy/position-components token :deferred-yield-claimed extra)
        previous (get-in state-before
                         [:held-artifacts (:held-adjustment/id (last prior))])]
    (held-adjustment/build-held-adjustment
     (merge {:held-adjustment/id (str "held-adjustment-" (count prior))
             :held/direction :out
             :token token
             :amount reclaimed
             :held/before before
             :held/after (- before reclaimed)
             :held/reason :deferred-yield-claimed
             :held/action "claim-deferred-yield"}
            (when-let [previous-hash (:artifact/hash previous)]
              {:held/previous-artifact-hash previous-hash})
            position
            extra))))

(defn- claimable-after [state-before operation]
  (let [{:keys [workflow-id recipient reclaimed]} operation]
    (-> state-before
        (update-in [:claimable-v2 workflow-id :settlement/yield recipient]
                   (fnil + 0) reclaimed)
        (update-in [:claimable workflow-id recipient] (fnil + 0) reclaimed))))

(defn- custody-after [state-before adjustment artifact]
  (let [adjustments (conj (vec (:held-adjustments state-before [])) adjustment)
        artifacts (assoc (or (:held-artifacts state-before {}) )
                         (:held-adjustment/id adjustment) artifact)
        replayed (custody/replay-held-adjustment-state {} adjustments)]
    {:held-adjustments adjustments
     :held-artifacts artifacts
     :held-ledger/index (:held-ledger/index replayed)
     :total-held (:total-held replayed)
     :held/positions (:held/positions replayed)}))

(defn derive-transition
  [{:keys [state-before operation]}]
  (let [adjustment (derive-adjustment {:state-before state-before
                                       :operation operation})
        artifact (custody/build-held-custody-artifact adjustment)
        root (settlement-root {:workflow-id (:workflow-id operation)
                               :token (:token adjustment)
                               :reclaimed (:reclaimed operation)
                               :recipient (:recipient operation)})
        settlement {:settlement/root root
                    :settlement/workflow-id (:workflow-id operation)
                    :settlement/token (:token adjustment)
                    :settlement/direction :released
                    :settlement/recipient (:recipient operation)
                    :settlement/filled (:reclaimed operation)
                    :settlement/adjustment-ids [(:held-adjustment/id adjustment)]
                    :settlement/held-adjustment-set-root
                    (held-adjustment/settlement-held-adjustment-set-root [adjustment])}
        claimable (claimable-after state-before operation)
        custody (custody-after state-before adjustment artifact)
        after (merge custody
                     (select-keys claimable [:claimable-v2 :claimable])
                     {:sew/settlements {root settlement}})
        effects [{:effect/kind :custody-decrease
                  :effect/root (hash/domain-hash settlement-domain adjustment)}
                 {:effect/kind :claimable-increase
                  :effect/root (hash/domain-hash claimable-domain
                                                 {:workflow-id (:workflow-id operation)
                                                  :domain :settlement/yield
                                                  :recipient (:recipient operation)
                                                  :amount (:reclaimed operation)})}
                 {:effect/kind :settlement-identity
                  :effect/root root}]]
    {:schema :sew/reclaimed-yield-reconstruction.v1
     :operation-root (operation-root operation)
     :before-root (before-root state-before)
     :effects effects
     :effects-root (hash/domain-hash effects-domain effects)
     :after after
     :after-root (hash/domain-hash after-domain after)
     :adjustment adjustment
     :artifact artifact
     :settlement settlement}))

(defn verify-transition
  [{:keys [state-before state-after operation]}]
  (let [{:keys [after] :as expected}
        (derive-transition {:state-before state-before :operation operation})
        mismatches (->> (keys after)
                        (keep (fn [key]
                                (when-not (= (get after key) (get state-after key))
                                  key)))
                        vec)]
    (assoc expected :valid? (empty? mismatches) :mismatches mismatches)))
