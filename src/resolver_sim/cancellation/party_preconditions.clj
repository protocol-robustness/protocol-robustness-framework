(ns resolver-sim.cancellation.party-preconditions
  "Rooted party-cancellation preconditions and their pure evaluator."
  (:require [resolver-sim.cancellation.sew-escrow-snapshot :as snapshot]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "sew-party-cancellation-preconditions.v1")
(def preconditions-domain "SEW_PARTY_CANCELLATION_PRECONDITIONS_V1")
(defn party-principal [s party] (case party :sender (:escrow/sender s) :recipient (:escrow/recipient s) nil))
(defn precondition-errors [s party principal]
  (vec (concat
        (when-not (snapshot/valid-snapshot? s) [:precondition/invalid-snapshot])
        (when-not (contains? #{:sender :recipient} party) [:precondition/unknown-party])
        (when (and (contains? #{:sender :recipient} party) (not= principal (party-principal s party))) [:precondition/principal-not-party]))))
(defn preconditions [s party principal]
  {:preconditions/schema schema-version :workflow/id (:workflow/id s) :party party :principal principal
   :preconditions/errors (precondition-errors s party principal)})
(defn preconditions-root [p]
  (when-not (= schema-version (:preconditions/schema p)) (throw (ex-info "invalid party cancellation preconditions" {:preconditions p})))
  (hash-ref/sha256-ref (hc/domain-hash preconditions-domain (dissoc p :preconditions/root))))
(defn preconditions-root-valid? [p]
  (and (map? p) (= schema-version (:preconditions/schema p)) (= (:preconditions/root p) (preconditions-root (dissoc p :preconditions/root)))))
(defn other-party-agreed? [s party]
  (= :agree-to-cancel (case party :sender (:recipient/cancellation-status s) :recipient (:sender/cancellation-status s))))
