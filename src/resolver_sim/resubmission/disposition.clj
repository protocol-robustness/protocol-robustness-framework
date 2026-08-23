(ns resolver-sim.resubmission.disposition
  "Immutable attempt-disposition events (attempt-disposition.v1).

   Content-addressed receipts cannot change state without changing identity, so
   lifecycle transitions are IMMUTABLE, signed disposition events. The effective
   state of an attempt is derived from the latest valid disposition; the
   original receipt never mutates.

   Canonical contract:

     disposition-hash = \"sha256:\" + domain-hash(
         \"prf.attempt-disposition.v1\",
         canonical-bytes-v2(unsigned-disposition-projection))

   The unsigned projection excludes ONLY the signature. The signature covers the
   same unsigned projection bytes."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as sed]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const disposition-schema "attempt-disposition.v1")
(def ^:const disposition-domain :prf-attempt-disposition-v1)

(def ^:const disposition-statuses
  "Event vocabulary. These are not receipt lifecycle statuses."
  #{:pending-review :final :withdrawn :revoked :superseded})

(def ^:const disposition->lifecycle-status
  "The sole mapping from an immutable disposition event to the receipt lifecycle
   vocabulary. Review and finality describe disposition workflow, not a change
   to receipt eligibility; only explicit lifecycle events deactivate a receipt."
  {:pending-review :active
   :final :active
   :withdrawn :withdrawn
   :revoked :revoked
   :superseded :superseded})

(defn unsigned-disposition-projection
  "Everything except the signature."
  [disposition]
  (dissoc disposition :attempt-disposition/signature))

(defn disposition-hash
  [disposition]
  (hash-ref/sha256-ref (hc/domain-hash disposition-domain (unsigned-disposition-projection disposition))))

(defn sign-disposition
  "Attach the signing authority signature over the unsigned projection bytes."
  [disposition private-key]
  (assoc disposition
         :attempt-disposition/signature
         {:signature/algorithm :ed25519
          :signature (sed/ed25519-sign-bytes
                      (hc/canonical-bytes (unsigned-disposition-projection disposition))
                      private-key)}))

(defn verify-disposition
  "Verify a disposition signature and structural shape. Returns
   {:valid? bool :reason kw :detail str}."
  [disposition public-hex]
  (let [sig (:attempt-disposition/signature disposition)]
    (cond
      (not= disposition-schema (:attempt-disposition/schema disposition))
      {:valid? false :reason :invalid-disposition-schema
       :detail (:attempt-disposition/schema disposition)}

      (not (contains? disposition-statuses (:attempt-disposition/status disposition)))
      {:valid? false :reason :invalid-disposition-status
       :detail (:attempt-disposition/status disposition)}

      (nil? sig)
      {:valid? false :reason :missing-disposition-signature}

      (not (sed/ed25519-verify-bytes
            (hc/canonical-bytes (unsigned-disposition-projection disposition))
            (:signature sig)
            public-hex))
      {:valid? false :reason :invalid-disposition-signature}

      :else
      {:valid? true :reason :ok})))

(def ^:const authority-context-schema
  "resubmission-disposition-authority-context.v1")

(defn verify-authorized-disposition
  "Verify a disposition against an admitted authority context. The context is
   produced only after genesis/governance authorization at the authoritative
   chain boundary; a state root alone is deliberately insufficient authority.

   A supplied `:signature/public-key` is a signer claim, not a trust anchor. In
   an authoritative context it must equal the admitted key, making a valid
   attacker-key signature distinguishable from an authorized signature."
  [disposition authority-context]
  (let [public-key (:authority/public-key authority-context)
        signer-key (get-in disposition [:attempt-disposition/signature :signature/public-key])]
    (cond
      (not= authority-context-schema (:authority/context-schema authority-context))
      {:valid? false :reason :invalid-disposition-authority-context}

      (not (contains? (set (:authority/permitted-actions authority-context))
                      :prf.resubmission/apply-disposition))
      {:valid? false :reason :disposition-action-not-authorized}

      (nil? public-key)
      {:valid? false :reason :disposition-authority-not-configured}

      (and signer-key (not= signer-key public-key))
      {:valid? false :reason :unauthorized-disposition-key}

      :else
      (verify-disposition disposition public-key))))

;; ─── v2 authoritative disposition (resubmission-authoritative-disposition.v2)

(def ^:const authoritative-disposition-schema
  "Schema identifier for resubmission-authoritative-disposition.v2."
  "attempt-disposition-authoritative.v2")

(def ^:const authoritative-disposition-domain
  :prf-resubmission-authoritative-disposition-v2)

(defn unsigned-authoritative-disposition-projection
  "Everything except the signature."
  [disposition]
  (dissoc disposition :attempt-disposition/signature))

(defn authoritative-disposition-hash
  "Canonical content hash of an unsigned authoritative disposition."
  [disposition]
  (hash-ref/sha256-ref
   (hc/domain-hash authoritative-disposition-domain
                   (unsigned-authoritative-disposition-projection disposition))))

(defn sign-authoritative-disposition
  "Attach an Ed25519 signature over the unsigned authoritative disposition."
  [disposition private-key public-hex]
  (assoc disposition
         :attempt-disposition/signature
         {:signature/algorithm :ed25519
          :signature/public-key public-hex
          :signature (sed/ed25519-sign-bytes
                      (hc/canonical-bytes (unsigned-authoritative-disposition-projection disposition))
                      private-key)}))

(defn verify-authoritative-disposition
  "Verify an authoritative disposition signature against the expected public key."
  [disposition expected-public-hex]
  (let [sig (:attempt-disposition/signature disposition)
        disposition-public-key (get-in disposition [:attempt-disposition/signature :signature/public-key])
        signer-key (or disposition-public-key expected-public-hex)]
    (cond
      (not= authoritative-disposition-schema
            (:attempt-disposition/schema disposition))
      {:valid? false :reason :invalid-disposition-schema}

      (nil? expected-public-hex)
      {:valid? false :reason :invalid-public-key}

      (not= :prf.resubmission/admit
            (:attempt-disposition/action disposition))
      {:valid? false :reason :invalid-disposition-action}

      (nil? sig)
      {:valid? false :reason :missing-disposition-signature}

      (not= expected-public-hex signer-key)
      {:valid? false :reason :unauthorized-signer-key}

      (not (sed/ed25519-verify-bytes
            (hc/canonical-bytes (unsigned-authoritative-disposition-projection disposition))
            (:signature sig)
            expected-public-hex))
      {:valid? false :reason :invalid-disposition-signature}

      :else
      {:valid? true :reason :ok})))

(defn valid-disposition-chain?
  "Validate an ordered (most-recent-first) disposition chain for one receipt.

   Every item must verify, bind to `attempt-receipt-hash`, and point to the hash
   of the following item. The tail must explicitly have no predecessor. This
   fails closed rather than selecting a valid-looking item from an incoherent
   caller-supplied list."
  [dispositions attempt-receipt-hash verify-fn]
  (let [items (vec dispositions)]
    (and (every? #(true? (:valid? (verify-fn %))) items)
         (every? #(= attempt-receipt-hash
                     (:attempt-disposition/attempt-receipt-hash %))
                 items)
         (every? true?
                 (map (fn [current previous]
                        (= (:attempt-disposition/previous-disposition-hash current)
                           (disposition-hash previous)))
                      items
                      (rest items)))
         (or (empty? items)
             (nil? (:attempt-disposition/previous-disposition-hash (peek items)))))))

(defn latest-disposition
  "Resolve the effective lifecycle state from a coherent, receipt-bound ordered
   disposition chain. Returns nil when the chain is absent or invalid."
  [dispositions attempt-receipt-hash verify-fn]
  (when (and (seq dispositions)
             (valid-disposition-chain? dispositions attempt-receipt-hash verify-fn))
    (let [d (first dispositions)]
      {:disposition d
       :status (:attempt-disposition/status d)
       :previous-hash (:attempt-disposition/previous-disposition-hash d)})))

(defn effective-lifecycle-status
  "The effective lifecycle status of a receipt. Defaults to :active only when
   no dispositions exist; an invalid non-empty chain is returned as nil so a
   caller cannot mistake it for an active receipt."
  [dispositions attempt-receipt-hash verify-fn]
  (if (empty? dispositions)
    :active
    (some-> (latest-disposition dispositions attempt-receipt-hash verify-fn)
            :status
            disposition->lifecycle-status)))
