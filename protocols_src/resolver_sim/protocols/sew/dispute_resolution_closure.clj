(ns resolver-sim.protocols.sew.dispute-resolution-closure
  "Derived post-settlement closure for final authenticated release/refund rulings."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.adjudication-snapshot :as snapshot]
            [resolver-sim.protocols.sew.dispute-ruling-finality :as finality]
            [resolver-sim.protocols.sew.resolution-basis :as basis]))

(def ^:const schema :prf/dispute-resolution-closure.v1)
(def ^:const domain :prf-dispute-resolution-closure-v1)
(def ^:private root-key :closure/root)
(def ^:private required-fields
  #{:artifact/schema :workflow/id :workflow/round :state-before/root
    :adjudication-snapshot/root :case/root :instance/root :resolution-basis/root
    :ruling/root :authentication-verification/root :finality/root
    :settlement-effect/root :terminal-effect/root :post-settlement-state/root})
(def ^:private root-fields (disj required-fields :artifact/schema :workflow/id :workflow/round))

(defn closure-payload [closure] (dissoc closure root-key))

(defn closure-errors [closure]
  (if-not (map? closure)
    [:invalid-dispute-resolution-closure]
    (let [have (set (keys closure))
          missing (set/difference required-fields have)
          extra (set/difference have (conj required-fields root-key))]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-dispute-resolution-closure-field)
        (not= schema (:artifact/schema closure)) (conj :invalid-schema)
        (not (and (integer? (:workflow/id closure)) (not (neg? (:workflow/id closure)))) )
        (conj :invalid-workflow-id)
        (not (and (integer? (:workflow/round closure)) (not (neg? (:workflow/round closure)))) )
        (conj :invalid-workflow-round)
        (not (every? #(ref/valid-sha256-ref? (get closure %)) root-fields))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? (get closure root-key))) (conj :invalid-closure-root)
        (and (empty? missing) (empty? extra)
             (every? #(ref/valid-sha256-ref? (get closure %)) root-fields)
             (ref/valid-sha256-ref? (get closure root-key))
             (not= (get closure root-key)
                   (ref/sha256-ref
                    (hc/domain-hash domain (hc/project-canonical-safe (closure-payload closure))))))
        (conj :closure-root-mismatch)))))

(defn validate-closure [closure]
  (let [errors (closure-errors closure)] {:valid? (empty? errors) :errors errors}))

(defn post-settlement-state-root [world]
  (ref/sha256-ref
   (hc/domain-hash :sew-dispute-resolution-post-settlement-state-v1
                   (hc/project-canonical-safe
                    (hc/project-world-to-structure-view world :world-structure)))))

(defn- settlement-for [world workflow-id]
  (some (fn [[_ settlement]]
          (when (= workflow-id (:settlement/workflow-id settlement)) settlement))
        (:sew/settlements world)))

(defn derive-closure
  "Derive closure exclusively from the authenticated terminal transition facts.
   No closure or envelope field is read from either semantic world."
  [{:keys [state-before state-after resolution-basis dispute-ruling authentication-verification
           finality adjudication-snapshot adjudication-case adjudication-instance]}]
  (let [workflow-id (:workflow/id resolution-basis)
        settlement (settlement-for state-after workflow-id)
        base {:artifact/schema schema
              :workflow/id workflow-id
              :workflow/round (:finality/round-id finality)
              :state-before/root (:state-before/root resolution-basis)
              :adjudication-snapshot/root (:sew/adjudication-snapshot-root adjudication-snapshot)
              :case/root (:case/root adjudication-case)
               :instance/root (:sew/adjudication-instance/root adjudication-instance)
              :resolution-basis/root (:sew-resolution-basis/root resolution-basis)
              :ruling/root (:ruling/root dispute-ruling)
              :authentication-verification/root (:authentication-verification/root authentication-verification)
              :finality/root (:finality/root finality)
              :settlement-effect/root (ref/sha256-ref (:settlement/root settlement))
              :terminal-effect/root
              (ref/sha256-ref (:settlement/held-adjustment-set-root settlement))
              :post-settlement-state/root (post-settlement-state-root state-after)}]
    (assoc base root-key
           (ref/sha256-ref (hc/domain-hash domain (hc/project-canonical-safe base))))))

(defn validate-closure-against-transition
  "Independently reconstruct the closure from retained transition facts." 
  [closure inputs]
  (let [own (validate-closure closure)
        resolution-basis (:resolution-basis inputs)
        snapshot (:adjudication-snapshot inputs)
        expected (derive-closure inputs)
        errors (cond-> (vec (:errors own))
                 (not (:valid? (basis/validate-resolution-basis resolution-basis)))
                 (conj :invalid-resolution-basis)
                 (not (:valid? (snapshot/validate-adjudication-snapshot snapshot)))
                 (conj :invalid-adjudication-snapshot)
                 (not (:valid? (finality/validate-finality (:finality inputs))))
                 (conj :invalid-finality)
                 (not= (:state-before/root resolution-basis)
                       (ref/sha256-ref
                        (hc/domain-hash :sew-resolution-live-commitment-v1
                                        (hc/project-canonical-safe
                                         {:commitment/kind :state-before
                                          :commitment/value
                                          (hc/project-world-to-structure-view
                                           (:state-before inputs) :world-structure)}))))
                 (conj :state-before-reconstruction-mismatch)
                 (not= closure expected) (conj :closure-reconstruction-mismatch))]
    {:valid? (empty? errors) :errors errors :expected expected}))
