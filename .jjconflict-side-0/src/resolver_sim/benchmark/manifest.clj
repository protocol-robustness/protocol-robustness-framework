(ns resolver-sim.benchmark.manifest
  "Rooted benchmark-manifest.v1 artifacts.

   The artifact commits the application-defined benchmark body. Framework code
   validates and executes it but does not assign a default or interpret which
   requirements an application ought to select."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "benchmark-manifest.v1")

(defn manifest-root
  [manifest]
  (hash-ref/sha256-ref
   (hc/domain-hash :benchmark-manifest
                   (dissoc manifest :benchmark-manifest/root))))

(defn validate-research-binding
  "Validate selection-only binding to a frozen research definition."
  [binding frozen]
  (let [measure-ids (:measure-ids binding)
        known (set (map :measure/id (get-in frozen [:research-definition :research/measures])))]
    (cond-> []
      (not (hash-ref/valid-sha256-ref? (:research-definition/root binding)))
      (conj :benchmark-research/invalid-definition-root)
      (not (and (vector? measure-ids) (seq measure-ids) (every? qualified-keyword? measure-ids)))
      (conj :benchmark-research/invalid-measure-ids)
      (and (vector? measure-ids) (not= (count measure-ids) (count (set measure-ids))))
      (conj :benchmark-research/duplicate-measure-ids)
      (and frozen (not= (:research-definition/root binding) (:research-definition/root frozen)))
      (conj :benchmark-research/definition-root-mismatch)
      (and frozen (some #(not (contains? known %)) measure-ids))
      (conj :benchmark-research/unknown-measure-id))))

(defn selected-research-measures
  "Resolve selected measures from the exact frozen definition named by a binding."
  [binding frozen]
  (let [errors (validate-research-binding binding frozen)]
    (when (seq errors)
      (throw (ex-info "Invalid benchmark research binding" {:errors errors})))
    (let [selected (set (:measure-ids binding))]
      (filterv #(contains? selected (:measure/id %))
               (get-in frozen [:research-definition :research/measures])))))

(defn validate-outcome-policy
  "Validate the small, application-owned all-of policy vocabulary."
  [manifest]
  (let [policy (:benchmark/outcome-policy manifest)
        inputs (:policy/inputs policy)
        research-selected (set (get-in manifest [:benchmark/research-binding :measure-ids]))
        input-key (fn [input]
                    (case (:input/kind input)
                      :research-requirements [:research-requirements (vec (:measure-ids input))]
                      [(:input/kind input) (:input/selection input)]))]
    (cond-> []
      (and policy (not= :all-of (:policy/kind policy)))
      (conj :benchmark-outcome/unsupported-policy-kind)
      (and policy (not (and (vector? inputs) (seq inputs))))
      (conj :benchmark-outcome/invalid-inputs)
      (and (vector? inputs)
           (some #(not (contains? #{:scenario-results :claim-results :research-requirements}
                                  (:input/kind %))) inputs))
      (conj :benchmark-outcome/unknown-input-kind)
      (and (vector? inputs)
           (some #(and (contains? #{:scenario-results :claim-results} (:input/kind %))
                       (not= :all (:input/selection %))) inputs))
      (conj :benchmark-outcome/invalid-result-selection)
      (and (vector? inputs)
           (some #(and (= :research-requirements (:input/kind %))
                       (not (and (vector? (:measure-ids %))
                                 (seq (:measure-ids %))
                                 (every? qualified-keyword? (:measure-ids %))))) inputs))
      (conj :benchmark-outcome/invalid-research-selection)
      (and (vector? inputs) (not= (count inputs) (count (set (map input-key inputs)))))
      (conj :benchmark-outcome/duplicate-input-selection)
      (and (vector? inputs)
           (some #(and (= :research-requirements (:input/kind %))
                       (not-every? research-selected (:measure-ids %))) inputs))
      (conj :benchmark-outcome/research-input-not-selected))))

(defn validate-manifest
  "Validate a rooted benchmark manifest without imposing application policy."
  [manifest]
  (let [errors (cond-> []
                 (not (map? manifest)) (conj :benchmark-manifest/not-a-map)
                 (and (map? manifest)
                      (not= schema (:benchmark-manifest/schema manifest)))
                 (conj :benchmark-manifest/unsupported-schema)
                 (and (map? manifest) (not (qualified-keyword? (:benchmark/id manifest))))
                 (conj :benchmark-manifest/missing-or-invalid-id)
                 (and (map? manifest)
                      (:benchmark/research-binding manifest)
                      (seq (validate-research-binding (:benchmark/research-binding manifest) nil)))
                 (conj :benchmark-manifest/invalid-research-binding)
                 (and (map? manifest)
                      (:benchmark/outcome-policy manifest)
                      (seq (validate-outcome-policy manifest)))
                 (conj :benchmark-manifest/invalid-outcome-policy)
                 (and (map? manifest)
                      (not (hash-ref/valid-sha256-ref? (:benchmark-manifest/root manifest))))
                 (conj :benchmark-manifest/invalid-root)
                 (and (map? manifest)
                      (not= (:benchmark-manifest/root manifest) (manifest-root manifest)))
                 (conj :benchmark-manifest/root-mismatch))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-manifest
  "Root an application-authored benchmark body."
  [body]
  (let [manifest (assoc body :benchmark-manifest/schema schema)
        rooted (assoc manifest :benchmark-manifest/root (manifest-root manifest))
        validation (validate-manifest rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "invalid benchmark manifest" validation)))
    rooted))

(defn rooted-manifest?
  [manifest]
  (contains? manifest :benchmark-manifest/schema))

(defn body
  "Return the application benchmark body after verifying a rooted artifact."
  [manifest]
  (when-not (:valid? (validate-manifest manifest))
    (throw (ex-info "invalid rooted benchmark manifest"
                    (validate-manifest manifest))))
  (dissoc manifest :benchmark-manifest/schema :benchmark-manifest/root))
