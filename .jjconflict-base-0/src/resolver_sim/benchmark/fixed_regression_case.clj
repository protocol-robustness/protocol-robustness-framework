(ns resolver-sim.benchmark.fixed-regression-case
  "fixed-regression-case.v1

   Content-addressed artifact for fixed regression test cases.
   Provides a stable set of curated cases that must produce
   known outcomes, used as regression guards against protocol changes.

   The artifact commits the protocol situation and expected assurance-relevant
   behaviour. It does not embed Sew world-state layout — that belongs in the
   Sew test adapter or fixture.

   The same operational fixed case is reusable across compensation
   configurations (no bounty, default bounty, invalid bounty, etc.)."
  (:require [resolver-sim.hash.canonical :as hc]))

(def schema-version "fixed-regression-case.v1")

;; ── hash projection ─────────────────────────────────────────────────────────

(defn fixed-regression-case-hash-projection
  "Return only the committed fields used for the content hash."
  [case-map]
  (select-keys case-map
               [:schema-version
                :case/id
                :case/kind
                :case/description
                :case/gross-slash-amount
                :case/policy-root
                :case/parameter-context
                :case/challenger
                :case/beneficiary
                :case/evidence-references
                :case/expected-invariant-ids
                :case/expected-distribution-root
                :case/metadata]))

(defn fixed-regression-case-hash
  "Compute the FIXED_REGRESSION_CASE_V1 content hash."
  [case-map]
  (hc/domain-hash :fixed-regression-case-v1
                  (fixed-regression-case-hash-projection case-map)))

;; ── validation ─────────────────────────────────────────────────────────────

(defn- case-validation-errors
  "Structural validation shared by the builder and the validator, so the two
   can never disagree about which cases are well-formed."
  [case-map]
  (let [id (:case/id case-map)
        kind (:case/kind case-map)
        description (:case/description case-map)
        gross (:case/gross-slash-amount case-map)
        policy-root (:case/policy-root case-map)
        pc (:case/parameter-context case-map)
        pc-values (when (map? pc) (:values pc))
        refs (:case/evidence-references case-map)
        invariant-ids (:case/expected-invariant-ids case-map)
        dist-root (:case/expected-distribution-root case-map)
        metadata (:case/metadata case-map)]
    (cond-> []
      (not (and (string? id) (seq id)))
      (conj :missing-case-id)

      (not (keyword? kind))
      (conj :invalid-case-kind)

      (not (and (string? description) (seq description)))
      (conj :missing-case-description)

      (not (and (integer? gross) (not (neg? gross))))
      (conj :invalid-gross-slash-amount)

      (not (and (string? policy-root) (seq policy-root)))
      (conj :missing-policy-root)

      (not (map? pc))
      (conj :invalid-parameter-context)

      (and (map? pc) (contains? pc :source-root) (not (string? (:source-root pc))))
      (conj :invalid-parameter-context)

      (and (map? pc) (not (map? pc-values)))
      (conj :invalid-parameter-values)

      (and (map? pc-values) (some (complement integer?) (vals pc-values)))
      (conj :invalid-parameter-values)

      (not (vector? refs))
      (conj :invalid-evidence-references)

      (some (complement string?) refs)
      (conj :invalid-evidence-reference)

      (and (contains? case-map :case/challenger) (not (string? (:case/challenger case-map))))
      (conj :invalid-challenger)

      (and (contains? case-map :case/beneficiary) (not (string? (:case/beneficiary case-map))))
      (conj :invalid-beneficiary)

      (and (some? invariant-ids) (not (vector? invariant-ids)))
      (conj :invalid-expected-invariant-ids)

      (and (vector? invariant-ids) (some (complement keyword?) invariant-ids))
      (conj :invalid-expected-invariant-ids)

      (and (some? dist-root) (not (string? dist-root)))
      (conj :invalid-expected-distribution-root)

      (and (some? metadata) (not (map? metadata)))
      (conj :invalid-metadata))))

;; ── builder ─────────────────────────────────────────────────────────────────

(defn build-fixed-regression-case
  "Build a fixed-regression-case.v1 artifact.

   Required keys:
     :case/id                    — unique case identifier string
     :case/kind                  — case kind keyword (e.g. :slash/standard)
     :case/gross-slash-amount    — gross slash amount (non-negative integer)
     :case/policy-root           — slash-distribution policy root hash string
     :case/parameter-context     — map with :values {:key <int> ...}
     :case/challenger            — challenger address string (optional)
     :case/beneficiary           — beneficiary address string (optional)
     :case/evidence-references   — vector of evidence reference strings
     :case/description           — human-readable description string

   Optional keys:
     :case/expected-invariant-ids  — vector of expected invariant keyword IDs
     :case/expected-distribution-root  — expected distribution root hash
     :case/metadata               — any additional metadata map

   Returns the case map with :case/hash attached. Construction is fail-closed:
   a case missing a required field or carrying a malformed one is rejected."
  [m]
  (let [base (merge {:schema-version schema-version
                     :case/kind :slash/standard
                     :case/evidence-references []
                     :case/parameter-context {:source-root "fixed-case"
                                              :values {}}
                     :case/metadata {}}
                    m)
        errors (case-validation-errors base)]
    (when (seq errors)
      (throw (ex-info "Invalid fixed-regression-case"
                      {:errors errors
                       :provided (dissoc base :case/hash)})))
    (let [case-hash (fixed-regression-case-hash base)]
      (assoc base :case/hash case-hash))))

;; ── validator ───────────────────────────────────────────────────────────────

(defn validate-fixed-regression-case
  "Validate a fixed-regression-case.v1 artifact.

   Returns {:valid? true} or {:valid? false :errors [<error-kw> ...]}."
  [case-map]
  (let [errors (cond-> (case-validation-errors case-map)
                 (not= schema-version (:schema-version case-map))
                 (conj :unsupported-schema-version)
                 (not (string? (:case/hash case-map)))
                 (conj :missing-case-hash))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn verify-fixed-regression-case
  "Independently verify the case hash matches the committed fields.
   Returns {:valid? true} or {:valid? false :errors [...]}."
  [case-map]
  (let [validation (validate-fixed-regression-case case-map)]
    (if-not (:valid? validation)
      validation
      (let [computed (fixed-regression-case-hash case-map)
            stored (:case/hash case-map)]
        (if (= computed stored)
          {:valid? true}
          {:valid? false
           :errors [:hash-mismatch
                    {:stored stored :computed computed}]})))))

(defn verify-fixed-regression-case-root
  "Verify only the case root hash without full structural validation.
   Useful for content-registry comparisons."
  [case-map]
  (let [computed (fixed-regression-case-hash case-map)
        stored (:case/hash case-map)]
    (= computed stored)))
