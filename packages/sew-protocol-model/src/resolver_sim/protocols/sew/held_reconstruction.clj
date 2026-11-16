(ns resolver-sim.protocols.sew.held-reconstruction
  "Application-owned reconstruction for one Sew accounting add-held transition.

  This is not an admission checker and never invokes accounting/add-held.  Its
  input is an already-admitted operation plus retained historical basis; it
  derives the custody and authorization consequences independently."
  (:require [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.accounting.held-position-policy :as held-policy]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.protocols.sew.accounting :as accounting]))

(def ^:private operation-domain "sew.held-add.operation.v1")
(def ^:private before-domain "sew.held-add.before.v1")
(def ^:private effects-domain "sew.held-add.effects.v1")
(def ^:private after-domain "sew.held-add.after.v1")
(def ^:private adjustment-domain "sew.held-add.adjustment.v1")
(def ^:private consumption-domain "sew.held-add.authorization-consumption.v1")

(defn operation-basis
  "Canonical semantic inputs for one add-held operation. `:extra` is retained
  because its reason-policy scope fields are committed to the adjustment."
  [{:keys [token amount action reason extra authorization-provenance]
    parameter-context :parameter/context
    parameter-address :parameter/address}]
  {:token (if (keyword? token) token (keyword token))
   :amount amount
   :held/direction :in
   :held/action (or action "add-held")
   :held/reason (or reason :held/unspecified)
   :extra (or extra {})
   :parameter/context parameter-context
   :parameter/address parameter-address
   :authorization/provenance authorization-provenance})

(defn operation-root [operation]
  (hash/domain-hash operation-domain (operation-basis operation)))

(defn- custody-before-projection [state-before initial-held]
  {:initial-held (or initial-held {})
   :held-adjustments (vec (:held-adjustments state-before []))
   :held-artifacts (:held-artifacts state-before {})})

(defn- authorization-before-projection [state-before]
  (select-keys state-before [:force-authorisations
                             :force-authorisations/consumed
                             :force-authorisations/consumption-records]))

(defn before-root
  "Root of the retained historical basis relevant to this operation."
  [{:keys [state-before initial-held]}]
  (hash/domain-hash before-domain
                    {:custody (custody-before-projection state-before initial-held)
                     :authorization (authorization-before-projection state-before)}))

(defn derive-held-adjustment
  "Derive the exact add-held adjustment from retained history and operation
  inputs. The prior canonical ledger, not a mutable :total-held alias, supplies
  the before amount."
  [{:keys [state-before initial-held operation]}]
  (let [basis (operation-basis operation)
        adjustments (vec (:held-adjustments state-before []))
        replayed (custody/replay-held-adjustment-state (or initial-held {}) adjustments)
        token (:token basis)
        extra (merge (:extra basis)
                     (cond-> {}
                       (:parameter/context basis) (assoc :parameter/context (:parameter/context basis))
                       (:parameter/address basis) (assoc :parameter/address (:parameter/address basis))))
        previous-id (:held-adjustment/id (last adjustments))
        previous-artifact (get-in state-before [:held-artifacts previous-id])
        position-fields (held-policy/position-components token (:held/reason basis) extra)]
    (held-adjustment/build-held-adjustment
     (merge {:held-adjustment/id (str "held-adjustment-" (count adjustments))
             :held/direction :in
             :token token
             :amount (:amount basis)
             :held/before (get-in replayed [:total-held token] 0)
             :held/after (+ (get-in replayed [:total-held token] 0) (:amount basis))
             :held/reason (:held/reason basis)
             :held/action (:held/action basis)}
            (when-let [previous-hash (:artifact/hash previous-artifact)]
              {:held/previous-artifact-hash previous-hash})
            position-fields
            (when-let [provenance (:authorization/provenance basis)]
              {:authorization/provenance provenance})
            extra))))

(defn- custody-after-projection [initial-held adjustments artifacts]
  (let [replayed (custody/replay-held-adjustment-state (or initial-held {}) adjustments)]
    {:held-adjustments adjustments
     :held-artifacts artifacts
     :held-ledger/index (:held-ledger/index replayed)
     :total-held (:total-held replayed)
     :held/positions (:held/positions replayed)}))

(defn derive-transition
  "Derive all accounting effects of one already-admitted add-held operation.

  `state-before` retains the historical adjustment/artifact ledger and, when
  applicable, authorization registry. `initial-held` is required only for a
  non-zero historical opening. No current-world lookup or clock is used."
  [{:keys [state-before initial-held operation] :as input}]
  (let [adjustment (derive-held-adjustment input)
        artifact (custody/build-held-custody-artifact adjustment)
        adjustments (conj (vec (:held-adjustments state-before [])) adjustment)
        artifacts (assoc (:held-artifacts state-before {})
                         (:held-adjustment/id adjustment) artifact)
        custody-after (custody-after-projection initial-held adjustments artifacts)
        provenance (:authorization/provenance (operation-basis operation))
        authorization-after
        (when (= :force-authorisation (:authorization/type provenance))
          (authorization-before-projection
           (accounting/apply-force-authorisation-consumption
            state-before provenance adjustment)))
        effects (cond-> [{:effect/kind :held-adjustment
                          :effect/root (hash/domain-hash adjustment-domain adjustment)}
                         {:effect/kind :held-custody-artifact
                          :effect/root (:artifact/hash artifact)}]
                  authorization-after
                  (conj {:effect/kind :force-authorisation-consumption
                         :effect/root (hash/domain-hash consumption-domain authorization-after)}))
        after (cond-> custody-after authorization-after (merge authorization-after))]
    {:schema :sew/held-add-reconstruction.v1
     :operation-root (operation-root operation)
     :before-root (before-root input)
     :effects effects
     :effects-root (hash/domain-hash effects-domain effects)
     :after after
     :after-root (hash/domain-hash after-domain after)
     :adjustment adjustment
     :artifact artifact
     :authorization-after authorization-after}))

(defn verify-transition
  "Compare a committed successor against the exact accounting transition
  derived from its retained historical basis. Returns all mismatched semantic
  projections, including history rather than only final balances."
  [{:keys [state-after] :as input}]
  (let [{:keys [after] :as expected} (derive-transition input)
        keys (keys after)
        mismatches (->> keys
                        (keep (fn [k]
                                (when-not (= (get after k) (get state-after k)) k)))
                        vec)]
    (assoc expected
           :valid? (empty? mismatches)
           :mismatches mismatches)))
