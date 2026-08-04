(ns resolver-sim.composition.contract
  "The composition contract: a local, versioned declaration of how ONE
   capability may participate in a composition.

   It is NOT proof that an entire multi-capability graph is valid. It defines
   the capability's admissible pipeline value inputs and outputs, roles,
   execution modes, effects, control behaviour, determinism requirements,
   adapter policy, and verification obligations. Graph-wide validity is the
   responsibility of the composition compiler.

   The contract is content-addressed (:composition-contract-root) and is bound
   into capability descriptor roots and compiled plans. Fields are optional in
   the authored form with documented defaults; the compiler normalises to the
   canonical form before hashing and comparison."
  (:require [resolver-sim.hash.canonical :as hc]))

(def contract-version
  1)

(def contract-domain-tag
  "COMPOSITION_CONTRACT_V1")

;; ── supported value domains ───────────────────────────────────────────────

(def supported-modes
  "Execution modes understood by the initial compiler."
  #{:sequential})

(def supported-merge-strategies
  #{:accumulate :replace :partition})

(def supported-failure-modes
  "Failure propagation semantics. v1 supports :abort (fail-closed)."
  #{:abort :continue})

(def supported-cardinalities
  #{:one :many})

;; ── canonical form ────────────────────────────────────────────────────────

(def contract-projection-fields
  [:composition-contract/version
   :composition/input
   :composition/output
   :composition/roles
   :composition/modes
   :composition/effects
   :composition/control
   :composition/determinism
   :composition/adapters
   :composition/verification])

(defn contract-projection
  "Committed fields of a composition contract (canonical, closed)."
  [contract]
  (select-keys contract contract-projection-fields))

(defn composition-contract-root
  "Content-addressed root of the canonical composition contract."
  [contract]
  (hc/domain-hash contract-domain-tag (contract-projection contract)))

;; ── defaults ──────────────────────────────────────────────────────────────

(defn default-effects
  []
  {:emits #{}
   :merge-strategy :accumulate
   :exclusive-effects #{}})

(defn default-control
  []
  {:terminal? false
   :may-short-circuit? false
   :failure-mode :abort})

(defn default-determinism
  []
  {:required? true
   :context-reads #{}
   :external-reads #{}})

(defn default-adapters
  []
  {:accepted #{}
   :implicit? false})

(defn default-verification
  []
  {:intermediate-output-committed? true
   :evidence-contract-ref nil})

(defn normalize-contract
  "Fill defaults to the canonical contract form."
  [{:keys [composition-contract/version :composition/roles :composition/modes]
    :as contract}]
  (-> (or contract {})
      (assoc :composition-contract/version (or version contract-version))
      (update :composition/input (fn [v] (merge {:schema-ref nil :semantic-type nil :cardinality :one} v)))
      (update :composition/output (fn [v] (merge {:schema-ref nil :semantic-type nil :cardinality :one} v)))
      (assoc :composition/roles (set (or roles #{})))
      (assoc :composition/modes (set (or modes #{:sequential})))
      (update :composition/effects (fn [v] (merge (default-effects) v)))
      (update :composition/control (fn [v] (merge (default-control) v)))
      (update :composition/determinism (fn [v] (merge (default-determinism) v)))
      (update :composition/adapters (fn [v] (merge (default-adapters) v)))
      (update :composition/verification (fn [v] (merge (default-verification) v)))))

;; ── validation ────────────────────────────────────────────────────────────

(defn- check-value-contract
  [violations field value]
  (cond
    (nil? value)
    (conj violations {:violation/id :violation/missing-composition-value-contract
                      :details {:field field}})

    (not (map? value))
    (conj violations {:violation/id :violation/invalid-composition-value-contract
                      :details {:field field :value value}})

    :else
    (let [schema-ref (:schema-ref value)
          semantic (:semantic-type value)
          cardinality (:cardinality value :one)]
      (cond-> violations
        (not (keyword? schema-ref))
        (conj {:violation/id :violation/malformed-schema-reference
               :details {:field field :schema-ref schema-ref}})

        (not (keyword? semantic))
        (conj {:violation/id :violation/missing-composition-semantic-type
               :details {:field field :semantic-type semantic}})

        (not (contains? supported-cardinalities cardinality))
        (conj {:violation/id :violation/unsupported-composition-cardinality
               :details {:field field :cardinality cardinality
                         :supported (vec supported-cardinalities)}})))))

(defn validate-composition-contract
  "Validate a composition contract structurally.
   Returns {:valid? bool, :violations [violation-maps]}."
  [contract]
  (if-not (map? contract)
    {:valid? false
     :violations [{:violation/id :violation/non-map-composition-contract
                   :details {:contract contract}}]}
    (let [known (set contract-projection-fields)
          keys (set (keys contract))
          unknown (vec (remove known keys))
          v (cond-> []
              (not= contract-version (:composition-contract/version contract))
              (conj {:violation/id :violation/invalid-composition-contract-version
                     :details {:version (:composition-contract/version contract)
                               :supported contract-version}})
              (seq unknown)
              (conj {:violation/id :violation/unknown-composition-contract-key
                     :details {:unknown (vec (sort unknown))}}))
          v (into v (check-value-contract [] :composition/input (:composition/input contract)))
          v (into v (check-value-contract [] :composition/output (:composition/output contract)))
          roles (:composition/roles contract)
          v (if (and (some? roles) (not (set? roles)))
              (conj v {:violation/id :violation/invalid-composition-roles
                       :details {:roles roles}})
              v)
          modes (:composition/modes contract)
          v (if (and (some? modes) (not (set? modes)))
              (conj v {:violation/id :violation/invalid-composition-modes
                       :details {:modes modes}})
              v)
          effects (:composition/effects contract)
          v (if (and (some? effects) (not (map? effects)))
              (conj v {:violation/id :violation/invalid-composition-effects
                       :details {:effects effects}})
              (if (and (map? effects)
                       (not (contains? supported-merge-strategies
                                       (:merge-strategy effects :accumulate))))
                (conj v {:violation/id :violation/unsupported-effect-merge-strategy
                         :details {:merge-strategy (:merge-strategy effects)
                                   :supported (vec supported-merge-strategies)}})
                v))
          control (:composition/control contract)
          v (if (and (map? control)
                     (not (contains? supported-failure-modes
                                     (:failure-mode control :abort))))
              (conj v {:violation/id :violation/unsupported-failure-mode
                       :details {:failure-mode (:failure-mode control)
                                 :supported (vec supported-failure-modes)}})
              v)
          adapters (:composition/adapters contract)
          v (if (and (map? adapters)
                     (not (boolean? (:implicit? adapters false))))
              (conj v {:violation/id :violation/invalid-implicit-adapter-flag
                       :details {:implicit? (:implicit? adapters)}})
              v)]
      {:valid? (empty? v)
       :violations (vec v)})))
