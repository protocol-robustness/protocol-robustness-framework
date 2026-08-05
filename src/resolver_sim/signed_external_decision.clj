(ns resolver-sim.signed-external-decision
  "Generic signed-external-decision primitive (signed-external-decision.v1).

   Owns only the signing/verification mechanics for a decision produced by an
   out-of-process authority:

     - request commitment / hashing
     - canonical decision-envelope hashing with explicit domain separation
     - signer metadata and Ed25519 signing
     - signature verification against a public trust policy
     - trust-role and key-status verification

   It intentionally does NOT own sensitivity classification, sink policy,
   findings, or disclosure overrides; those belong to the sentinel domain so
   that the authority's decision cannot be confused with, or reinterpreted as,
   a release attestation or any other signed artifact.

   A verifier passes an explicit `domain-tag` string. Signatures created under
   one domain tag are never valid under another, preventing cross-protocol
   substitution."
  (:require [resolver-sim.hash.canonical :as hc]
            [clojure.walk :as walk]
            [buddy.core.codecs :as codecs])
  (:import [java.security KeyFactory Signature]
           [java.security.spec X509EncodedKeySpec]))

(def signature-schema "prf-signed-external-decision-signature.v1")

;; ── Ed25519 primitives ──────────────────────────────────────────────────────

(def ^:private x509-ed25519-prefix
  (byte-array [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))

(defn- public-key-from-hex
  "Build an Ed25519 PublicKey from a hex-encoded raw 32-byte key."
  [hex]
  (let [raw (codecs/hex->bytes hex)
        encoded (byte-array (concat x509-ed25519-prefix raw))]
    (.generatePublic (KeyFactory/getInstance "Ed25519")
                     (X509EncodedKeySpec. encoded))))

(defn- hex [bytes] (codecs/bytes->hex bytes))

(defn ed25519-sign-bytes
  "Sign UTF-8 bytes with an Ed25519 private key. Returns hex signature bytes."
  [data-bytes private-key]
  (let [signer (Signature/getInstance "Ed25519")]
    (.initSign signer private-key)
    (.update signer data-bytes)
    (hex (.sign signer))))

(defn ed25519-verify-bytes
  "Verify a hex-encoded Ed25519 signature over UTF-8 bytes with a hex public key."
  [data-bytes signature-hex public-key-hex]
  (try
    (let [verifier (Signature/getInstance "Ed25519")]
      (.initVerify verifier (public-key-from-hex public-key-hex))
      (.update verifier data-bytes)
      (.verify verifier (codecs/hex->bytes signature-hex)))
    (catch Exception _ false)))

;; ── Canonicalisation ────────────────────────────────────────────────────────

(defn normalize-decision
  "Normalize order-insensitive collection ordering for deterministic hashing.

   Vectors whose elements are all keywords/strings/nils (e.g. reason-code
   lists) are sorted so that nondeterministic producer ordering yields the
   same canonical decision. Structured vectors (of maps, steps, etc.) are left
   in producer order."
  [envelope]
  (walk/postwalk
   (fn [x]
     (if (and (vector? x) (seq x) (every? #(or (keyword? %) (string? %) (nil? %)) x))
       (vec (sort-by pr-str x))
       x))
   envelope))

(defn preimage
  "The canonical preimage of an envelope: the envelope minus its signature,
   normalized for deterministic hashing."
  [envelope]
  (normalize-decision (dissoc envelope :signature)))

(defn envelope-hash
  "Compute the domain-separated hash of a decision envelope.

   HASH = SHA256(domain-tag || canonical(preimage))
   The signature is excluded from the preimage so the hash does not depend on
   itself."
  [domain-tag envelope]
  (str "sha256:" (hc/domain-hash domain-tag (preimage envelope))))

;; ── Signing ────────────────────────────────────────────────────────────────

(defn sign-envelope
  "Sign a decision envelope with an Ed25519 private key.

   Returns the envelope with a :signature map attached:
     {:schema-version prf-signed-external-decision-signature.v1
      :key-id <kw>
      :algorithm :ed25519
      :signed-hash <sha256:... envelope hash>
      :signature-encoding :hex
      :signature-bytes <hex>}

   `domain-tag` is the UTF-8 string that separates this decision's domain from
   all others. The signed-hash binds the complete envelope preimage."
  [envelope domain-tag private-key key-id]
  (let [h (envelope-hash domain-tag envelope)]
    (assoc envelope
           :signature {:schema-version signature-schema
                       :key-id key-id
                       :algorithm :ed25519
                       :signed-hash h
                       :signature-encoding :hex
                       :signature-bytes (ed25519-sign-bytes (.getBytes h "UTF-8") private-key)})))

;; ── Trust policy & verification ─────────────────────────────────────────────

(defn- trust-key-for
  "Find a trust-policy key entry by :key/id. Keys are looked up by the
   signature's :key-id so a replayed signature names the same key."
  [trust-policy key-id]
  (some #(when (= key-id (:key/id %)) %) (:trusted-keys trust-policy)))

(defn verify-envelope
  "Verify a signed decision envelope against a public trust policy.

   trust-policy: {:trusted-keys [{:key/id <kw> :key/public <hex> :key/role <kw> :key/status <:active|...>}]}
   expected-role: the :key/role the authority key must carry (e.g. :sensitivity-sentinel)
   domain-tag: the same domain tag the signer used.

   Returns {:valid? true :key-id <kw>} or {:valid? false :reason <kw> :detail <str>}.

   The verifier recomputes the envelope hash from the embedded preimage and
   compares it to the signature's :signed-hash before doing the cryptographic
   check, then requires the key role and status to be valid."
  [envelope domain-tag trust-policy expected-role]
  (let [sig (:signature envelope)
        recomputed (envelope-hash domain-tag envelope)
        key-id (:key-id sig)
        key (when key-id (trust-key-for trust-policy key-id))]
    (cond
      (nil? sig)
      {:valid? false :reason :missing-signature}

      (not= signature-schema (:schema-version sig))
      {:valid? false :reason :invalid-signature-schema :detail (:schema-version sig)}

      (not= :ed25519 (:algorithm sig))
      {:valid? false :reason :unsupported-signature-algorithm :detail (:algorithm sig)}

      (nil? key-id)
      {:valid? false :reason :missing-key-id}

      (not= (:signed-hash sig) recomputed)
      {:valid? false :reason :signed-hash-mismatch
       :detail (str "signed " (:signed-hash sig) " recomputed " recomputed)}

      (nil? key)
      {:valid? false :reason :untrusted-key :key-id key-id}

      (not= expected-role (:key/role key))
      {:valid? false :reason :wrong-key-role
       :detail (str "expected " expected-role " got " (:key/role key))}

      (not= :active (:key/status key))
      {:valid? false :reason :inactive-key :detail (:key/status key)}

      :else
      (let [ok (ed25519-verify-bytes (.getBytes (:signed-hash sig) "UTF-8")
                                     (:signature-bytes sig)
                                     (:key/public key))]
        (if ok
          {:valid? true :key-id key-id}
          {:valid? false :reason :invalid-signature})))))

;; ── Request commitment ──────────────────────────────────────────────────────

(defn request-hash
  "Commit to the full content of a request via a dedicated domain tag.
   Excludes :request/hash itself (self-referential exclusion)."
  [request-domain-tag request]
  (str "sha256:" (hc/domain-hash request-domain-tag (normalize-decision (dissoc request :request/hash)))))

(defn attach-request-hash
  "Attach a committed :request/hash to a request map."
  [request-domain-tag request]
  (assoc request :request/hash (request-hash request-domain-tag request)))
