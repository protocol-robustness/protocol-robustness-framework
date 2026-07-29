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

   Returns the case map with :case/hash attached."
  [{:keys [case/id case/gross-slash-amount] :as m}]
  (when-not (and (string? id) (seq id))
    (throw (ex-info "fixed-regression-case requires :case/id"
                    {:provided id})))
  (when-not (and (integer? gross-slash-amount) (not (neg? gross-slash-amount)))
    (throw (ex-info "fixed-regression-case requires non-negative :case/gross-slash-amount"
                    {:provided gross-slash-amount})))
  (let [base (merge {:schema-version schema-version
                     :case/kind :slash/standard
                     :case/evidence-references []
                     :case/parameter-context {:source-root "fixed-case"
                                              :values {}}
                     :case/metadata {}}
                    m)
        case-hash (fixed-regression-case-hash base)]
    (assoc base :case/hash case-hash)))

;; ── validator ───────────────────────────────────────────────────────────────

(defn validate-fixed-regression-case
  "Validate a fixed-regression-case.v1 artifact.

   Returns {:valid? true} or {:valid? false :errors [<error-kw> ...]}."
  [case-map]
  (let [errors (cond-> []
                 (not= schema-version (:schema-version case-map))
                 (conj :unsupported-schema-version)
                 (not (string? (:case/id case-map)))
                 (conj :missing-case-id)
                 (not (and (integer? (:case/gross-slash-amount case-map))
                           (not (neg? (:case/gross-slash-amount case-map)))))
                 (conj :invalid-gross-slash-amount)
                 (not (string? (:case/policy-root case-map)))
                 (conj :missing-policy-root)
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
