(ns resolver-sim.benchmark.outcome-manifest
  "Canonical benchmark outcome manifest.

   The outcome layer answers three distinct questions:
     What happened?       → execution results (execution section)
     What was established? → theorem outcomes and concentrated conclusions
     Why does the evidence support that? → premises, inference, falsifiers

   Hierarchy:
     execution results → theorem outcomes → concentrated conclusions → manifest

   Singular outcome-hash:
     Commits to benchmark content root, execution identity, theorem outcome
     hashes, and conclusion root. Two researchers only share the same
     outcome-hash when they reached the same canonical theorem results and
     conclusions over the same execution scope.

   Plural outcome-hashes:
     Independently addressable results per theorem and conclusion — a
     researcher can reproduce one theorem while challenging another without
     disputing the entire outcome.

   Execution fields:
     parameter-domain-root         — declared parameter bounds/domain
     sampling-policy-root          — generator, selection policy, seed schedule
     realised-parameter-set-root   — exact parameter values used per case
     generated-case-set-root       — the generated case identifiers
     command-root                  — structured execution provenance

   Comparison predicates distinguish exact replication, independent
   sampling and model-level corroboration. All predicates are symmetric."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.research-theorem-outcome :as theorem]
            [resolver-sim.benchmark.research-conclusion :as conclusion]))

(def ^:const schema-version "benchmark-outcome.v1")

(def ^:const execution-statuses
  "Controlled vocabulary for execution/status."
  #{:completed :partial :failed})

(def ^:private hash-excluded-keys
  "Keys excluded from the singular outcome hash projection.

   Mirrored top-level fields are committed via :outcome-hashes
   (the canonical commitment map), not individually. The
   :outcome-hashes map itself IS included in the projection.
   :benchmark-outcome/hash is self-excluded."
  [:execution/command-root :outcomes/operational-root
   :outcomes/incentive-root :outcomes/incentive-compatibility-root
   :outcomes/theorems :outcomes/conclusions
   :benchmark-outcome/hash])

(def ^:const valid-manifest-keys
  "Known top-level keys in a canonical benchmark-outcome.v1 manifest.
   Any key outside this set is rejected by validate-manifest."
  #{:schema-version
    :benchmark/content-root :benchmark/model-root
    :benchmark/evaluation-policy-root
    :execution/status
    :execution/model-instance-root :execution/plan-root
    :execution/parameter-domain-root :execution/sampling-policy-root
    :execution/realised-parameter-set-root
    :execution/generated-case-set-root
    :execution/command-root :execution/force-authorisation
    :results/operational :results/incentives :results/claims
    :results/model-coverage-root
    :evidence/semantic-commitments
    :outcomes/operational-root :outcomes/incentive-root
    :outcomes/incentive-compatibility-root
    :outcomes/theorems :outcomes/conclusions
    :outcome-hashes :benchmark-outcome/hash})

(defn- hash-projection
  "Return the map committed by the singular outcome hash.
   Excludes mirrored top-level fields (carried in :outcome-hashes)
   and the self-hash field."
  [manifest]
  (apply dissoc manifest hash-excluded-keys))

(defn derive-outcome-hashes
  "Derive the canonical commitment projection from a manifest's
   top-level hierarchical fields.

   This is the sole authoritative source for :outcome-hashes — the
   parent hash commits to this map.  Callers must not supply an
   :outcome-hashes key independently; build-manifest derives it.
   validate-manifest asserts exact equality.

   Returns nil when no hierarchical fields are present."
  [manifest]
  (let [entries (cond-> {}
                  (:execution/command-root manifest)
                  (assoc :command-root
                         (:execution/command-root manifest))

                  (:outcomes/operational-root manifest)
                  (assoc :operational-root
                         (:outcomes/operational-root manifest))

                  (:outcomes/incentive-root manifest)
                  (assoc :incentive-root
                         (:outcomes/incentive-root manifest))

                  (:outcomes/incentive-compatibility-root manifest)
                  (assoc :incentive-compatibility-root
                         (:outcomes/incentive-compatibility-root manifest))

                  (:outcomes/theorems manifest)
                  (assoc :theorem-root
                         (theorem/theorem-outcome-collective-hash
                          (:outcomes/theorems manifest)))

                  (:outcomes/conclusions manifest)
                  (assoc :conclusion-root
                         (conclusion/conclusion-collective-hash
                          (:outcomes/conclusions manifest))))]
    (when (seq entries) entries)))

