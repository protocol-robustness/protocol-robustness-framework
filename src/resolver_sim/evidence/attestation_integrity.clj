(ns resolver-sim.evidence.attestation-integrity
  "Attestation integrity verification: hash recomputation, structural validation,
   and cross-reference checks for the attestation registry.

   Uses the same canonical projection pipeline as build-attestation to recompute
   attestation hashes and verify they match the recorded values. This detects
   data corruption, accidental modification, or hash/schema version drift.

   Usage:
     (require '[resolver-sim.evidence.attestation-integrity :as ai])
     (require '[resolver-sim.evidence.attestation-registry :as ar])

     ;; Check a single attestation
     (ai/verify-attestation-integrity attestation)

     ;; Check all attestations in the registry
     (ai/verify-attestation-registry (ar/all-attestations))

     ;; Get a full integrity report
     (ai/integrity-report (ar/all-attestations))"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.attestation :as att]))

;; ── Single attestation integrity ─────────────────────────────────────────────

(defn- attestation-errors
  "Return a vector of structural error strings for an attestation, or nil if
   the attestation passes all checks."
  [attestation]
  (let [errors (volatile! [])]
    ;; Required fields
    (when-not (:schema-version attestation)
      (vswap! errors conj "Missing required field: :schema-version"))
    (when-not (:attestation/id attestation)
      (vswap! errors conj "Missing required field: :attestation/id"))
    (when-not (:attestation/hash attestation)
      (vswap! errors conj "Missing required field: :attestation/hash"))
    (when-not (:attestation/subject-hash attestation)
      (vswap! errors conj "Missing required field: :attestation/subject-hash"))
    (when-not (:attestation/subject-kind attestation)
      (vswap! errors conj "Missing required field: :attestation/subject-kind"))
    (when-not (:attestation/claim-result attestation)
      (vswap! errors conj "Missing required field: :attestation/claim-result"))
    (when-not (:attestation/attestor-id attestation)
      (vswap! errors conj "Missing required field: :attestation/attestor-id"))
    (when-not (:attestation/signed-at attestation)
      (vswap! errors conj "Missing required field: :attestation/signed-at"))
    ;; id == hash invariant
    (when (and (:attestation/id attestation) (:attestation/hash attestation)
               (not= (:attestation/id attestation) (:attestation/hash attestation)))
      (vswap! errors conj (str ":attestation/id must equal :attestation/hash, got "
                               (:attestation/id attestation) " vs "
                               (:attestation/hash attestation))))
    ;; Valid subject kind
    (when-let [kind (:attestation/subject-kind attestation)]
      (when-not (contains? att/supported-subject-kinds kind)
        (vswap! errors conj (str "Invalid :attestation/subject-kind: " kind))))
    ;; Valid claim result
    (when-let [cr (:attestation/claim-result attestation)]
      (when-not (#{:verified :reproduced :certified :approved :rejected} cr)
        (vswap! errors conj (str "Invalid :attestation/claim-result: " cr))))
    ;; Non-empty subject-hash
    (when-let [sh (:attestation/subject-hash attestation)]
      (when (and (string? sh) (empty? sh))
        (vswap! errors conj ":attestation/subject-hash must not be empty")))
    ;; Non-empty signed-at
    (when-let [sa (:attestation/signed-at attestation)]
      (when (and (string? sa) (empty? sa))
        (vswap! errors conj ":attestation/signed-at must not be empty")))
    (seq @errors)))

(defn hash-valid?
  "Recompute the attestation hash from its canonical projection and compare
   with the recorded :attestation/hash.

   Returns true if the recomputed hash matches the recorded hash, false
   otherwise (or nil if the attestation lacks required hash fields)."
  [attestation]
  (let [recorded (:attestation/hash attestation)]
    (when recorded
      (let [;; Strip self-referential fields before recomputing
            body (dissoc attestation
                         :attestation/id :attestation/hash
                         :attestation/signature :attestation/metadata)
            computed (hc/hash-with-intent {:hash/intent :attestation-record} body)]
        (= computed recorded)))))

(defn verify-attestation-integrity
  "Verify the integrity of a single attestation record.

   Checks:
   - All required fields are present
   - :attestation/id equals :attestation/hash
   - Subject kind is valid
   - Claim result is valid
   - Content hash is consistent with the canonical projection

   Returns {:valid? true} or {:valid? false :errors [...]} with
   per-error details."
  [attestation]
  (let [errors (attestation-errors attestation)
        hash-ok? (hash-valid? attestation)
        all-errors (cond-> (vec (or errors []))
                     (false? hash-ok?)
                     (conj (str "Hash mismatch: recorded "
                                (:attestation/hash attestation)
                                " does not match recomputed hash")))]
    (if (seq all-errors)
      {:valid? false :errors all-errors}
      {:valid? true})))

;; ── Registry-level integrity ─────────────────────────────────────────────────

(defn verify-attestation-registry
  "Run integrity verification on a collection of attestations.

   Arguments:
     attestations — seq of attestation records (e.g. from ar/all-attestations)

   Returns a map with:
     :total-checked  — number of attestations checked
     :valid-count    — attestations passing all checks
     :invalid-count  — attestations with at least one failure
     :invalid        — seq of {:attestation/id <id> :errors [...]}"
  [attestations]
  (let [attestations (vec attestations)
        results (mapv (fn [a]
                        {:attestation/id (:attestation/id a)
                         :result (verify-attestation-integrity a)})
                      attestations)
        valid (filter #(:valid? (:result %)) results)
        invalid (filter #(not (:valid? (:result %))) results)]
    {:total-checked (count attestations)
     :valid-count (count valid)
     :invalid-count (count invalid)
     :invalid (mapv (fn [r]
                      {:attestation/id (:attestation/id r)
                       :errors (:errors (:result r))})
                    invalid)}))

;; ── Integrity report ─────────────────────────────────────────────────────────

(defn integrity-report
  "Generate a comprehensive integrity report for a collection of attestations.

   Includes:
   - Summary counts (total, valid, invalid, hashed)
   - Per-attestation integrity results
   - Hash verification coverage
   - Structural validation summary"
  [attestations]
  (let [atts (vec attestations)
        registry-check (verify-attestation-registry atts)
        hash-results (mapv (fn [a]
                             {:attestation/id (:attestation/id a)
                              :schema-version (:schema-version a)
                              :attestor-id (:attestation/attestor-id a)
                              :subject-kind (:attestation/subject-kind a)
                              :claim-result (:attestation/claim-result a)
                              :hash-valid? (hash-valid? a)
                              :has-signature? (some? (:attestation/signature a))})
                           atts)]
    {:generated-at (str (java.time.Instant/now))
     :total-attestations (count atts)
     :valid-count (:valid-count registry-check)
     :invalid-count (:invalid-count registry-check)
     :hash-verified-count (count (filter :hash-valid? hash-results))
     :hash-failed-count (count (remove :hash-valid? hash-results))
     :signed-count (count (filter :has-signature? hash-results))
     :unsigned-count (count (remove :has-signature? hash-results))
     :invalid-attestations (:invalid registry-check)
     :per-attestation hash-results}))
