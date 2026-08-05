(ns resolver-sim.economics.schemas
  "Explicit input/output contract schemas for the economics capability kinds,
   plus a lightweight structural conformance check.

   A schema is a data map whose keys map to expected type tags
   (:integer, :integer-or-nil, :map, :map-or-nil, :vector, :string, :keyword,
   :symbol, :boolean, :nil, :any, :opaque) or to nested schema maps.
   Schema roots are content-addressed so capability contracts can be pinned;
   :core-schemas feeds the :schemas option of extension resolution so the
   committed :extensions/resolution-root reflects the exact contract set.

   This is the conformance-kit seed (ADR-0005 Phase 3): structural
   verification of extension-backed invocations. It detects schema and
   type-shape violations; it does not prove arbitrary-code safety."
  (:require [resolver-sim.extensions.execution :as ext-exec]
            [resolver-sim.hash.canonical :as hc]))

(def schema-domain-tag
  "PRF_CONTRACT_SCHEMA_V1")

;; ── core contract schemas ─────────────────────────────────────────────────

(def award-amount-context
  {:gross-amount :integer
   :amount-spec :map
   :param-values :map
   :resolved-award :map})

(def calculation-result
  {:amount :integer-or-nil
   :calculation :map-or-nil})

(def allocation-context
  {:gross-amount :integer
   :allocation-spec :map})

(def allocation-result
  {:base-allocations :map})

(def funding-context
  {:award-amount :integer
   :funding-spec :map})

(def funding-result
  {:funding :map})

(def eligibility-context
  "Input contract for an :economics/eligibility capability under the
   with-bounty composition: committed base result plus the event context the
   eligibility decision may read."
  {:event/context :map
   :base/result :map})

(def eligibility-result
  "Result contract for an :economics/eligibility capability. An ineligible
   result is not an error; it records :ineligible and a reason."
  {:result/classification :keyword
   :result/value :boolean
   :result/domain-evidence :map-or-nil})

(def with-bounty-amount-context
  "Input contract for a with-bounty award-amount capability: the committed
   base result projection the amount may reference plus the parameter values
   declared by the policy. Distinct from :prf/award-amount-context.v1, which
   is the slash-distribution award-amount contract."
  {:base/result :map
   :param-values :map})

(def schema-maps
  "Schema-id -> schema map for the core economics contracts."
  {:prf/award-amount-context.v1 award-amount-context
   :prf/calculation-result.v1 calculation-result
   :prf/allocation-context.v1 allocation-context
   :prf/allocation-result.v1 allocation-result
   :prf/funding-context.v1 funding-context
   :prf/funding-result.v1 funding-result
   :prf/eligibility-context.v1 eligibility-context
   :prf/eligibility-result.v1 eligibility-result
   :prf/with-bounty-amount-context.v1 with-bounty-amount-context})

(defn schema-root
  "Content-addressed root of a schema map."
  [schema]
  (hc/domain-hash schema-domain-tag schema))

(def core-schemas
  "Schema-id -> content-addressed root, used by extension resolution."
  (into {} (map (fn [[id m]] [id (schema-root m)])) schema-maps))

;; ── structural conformance ────────────────────────────────────────────────

(declare valid-against-schema?)

(defn- tag-matches?
  [tag v]
  (cond
    (map? tag) (and (map? v) (valid-against-schema? tag v))
    :else (case tag
            :any true
            :nil (nil? v)
            :integer (integer? v)
            :integer-or-nil (or (nil? v) (integer? v))
            :map (map? v)
            :map-or-nil (or (nil? v) (map? v))
            :vector (vector? v)
            :vector-or-nil (or (nil? v) (vector? v))
            :string (string? v)
            :keyword (keyword? v)
            :symbol (symbol? v)
            :boolean (boolean? v)
            :opaque true
            false)))

(defn- valid-against-schema?
  [schema v]
  (and (map? v)
       (every? #(contains? v %) (keys schema))
       (every? (fn [k] (tag-matches? (get schema k) (get v k))) (keys schema))))

(defn validate-against-schema
  "Validate a value against a schema map. Returns {:valid? bool
   :violations [...]}."
  [schema v]
  (if (not (map? v))
    {:valid? false
     :violations [{:violation/id :violation/not-a-map
                   :details {:value v}}]}
    (let [missing (vec (remove #(contains? v %) (keys schema)))
          bad-type (mapv (fn [k]
                           {:key k :value (get v k) :expected (get schema k)})
                         (filter (fn [k] (not (tag-matches? (get schema k)
                                                            (get v k))))
                                 (keys schema)))]
      (cond-> {:valid? (and (empty? missing) (empty? bad-type))
               :violations []}
        (seq missing)
        (update :violations conj
                {:violation/id :violation/missing-schema-key
                 :details {:missing missing}})

        (seq bad-type)
        (update :violations conj
                {:violation/id :violation/schema-type-mismatch
                 :details {:bad-type bad-type}})))))

(defn conformance-check
  "Structural verification of a capability invocation:
   1. validate the input context against the capability's :input-schema;
   2. invoke the capability (via invoke-fn);
   3. validate the result against the capability's :output-schema.

   Schemas are looked up in schema-maps (default core). A capability that
   declares an unknown schema is reported as :violation/unknown-schema.

   Returns {:valid? bool :violations [...] :result <result-or-nil>}.
   This is structural verification — it does not prove independent
   correctness and must not be labelled as such."
  ([entry input] (conformance-check entry ext-exec/invoke-capability input))
  ([entry invoke-fn input]
   (let [cap (:capability entry)
         in-schema (get schema-maps (:input-schema cap))
         out-schema (get schema-maps (:output-schema cap))
         unknown (into []
                       (keep (fn [id]
                               (when (and id (not (contains? schema-maps id)))
                                 id)))
                       [(:input-schema cap) (:output-schema cap)])
         in-violations (when in-schema
                         (:violations (validate-against-schema in-schema input)))
         result (when (and in-schema (empty? in-violations))
                  (invoke-fn entry input))
         out-violations (when (and out-schema result)
                          (:violations (validate-against-schema out-schema result)))]
     {:valid? (and (empty? unknown)
                   (empty? in-violations)
                   (empty? out-violations))
      :violations (vec (concat
                        (map (fn [id]
                               {:violation/id :violation/unknown-schema
                                :details {:schema-id id}})
                             unknown)
                        (when in-schema in-violations)
                        (when out-schema out-violations)))
      :result result})))
