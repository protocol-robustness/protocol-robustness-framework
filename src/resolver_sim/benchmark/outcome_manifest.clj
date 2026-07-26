(ns resolver-sim.benchmark.outcome-manifest
  "Canonical benchmark outcome manifest.
   
   The outcome hash is computed from model-relative results and excludes
   researcher identity, signature, timestamp, runner identity and
   environment identity — enabling cross-researcher outcome equality.
   
   Execution fields separate sampling identity into four distinct roots:
     parameter-domain-root         — declared parameter bounds/domain
     sampling-policy-root          — generator, selection policy, seed schedule
     realised-parameter-set-root   — exact parameter values used per case
     generated-case-set-root       — the generated case identifiers
   
   Comparison predicates distinguish exact replication, independent
   sampling and model-level corroboration. All predicates are symmetric."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "benchmark-outcome.v1")

(def ^:const execution-statuses
  "Controlled vocabulary for execution/status."
  #{:completed :partial :failed})

(defn build-manifest
  "Build a canonical benchmark outcome manifest.
   
   Required:
     benchmark/content-root       — semantic content root of the benchmark model
     benchmark/model-root         — root hash of the research-benchmark-model.v1
     execution/status             — :completed | :partial | :failed
   
   Optional:
     benchmark/evaluation-policy-root
     execution/parameter-domain-root
     execution/sampling-policy-root
     execution/realised-parameter-set-root
     execution/generated-case-set-root
     results/operational, results/incentives, results/claims
     evidence/semantic-commitments
     execution/model-instance-root, execution/plan-root
     results/model-coverage-root"
  [{:keys [benchmark/content-root
           benchmark/model-root
           benchmark/evaluation-policy-root
           execution/status
           execution/model-instance-root
           execution/plan-root
           execution/parameter-domain-root
           execution/sampling-policy-root
           execution/realised-parameter-set-root
           execution/generated-case-set-root
           results/operational
           results/incentives
           results/claims
           results/model-coverage-root
           evidence/semantic-commitments]}]
  (let [base {:schema-version schema-version
              :benchmark/content-root content-root
              :benchmark/model-root model-root
              :benchmark/evaluation-policy-root evaluation-policy-root
              :execution/status status
              :execution/model-instance-root model-instance-root
              :execution/plan-root plan-root
              :execution/parameter-domain-root parameter-domain-root
              :execution/sampling-policy-root sampling-policy-root
              :execution/realised-parameter-set-root realised-parameter-set-root
              :execution/generated-case-set-root generated-case-set-root
              :results/operational (or operational {})
              :results/incentives (or incentives {})
              :results/claims (or claims {})
              :results/model-coverage-root model-coverage-root
              :evidence/semantic-commitments (or semantic-commitments {})}
        outcome-hash (str "sha256:"
                          (hc/domain-hash :benchmark-outcome base))]
    (assoc base :benchmark-outcome/hash outcome-hash)))

(defn outcome-hash
  "Return the outcome-hash from a benchmark-outcome manifest."
  [manifest]
  (:benchmark-outcome/hash manifest))

(defn manifest-valid?
  "Structural validity check for a benchmark outcome manifest."
  [manifest]
  (and (= schema-version (:schema-version manifest))
       (some? (:benchmark/content-root manifest))
       (some? (:benchmark/model-root manifest))
       (some? (:benchmark-outcome/hash manifest))
       (let [without-hash (dissoc manifest :benchmark-outcome/hash)
             computed (str "sha256:" (hc/domain-hash :benchmark-outcome without-hash))]
         (= computed (:benchmark-outcome/hash manifest)))))

;; ── Comparison predicates (all symmetric) ─────────────────────────────────

(defn exact-replication-scope?
  "True when two manifests are from the same exact execution scope.
   Required for direct byte-identical outcome-hash comparison."
  [a b]
  (and (= (:benchmark/content-root a) (:benchmark/content-root b))
       (= (:benchmark/model-root a) (:benchmark/model-root b))
       (= (:execution/model-instance-root a) (:execution/model-instance-root b))
       (= (:execution/plan-root a) (:execution/plan-root b))
       (= (:execution/parameter-domain-root a) (:execution/parameter-domain-root b))
       (= (:execution/sampling-policy-root a) (:execution/sampling-policy-root b))
       (= (:execution/realised-parameter-set-root a) (:execution/realised-parameter-set-root b))
       (= (:execution/generated-case-set-root a) (:execution/generated-case-set-root b))
       (= (:benchmark/evaluation-policy-root a) (:benchmark/evaluation-policy-root b))
       (= (:schema-version a) (:schema-version b))))

(defn sampling-comparison-scope?
  "True when two manifests share the same model, parameter domain and
   sampling policy but generated independent case sets.
   
   Outcomes should be compared by claim pass rates, confidence bounds,
   failure classes and incentive findings — not byte-identical hashes."
  [a b]
  (and (= (:benchmark/content-root a) (:benchmark/content-root b))
       (= (:execution/parameter-domain-root a) (:execution/parameter-domain-root b))
       (= (:execution/sampling-policy-root a) (:execution/sampling-policy-root b))
       (not= (:execution/generated-case-set-root a) (:execution/generated-case-set-root b))))

(defn related-model-scope?
  "True when two manifests share the same primary model root but may
   differ in any other component. Outcomes must not be grouped as
   directly replicated results."
  [a b]
  (= (:benchmark/model-root a) (:benchmark/model-root b)))

(defn classify-outcome-compatibility
  "Classify the comparison scope between two outcome manifests.
   Symmetric: (classify-outcome-compatibility a b) == (classify b a).
   
   Returns :exact-replication | :independent-sampling | :model-corroboration
           | :incompatible-scope"
  [a b]
  (cond
    (exact-replication-scope? a b) :exact-replication
    (sampling-comparison-scope? a b) :independent-sampling
    (and (related-model-scope? a b)
         (some? (:benchmark/evaluation-policy-root a))
         (some? (:benchmark/evaluation-policy-root b))) :model-corroboration
    :else :incompatible-scope))

(defn compatible-outcomes?
  "Boolean wrapper: true when two manifests are in the same comparison scope
   (any level except :incompatible-scope)."
  [a b]
  (not= :incompatible-scope
        (classify-outcome-compatibility a b)))

(defn pre-application-checks
  "Pre-application validation: verify that an outcome manifest is valid
   and ready for benchmark execution.
   
   Checks that must pass BEFORE a benchmark run can proceed:
     1. Schema version is recognised
     2. Content root is present (model identity)
     3. Model root is present (model version pinning)
     4. Evaluation policy root is present (scoring policy)
     5. Execution fields are present (parameter domain, sampling policy,
        generated case set — required for execution traceability)
     6. Status is set (benchmark lifecycle state)
   
   Unlike validate-manifest (post-hoc), this does NOT require a
   pre-computed outcome-hash — the hash is a result, not a precondition.
   
   Returns {:pre-application-valid? bool :errors [string]}."
  [manifest]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version manifest))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version manifest))))
    (when-not (some? (:benchmark/content-root manifest))
      (swap! errors conj "missing :benchmark/content-root"))
    (when-not (some? (:benchmark/model-root manifest))
      (swap! errors conj "missing :benchmark/model-root"))
    (when-not (some? (:benchmark/evaluation-policy-root manifest))
      (swap! errors conj "missing :benchmark/evaluation-policy-root (required for scoring)"))
    (when-not (some? (:execution/parameter-domain-root manifest))
      (swap! errors conj "missing :execution/parameter-domain-root"))
    (when-not (some? (:execution/sampling-policy-root manifest))
      (swap! errors conj "missing :execution/sampling-policy-root"))
    (when-not (some? (:execution/generated-case-set-root manifest))
      (swap! errors conj "missing :execution/generated-case-set-root"))
    (let [exec-st (:execution/status manifest)]
      (when-not exec-st
        (swap! errors conj "missing :execution/status"))
      (when (and exec-st (not (contains? execution-statuses exec-st)))
        (swap! errors conj (str "invalid execution/status: " exec-st))))
    {:pre-application-valid? (empty? @errors) :errors @errors}))

