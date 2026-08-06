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
            [resolver-sim.signed-external-decision :as sed]))

(def disposition-schema "attempt-disposition.v1")
(def disposition-domain "prf.attempt-disposition.v1")

(def disposition-statuses
  #{:pending-review :final :withdrawn :revoked :superseded})

(defn unsigned-disposition-projection
  "Everything except the signature."
  [disposition]
  (dissoc disposition :attempt-disposition/signature))

(defn disposition-hash
  [disposition]
  (str "sha256:" (hc/domain-hash disposition-domain (unsigned-disposition-projection disposition))))

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

(defn latest-disposition
  "Resolve the effective lifecycle state from an ordered seq of valid
   dispositions (most-recent-first, as established by the store / previous
   hash chain). Returns {:disposition <map> :status kw :previous-hash <str|nil>}
   or nil when no valid disposition is present.

   `verify-fn` is called on each candidate to filter to valid signatures."
  [dispositions verify-fn]
  (let [valid (filterv #(true? (:valid? (verify-fn %))) dispositions)]
    (when (seq valid)
      (let [d (first valid)]
        {:disposition d
         :status (:attempt-disposition/status d)
         :previous-hash (:attempt-disposition/previous-disposition-hash d)}))))

(defn effective-lifecycle-status
  "The effective lifecycle status of an attempt given its dispositions
   (most-recent-first) and a verifier. Defaults to :active when no disposition
   exists."
  [dispositions verify-fn]
  (or (:status (latest-disposition dispositions verify-fn)) :active))
