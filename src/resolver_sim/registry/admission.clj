(ns resolver-sim.registry.admission
  "PRF-native registry admission v1.

   This namespace deliberately does not reuse evidence-package admission:
   registry admission answers whether candidate C may occupy binding B for
   canonical scope S under immutable policy P. All decisions are derived from
   committed inputs; caller-supplied decisions, checks, and reasons are ignored."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as canonical]))

(def ^:const candidate-schema :prf/registry-binding-candidate.v1)
(def ^:const policy-schema :prf/registry-admission-policy.v1)
(def ^:const evaluation-schema :prf/registry-admission-evaluation.v1)
(def ^:const release-schema :prf/protocol-registry-release.v1)

(def ^:private candidate-fields
  #{:type :source :contract :deployment :properties :candidate-root})
(def ^:private policy-fields
  #{:type :registry/id :rules})
(def ^:private release-fields
  #{:type :registry/id :registry/parent-root :admission-policy-root :bindings})

(defn- fail! [kind message data]
  (throw (ex-info message (merge {:kind kind} data))))

(defn- root? [x]
  (and (string? x) (boolean (re-matches #"[0-9a-f]{64}" x))))

(defn- closed-shape! [value fields kind]
  (when-not (map? value) (fail! kind "artifact must be a map" {:value value}))
  (let [unknown (set/difference (set (keys value)) fields)]
    (when (seq unknown)
      (fail! kind "artifact contains unknown fields" {:unknown unknown})))
  value)

(defn scope-root
  "Return the canonical root for a scope value. Scope is committed even when
   it is not persisted as a separate top-level artifact in v1."
  [scope]
  (when-not (and (map? scope)
                 (keyword? (:scope/type scope))
                 (map? (:scope/value scope)))
    (fail! :scope-invalid "scope must contain namespaced type and map value" {:scope scope}))
  (canonical/domain-hash "prf.registry-scope.v1" scope))

(defn- candidate-content [candidate]
  (closed-shape! candidate candidate-fields :candidate-invalid))

(defn candidate-root
  "Derive the candidate root from the PRF-native candidate projection."
  [candidate]
  (canonical/domain-hash "prf.registry-binding-candidate.v1" (dissoc (candidate-content candidate) :candidate-root)))

(defn verify-candidate
  "Validate a candidate and its sealed deployment manifest. The manifest is
   supplied as a map for deterministic local evaluation; production loaders
   must resolve it by :source/:deployment-manifest-root before this function."
  [candidate manifest]
  (candidate-content candidate)
  (when-not (= candidate-schema (:type candidate))
    (fail! :candidate-schema-unsupported "unsupported candidate schema" {:type (:type candidate)}))
  (when-not (= (:deployment-manifest-root (:source candidate))
               (:manifest-root manifest))
    (fail! :candidate-manifest-mismatch "candidate does not reference this manifest" {}))
  (when-not (= (candidate-root candidate) (:candidate-root candidate))
    (fail! :candidate-root-invalid "candidate root does not recompute" {}))
  (when-not (= (:chain-id (:contract candidate)) (:chain-id manifest))
    (fail! :candidate-manifest-mismatch "candidate chain differs from manifest" {}))
  true)

(defn build-candidate
  "Build a PRF-native candidate from verified Deployment Ledger facts."
  [{:keys [deployment-manifest-root chain-id address logical-id artifact-root runtime-code-hash local-only?]}]
  (let [candidate {:type candidate-schema
                   :source {:deployment-manifest-root deployment-manifest-root}
                   :contract {:chain-id chain-id :address address}
                   :deployment {:logical-id logical-id
                                :artifact-root artifact-root
                                :runtime-code-hash runtime-code-hash}
                   :properties {:local-only? (boolean local-only?)}}]
    (assoc candidate :candidate-root (candidate-root candidate))))

(defn policy-root [policy]
  (canonical/domain-hash "prf.registry-admission-policy.v1" (closed-shape! policy policy-fields :policy-invalid)))

(defn- rule-for [policy binding-id]
  (some #(when (= binding-id (:binding %)) %) (:rules policy)))

(defn- check [id passed reason]
  {:check id :result (if passed :pass :fail) :reason (when-not passed reason)})

(defn evaluate
  "Derive an admission evaluation from candidate, binding, scope, and policy.
   Caller-provided result/check/reason fields are not accepted as inputs."
  [candidate binding-id scope policy manifest]
  (closed-shape! policy policy-fields :policy-invalid)
  (when-not (= policy-schema (:type policy))
    (fail! :policy-schema-unsupported "unsupported policy schema" {:type (:type policy)}))
  (let [s-root (scope-root scope)
        p-root (policy-root policy)
        c-root (:candidate-root candidate)
        rule (rule-for policy binding-id)
        checks [(check :candidate-root-valid
                       (try (verify-candidate candidate manifest) true
                            (catch clojure.lang.ExceptionInfo _ false))
                       :candidate-root-invalid)
                (check :binding-permitted (some? rule) :binding-not-permitted)
                (check :scope-type-permitted
                       (and rule (contains? (set (:allowed-scope-types rule)) (:scope/type scope)))
                       :scope-type-not-permitted)
                (check :chain-permitted
                       (and rule (contains? (set (:allowed-chain-ids rule)) (:chain-id (:contract candidate))))
                       :chain-not-permitted)
                (check :logical-id-permitted
                       (and rule (contains? (set (:allowed-logical-ids rule)) (get-in candidate [:deployment :logical-id])))
                       :logical-id-not-permitted)
                (check :local-only-policy
                       (or (not (get-in candidate [:properties :local-only?]))
                           (true? (:allow-local-only? rule)))
                       :local-only-not-permitted)]
        passed? (every? #(= :pass (:result %)) checks)
        reasons (vec (keep :reason (remove #(= :pass (:result %)) checks)))]
    {:type evaluation-schema
     :registry/id (:registry/id policy)
     :candidate-root c-root
     :policy-root p-root
     :request {:binding-id binding-id :scope-root s-root}
     :result {:decision (if passed? :admit :reject)
              :checks checks
              :reasons reasons}}))

(defn evaluation-root [evaluation]
  (canonical/domain-hash "prf.registry-admission-evaluation.v1" evaluation))

(defn release-root [release]
  (canonical/domain-hash "prf.protocol-registry-release.v1" (closed-shape! release release-fields :release-invalid)))

(defn validate-release
  "Validate release bindings against their derived evaluations."
  [release evaluations]
  (closed-shape! release release-fields :release-invalid)
  (when-not (= release-schema (:type release))
    (fail! :release-schema-unsupported "unsupported release schema" {:type (:type release)}))
  (when-not (or (nil? (:registry/parent-root release))
                (root? (:registry/parent-root release)))
    (fail! :parent-release-root-invalid "parent release root must be nil or a canonical root" {}))
  (let [keys* (map #(select-keys % [:binding-id :scope-root]) (:bindings release))]
    (when-not (= (count keys*) (count (set keys*)))
      (fail! :duplicate-binding-scope "duplicate binding/scope pair" {})))
  (doseq [binding (:bindings release)]
    (let [evaluation (get evaluations (:admission-evaluation-root binding))]
      (when-not evaluation (fail! :evaluation-missing "release references missing evaluation" {:binding binding}))
      (when-not (and (= :admit (get-in evaluation [:result :decision]))
                     (= (:candidate-root binding) (:candidate-root evaluation))
                     (= (:scope-root binding) (get-in evaluation [:request :scope-root]))
                     (= (:binding-id binding) (get-in evaluation [:request :binding-id]))
                     (= (:admission-policy-root release) (:policy-root evaluation))
                     (= (:registry/id release) (:registry/id evaluation)))
        (fail! :evaluation-mismatch "release binding does not match admitted evaluation" {:binding binding}))))
  true)