(defn cross-artifact-roots-consistent?
  "Pre-condition: verify that a registry entry and outcome manifest
   agree on content-root and model-root.
   
   Returns {:consistent? bool :mismatches [{:field field :entry-value v :manifest-value v}]}."
   [registry-entry outcome-manifest]
  (let [pairs [[:benchmark/content-root :benchmark/content-root]
               [:benchmark/model-root :benchmark/model-root]
               [:benchmark/evaluation-policy-root :benchmark/evaluation-policy-root]]
        mismatches (vec (keep (fn [[entry-key manifest-key]]
                                (let [ev (get registry-entry entry-key)
                                      mv (get outcome-manifest manifest-key)]
                                  (when (not= ev mv)
                                    {:field entry-key
                                     :entry-value ev
                                     :manifest-value mv})))
                              pairs))]
    {:consistent? (empty? mismatches) :mismatches mismatches}))

(defn validate-manifest
  "Standalone validator for a loaded outcome manifest.
   Recomputes the outcome hash, checks required fields, and returns
   structured errors.
   
   Returns {:valid? bool :errors [string]}."
  [manifest]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version manifest))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version manifest))))
    (when-not (some? (:benchmark/content-root manifest))
      (swap! errors conj "missing :benchmark/content-root"))
    (when-not (some? (:benchmark/model-root manifest))
      (swap! errors conj "missing :benchmark/model-root"))
    (when (some? (:benchmark-outcome/hash manifest))
      (let [without-hash (dissoc manifest :benchmark-outcome/hash)
            computed (str "sha256:" (hc/domain-hash :benchmark-outcome without-hash))]
        (when-not (= computed (:benchmark-outcome/hash manifest))
          (swap! errors conj (str "outcome-hash mismatch: declared "
                                  (:benchmark-outcome/hash manifest)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn semantic-commitment
  "Lookup a semantic commitment from the evidence section by key."
  [manifest key]
  (get-in manifest [:evidence/semantic-commitments key]))
