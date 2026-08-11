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

(def disposition-schema "attempt-disposition.v1")
(def disposition-domain "prf.attempt-disposition.v1")

(def disposition-statuses
  "Event vocabulary. These are not receipt lifecycle statuses."
  #{:pending-review :final :withdrawn :revoked :superseded})

(def disposition->lifecycle-status
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