(defn build-manifest
  "Build a canonical benchmark outcome manifest.

   Required:
     benchmark/content-root       — semantic content root of the benchmark model
     benchmark/model-root         — root hash of the research-benchmark-model.v1
     execution/status             — :completed | :partial | :failed

   Optional (legacy flat results — kept for backward compatibility):
     benchmark/evaluation-policy-root
     execution/parameter-domain-root
     execution/sampling-policy-root
     execution/realised-parameter-set-root
     execution/generated-case-set-root
     results/operational, results/incentives, results/claims
     evidence/semantic-commitments
     execution/model-instance-root, execution/plan-root
     results/model-coverage-root

   Optional (hierarchical outcome structure):
     execution/command-root       — research-command.v1 hash
     outcomes/operational-root    — operational outcome hash
     outcomes/incentive-root      — incentive outcome hash
     outcomes/incentive-compatibility-root — incentive-compatibility theorem hash
     outcomes/theorems            — [{:theorem/id kw :theorem/hash sha256 :status kw}]
     outcomes/conclusions         — [{:conclusion/id kw :conclusion/hash sha256}]

   Optional (force-authorisation execution binding):
     execution/force-authorisation — map:
       {:authorisation-hash sha256
        :consumption-key sha256
        :reservation-hash sha256
        :execution-attempt-id kw
        :branch-descriptor-hash sha256
        :baseline-content-root sha256
        :executed-content-root sha256
        :status :consumed}  — references pre-execution reservation, not
                              post-execution consumption receipt (acyclic)

   When hierarchical fields are provided, the outcome hash commits to them
   alongside the execution identity. Plural outcome-hashes are computed
   from the theorems and conclusions collections."
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
           execution/command-root
           results/operational
           results/incentives
           results/claims
           results/model-coverage-root
           evidence/semantic-commitments
           outcomes/operational-root
           outcomes/incentive-root
           outcomes/incentive-compatibility-root
           outcomes/theorems
           outcomes/conclusions
           execution/force-authorisation]}]
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
        ;; ── Execution command root (optional) ───────────────────────
        base (if (some? command-root)
               (assoc base :execution/command-root command-root)
               base)
        ;; ── Force-authorisation (optional) ──────────────────────────
        base (if (some? force-authorisation)
               (assoc base :execution/force-authorisation force-authorisation)
               base)
        ;; ── Hierarchical outcome roots (independently optional) ─────
        base (if (some? operational-root)
               (assoc base :outcomes/operational-root operational-root)
               base)
        base (if (some? incentive-root)
               (assoc base :outcomes/incentive-root incentive-root)
               base)
        base (if (some? incentive-compatibility-root)
               (assoc base :outcomes/incentive-compatibility-root
                      incentive-compatibility-root)
               base)
        ;; ── Theorem and conclusion references (optional) ───────────
        base (if (seq theorems)
               (assoc base :outcomes/theorems (vec theorems))
               base)
        base (if (seq conclusions)
               (assoc base :outcomes/conclusions (vec conclusions))
               base)
        ;; ── Canonical commitment projection (derived, not supplied) ──
        outcome-hashes (derive-outcome-hashes base)
        base (if outcome-hashes
               (assoc base :outcome-hashes outcome-hashes)
               base)
        ;; ── Singular outcome-hash (excludes mirrored top-level fields) ──
        outcome-hash (str "sha256:"
                          (hc/domain-hash :benchmark-outcome
                                          (hash-projection base)))]
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
       (let [projection (hash-projection manifest)
             computed (str "sha256:" (hc/domain-hash :benchmark-outcome projection))]
         (= computed (:benchmark-outcome/hash manifest)))))

;; ── Comparison predicates (all symmetric) ─────────────────────────────────

(defn- executed-content-root
  "Extract the executed content root from a manifest.
   For force-authorised runs, this is :executed-content-root in the FA section.
   For normal runs, it is the baseline :benchmark/content-root."
  [manifest]
  (or (get-in manifest [:execution/force-authorisation :executed-content-root])
      (:benchmark/content-root manifest)))

(defn exact-replication-scope?
  "True when two manifests are from the same exact execution scope.
   Required for direct byte-identical outcome-hash comparison.

   Compares effective execution scope only — does NOT require equality
   of authorisation provenance (authorisation-hash, reservation-hash,
   consumption-key, execution-attempt-id, etc.) so that independently
   authorised reproductions of the same branch can classify as exact
   replication when they executed the same content."
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
       (= (executed-content-root a) (executed-content-root b))
       (= (:schema-version a) (:schema-version b))))

