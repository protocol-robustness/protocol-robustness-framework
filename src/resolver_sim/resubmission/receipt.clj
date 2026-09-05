(ns resolver-sim.resubmission.receipt
  "Submission-attempt receipt (submission-attempt-receipt.v1 and v2).

   The validator-issued, content-addressed receipt is the ROOT OF AUTHORITY for
   a resubmission link. It records the claimed (never trusted) run id, the
   status-bearing four roots, the results status, submitter identity
   provenance, outcome/finality/eligibility/lifecycle dimensions, the chain
   admission cutpoint, the bound acceptance evaluation, structured findings,
   and full validator authority.

   Canonical contract:

     attempt-receipt-hash = \"sha256:\" + domain-hash(
         \"prf.submission-attempt-receipt.v1\",
         canonical-bytes-v2(unsigned-receipt-projection))

   The unsigned projection EXCLUDES ONLY :attempt-receipt/id and the validator
   signature bytes. The validator signature covers EXACTLY the same unsigned
   projection bytes as the identity hash.

   V2 adds an explicit V2 receipt domain/root, requires the
   :attempt-receipt/attempt-subject-root to be present in the closed V2
   projection, and dispatches V1/V2 by schema. V1 parsing, hashing, signing,
   and verification are byte-for-byte unchanged.

   Direct resubmission eligibility requires:
     {:outcome :rejected :finality :final
      :resubmission-eligibility :eligible :lifecycle-status :active}"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.attempt-subject :as attempt-subject]))

;; ── Schema / domain constants ────────────────────────────────────────────────

(def ^:const receipt-schema "submission-attempt-receipt.v1")
(def ^:const receipt-domain :prf-submission-attempt-receipt-v1)

(def ^:const receipt-v2-schema "submission-attempt-receipt.v2")
(def ^:const receipt-domain-v2 :prf-submission-attempt-receipt-v2)

