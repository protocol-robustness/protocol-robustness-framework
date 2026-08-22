(ns resolver-sim.resubmission.receipt
  "Submission-attempt receipt (submission-attempt-receipt.v1).

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

   Direct resubmission eligibility requires:
     {:outcome :rejected :finality :final
      :resubmission-eligibility :eligible :lifecycle-status :active}"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const receipt-schema "submission-attempt-receipt.v1")
(def ^:const receipt-domain :prf-submission-attempt-receipt-v1)

(def ^:const outcomes #{:accepted :rejected :system-failure :indeterminate})
(def ^:const finalities #{:provisional :final})
(def ^:const resubmission-eligibilities #{:eligible :ineligible :retry-same-attempt})
(def ^:const lifecycle-statuses #{:active :withdrawn :revoked :superseded})
(def ^:const root-statuses #{:verified :invalid :unavailable})
(def ^:const results-statuses #{:verified :invalid :missing})
(def ^:const submitter-statuses #{:verified :authenticated-session :claimed :missing})
(def ^:const identity-sources #{:publisher-signature :submission-auth :authenticated-session})

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

(defn valid-root-shape?
  "True when a receipt root entry is {:root/schema str :status kw :hash str}."
  [root]
  (and (map? root)
       (string? (:root/schema root))
       (contains? root-statuses (:status root))
       (or (string? (:hash root))
           (and (= :missing (:status root)) (nil? (:hash root))))))

(defn valid-receipt-shape?
  "Structural validation of a receipt (does not verify signatures)."
  [receipt]
  (and (map? receipt)
       (= receipt-schema (:attempt-receipt/schema receipt))
       (string? (:attempt-receipt/submitted-bundle-root receipt))
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
     :parent-not-rejected          — the attempt was never rejected (outcome)
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