(defn exact-execution-scope?
  "Alias for exact-replication-scope?.  Same semantics."
  [a b]
  (exact-replication-scope? a b))

(defn same-authorisation-provenance?
  "True when two manifests share the same force-authorisation provenance.
   Checks the authorisation, reservation, and consumption identity fields
   inside :execution/force-authorisation.

   Returns true when both manifests lack an FA section.
   Returns false when one has an FA section and the other does not.
   Symmetric."
  [a b]
  (let [fa-a (:execution/force-authorisation a)
        fa-b (:execution/force-authorisation b)]
    (if (and (nil? fa-a) (nil? fa-b))
      true
      (and (some? fa-a) (some? fa-b)
           (= (:authorisation-hash fa-a) (:authorisation-hash fa-b))
           (= (:reservation-hash fa-a) (:reservation-hash fa-b))
           (= (:consumption-key fa-a) (:consumption-key fa-b))
           (= (:execution-attempt-id fa-a) (:execution-attempt-id fa-b))))))

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

(defn- hash-prefix-valid?
  "True when v is a sha256: prefixed hash string (the canonical encoding)."
  [v]
  (and (string? v) (re-matches #"sha256:[0-9a-f]{64}" v)))

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
     7. Any present hierarchical root carries a valid hash encoding
     8. :outcome-hashes exactly matches the derived projection from
        top-level fields (when :outcome-hashes is present)
   
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
    ;; ── Hierarchical root hash validation ───────────────────────────
    (doseq [[k v] (select-keys manifest
                               [:execution/command-root
                                :outcomes/operational-root
                                :outcomes/incentive-root
                                :outcomes/incentive-compatibility-root])
            :when (some? v)]
      (when-not (hash-prefix-valid? v)
        (swap! errors conj (str k " is not a valid sha256: hash: " v))))
    ;; ── Derived outcome-hashes consistency ──────────────────────────
    (when (some? (:outcome-hashes manifest))
      (let [derived (derive-outcome-hashes manifest)]
        (if (nil? derived)
          (swap! errors conj ":outcome-hashes present but no hierarchical fields found")
          (when-not (= (:outcome-hashes manifest) derived)
            (swap! errors conj (str ":outcome-hashes mismatch: declared "
                                    (pr-str (:outcome-hashes manifest))
                                    " derived " (pr-str derived)))))))
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
   Recomputes the outcome hash, checks required fields, verifies
   that :outcome-hashes exactly matches the derived projection,
   and rejects unknown top-level keys.

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
    (let [mr (:benchmark/model-root manifest)]
      (when (and (some? mr) (not (hash-prefix-valid? mr)))
        (swap! errors conj (str ":benchmark/model-root is not a valid sha256: hash: " mr))))
    ;; ── Reject unknown top-level keys ───────────────────────────────
    (doseq [k (keys manifest)
            :when (not (contains? valid-manifest-keys k))]
      (swap! errors conj (str "unknown manifest key: " (pr-str k))))
    ;; ── Hash projection integrity ───────────────────────────────────
    (when (some? (:benchmark-outcome/hash manifest))
      (let [projection (hash-projection manifest)
            computed (str "sha256:" (hc/domain-hash :benchmark-outcome projection))]
        (when-not (= computed (:benchmark-outcome/hash manifest))
          (swap! errors conj (str "outcome-hash mismatch: declared "
                                  (:benchmark-outcome/hash manifest)
                                  " computed " computed)))))
    ;; ── Derived outcome-hashes exact match ──────────────────────────
    (when (some? (:outcome-hashes manifest))
      (let [derived (derive-outcome-hashes manifest)]
        (if (nil? derived)
          (swap! errors conj ":outcome-hashes present but no hierarchical fields found")
          (when-not (= (:outcome-hashes manifest) derived)
            (swap! errors conj (str ":outcome-hashes mismatch: declared "
                                    (pr-str (:outcome-hashes manifest))
                                    " derived " (pr-str derived)))))))
    {:valid? (empty? @errors) :errors @errors}))

(defn semantic-commitment
  "Lookup a semantic commitment from the evidence section by key."
  [manifest key]
  (get-in manifest [:evidence/semantic-commitments key]))
