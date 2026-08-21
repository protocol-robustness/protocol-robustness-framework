(ns resolver-sim.assurance.trust-sequence-definition
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version 1)

(def ^:private valid-step-types #{:assertion :state-transition})

(defn build-definition
  [{:keys [id provider steps]}]
  (let [base {:trust-sequence-definition/schema-version schema-version
              :trust-sequence-definition/id id
              :trust-sequence-definition/provider provider
              :trust-sequence-definition/steps steps}
        root (hc/hash-with-intent {:hash/intent :trust-sequence-definition} base)]
    (assoc base :trust-sequence-definition/root root)))

(defn- check [pred err]
  (if pred [] [err]))

(defn validate-definition
  [definition]
  (let [base (dissoc definition :trust-sequence-definition/root)
        expected-root (hc/hash-with-intent {:hash/intent :trust-sequence-definition} base)
        provider (:trust-sequence-definition/provider definition)
        steps (vec (:trust-sequence-definition/steps definition []))
        step-ids (map :step/id steps)
        errors (into []
                     cat
                     [(check (= (:trust-sequence-definition/schema-version definition) schema-version)
                             (str "schema-version mismatch: expected " schema-version))
                      (check (= expected-root (:trust-sequence-definition/root definition))
                             "definition root mismatch")
                      (check (qualified-keyword? (:trust-sequence-definition/id definition))
                             "definition id must be a qualified keyword")
                      (check (map? provider)
                             "provider must be a map")
                      (check (qualified-keyword? (:protocol/id provider))
                             "provider :protocol/id must be a qualified keyword")
                      (check (and (string? (:protocol/version provider)) (seq (:protocol/version provider)))
                             "provider :protocol/version must be a non-empty string")
                      (check (seq steps)
                             "definition must have at least one step")
                      (check (= (count step-ids) (count (set step-ids)))
                             "step ids must be unique")
                      (mapcat (fn [step]
                                (let [pr (:step/policy-requirement step)]
                                  (into []
                                        cat
                                        [(check (qualified-keyword? (:step/id step))
                                                (str "step id must be a qualified keyword: " (:step/id step)))
                                         (check (contains? valid-step-types (:step/type step))
                                                (str "invalid step type: " (:step/type step)
                                                     " (expected :assertion or :state-transition)"))
                                         (check (map? pr)
                                                (str "step " (:step/id step) " missing policy-requirement"))
                                         (check (qualified-keyword? (:policy/id pr))
                                                (str "step " (:step/id step) " policy id must be a qualified keyword"))
                                         (check (and (integer? (:policy/version pr)) (pos? (:policy/version pr)))
                                                (str "step " (:step/id step) " policy version must be a positive integer"))])))
                              steps)])
        valid? (empty? errors)]
    {:valid? valid?
     :definition-root (:trust-sequence-definition/root definition)
     :errors (when (seq errors) errors)
     :status (if valid? :valid :invalid)}))