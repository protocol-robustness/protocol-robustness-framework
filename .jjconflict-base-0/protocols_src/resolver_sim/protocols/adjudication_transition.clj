(ns resolver-sim.protocols.adjudication-transition
  "Protocol-neutral identities for predecessor decisions and adjudicative
   authority transitions. Protocols own derivation/publication; this namespace
   supplies closed canonical artifacts and cross-artifact validation only."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(defn- rooted [schema domain root-key body]
  (let [base (assoc body :artifact/schema schema)]
    (assoc base root-key (ref/sha256-ref (hc/domain-hash domain (hc/project-canonical-safe base))))))

(defn- errors [x schema domain root-key required refs keyword-fields]
  (if-not (map? x) [:invalid-artifact]
          (let [allowed (conj required root-key)
                missing (set/difference required (set (keys x)))
                extra (set/difference (set (keys x)) allowed)]
            (cond-> []
              (seq missing) (conj :missing-required-field)
              (seq extra) (conj :unknown-field)
              (not= schema (:artifact/schema x)) (conj :invalid-schema)
              (not (every? #(ref/valid-sha256-ref? (get x %)) refs)) (conj :invalid-reference)
              (not (every? #(keyword? (get x %)) keyword-fields)) (conj :invalid-enum)
              (and (empty? missing) (empty? extra)
                   (not= (get x root-key)
                         (ref/sha256-ref (hc/domain-hash domain (hc/project-canonical-safe (dissoc x root-key))))))
              (conj :root-mismatch)))))

(def decision-schema "prf/adjudication-decision.v1")
(def decision-domain :prf-adjudication-decision-v1)
(def decision-fields #{:artifact/schema :decision/subject-root :decision/case-root
                       :decision/adjudication-root :decision/adjudicator-root
                       :decision/outcome :decision/temporal-basis-root})
(defn build-decision [x] (rooted decision-schema decision-domain :decision/root x))
(defn validate-decision [x]
  (let [e (errors x decision-schema decision-domain :decision/root decision-fields
                  #{:decision/subject-root :decision/case-root :decision/adjudication-root
                    :decision/adjudicator-root :decision/temporal-basis-root}
                  #{:decision/outcome})]
    {:valid? (empty? e) :errors e}))

(def eligibility-schema "prf/adjudication-transition-eligibility.v1")
(def eligibility-domain :prf-adjudication-transition-eligibility-v1)
(def eligibility-fields #{:artifact/schema :transition/type :transition/predecessor-adjudication-root
                          :transition/predecessor-decision-root :transition/state-root
                          :transition/temporal-basis-root :transition/eligibility})
(defn build-eligibility [x] (rooted eligibility-schema eligibility-domain :transition/eligibility-root x))
(defn validate-eligibility [x]
  (let [e (errors x eligibility-schema eligibility-domain :transition/eligibility-root eligibility-fields
                  #{:transition/predecessor-adjudication-root :transition/predecessor-decision-root
                    :transition/state-root :transition/temporal-basis-root}
                  #{:transition/type :transition/eligibility})]
    {:valid? (and (empty? e) (contains? #{:eligible :ineligible} (:transition/eligibility x)))
     :errors (cond-> e (not (contains? #{:eligible :ineligible} (:transition/eligibility x))) (conj :invalid-eligibility))}))

(def selection-basis-schema "prf/adjudication-transition-selection-basis.v1")
(def selection-basis-domain :prf-adjudication-transition-selection-basis-v1)
(def selection-basis-fields #{:artifact/schema :transition/subject-root :transition/predecessor-adjudication-root
                              :transition/predecessor-decision-root :transition/eligibility-root
                              :transition/module-binding-root :transition/protocol-instance-root
                              :transition/state-root})
(defn build-selection-basis [x] (rooted selection-basis-schema selection-basis-domain :transition/selection-basis-root x))
(defn validate-selection-basis [x]
  (let [e (errors x selection-basis-schema selection-basis-domain :transition/selection-basis-root
                  selection-basis-fields (disj selection-basis-fields :artifact/schema) #{})]
    {:valid? (empty? e) :errors e}))

(def selection-result-schema "prf/adjudication-transition-selection-result.v1")
(def selection-result-domain :prf-adjudication-transition-selection-result-v1)
(def selection-result-fields #{:artifact/schema :transition/selection-basis-root
                               :transition/successor-adjudication-root
                               :transition/successor-adjudicator-root :transition/selection})
(defn build-selection-result [x] (rooted selection-result-schema selection-result-domain :transition/selection-result-root x))
(defn validate-selection-result [x]
  (let [e (errors x selection-result-schema selection-result-domain :transition/selection-result-root
                  selection-result-fields (disj selection-result-fields :artifact/schema :transition/selection)
                  #{:transition/selection})]
    {:valid? (and (empty? e) (= :selected (:transition/selection x)))
     :errors (cond-> e (not= :selected (:transition/selection x)) (conj :invalid-selection))}))

(def transition-schema "prf/adjudication-authority-transition.v1")
(def transition-domain :prf-adjudication-authority-transition-v1)
(def transition-fields #{:artifact/schema :transition/subject-root
                         :transition/predecessor-adjudication-root :transition/predecessor-decision-root
                         :transition/predecessor-adjudicator-root :transition/successor-adjudication-root
                         :transition/successor-adjudicator-root :transition/eligibility-root
                         :transition/selection-basis-root :transition/selection-result-root
                         :transition/module-binding-root :transition/protocol-instance-root})
(defn build-authority-transition [x] (rooted transition-schema transition-domain :transition/root x))

(defn validate-authority-transition [transition predecessor-decision eligibility selection-basis selection-result]
  (let [own (errors transition transition-schema transition-domain :transition/root transition-fields
                    (disj transition-fields :artifact/schema) #{})
        errors (cond-> (vec (concat own (:errors (validate-decision predecessor-decision))
                                    (:errors (validate-eligibility eligibility))
                                    (:errors (validate-selection-basis selection-basis))
                                    (:errors (validate-selection-result selection-result))))
                 (not= (:transition/predecessor-decision-root transition) (:decision/root predecessor-decision)) (conj :predecessor-decision-mismatch)
                 (not= (:transition/eligibility-root transition) (:transition/eligibility-root eligibility)) (conj :eligibility-mismatch)
                 (not= (:transition/selection-basis-root transition) (:transition/selection-basis-root selection-basis)) (conj :selection-basis-mismatch)
                 (not= (:transition/selection-result-root transition) (:transition/selection-result-root selection-result)) (conj :selection-result-mismatch)
                 (not= (:transition/successor-adjudication-root transition) (:transition/successor-adjudication-root selection-result)) (conj :successor-adjudication-mismatch)
                 (not= (:transition/successor-adjudicator-root transition) (:transition/successor-adjudicator-root selection-result)) (conj :successor-adjudicator-mismatch))]
    {:valid? (empty? errors) :errors errors}))
