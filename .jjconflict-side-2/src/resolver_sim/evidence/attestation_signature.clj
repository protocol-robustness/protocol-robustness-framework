(ns resolver-sim.evidence.attestation-signature
  "Strict v1 attestation signature envelope and canonical payload helpers.

   This namespace validates envelope data only. Trusted key resolution and
   cryptographic verification are deliberately deferred to later work packages."
  (:require [resolver-sim.evidence.attestation :as att]
            [resolver-sim.hash.canonical :as hc]
            [buddy.core.codecs :as codecs]
            [resolver-sim.hash.reference :as hash-ref])
  (:import [java.security KeyFactory Signature]
           [java.security.spec X509EncodedKeySpec]
           [java.time Instant]))

(def ^:const signature-version "attestation-signature.v1")
(def ^:private sha256-ref-pattern #"sha256:[0-9a-f]{64}")
(def ^:private hex-64-pattern #"[0-9a-f]{64}")
(def ^:private hex-128-pattern #"[0-9a-f]{128}")

(defn signing-payload-hash
  "Return the exact, domain-separated SHA-256 reference signed by v1 envelopes."
  [attestation]
  ;; attestation-record's projection must receive the record, not the already
  ;; projected {:intent ... :artifact ...} wrapper. This is the same content
  ;; identity stored in :attestation/hash.
  (hash-ref/sha256-ref (hc/hash-with-intent {:hash/intent :attestation-record}
                                            attestation)))

(defn signature-envelope-v1?
  [signature]
  (= signature-version (:signature/version signature)))

(defn validate-signature-envelope
  "Validate an attestation-signature.v1 envelope before any crypto operation.
   Returns {:valid? true} or {:valid? false :errors [keyword ...]}. Legacy
   envelopes without :signature/version are reported as :legacy-envelope."
  [signature]
  (let [errors (cond-> []
                 (not (map? signature)) (conj :signature-not-a-map)
                 (and (map? signature) (not= signature-version (:signature/version signature)))
                 (conj (if (:signature/version signature) :unsupported-signature-version :legacy-envelope))
                 (and (map? signature) (= signature-version (:signature/version signature))
                      (not (contains? att/supported-signature-algorithms (:algorithm signature)))) (conj :unsupported-algorithm)
                 (and (map? signature) (= signature-version (:signature/version signature))
                      (not= :hex (:signature-encoding signature))) (conj :unsupported-signature-encoding)
                 (and (map? signature) (= signature-version (:signature/version signature))
                      (not (and (string? (:key-id signature)) (seq (:key-id signature))))) (conj :invalid-key-id)
                 (and (map? signature) (= signature-version (:signature/version signature))
                      (not (and (string? (:signature-bytes signature))
                                (re-matches hex-128-pattern (:signature-bytes signature))))) (conj :invalid-signature-bytes)
                 (and (map? signature) (= signature-version (:signature/version signature))
                      (not (and (string? (:payload-hash signature))
                                (re-matches sha256-ref-pattern (:payload-hash signature))))) (conj :invalid-payload-hash))]
    (if (seq errors) {:valid? false :errors errors} {:valid? true})))

(defn validate-envelope-for-attestation
  "Validate v1 envelope shape and require its payload hash to equal the
   recomputed canonical payload hash."
  [attestation]
  (let [signature (:attestation/signature attestation)
        envelope (validate-signature-envelope signature)]
    (if-not (:valid? envelope)
      envelope
      (let [computed (signing-payload-hash attestation)]
        (if (= computed (:payload-hash signature))
          {:valid? true :payload-hash computed}
          {:valid? false :errors [:payload-hash-mismatch]
           :expected computed :actual (:payload-hash signature)})))))

(def ^:private x509-ed25519-prefix
  ;; DER SubjectPublicKeyInfo prefix for a raw 32-byte Ed25519 public key.
  (byte-array [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))

(defn- parse-instant [value]
  (try (Instant/parse value) (catch Exception _ nil)))

(defn- public-key-from-hex [hex]
  (let [raw (codecs/hex->bytes hex)
        encoded (byte-array (concat x509-ed25519-prefix raw))]
    (.generatePublic (KeyFactory/getInstance "Ed25519") (X509EncodedKeySpec. encoded))))

(defn- key-valid-at? [key signed-at]
  (let [from (parse-instant (:valid-from key))
        until (some-> (:valid-until key) parse-instant)]
    (and from signed-at
         (not (.isBefore signed-at from))
         (or (nil? until) (.isBefore signed-at until)))))

(defn verify-attestation-signature
  "Verify a v1 envelope using only an externally trusted normalized registry.
   Returns a structured result; registry snapshot trust is intentionally a
   bundle-layer concern and is not inferred here."
  ([attestation trusted-attestor-registry]
   (verify-attestation-signature attestation trusted-attestor-registry {}))
  ([attestation trusted-attestor-registry
    {:keys [allow-retired-attestors? allow-retired-keys? revoked-key-policy]
     :or {allow-retired-attestors? false allow-retired-keys? false
          revoked-key-policy :reject-all}}]
   (let [envelope-result (validate-envelope-for-attestation attestation)
         attestor-id (:attestation/attestor-id attestation)
         signature (:attestation/signature attestation)
         attestor (some #(when (= attestor-id (:id %)) %) (:attestors trusted-attestor-registry))
         signed-at (parse-instant (:attestation/signed-at attestation))
         key (when attestor (some #(when (= (:key-id signature) (:key-id %)) %) (:keys attestor)))]
     (cond
       (not (:valid? envelope-result)) {:valid? false :reason :malformed-signature :detail envelope-result}
       (nil? signed-at) {:valid? false :reason :invalid-signed-at}
       (nil? attestor) {:valid? false :reason :unknown-attestor :attestor-id attestor-id}
       (and (= :retired (:status attestor)) (not allow-retired-attestors?)) {:valid? false :reason :retired-attestor}
       (not= :active (:status attestor)) {:valid? false :reason :inactive-attestor}
       (nil? key) {:valid? false :reason :unknown-key :attestor-id attestor-id :key-id (:key-id signature)}
       (and (= :retired (:status key)) (not allow-retired-keys?)) {:valid? false :reason :retired-key :key-id (:key-id key)}
       (= :revoked (:status key)) {:valid? false :reason :revoked-key :key-id (:key-id key) :policy revoked-key-policy}
       (not= :active (:status key)) {:valid? false :reason :inactive-key :key-id (:key-id key)}
       (not (key-valid-at? key signed-at)) {:valid? false :reason :key-not-valid-at-signing-time :key-id (:key-id key)}
       :else
       (try
         (let [verifier (Signature/getInstance "Ed25519")
               public-key (public-key-from-hex (:public-key key))
               payload-hash (signing-payload-hash attestation)]
           (.initVerify verifier public-key)
           (.update verifier (.getBytes payload-hash "UTF-8"))
           (if (.verify verifier (codecs/hex->bytes (:signature-bytes signature)))
             {:valid? true :attestor-id attestor-id :key-id (:key-id key)
              :algorithm (first att/supported-signature-algorithms)
              :payload-hash payload-hash}
             {:valid? false :reason :invalid-signature :key-id (:key-id key)}))
         (catch Exception e
           {:valid? false :reason :invalid-key-material :detail (.getMessage e)}))))))
