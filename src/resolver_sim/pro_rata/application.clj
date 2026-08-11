(ns resolver-sim.pro-rata.application
  "Exact authorization and full-application receipts for refined pro-rata effects."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.economics.effects :as effects]))

(def authorization-schema "authorized-effect-execution.v1")
(def receipt-schema "applied-effect-receipt.v1")

(defn authorize
  "Commits permission to apply exactly one refined effect set to exactly one
   pre-state. Callers supply an already verified authority root."
  [{:keys [allocation-root proposed-effects-root protocol-effect-set-root
           state-before-root policy-root authorization-root consumption-key] :as x}]
  (when-not (every? some? [allocation-root proposed-effects-root protocol-effect-set-root
                           state-before-root policy-root authorization-root consumption-key])
    (throw (ex-info "incomplete exact effect authorization" {:value x})))
  (let [base {:schema-version authorization-schema
              :allocation/root allocation-root :proposed-effects/root proposed-effects-root
              :protocol-effect-set/root protocol-effect-set-root :state-before/root state-before-root
              :policy/root policy-root :authorization/root authorization-root
              :conflict/consumption-key consumption-key}]
    (assoc base :authorized-effect-execution/root
           (hc/domain-hash :authorized-effect-execution base))))

(defn authorization-valid? [authorization]
  (= (:authorized-effect-execution/root authorization)
     (hc/domain-hash :authorized-effect-execution
                     (dissoc authorization :authorized-effect-execution/root))))

(defn applied-adjustment-refinement
  "Commits the verified bijection between a protocol effect set and canonical
   applied held-adjustments. The two roots are intentionally distinct: they
   represent different schemas." [protocol-effect-set-root adjustments-root pairs]
  (let [base {:schema-version "applied-adjustment-refinement.v1"
              :protocol-effect-set/root protocol-effect-set-root
              :applied-adjustments/root adjustments-root
              :pairs pairs}]
    (assoc base :applied-adjustment-refinement/root
           (hc/domain-hash :applied-adjustment-refinement base))))

(defn applied-adjustment-refinement-violations
  "Checks a total one-to-one mapping from protocol add-held effects to applied
   canonical held-adjustments. `pairs` contain effect and adjustment indices."
  [protocol-effects adjustments refinement]
  (let [pairs (:pairs refinement)
        effect-ids (mapv :effect/root protocol-effects)
        adjustment-roots (mapv effects/held-adjustment-root adjustments)
        p-effects (mapv :effect/root pairs)
        p-adjustments (mapv :adjustment/root pairs)]
    (vec (concat
          (when-not (= (set effect-ids) (set p-effects)) [{:reason :pro-rata/applied-refinement-not-total}])
          (when-not (= (count p-effects) (count (distinct p-effects))) [{:reason :pro-rata/applied-refinement-effect-duplicate}])
          (when-not (= (set adjustment-roots) (set p-adjustments)) [{:reason :pro-rata/applied-refinement-adjustment-not-total}])
          (when-not (= (count p-adjustments) (count (distinct p-adjustments))) [{:reason :pro-rata/applied-refinement-adjustment-duplicate}])
          (mapcat (fn [pair]
                    (let [effect (:effect (first (filter #(= (:effect/root %) (:effect/root pair)) protocol-effects)))
                          adjustment (first (filter #(= (effects/held-adjustment-root %) (:adjustment/root pair)) adjustments))]
                      (when (or (nil? effect) (nil? adjustment)
                                (not= "add-held" (:effect/action effect))
                                (not= :in (:held/direction adjustment))
                                (not= (:effect/amount effect) (:amount adjustment))
                                (not= (:effect/token effect) (:token adjustment))
                                (not= (:effect/account effect) (:held/account adjustment))
                                (not= (:held/kind effect) (:held/reason adjustment))
                                (not= (:owner/address effect) (:owner/address adjustment))
                                (not= (:parameter/context effect) (:parameter/context adjustment))
                                (not= (:parameter/address effect) (:parameter/address adjustment)))
                        [{:reason :pro-rata/applied-refinement-semantic-mismatch
                          :effect/root (:effect/root pair) :adjustment/root (:adjustment/root pair)}])))
                  pairs)))))

(defn applied-adjustment-refinement-valid?
  [protocol-effects adjustments refinement]
  (empty? (applied-adjustment-refinement-violations protocol-effects adjustments refinement)))

(defn applied-receipt
  "Builds a full-application receipt. The supplied refinement proof—not root
   equality—links executable effects to canonical applied adjustments."
  [{:keys [authorization state-before-root state-after-root executed-effect-set-root
           applied-adjustment-refinement protocol-effects applied-adjustments
           ledger-before-root ledger-after-root] :as x}]
  (let [adjustments-root (:applied-adjustments/root applied-adjustment-refinement)]
    (when-not (and (authorization-valid? authorization)
                   (applied-adjustment-refinement-valid?
                    protocol-effects applied-adjustments applied-adjustment-refinement)
                   (= state-before-root (:state-before/root authorization))
                   (= executed-effect-set-root (:protocol-effect-set/root authorization))
                   (= executed-effect-set-root (:protocol-effect-set/root applied-adjustment-refinement))
                   (= (:applied-adjustment-refinement/root applied-adjustment-refinement)
                      (hc/domain-hash :applied-adjustment-refinement
                                      (dissoc applied-adjustment-refinement :applied-adjustment-refinement/root)))
                   (every? some? [adjustments-root state-after-root ledger-before-root ledger-after-root]))
      (throw (ex-info "invalid full pro-rata effect application" {:value x})))
    (let [base {:schema-version receipt-schema
                :authorization/root (:authorized-effect-execution/root authorization)
                :protocol-effect-set/root executed-effect-set-root
                :executed-effect-set/root executed-effect-set-root
                :applied-adjustment-refinement/root (:applied-adjustment-refinement/root applied-adjustment-refinement)
                :applied-adjustments/root adjustments-root
                :state-before/root state-before-root :state-after/root state-after-root
                :ledger-before/root ledger-before-root :ledger-after/root ledger-after-root
                :application/status :applied}]
      (assoc base :applied-effect-receipt/root (hc/domain-hash :applied-effect-receipt base)))))

(defn receipt-valid? [receipt]
  (= (:applied-effect-receipt/root receipt)
     (hc/domain-hash :applied-effect-receipt (dissoc receipt :applied-effect-receipt/root))))
