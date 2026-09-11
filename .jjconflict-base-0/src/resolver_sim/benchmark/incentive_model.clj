(ns resolver-sim.benchmark.incentive-model
  "Canonical, content-addressed incentive-model.v1 artifacts.

   The model declares the payoff semantics an analysis is scoped to; it does not
   itself establish incentive compatibility or evaluate a strategy space."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "incentive-model.v1")

(def ^:const payoff-interpretations
  #{:net-payoff :expected-net-payoff :terminal-payoff})

(defn- root? [value]
  (hash-ref/valid-sha256-ref? value))

(defn- keyword-vector? [value]
  (and (vector? value) (seq value) (every? keyword? value)
       (= value (vec (sort (distinct value))))))

(defn normalise-model
  "Normalise unordered participant and policy references before hashing.
   Maps retain their canonical-map semantics through `domain-hash`."
  [model]
  (-> model
      (update :incentive-model/participant-roles #(vec (sort (distinct (or % [])))))
      (update :incentive-model/policy-roots #(vec (sort (distinct (or % [])))))))

(defn model-root [model]
  (hash-ref/sha256-ref
   (hc/domain-hash :incentive-model
                   (dissoc (normalise-model model) :incentive-model/root))))

(defn validate-model [model]
  (let [model (normalise-model model)
        errors (cond-> []
                 (not= schema-version (:schema-version model))
                 (conj :unsupported-schema-version)
                 (not (keyword? (:incentive-model/id model)))
                 (conj :invalid-model-id)
                 (not (root? (:incentive-model/subject-root model)))
                 (conj :invalid-subject-root)
                 (not (keyword-vector? (:incentive-model/participant-roles model)))
                 (conj :invalid-participant-roles)
                 (not (contains? payoff-interpretations
                                 (:incentive-model/payoff-interpretation model)))
                 (conj :unsupported-payoff-interpretation)
                 (not (map? (:incentive-model/rewards model)))
                 (conj :invalid-rewards)
                 (not (map? (:incentive-model/penalties model)))
                 (conj :invalid-penalties)
                 (not (map? (:incentive-model/costs model)))
                 (conj :invalid-costs)
                 (not (root? (:incentive-model/evaluator-semantics-root model)))
                 (conj :invalid-evaluator-semantics-root)
                 (not (and (vector? (:incentive-model/policy-roots model))
                           (every? root? (:incentive-model/policy-roots model))))
                 (conj :invalid-policy-roots)
                 (and (:incentive-model/root model)
                      (not= (:incentive-model/root model) (model-root model)))
                 (conj :model-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn model-valid? [model]
  (:valid? (validate-model model)))

(defn build-model
  "Build a validated incentive model and derive its root. A supplied root must
   equal the derived root; callers cannot assert an unverified model identity."
  [fields]
  (let [model (normalise-model (assoc fields :schema-version schema-version))
        validation (validate-model (dissoc model :incentive-model/root))]
    (when-not (:valid? validation)
      (throw (ex-info "Incentive model build failed" validation)))
    (let [root (model-root model)]
      (when (and (:incentive-model/root fields)
                 (not= (:incentive-model/root fields) root))
        (throw (ex-info "Declared incentive model root does not match computed value"
                        {:declared (:incentive-model/root fields) :computed root})))
      (assoc model :incentive-model/root root))))
