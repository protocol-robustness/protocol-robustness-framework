(ns resolver-sim.yield.scenario-commitment
  "Canonical identity for materialized yield scenarios and separate generator provenance.

   These commitments establish content integrity only. They do not assert that
   a scenario is admitted, replayed, economically valid, or authoritative."
  (:require [clojure.string :as str]
            [resolver-sim.contract-model.replay.yield :as replay]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.schema-profile :as schema-profile]))

(def materialized-schema "prf.yield-materialized-scenario.v1")
(def generation-provenance-schema "prf.yield-generation-provenance.v1")

(def ^:private materialized-top-level-fields
  ;; Closed to the known PRF scenario envelope and the yield-specific fields
  ;; used by the current Y01-Y14 fixtures and generated scenarios.
  #{:scenario-id :id :schema-version :seed :title :description :scenario-author
    :scenario-adversary :notes :purpose :scenario-purpose :protocol
    :protocol-params :initial-block-time :agents :yield-config :events :options
    :threat-tags :expectations :expected-outcomes :expected-failures})

(def ^:private non-executable-fields
  ;; IDs and the test.check seed describe how a scenario was named/generated;
  ;; they do not affect yield replay. The seed is committed in provenance.
  #{:scenario-id :id :seed})

(def ^:private provenance-input-fields
  #{:generator/id :generator/version :generator/source-commitment
    :rng/algorithm :rng/seed :generation/options})

(def ^:private provenance-body-fields
  (conj provenance-input-fields :yield-generation/schema :yield-scenario/root))

