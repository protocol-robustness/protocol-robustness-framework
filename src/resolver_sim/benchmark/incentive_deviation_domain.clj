(ns resolver-sim.benchmark.incentive-deviation-domain
  "Content-addressed declared coverage for an incentive analysis.

   A domain makes the evaluated baseline and deviations inspectable. It never
   upgrades observed evidence into exhaustive mechanism compatibility."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "incentive-deviation-domain.v1")
(def ^:const supported-evaluation-methods #{:observed-single-trace})
(def ^:const coalition-scopes #{:none :declared-coalitions})

(defn- root? [value] (hash-ref/valid-sha256-ref? value))
(defn- canonical-keywords? [value]
  (and (vector? value) (seq value) (every? keyword? value)
       (= value (vec (sort (distinct value))))))

(defn normalise-domain [domain]
  (-> domain
      (update :deviation-domain/participants #(vec (sort (distinct (or % [])))))
      (update :deviation-domain/deviations #(vec (sort (distinct (or % [])))))))

(defn domain-root [domain]
  (hash-ref/sha256-ref
   (hc/domain-hash :incentive-deviation-domain
                   (dissoc (normalise-domain domain) :deviation-domain/root))))

(defn validate-domain [domain]
  (let [domain (normalise-domain domain)
        errors (cond-> []
                 (not= schema-version (:schema-version domain)) (conj :unsupported-schema-version)
                 (not (keyword? (:deviation-domain/id domain))) (conj :invalid-domain-id)
                 (not (root? (:deviation-domain/subject-root domain))) (conj :invalid-subject-root)
                 (not (root? (:deviation-domain/incentive-model-root domain))) (conj :invalid-model-root)
                 (not (keyword? (:deviation-domain/baseline-strategy domain))) (conj :invalid-baseline-strategy)
                 (not (canonical-keywords? (:deviation-domain/participants domain))) (conj :invalid-participants)
                 (not (canonical-keywords? (:deviation-domain/deviations domain))) (conj :invalid-deviations)
                 (not (contains? coalition-scopes (:deviation-domain/coalition-scope domain))) (conj :invalid-coalition-scope)
                 (not (map? (:deviation-domain/constraints domain))) (conj :invalid-constraints)
                 (not (contains? supported-evaluation-methods (:deviation-domain/evaluation-method domain)))
                 (conj :unsupported-evaluation-method)
                 (and (:deviation-domain/root domain)
                      (not= (:deviation-domain/root domain) (domain-root domain)))
                 (conj :domain-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn domain-valid? [domain] (:valid? (validate-domain domain)))

(defn build-domain [fields]
  (let [domain (normalise-domain (assoc fields :schema-version schema-version))
        validation (validate-domain (dissoc domain :deviation-domain/root))]
    (when-not (:valid? validation)
      (throw (ex-info "Incentive deviation domain build failed" validation)))
    (let [root (domain-root domain)]
      (when (and (:deviation-domain/root fields)
                 (not= (:deviation-domain/root fields) root))
        (throw (ex-info "Declared deviation domain root does not match computed value"
                        {:declared (:deviation-domain/root fields) :computed root})))
      (assoc domain :deviation-domain/root root))))

(defn evidence-class [domain]
  (case (:deviation-domain/evaluation-method domain)
    :observed-single-trace :evidence/observed-single-trace
    :evidence/unsupported))
