(ns resolver-sim.protocols.sew.resolution-decision
  "Canonical outcome artifact for a validated SEW resolution basis."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.resolution-basis :as basis]))

(def ^:const schema "sew-resolution-decision.v1")
(def ^:const domain :sew-resolution-decision-v1)
(def outcomes #{:release :refund :split :refused})

(def ^:private root-key :sew-resolution-decision/root)
(def ^:private required-fields
  #{:artifact/schema :resolution-basis/root :workflow/id :state-before/root
    :decision/outcome :decision-inputs/root :evidence/root :resolver/root
    :resolution-module/root :resolution-module/snapshot-root})
(def ^:private reference-fields
  (disj required-fields :artifact/schema :workflow/id :decision/outcome))

(defn decision-payload [decision] (dissoc decision root-key))

(defn decision-errors [decision]
  (if-not (map? decision)
    [:invalid-decision]
    (let [have (set (keys decision))
          missing (set/difference required-fields have)
          extra (set/difference have (conj required-fields root-key))]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-decision-field)
        (not= schema (:artifact/schema decision)) (conj :invalid-schema)
        (not (and (integer? (:workflow/id decision))
                  (not (neg? (:workflow/id decision))))) (conj :invalid-workflow-id)
        (not (contains? outcomes (:decision/outcome decision))) (conj :invalid-outcome)
        (not (every? #(ref/valid-sha256-ref? (get decision %)) reference-fields))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? (get decision root-key))) (conj :invalid-decision-root)
        (and (empty? missing) (empty? extra)
             (every? #(ref/valid-sha256-ref? (get decision %)) reference-fields)
             (ref/valid-sha256-ref? (get decision root-key))
             (not= (get decision root-key)
                   (ref/sha256-ref (hc/domain-hash domain
                                                    (hc/project-canonical-safe
                                                     (decision-payload decision))))))
        (conj :decision-root-mismatch)))))

(defn validate-resolution-decision [decision]
  (let [errors (decision-errors decision)] {:valid? (empty? errors) :errors errors}))
(defn valid-resolution-decision? [decision] (:valid? (validate-resolution-decision decision)))

(defn build-resolution-decision [decision]
  (let [base (assoc decision :artifact/schema schema)
        artifact (assoc base root-key
                        (ref/sha256-ref (hc/domain-hash domain
                                                        (hc/project-canonical-safe
                                                         (decision-payload base)))))
        validation (validate-resolution-decision artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot build invalid sew-resolution-decision.v1"
                      {:type :sew-resolution-decision/invalid :errors (:errors validation)})))
    artifact))

(defn validate-decision-against-basis
  "Validate a decision and ensure its repeated subject/resolver/module bindings
   exactly agree with the referenced, already-validated SEW basis."
  [decision resolution-basis]
  (let [errors (vec (concat (:errors (validate-resolution-decision decision))
                            (:errors (basis/validate-resolution-basis resolution-basis))))
        bindings [[:resolution-basis/root :sew-resolution-basis/root]
                  [:workflow/id :workflow/id]
                  [:state-before/root :state-before/root]
                  [:resolver/root :resolver/root]
                  [:resolution-module/root :resolution-module/root]
                  [:resolution-module/snapshot-root :resolution-module/snapshot-root]]
        errors (reduce (fn [xs [decision-key basis-key]]
                         (if (= (get decision decision-key) (get resolution-basis basis-key))
                           xs
                           (conj xs :basis-binding-mismatch)))
                       errors bindings)]
    {:valid? (empty? errors) :errors errors}))