(def ^:private provenance-artifact-fields
  #{:yield-generation/body :canonical/bytes :canonical/hash})

(defn- fail!
  [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- supported-number?
  [x]
  (or (integer? x)
      (ratio? x)
      (instance? java.math.BigDecimal x)
      (and (instance? Double x) (Double/isFinite ^double x))
      (and (instance? Float x) (Float/isFinite ^float x))))

(defn- validate-source-value!
  "Reject values the existing canonical projection would otherwise coerce
   ambiguously or leave outside its typed canonical domain."
  [x path]
  (cond
    (or (nil? x) (boolean? x) (string? x) (keyword? x) (supported-number? x)) nil
    (number? x) (fail! :yield-scenario/non-finite-number
                       "Yield scenario contains a non-finite or unsupported number"
                       {:path path :value x})
    (record? x) (fail! :yield-scenario/unsupported-value
                       "Records are not accepted in canonical yield scenarios"
                       {:path path :class (.getName (class x))})
    (map? x)
    (let [entries (mapv (fn [[k v]]
                          (validate-source-value! k (conj path :map-key))
                          (validate-source-value! v (conj path k))
                          (hc/project-committable-content k)) x)]
      (when-not (= (count entries) (count (set entries)))
        (fail! :yield-scenario/ambiguous-map-keys
               "Distinct source map keys collapse under canonical projection"
               {:path path}))
      nil)
    (set? x)
    (let [projected (mapv (fn [v]
                            (validate-source-value! v (conj path :set-member))
                            (hc/project-committable-content v)) x)]
      (when-not (= (count projected) (count (set projected)))
        (fail! :yield-scenario/ambiguous-set-members
               "Distinct source set members collapse under canonical projection"
               {:path path}))
      nil)
    (sequential? x) (doseq [[i v] (map-indexed vector x)]
                      (validate-source-value! v (conj path i)))
    :else (fail! :yield-scenario/unsupported-value
                 "Yield scenario contains a value outside the supported canonical domain"
                 {:path path :class (some-> x class .getName)})))

(declare normalized-scenario-value)

(defn- normalized-map-key
  "Match the key coercion performed by scenario.normalize/normalize-map-keys."
  [k]
  (if (string? k)
    (try (Integer/parseInt k)
         (catch Exception _
           (if (and (.startsWith ^String k ":") (> (count k) 1))
             (keyword (subs k 1))
             k)))
    (normalized-scenario-value k)))

(defn- normalized-scenario-value
  "Project the recursive key/value coercions that can merge map or set members."
  [x]
  (cond
    (string? x) (if (and (.startsWith ^String x ":") (> (count x) 1))
                  (keyword (subs x 1))
                  x)
    (map? x) (into {} (map (fn [[k v]]
                             [(normalized-map-key k)
                              (normalized-scenario-value v)])) x)
    (set? x) (into #{} (map normalized-scenario-value) x)
    (sequential? x) (mapv normalized-scenario-value x)
    :else x))

(defn- validate-normalization-key-collisions!
  "Reject map keys that the scenario normalizer would collapse before it can overwrite a value."
  [x path]
  (cond
    (map? x)
    (do
      (doseq [[k v] x]
        (validate-normalization-key-collisions! k (conj path :map-key))
        (validate-normalization-key-collisions! v (conj path k)))
      (let [normalized-keys (mapv (comp normalized-map-key key) x)]
        (when-not (= (count normalized-keys) (count (set normalized-keys)))
          (fail! :yield-scenario/ambiguous-normalized-map-keys
                 "Distinct source map keys collapse under scenario normalization"
                 {:path path}))))
    (set? x)
    (do
      (doseq [[i v] (map-indexed vector x)]
        (validate-normalization-key-collisions! v (conj path i)))
      (let [normalized-members (mapv normalized-scenario-value x)]
        (when-not (= (count normalized-members) (count (set normalized-members)))
          (fail! :yield-scenario/ambiguous-normalized-set-members
                 "Distinct source set members collapse under scenario normalization"
                 {:path path}))))
    (sequential? x)
    (doseq [[i v] (map-indexed vector x)]
      (validate-normalization-key-collisions! v (conj path i)))
    :else nil))

(defn- validated-scenario
  [scenario]
  (when-not (map? scenario)
    (fail! :yield-scenario/invalid-shape "Yield scenario must be a map" {}))
  (validate-normalization-key-collisions! scenario [])
  (let [scenario (normalize/normalize-scenario scenario)
        unknown (seq (remove materialized-top-level-fields (keys scenario)))
        version (:schema-version scenario)]
    (when unknown
      (fail! :yield-scenario/unknown-fields
             "Yield scenario contains unsupported top-level fields"
             {:fields (vec (sort-by str unknown))}))
    (when-not (schema-profile/supported-version? version)
      (fail! :yield-scenario/unsupported-schema
             "Yield scenario schema version is not supported"
             {:schema-version version
              :supported (sort (:supported-versions schema-profile/default-profile))}))
    (when (and (contains? scenario :protocol)
               (not= "yield-v1" (:protocol scenario)))
      (fail! :yield-scenario/unsupported-protocol
             "This commitment profile accepts only yield-v1 scenarios"
             {:protocol (:protocol scenario)}))
    (when-not (and (integer? (:initial-block-time scenario))
                   (not (neg? (:initial-block-time scenario))))
      (fail! :yield-scenario/invalid-clock
             "Canonical yield scenarios require an integer non-negative initial block time"
             {:field :initial-block-time :value (:initial-block-time scenario)}))
    (doseq [[index event] (map-indexed vector (:events scenario))]
      (doseq [field [:seq :time]
              :let [value (get event field)]
              :when (not (and (integer? value) (not (neg? value))))]
        (fail! :yield-scenario/invalid-clock
               "Canonical yield event sequence and time fields must be non-negative integers"
               {:event-index index :field field :value value})))
    (validate-source-value! scenario [])
    (let [validation (replay/validate-yield-scenario scenario)]
      (when-not (:ok validation)
        (fail! :yield-scenario/invalid-replay-input
               "Yield scenario failed the existing replay validator"
               {:validation validation})))
    scenario))

(defn materialized-scenario-body
  "Validate and project the executable materialization.

   The scenario ID aliases and generator seed are omitted. All replay inputs,
   including expectations and protocol configuration, remain committed."
  [scenario]
  (let [scenario (validated-scenario scenario)
        materialization (-> scenario
                            (dissoc :scenario-id :id :seed)
                            (assoc :protocol "yield-v1")
                            hc/project-committable-content)
        body {:yield-scenario/schema materialized-schema
              :yield-scenario/materialization materialization}]
    (hc/validate-canonical-value! body)
    body))

(defn scenario-commitment
  "Return the canonical materialized scenario body, exact typed bytes and root."
  [scenario]
  (let [body (materialized-scenario-body scenario)
        commitment (hc/canonical-commitment :yield-materialized-scenario-v1 body)]
    {:yield-scenario/body body
     :canonical/bytes (:canonical/bytes commitment)
     :canonical/hash (:canonical/hash commitment)}))

(defn- validate-provenance-inputs!
  [inputs]
  (when-not (map? inputs)
    (fail! :yield-provenance/invalid-inputs
           "Generation provenance inputs must be a map" {}))
  (let [missing (seq (remove #(contains? inputs %) provenance-input-fields))
        unknown (seq (remove provenance-input-fields (keys inputs)))]
    (when missing
      (fail! :yield-provenance/missing-fields
             "Generation provenance is missing required inputs"
             {:fields (vec (sort-by str missing))}))
    (when unknown
      (fail! :yield-provenance/unknown-fields
             "Generation provenance contains unsupported fields"
             {:fields (vec (sort-by str unknown))}))
    (doseq [field [:generator/id :generator/version :rng/algorithm]
            :let [value (get inputs field)]
            :when (or (not (string? value)) (str/blank? value))]
      (fail! :yield-provenance/invalid-field
             "Generator identity and RNG algorithm must be non-empty strings"
             {:field field :value (get inputs field)}))
    (when-not (and (string? (:generator/source-commitment inputs))
                   (re-matches #"sha256:[0-9a-f]{64}" (:generator/source-commitment inputs)))
      (fail! :yield-provenance/invalid-source-commitment
             "Generator source commitment must be a lowercase sha256 reference"
             {:value (:generator/source-commitment inputs)}))
    (when-not (and (integer? (:rng/seed inputs))
                   (<= Long/MIN_VALUE (:rng/seed inputs) Long/MAX_VALUE))
      (fail! :yield-provenance/invalid-seed
             "The actual RNG seed must be a signed 64-bit integer"
             {:value (:rng/seed inputs)}))
    (when-not (map? (:generation/options inputs))
      (fail! :yield-provenance/invalid-options
             "Normalized generation options must be a map"
             {:value (:generation/options inputs)}))
    (validate-source-value! (:generation/options inputs) [:generation/options])
    nil))

(defn generation-provenance-body
  "Build the separate provenance statement bound to the computed scenario root."
  [scenario inputs]
  (validate-provenance-inputs! inputs)
  (let [scenario (validated-scenario scenario)
        _ (when (and (contains? scenario :seed)
                     (not= (:seed scenario) (:rng/seed inputs)))
            (fail! :yield-provenance/seed-mismatch
                   "Provenance seed differs from the materialized scenario seed field"
                   {:scenario-seed (:seed scenario) :provenance-seed (:rng/seed inputs)}))
        scenario-root (:canonical/hash (scenario-commitment scenario))
        body (-> (assoc inputs
                        :yield-generation/schema generation-provenance-schema
                        :yield-scenario/root scenario-root)
                 hc/project-committable-content)]
    (when-not (= provenance-body-fields (set (keys body)))
      (fail! :yield-provenance/internal-shape
             "Generation provenance body is not closed"
             {:fields (set (keys body))}))
    (hc/validate-canonical-value! (hc/project-committable-content body))
    body))

(defn generation-provenance-commitment
  "Commit generator identity, source, RNG and normalized options separately
   from executable scenario identity. This does not establish admission or replay."
  [scenario inputs]
  (let [body (generation-provenance-body scenario inputs)
        commitment (hc/canonical-commitment :yield-generation-provenance-v1 body)]
    {:yield-generation/body body
     :canonical/bytes (:canonical/bytes commitment)
     :canonical/hash (:canonical/hash commitment)}))

(defn verify-generation-provenance!
  "Independently recompute both the scenario root and provenance commitment.
   Throws on malformed or substituted artifacts; returns the verified roots."
  [scenario artifact]
  (when-not (and (map? artifact)
                 (= provenance-artifact-fields (set (keys artifact))))
    (fail! :yield-provenance/invalid-artifact
           "Generation provenance artifact has an invalid shape"
           {:fields (when (map? artifact) (set (keys artifact)))}))
  (let [body (:yield-generation/body artifact)
        inputs (dissoc body :yield-generation/schema :yield-scenario/root)
        expected (generation-provenance-commitment scenario inputs)]
    (when-not (= artifact expected)
      (fail! :yield-provenance/commitment-mismatch
             "Generation provenance or bound scenario commitment does not match"
             {:expected-root (:canonical/hash expected)
              :observed-root (:canonical/hash artifact)}))
    {:valid? true
     :scenario-root (:yield-scenario/root body)
     :provenance-root (:canonical/hash expected)}))