(def ^:const outcomes #{:accepted :rejected :system-failure :indeterminate})
(def ^:const finalities #{:provisional :final})
(def ^:const resubmission-eligibilities #{:eligible :ineligible :retry-same-attempt})
(def ^:const lifecycle-statuses #{:active :withdrawn :revoked :superseded})
(def ^:const root-statuses #{:verified :invalid :unavailable})
(def ^:const results-statuses #{:verified :invalid :missing})
(def ^:const submitter-statuses #{:verified :authenticated-session :claimed :missing})
(def ^:const identity-sources #{:publisher-signature :submission-auth :authenticated-session})

;; ── V1: unchanged ────────────────────────────────────────────────────────────

(defn unsigned-receipt-projection
  "The canonical unsigned projection of a receipt: everything except
   :attempt-receipt/id and the validator signature bytes. Key/policy ids,
   findings, root statuses, outcome/finality/eligibility/lifecycle, observed
   bundle root, chain data, submitter identity, evaluation binding, and
   authoritative timestamps are all included."
  [receipt]
  (-> receipt
      (dissoc :attempt-receipt/id)
      (update :attempt-receipt/validator dissoc :signature)))

(defn receipt-hash
  "Content-addressed identity of a receipt (self hash)."
  [receipt]
  (hash-ref/sha256-ref (hc/domain-hash receipt-domain (unsigned-receipt-projection receipt))))

(defn sign-receipt
  "Attach the validator signature over the unsigned receipt projection bytes.
   The signature block is {:signature/algorithm :ed25519 :signature <hex>} under
   :attempt-receipt/validator. Returns the signed receipt with
   :attempt-receipt/id attached (the identity hash)."
  [receipt private-key]
  (let [signed (assoc-in receipt [:attempt-receipt/validator :signature]
                         {:signature/algorithm :ed25519
                          :signature (sed/ed25519-sign-bytes
                                      (hc/canonical-bytes (unsigned-receipt-projection receipt))
                                      private-key)})]
    (assoc signed :attempt-receipt/id (receipt-hash signed))))

(defn verify-receipt-signature
  "Verify a signed receipt's validator signature over its unsigned projection
   bytes, and confirm :attempt-receipt/id matches the recomputed hash.
   Returns {:valid? bool :reason kw :detail str}."
  [receipt validator-public-hex]
  (let [sig (get-in receipt [:attempt-receipt/validator :signature])]
    (cond
      (nil? sig)
      {:valid? false :reason :missing-validator-signature}

      (not= :ed25519 (:signature/algorithm sig))
      {:valid? false :reason :unsupported-signature-algorithm
       :detail (:signature/algorithm sig)}

      (not= (:attempt-receipt/id receipt) (receipt-hash receipt))
      {:valid? false :reason :receipt-hash-mismatch
       :detail (str "stored " (:attempt-receipt/id receipt)
                    " recomputed " (receipt-hash receipt))}

      (not (sed/ed25519-verify-bytes
            (hc/canonical-bytes (unsigned-receipt-projection receipt))
            (:signature sig)
            validator-public-hex))
      {:valid? false :reason :invalid-signature}

      :else
      {:valid? true :reason :ok :detail (:attempt-receipt/id receipt)})))

(defn receipt-binds-attempt-subject?
  "Verify the receipt's explicit attempt-subject root against a reconstructed
   canonical subject. This is independent of receipt signature verification."
  [receipt subject]
  (and (attempt-subject/valid? subject)
       (= (:attempt/subject-root subject)
          (:attempt-receipt/attempt-subject-root receipt))))

(defn valid-root-shape?
  "True when a receipt root entry is {:root/schema str :status kw :hash str}."
  [root]
  (and (map? root)
       (string? (:root/schema root))
       (contains? root-statuses (:status root))
       (or (string? (:hash root))
           (and (= :missing (:status root)) (nil? (:hash root))))))

(defn valid-receipt-shape?
  "Structural validation of a V1 receipt (does not verify signatures).
   :attempt-receipt/attempt-subject-root is accepted when present but is not
   required (V1 is application-root-free)."
  [receipt]
  (and (map? receipt)
       (= receipt-schema (:attempt-receipt/schema receipt))
       (string? (:attempt-receipt/submitted-bundle-root receipt))
       (or (not (contains? receipt :attempt-receipt/attempt-subject-root))
           (hash-ref/valid-sha256-ref?
            (:attempt-receipt/attempt-subject-root receipt)))
       (contains? outcomes (:attempt-receipt/outcome receipt))
       (contains? finalities (:attempt-receipt/finality receipt))
       (contains? resubmission-eligibilities (:attempt-receipt/resubmission-eligibility receipt))
       (contains? lifecycle-statuses (:attempt-receipt/lifecycle-status receipt))
       (map? (:attempt-receipt/roots receipt))
       (every? #(valid-root-shape? (get (:attempt-receipt/roots receipt) %))
               [:research-subject :execution-context :results :submission-basis])
       (map? (:attempt-receipt/validator receipt))
       (string? (get-in receipt [:attempt-receipt/validator :policy/hash]))
       (string? (get-in receipt [:attempt-receipt/validator :key/id]))))

(defn direct-resubmission-parent?
  "True only when the receipt is eligible to be a direct resubmission parent."
  [receipt]
  (and (= :rejected (:attempt-receipt/outcome receipt))
       (= :final (:attempt-receipt/finality receipt))
       (= :eligible (:attempt-receipt/resubmission-eligibility receipt))
       (= :active (:attempt-receipt/lifecycle-status receipt))))

(defn resubmission-parent-requirement-mismatch
  "The first dimension that disqualifies a receipt as a direct parent, or nil.

   The disqualifying dimension is distinguished precisely:
     :parent-not-rejected           — the attempt was never rejected (outcome)
     :parent-rejection-not-final   — rejection is not final (finality)
     :parent-not-resubmittable     — rejected but not marked resubmittable-eligible
     :parent-attempt-withdrawn     — lifecycle status is not :active"
  [receipt]
  (cond
    (not= :rejected (:attempt-receipt/outcome receipt))
    :parent-not-rejected
    (not= :final (:attempt-receipt/finality receipt))
    :parent-rejection-not-final
    (not= :eligible (:attempt-receipt/resubmission-eligibility receipt))
    :parent-not-resubmittable
    (not= :active (:attempt-receipt/lifecycle-status receipt))
    :parent-attempt-withdrawn
    :else nil))

;; ── V2: submission-attempt-receipt.v2 ─────────────────────────────────────────
;;
;; V2 is the application-aware receipt. It uses a domain-separated root so a V2
;; receipt is never mistaken for V1 (and vice-versa). The V2 closed projection
;; REQUIRES :attempt-receipt/attempt-subject-root — a receipt that claims
;; application-aware issuance without binding an attempt subject is structurally
;; invalid.

(defn receipt-schema-of
  "Declared schema of a receipt (defaults to V1 for bare V1 records)."
  [receipt]
  (or (:attempt-receipt/schema receipt) receipt-schema))

(defn v2-receipt?
  "True when `receipt` declares the submission-attempt-receipt.v2 schema."
  [receipt]
  (= receipt-v2-schema (receipt-schema-of receipt)))

(defn- unsigned-receipt-projection-v2
  "V2 canonical unsigned projection: identical structure to V1 (excludes only
   :attempt-receipt/id and the validator signature), but committed under the
   V2 domain tag so V2 and V1 roots are never interchangeable."
  [receipt]
  (-> receipt
      (dissoc :attempt-receipt/id)
      (update :attempt-receipt/validator dissoc :signature)))

(defn receipt-hash-v2
  "Content-addressed identity of a V2 receipt (self hash)."
  [receipt]
  (hash-ref/sha256-ref (hc/domain-hash receipt-domain-v2 (unsigned-receipt-projection-v2 receipt))))

(defn sign-receipt-v2
  "Sign and issue a V2 receipt. Identical mechanics to V1 but under the V2
   domain. Requires :attempt-receipt/attempt-subject-root to be present."
  [receipt private-key]
  (when-not (hash-ref/valid-sha256-ref?
             (:attempt-receipt/attempt-subject-root receipt))
    (throw (ex-info "V2 receipt requires :attempt-receipt/attempt-subject-root"
                    {:reason :missing-attempt-subject-root
                     :schema receipt-v2-schema})))
  (let [signed (assoc-in receipt [:attempt-receipt/validator :signature]
                         {:signature/algorithm :ed25519
                          :signature (sed/ed25519-sign-bytes
                                      (hc/canonical-bytes (unsigned-receipt-projection-v2 receipt))
                                      private-key)})]
    (assoc signed :attempt-receipt/id (receipt-hash-v2 signed))))

(defn verify-receipt-signature-v2
  "Verify a V2 signed receipt's validator signature over its unsigned projection
   bytes, and confirm :attempt-receipt/id matches the recomputed V2 hash.
   Returns {:valid? bool :reason kw :detail str}."
  [receipt validator-public-hex]
  (let [sig (get-in receipt [:attempt-receipt/validator :signature])]
    (cond
      (nil? sig)
      {:valid? false :reason :missing-validator-signature}

      (not= :ed25519 (:signature/algorithm sig))
      {:valid? false :reason :unsupported-signature-algorithm
       :detail (:signature/algorithm sig)}

      (not= (:attempt-receipt/id receipt) (receipt-hash-v2 receipt))
      {:valid? false :reason :receipt-hash-mismatch
       :detail (str "stored " (:attempt-receipt/id receipt)
                    " recomputed " (receipt-hash-v2 receipt))}

      (not (sed/ed25519-verify-bytes
            (hc/canonical-bytes (unsigned-receipt-projection-v2 receipt))
            (:signature sig)
            validator-public-hex))
      {:valid? false :reason :invalid-signature}

      :else
      {:valid? true :reason :ok :detail (:attempt-receipt/id receipt)})))

(defn valid-receipt-v2-shape?
  "Structural validation of a V2 receipt (does not verify signatures).

   :attempt-receipt/attempt-subject-root is required and must be a valid sha256
   reference. The two-stage contract is:
     - unsigned/staging candidate: may temporarily lack the subject root during
       candidate construction (handled by the issuer, not this validator)
     - signed/final V2 receipt: subject root required
     - V2 verification: subject root required and reconstructed

   This validator enforces the FINAL contract: a valid V2 receipt must carry
   the subject root."
  [receipt]
  (and (map? receipt)
       (= receipt-v2-schema (:attempt-receipt/schema receipt))
       (string? (:attempt-receipt/submitted-bundle-root receipt))
       (hash-ref/valid-sha256-ref?
        (:attempt-receipt/attempt-subject-root receipt))
       (contains? outcomes (:attempt-receipt/outcome receipt))
       (contains? finalities (:attempt-receipt/finality receipt))
       (contains? resubmission-eligibilities (:attempt-receipt/resubmission-eligibility receipt))
       (contains? lifecycle-statuses (:attempt-receipt/lifecycle-status receipt))
       (map? (:attempt-receipt/roots receipt))
       (every? #(valid-root-shape? (get (:attempt-receipt/roots receipt) %))
               [:research-subject :execution-context :results :submission-basis])
       (map? (:attempt-receipt/validator receipt))
       (string? (get-in receipt [:attempt-receipt/validator :policy/hash]))
       (string? (get-in receipt [:attempt-receipt/validator :key/id]))))

(defn verify-receipt-signature-dispatch
  "Explicit V1/V2 dispatch for receipt signature verification.

   The schema is read from :attempt-receipt/schema (defaulting to V1). A V1
   receipt is never interpreted as V2 and vice-versa: the schema determines
   which domain tag and projection are used."
  [receipt validator-public-hex]
  (if (v2-receipt? receipt)
    (verify-receipt-signature-v2 receipt validator-public-hex)
    (verify-receipt-signature receipt validator-public-hex)))

(defn valid-receipt-dispatch?
  "Explicit V1/V2 structural dispatch. A V2 receipt with a V1 schema (or vice
   versa) is structurally invalid."
  [receipt]
  (if (v2-receipt? receipt)
    (valid-receipt-v2-shape? receipt)
    (valid-receipt-shape? receipt)))

(defn receipt-requires-subject-root?
  "True when the receipt schema requires an :attempt-receipt/attempt-subject-root."
  [receipt]
  (v2-receipt? receipt))
