(ns resolver-sim.run.release-attestation
  "Canonical release-attestation payload and fail-closed trust-policy checks."
  (:require [resolver-sim.hash.canonical :as canonical]
            [buddy.core.codecs :as codecs])
  (:import [java.security KeyFactory Signature]
           [java.security.spec X509EncodedKeySpec]))

(def payload-schema "prf-release-attestation-payload.v1")
(def signature-schema "prf-release-signature.v1")
(def verification-schema "prf-release-verification.v1")

(defn payload-hash [payload]
  (str "sha256:"
       (canonical/domain-hash "PRF_RELEASE_ATTESTATION_PAYLOAD_V1"
                              (dissoc payload :payload/hash))))

(defn build-payload [{:keys [distribution implementation release]}]
  (let [payload {:schema-version payload-schema
                 :distribution distribution
                 :implementation implementation
                 :release release}]
    (assoc payload :payload/hash (payload-hash payload))))

(defn verify-policy
  "Validate policy structure. An empty trusted-key set is valid but cannot
   authorize a release; this keeps production trust explicit and fail-closed."
  [policy]
  {:valid? (and (= "prf-release-trust-policy.v1" (:schema-version policy))
                (keyword? (:policy-id policy))
                (integer? (:policy-version policy))
                (vector? (:trusted-keys policy))
                (map? (:requirements policy))
                (= payload-schema (get-in policy [:canonicalization :payload-profile])))
   :reason (when-not (= "prf-release-trust-policy.v1" (:schema-version policy))
             :release-trust-policy-invalid)})

(def ^:private x509-ed25519-prefix
  (byte-array [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))

(defn- public-key-from-hex [hex]
  (let [raw (codecs/hex->bytes hex)
        encoded (byte-array (concat x509-ed25519-prefix raw))]
    (.generatePublic (KeyFactory/getInstance "Ed25519")
                     (X509EncodedKeySpec. encoded))))

(defn- hex [bytes] (codecs/bytes->hex bytes))

(defn sign-payload
  "Create a `prf-release-signature.v1` Ed25519 envelope. `private-key` is a
   Java PrivateKey so key-file parsing remains an explicit operational concern."
  [payload private-key key-id]
  (when-not (= (:payload/hash payload) (payload-hash payload))
    (throw (ex-info "Release payload hash mismatch" {:reason :release-payload-hash-mismatch})))
  (let [signer (Signature/getInstance "Ed25519")]
    (.initSign signer private-key)
    (.update signer (.getBytes (:payload/hash payload) "UTF-8"))
    {:schema-version signature-schema
     :key-id key-id
     :algorithm :ed25519
     :payload-hash (:payload/hash payload)
     :signature-encoding :hex
     :signature-bytes (hex (.sign signer))}))

(defn verify-signature
  "Verify one release signature against an explicit trust-policy key."
  [payload signature policy]
  (let [key (some #(when (= (:key-id signature) (:key-id %)) %) (:trusted-keys policy))]
    (cond
      (not (:valid? (verify-policy policy))) {:valid? false :reason :invalid-release-trust-policy}
      (not= signature-schema (:schema-version signature)) {:valid? false :reason :invalid-release-signature-schema}
      (not= (:payload/hash payload) (payload-hash payload)) {:valid? false :reason :release-payload-hash-mismatch}
      (not= :ed25519 (:algorithm signature)) {:valid? false :reason :unsupported-release-signature-algorithm}
      (not= (:payload/hash payload) (:payload-hash signature)) {:valid? false :reason :release-signature-payload-mismatch}
      (nil? key) {:valid? false :reason :untrusted-release-key}
      (not= :active (:status key)) {:valid? false :reason :inactive-release-key}
      :else
      (try
        (let [verifier (Signature/getInstance "Ed25519")]
          (.initVerify verifier (public-key-from-hex (:public-key key)))
          (.update verifier (.getBytes (:payload/hash payload) "UTF-8"))
          {:valid? (.verify verifier (codecs/hex->bytes (:signature-bytes signature)))
           :key-id (:key-id key)})
        (catch Exception e {:valid? false :reason :invalid-release-key-material
                            :detail (.getMessage e)})))))

(defn verify-authorization
  "Evaluate a release signature set against the explicit threshold for a
   distribution. Repeated signatures by one key count once."
  [payload signatures distribution policy]
  (let [required (get-in policy [:requirements :distribution distribution :minimum-valid-signatures])
        results (mapv #(verify-signature payload % policy) signatures)
        valid-keys (set (keep #(when (:valid? %) (:key-id %)) results))
        authorized? (and (integer? required) (<= required (count valid-keys)))]
    {:schema-version verification-schema
     :distribution distribution
     :authorization {:status (if authorized? :authorized :missing-or-insufficient)
                     :reason-code (if authorized? :release-signature-threshold-met :release-signature-threshold-not-met)
                     :valid-signature-count (count valid-keys)
                     :required-signatures required
                     :trust-policy-id (:policy-id policy)
                     :trust-policy-version (:policy-version policy)
                     :signature-results results}
     :verdict (if authorized? :release-authorized :integrity-verified-distribution)}))

(defn unsigned-verification [distribution policy]
  (verify-authorization {:payload/hash ""} [] distribution policy))
