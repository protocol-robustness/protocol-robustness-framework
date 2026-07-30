(ns resolver-sim.evidence.attestation
  "ATTESTATION_SPEC_V1 attestation builder and shape validator.

   Provides:
   - build-attestation  — construct a spec-compliant attestation map
   - validate-attestation-shape — structural validation per spec §9

   Usage:
     (require '[resolver-sim.evidence.attestation :as att])

     (att/build-attestation
       {:type :ci-runner :id \"github-actions\"}
       {:type :evidence-node :hash \"sha256:abc...\"}
       :verified
       {:timestamp \"2026-06-23T12:00:00Z\"
        :signing-fn (fn [data] {:algorithm :ed25519
                                :public-key-id \"key-001\"
                                :signature-bytes \"hex...\"})})"
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.definitions.passive-registries :as registries]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const schema-version 1)

(def ^:const supported-subject-kinds
  "The set of valid attestation subject kinds."
  #{:evidence-node :claim})

(def ^:const supported-signature-algorithms
  "The set of signature algorithms admitted by the attestation-signature.v1
   envelope format. This is the attestation-format admission set, distinct
   from what any underlying crypto library supports."
  #{:ed25519})

;; ── Attestation Builder ──────────────────────────────────────────────────────

(defn- default-timestamp
  []
  (str (java.time.Instant/now)))

(defn signing-payload
  "Reconstruct the canonical signing payload from an attestation record.

   The signing payload is the exact data structure that was signed:
     {:intent :attestation-record
      :artifact {:schema-version \"...\"
                 :attestation/subject-hash \"...\"
                 :attestation/subject-kind :evidence-node|:claim
                 :attestation/claim-id ...
                 :attestation/claim-result :verified|:reproduced|...
                 :attestation/attestor-id ...
                 :attestation/signing-key-id ...
                 :attestation/signed-at \"...\"
                 :attestation/provenance ...}}

   Self-identifiers (:attestation/id, :attestation/hash), signature,
   and metadata are excluded — they are not part of the attested content.
   The projection function project-attestation-record is the identity
   lens for what constitutes 'attestation content' for hashing and signing."
  [attestation]
  (hc/project-attestation-record attestation :attestation-record))

(defn build-attestation
  "Build a content-addressed attestation record with deterministic identity.

   Arguments:
     attestor  — map {:type ... :id ...}
     subject   — map {:type :evidence-node|:claim :hash ...|:claim-id ...}
     claim     — keyword claim-result, one of :verified :reproduced :certified
                 :approved :rejected
     opts      — optional map with keys:
                  :signed-at      — ISO-8601 UTC string (default: now)
                  :signing-key-id — string identifying the signing key
                  :signing-fn     — (fn [canonical-signing-payload]) returning
                                    {:algorithm :ed25519
                                     :public-key-id \"...\"
                                     :signature-bytes \"...\"}
                  :claim-id       — registered claim definition id (default: nil)
                  :provenance     — map with run-id, scenario-id, etc.
                  :metadata       — optional metadata map (excluded from hash)

   Returns a content-addressed attestation record:
     :schema-version \"attestation.v1\"
     :attestation/id           (= :attestation/hash, content-derived)
     :attestation/hash         sha256 of canonical projection
     :attestation/subject-hash from subject :hash
     :attestation/subject-kind from subject :type
     :attestation/claim-id     registered claim id (if provided)
     :attestation/claim-result :verified|:reproduced|...
     :attestation/attestor-id  from attestor :id
     :attestation/signing-key-id key identifier (if provided)
     :attestation/signed-at    ISO-8601 instant
     :attestation/provenance   context map (if provided)
     :attestation/signature    present if signing-fn provided
     :attestation/metadata     present if metadata provided

   The signing-fn receives the canonical signing payload as returned
   by signing-payload — this is the same data structure that was hashed.
   Use signing-payload to reconstruct what was signed for verification."
  [attestor subject claim & [{:keys [signed-at signing-key-id signing-fn claim-id provenance metadata]}]]
  (let [body (cond-> {:schema-version "attestation.v1"
                      :attestation/subject-hash (or (:hash subject) (:claim-id subject))
                      :attestation/subject-kind (:type subject)
                      :attestation/claim-id claim-id
                      :attestation/claim-result claim
                      :attestation/attestor-id (:id attestor)
                      :attestation/signed-at (or signed-at (default-timestamp))
                      :attestation/provenance provenance}
               signing-key-id (assoc :attestation/signing-key-id signing-key-id))
        projected (signing-payload body)
        body-hash (hc/hash-with-intent {:hash/intent :attestation-record} body)
        artifact (assoc body
                        :attestation/id body-hash
                        :attestation/hash body-hash)
        with-meta (if metadata (assoc artifact :attestation/metadata metadata) artifact)]
    (if signing-fn
      (assoc with-meta :attestation/signature (signing-fn projected))
      with-meta)))

;; ── Shape Validation ─────────────────────────────────────────────────────────

(defn- missing-field-error
  [field]
  {:type :attestation/missing-field
   :field field
   :message (str "Missing required field: " (name field))})

(defn- malformed-signature-error
  [detail]
  {:type :attestation/malformed-signature
   :message "Signature is malformed"
   :detail detail})

(defn- validate-required-fields
  "Validate required fields for both old and new shape.
   Old shape requires: :attestation-id, :attestor, :subject, :claim, :timestamp.
   New shape requires: :attestation/id, :attestation/attestor-id,
     :attestation/subject-kind, :attestation/subject-hash,
     :attestation/claim-result, :attestation/signed-at."
  [attestation]
  (if (:attestation/subject-kind attestation)
    ;; New shape validation
    (let [required #{:schema-version :attestation/id :attestation/hash
                     :attestation/subject-hash :attestation/subject-kind
                     :attestation/claim-result :attestation/attestor-id
                     :attestation/signed-at}
          missing (clojure.set/difference required (set (keys attestation)))]
      (mapv missing-field-error missing))
    ;; Old shape validation
    (let [required #{:attestation-id :attestor :subject :claim :timestamp}
          missing (clojure.set/difference required (set (keys attestation)))]
      (mapv missing-field-error missing))))

(defn- validate-subject
  [subject]
  (cond-> []
    (not (:type subject))
    (conj {:type :attestation/invalid-subject
           :message "Subject missing required :type field"
           :subject subject})
    (not (contains? supported-subject-kinds (:type subject)))
    (conj {:type :attestation/invalid-subject-type
           :message (str "Subject :type must be :evidence-node or :claim, got " (pr-str (:type subject)))
           :subject subject})
    (and (= :evidence-node (:type subject)) (not (:hash subject)))
    (conj {:type :attestation/invalid-subject
           :message "Subject of type :evidence-node missing required :hash"
           :subject subject})
    (and (= :claim (:type subject)) (not (:claim-id subject)))
    (conj {:type :attestation/invalid-subject
           :message "Subject of type :claim missing required :claim-id"
           :subject subject})))

(defn- validate-attestor
  [attestor]
  (cond-> []
    (not (map? attestor))
    (conj {:type :attestation/invalid-attestor
           :message (str "Attestor must be a map, got " (type attestor))
           :attestor attestor})
    (not (:type attestor))
    (conj {:type :attestation/invalid-attestor
           :message "Attestor missing required :type field"
           :attestor attestor})
    (not (:id attestor))
    (conj {:type :attestation/invalid-attestor
           :message "Attestor missing required :id field"
           :attestor attestor})))

(defn- validate-signature
  [signature]
  (when signature
    (cond-> []
      (not (map? signature))
      (conj (malformed-signature-error (str "Expected map, got " (type signature))))
      (and (map? signature) (not (:algorithm signature)))
      (conj (malformed-signature-error "Missing :algorithm"))
      (and (map? signature) (not (:public-key-id signature)))
      (conj (malformed-signature-error "Missing :public-key-id"))
      (and (map? signature) (not (:signature-bytes signature)))
      (conj (malformed-signature-error "Missing :signature-bytes")))))

(defn- validate-new-shape-attestor
  [attestation]
  (let [id (:attestation/attestor-id attestation)]
    (cond-> []
      (nil? id)
      (conj {:type :attestation/invalid-attestor
             :message "Attestor-id is missing"
             :attestation attestation}))))

(defn- validate-new-shape-subject
  [attestation]
  (let [kind (:attestation/subject-kind attestation)
        hash (:attestation/subject-hash attestation)]
    (cond-> []
      (nil? kind)
      (conj {:type :attestation/invalid-subject
             :message "Subject kind is missing"
             :attestation attestation})
      (not (contains? supported-subject-kinds kind))
      (conj {:type :attestation/invalid-subject-type
             :message (str "Subject :type must be :evidence-node or :claim, got " (pr-str kind))
             :attestation attestation})
      (nil? hash)
      (conj {:type :attestation/invalid-subject
             :message "Subject hash is missing"
             :attestation attestation}))))

(defn- validate-schema-version
  [attestation]
  (let [sv (:schema-version attestation)]
    (when (and (some? sv) (not= "attestation.v1" sv))
      [{:type :attestation/unsupported-schema-version
        :message (str "Expected attestation.v1, got " (pr-str sv))
        :schema-version sv}])))

(defn- validate-id-hash-consistency
  "For new-shape attestation, :attestation/id must equal :attestation/hash."
  [attestation]
  (let [id (:attestation/id attestation)
        h (:attestation/hash attestation)]
    (when (and (some? id) (some? h) (not= id h))
      [{:type :attestation/id-hash-mismatch
        :message (str ":attestation/id and :attestation/hash must be equal, got "
                      (pr-str id) " and " (pr-str h))
        :attestation/id id :attestation/hash h}])))

(defn validate-attestation-shape
  "Validate that an attestation map conforms to ATTESTATION_SPEC_V1 §9.
   Returns {:valid? true} or {:valid? false :errors [...]}.

   Checks (old shape):
   - Required fields present (:attestation-id, :attestor, :subject, :claim, :timestamp)
   - Attestor has :type and :id
   - Subject has valid :type and matching id field (:hash or :claim-id)
   - Signature is correctly structured (if present)

   Checks (new shape):
   - Required fields present (:schema-version, :attestation/id, :attestation/hash,
     :attestation/subject-hash, :attestation/subject-kind, :attestation/claim-result,
     :attestation/attestor-id, :attestation/signed-at)
   - Schema version is exactly \"attestation.v1\"
   - :attestation/id equals :attestation/hash
   - Subject kind is valid (:evidence-node or :claim)
   - Subject hash is non-nil
   - Signature is correctly structured (if present)"
  [attestation]
  (if (:attestation/subject-kind attestation)
    ;; New shape
    (let [required-errors (validate-required-fields attestation)
          schema-version-errors (validate-schema-version attestation)
          id-hash-errors (validate-id-hash-consistency attestation)
          attestor-errors (validate-new-shape-attestor attestation)
          subject-errors (validate-new-shape-subject attestation)
          signature-errors (validate-signature (:attestation/signature attestation))
          all-errors (vec (concat required-errors schema-version-errors id-hash-errors
                                  attestor-errors subject-errors signature-errors))]
      (if (seq all-errors)
        {:valid? false :errors all-errors}
        {:valid? true}))
    ;; Old shape
    (let [required-errors (validate-required-fields attestation)
          attestor-errors (if-let [a (:attestor attestation)]
                            (validate-attestor a)
                            [])
          subject-errors (if-let [s (:subject attestation)]
                           (validate-subject s)
                           [])
          signature-errors (validate-signature (:signature attestation))
          all-errors (vec (concat required-errors attestor-errors subject-errors signature-errors))]
      (if (seq all-errors)
        {:valid? false :errors all-errors}
        {:valid? true}))))

;; ── Attestation Verification (Two Layers) ─────────────────────────────────────
;; ATTESTATION_SPEC_V1 §9 and ATTESTOR_REGISTRY_SPEC_V1 §11.
;;
;; Layer 1 — Registry-backed authorization: attestor exists, active, key authorized.
;; Layer 2 — Cryptographic-only: verify-fn checks the signature.
;;
;; The two layers are independent. verify-attestation runs all checks and returns
;; per-check results; a caller can decide which layer matters for their use case.

(defn- attestor-id
  "Extract attestor identifier from an attestation.
   Handles both old shape (:attestor map) and new shape
   (:attestation/attestor-id keyword)."
  [attestation]
  (or (:attestation/attestor-id attestation)
      (:id (:attestor attestation))))

(defn- signing-key-id
  "Extract the signing key identifier from the attestation's signature.
   Handles both old shape (:signature) and new shape
   (:attestation/signature).
   Returns nil if no signature or no public-key-id in signature."
  [attestation]
  (or (get-in attestation [:attestation/signature :public-key-id])
      (get-in attestation [:signature :public-key-id])))

(defn- data-to-verify
  "Reconstruct the data that was signed for an old-shape attestation.
   Old shape: the attestation minus :signature and :metadata.
   New shape: use signing-payload for the canonical projection."
  [attestation]
  (if (:attestation/subject-kind attestation)
    (signing-payload attestation)
    (dissoc attestation :signature :metadata)))

(defn- check-attestor-exists
  "Check that the attestor is registered."
  [attestation]
  (let [id (attestor-id attestation)
        entry (when id (registries/find-attestor id))]
    {:check :attestor-exists
     :pass? (some? entry)
     :detail (if entry
               {:attestor-id (:id entry) :type (:type entry)}
               {:attestor-id id :reason :not-found})}))

(defn- check-attestor-active
  "Check that the attestor's current status is :active."
  [attestation]
  (let [id (attestor-id attestation)
        entry (when id (registries/find-attestor id))]
    (if entry
      {:check :attestor-active
       :pass? (registries/attestor-active? entry)
       :detail {:attestor-id (:id entry) :status (registries/attestor-status entry)}}
      {:check :attestor-active
       :pass? false
       :detail {:attestor-id id :reason :attestor-not-found}})))

(defn- check-key-authorized
  "Registry-backed authorization: is the signing key active for this attestor?
   Uses the attestor registry exclusively — no cryptographic work done here.
   Authorized means: primary key match, active delegate, or active in key-history.
   If the attestation has no signature, this check passes as :unsigned."
  [attestation]
  (let [id (attestor-id attestation)
        key-id (signing-key-id attestation)
        entry (when id (registries/find-attestor id))]
    (cond
      (nil? entry)
      {:check :key-authorized
       :pass? false
       :detail {:attestor-id id :reason :attestor-not-found}}
      (nil? (or (:attestation/signature attestation) (:signature attestation)))
      {:check :key-authorized
       :pass? :unsigned
       :detail {:reason :no-signature}}
      (nil? key-id)
      {:check :key-authorized
       :pass? false
       :detail {:reason :no-key-id-in-signature}}
      :else
      (let [authorized? (boolean (registries/key-authorized-for-attestor? entry key-id))
            known? (registries/key-known-for-attestor? entry key-id)]
        {:check :key-authorized
         :pass? authorized?
         :detail {:key-id key-id
                  :authorized? authorized?
                  :known? known?
                  :status (cond
                            authorized? :active
                            known? :retired
                            :else :unknown)}}))))

(defn- check-signature
  "Cryptographic signature verification. Purely cryptographic — does not
   check key authorization. Authorization is handled by check-key-authorized
   via the attestor registry.

   Assurance states (:pass? value):
     true         — verify-fn was provided and returned true.
                    The signature has been cryptographically verified.
     :unsigned    — the attestation has no signature field.
                    No authenticity claim is made — this is not a failure.
     :unavailable — the attestation has a signature but no verify-fn was
                    provided. A signature was claimed but could not be
                    verified in this context.  This is materially different
                    from :unsigned (which makes no authenticity claim).
     false        — verify-fn was provided and returned false, or threw.
                    The signature failed cryptographic verification.

   valid? treats :unsigned and :unavailable as non-failures (they are not
   Boolean false).  They do NOT imply cryptographic verification passed.
   A caller that requires cryptographic assurance must check for pass? true
   explicitly, or use a completeness profile that requires signatures.
   See verify-attestation for the full assurance model."
  [attestation verify-fn]
  (let [signature (or (:attestation/signature attestation) (:signature attestation))]
    (cond
      (nil? signature)
      {:check :signature-verified
       :pass? :unsigned
       :detail {:reason :no-signature-present}}

      (nil? verify-fn)
      {:check :signature-verified
       :pass? :unavailable
       :detail {:reason :no-verify-fn-provided}}

      :else
      (let [data (data-to-verify attestation)
            result (try
                     (verify-fn data signature)
                     (catch Exception e
                       {:pass? false :error (.getMessage e)}))
            pass? (if (map? result)
                    (true? (:pass? result))
                    (true? result))]
        {:check :signature-verified
         :pass? pass?
         :detail (if (map? result) result {:raw-result result})}))))

(defn- check-subject-exists
  "Check that the subject references a known evidence node or claim.
   Requires a subject-resolver function in opts.
   Handles both old shape (:subject map) and new shape
   (:attestation/subject-kind, :attestation/subject-hash)."
  [attestation subject-resolver]
  (let [subject (or (when (:attestation/subject-kind attestation)
                      (let [sk (:attestation/subject-kind attestation)
                            sh (:attestation/subject-hash attestation)]
                        (if (= :claim sk)
                          {:type :claim :claim-id sh}
                          {:type sk :hash sh})))
                    (:subject attestation))]
    (cond
      (nil? subject)
      {:check :subject-exists
       :pass? false
       :detail {:reason :no-subject}}

      (nil? subject-resolver)
      {:check :subject-exists
       :pass? :unavailable
       :detail {:reason :no-subject-resolver-provided}}

      :else
      (let [exists? (try
                       (subject-resolver subject)
                       (catch Exception e
                         (do
                           (.println *err* "subject-resolver failed:" (.getMessage e))
                           nil)))]
        (cond
          (nil? exists?)
          {:check :subject-exists
           :pass? :error
           :detail {:reason :resolver-threw :subject subject}}
          (true? exists?)
          {:check :subject-exists
           :pass? true
           :detail {:subject subject}}
          :else
          {:check :subject-exists
           :pass? false
           :detail {:reason :subject-not-found :subject subject}})))))

(defn- check-revocation
  "Check if the attestation has been revoked.
   Requires a revocation-resolver fn in opts.
   Per ATTESTATION_SPEC_V1 §7 and ATTESTOR_REGISTRY_SPEC_V1 §8,
   revocation does not invalidate the cryptographic attestation —
   this check is informational.
   Handles both old shape (:attestation-id) and new shape
   (:attestation/id)."
  [attestation revocation-resolver]
  (let [id (or (:attestation/id attestation) (:attestation-id attestation))]
    (cond
      (nil? id)
      {:check :revocation-status
       :pass? :unavailable
       :detail {:reason :no-attestation-id}}

      (nil? revocation-resolver)
      {:check :revocation-status
       :pass? :unavailable
       :detail {:reason :no-revocation-resolver-provided}}

      :else
      (let [revoked? (try
                        (revocation-resolver id)
                        (catch Exception e
                          (do (.println *err* "revocation-resolver failed:" (.getMessage e))
                              nil)))]
        (cond
          (nil? revoked?)
          {:check :revocation-status
           :pass? :error
           :detail {:reason :resolver-threw :attestation-id id}}
          (true? revoked?)
          {:check :revocation-status
           :pass? true
           :detail {:revoked? true :attestation-id id}}
          :else
          {:check :revocation-status
           :pass? false
           :detail {:revoked? false :attestation-id id}})))))

(defn verify-attestation
  "Verify an attestation. Two independent layers:

     Layer 1 — Registry-backed authorization (mandatory):
       :attestor-exists     — registry: is the attestor registered?
       :attestor-active     — registry: is the attestor status :active?
       :key-authorized      — registry: is the signing key active for this attestor?

     Layer 2 — Cryptographic verification (optional, via :verify-fn):
       :signature-verified  — cryptographic: does the signature match the data?

     Additional checks:
       :subject-exists      — does the subject hash/claim-id resolve?
       :revocation-status   — informational, registry-backed

   Layers are independent. Registry checks do not involve cryptography.
   The verify-fn does pure cryptographic work — it does not consult the registry.

   SEMANTIC DISTINCTION: valid? vs verified
     valid?  means \"no check returned Boolean false\" — the attestation is
             structurally sound and contains no demonstrated failures.  This
             is the default interpretation of verify-attestation.
     verified means \"all applicable checks passed cryptographic or registry
             scrutiny.\"  verify-attestation does NOT return :verified by
             itself — use verify-attestation-summary for that distinction.

     Checks returning :unsigned or :unavailable do NOT count as failures
     for valid?, but they do NOT satisfy a verified requirement either.
     A caller that needs verified must require signatures via the
     completeness profile and confirm that every :signature-verified check
     has :pass? true.

   Assurance states for :signature-verified:
     true         — cryptographically verified
     :unsigned    — no signature claimed
     :unavailable — signature claimed but not verifiable in this context
     false        — verification attempted and failed

   Pass/fail rule for resolver functions (verify-fn, subject-resolver,
   revocation-resolver): only explicit Boolean true counts as pass.
   nil, {}, [], or any non-map non-boolean result is treated as failure.
   Checks returning :unsigned or :unavailable are documented contract
   states — they indicate the check was not applicable, not failure.

   opts:
     :verify-fn             — (fn [data-to-verify signature-map]) -> boolean or {:pass? bool}
     :subject-resolver      — (fn [subject-map]) -> boolean
     :revocation-resolver   — (fn [attestation-id]) -> boolean; true if revoked"
  [attestation & [{:keys [verify-fn subject-resolver revocation-resolver]}]]
  (let [checks [(check-attestor-exists attestation)
                (check-attestor-active attestation)
                (check-key-authorized attestation)
                (check-signature attestation verify-fn)
                (check-subject-exists attestation subject-resolver)
                (check-revocation attestation revocation-resolver)]
        hard-failures (filterv (fn [c]
                                 (false? (:pass? c)))
                               checks)]
    {:valid? (empty? hard-failures)
     :checks checks}))

(defn verify-attestation-summary
  "Single-keyword summary of attestation verification.
   Returns one of:
     :verified                — all applicable checks pass
     :no-such-attestor        — attestor not in registry
     :attestor-revoked        — attestor status is :revoked or :retired
     :key-not-authorized      — signing key not authorized for attestor
     :signature-mismatch      — cryptographic signature verification failed
     :signature-unverifiable  — signature present but no verifier available
     :subject-unknown         — subject does not resolve
     :verification-failed     — failed checks without a specific summary mapping

   :verified means no check returned Boolean false.  It does NOT imply
   cryptographic verification — use :signature-verified check's :pass?
   value to distinguish unsigned (:unsigned) from cryptographically
   verified (true).
   A :signature-unverifiable result means the attestation carries a
   signature that could not be verified in this context (no verify-fn).
   This is materially different from :signature-mismatch (verification
   attempted and failed)."
  [attestation & opts]
  (let [{:keys [valid? checks]} (apply verify-attestation attestation opts)]
    (if valid?
      ;; All checks non-false — check for unverifiable signatures
      (let [sig-check (first (filter #(= :signature-verified (:check %)) checks))]
        (if (= :unavailable (:pass? sig-check))
          :signature-unverifiable
          :verified))
      (let [fail->summary {:attestor-exists :no-such-attestor
                           :attestor-active :attestor-revoked
                           :key-authorized :key-not-authorized
                           :signature-verified :signature-mismatch
                           :subject-exists :subject-unknown}
            failures (filterv (fn [c] (false? (:pass? c))) checks)]
        (or (some (fn [c] (get fail->summary (:check c))) failures)
            :verification-failed)))))

;; ── Claim Integration ────────────────────────────────────────────────────────

(defn build-claim-result-attestation
  "Build an attestation for a claim result.

   The attestation references the claim result hash as its subject, linking
   the attestor's verification to the specific claim evaluation outcome.
   The claim-id is passed through for cross-referencing via the registry.

   Arguments:
     attestor     — map {:type ... :id ...}
     claim-result — claim result map with at least :claim-id and
                    :claim-result-hash keys (as produced by
                    claim-result-entry in slashing.clj)
     opts         — optional map with keys:
                     :claim       — claim result for this attestation
                                   (default: :verified)
                     :signed-at, :signing-key-id, :signing-fn
                     :provenance, :metadata

   Returns a content-addressed attestation record with:
     :attestation/subject-kind :claim
     :attestation/subject-hash <claim-result-hash>
     :attestation/claim-id    <claim-id>
     :attestation/claim-result :verified (or as specified)"
  [attestor claim-result & [{:keys [claim signed-at signing-key-id signing-fn
                                    provenance metadata]
                             :or {claim :verified}}]]
  (build-attestation
   attestor
   {:type :claim :hash (:claim-result-hash claim-result)}
   claim
   (cond-> {:claim-id (:claim-id claim-result)}
     signed-at (assoc :signed-at signed-at)
     signing-key-id (assoc :signing-key-id signing-key-id)
     signing-fn (assoc :signing-fn signing-fn)
     provenance (assoc :provenance provenance)
     metadata (assoc :metadata metadata))))


