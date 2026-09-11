(ns resolver-sim.run.release-attestation
  "Canonical release-attestation payload and fail-closed trust-policy checks."
  (:require [resolver-sim.hash.canonical :as canonical]
            [buddy.core.codecs :as codecs]
            [resolver-sim.hash.reference :as hash-ref])
  (:import [java.security KeyFactory Signature]
           [java.security.spec X509EncodedKeySpec]))

(def ^:const payload-schema "prf-release-attestation-payload.v1")
(def ^:const signature-schema "prf-release-signature.v1")
(def ^:const verification-schema "prf-release-verification.v1")

(defn payload-hash [payload]
  (hash-ref/sha256-ref
   (canonical/domain-hash :prf-release-attestation-payload-v1
                          (dissoc payload :payload/hash))))

(defn build-payload [{:keys [distribution implementation release]}]
  (let [payload {:schema-version payload-schema
                 :distribution distribution
                 :implementation implementation
                 :release release}]
    (assoc payload :payload/hash (payload-hash payload))))

(def ^:private ed25519-public-key-pattern #"[0-9a-f]{64}")

(defn verify-policy
  "Validate a release trust policy before it can authorize signatures. An empty
   key set is structurally valid for the shipped unconfigured template, but its
   positive thresholds still prevent authorization."
  [policy]
  (let [trusted-keys (:trusted-keys policy)
        requirements (get-in policy [:requirements :distribution])
        key-ids (map :key-id trusted-keys)
        valid? (and (= "prf-release-trust-policy.v1" (:schema-version policy))
                    (keyword? (:policy-id policy))
                    (#{:unconfigured :active :retired} (:policy/status policy))
                    (and (integer? (:policy-version policy)) (pos? (:policy-version policy)))
                    (vector? trusted-keys)
                    (= (count key-ids) (count (distinct key-ids)))
                    (every? #(and (string? (:key-id %)) (seq (:key-id %))
                                  (#{:active :retired :revoked} (:status %))
                                  (boolean (re-matches ed25519-public-key-pattern (:public-key %))))
                            trusted-keys)
                    (map? requirements)
                    (every? #(and (keyword? %)
                                  (integer? (get-in requirements [% :minimum-valid-signatures]))
                                  (pos? (get-in requirements [% :minimum-valid-signatures])))
                            (keys requirements))
                    (= payload-schema (get-in policy [:canonicalization :payload-profile])))]
    {:valid? valid?
     :reason (when-not valid? :release-trust-policy-invalid)}))

(def ^:private x509-ed25519-prefix
  (byte-array [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))

(defn- public-key-from-hex [hex]
  (let [raw (codecs/hex->bytes hex)
        encoded (byte-array (concat x509-ed25519-prefix raw))]
    (.generatePublic (KeyFactory/getInstance "Ed25519")
                     (X509EncodedKeySpec. encoded))))

(defn- hex [bytes] (codecs/bytes->hex bytes))

(defn policy-authorizes-new-release?
  "True only for a structurally valid policy explicitly activated for new
   release authorization. Cryptographic signature verification intentionally
   remains usable for historical evidence under retired policies."
  [policy]
  (and (:valid? (verify-policy policy))
       (= :active (:policy/status policy))))

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
  (let [policy-valid? (:valid? (verify-policy policy))
        policy-active? (policy-authorizes-new-release? policy)
        required (get-in policy [:requirements :distribution distribution :minimum-valid-signatures])
        results (mapv #(verify-signature payload % policy) signatures)
        valid-keys (set (keep #(when (:valid? %) (:key-id %)) results))
        threshold-met? (and (integer? required) (pos? required)
                            (<= required (count valid-keys)))
        authorized? (and policy-active? threshold-met?)
        reason-code (cond
                      (not policy-valid?) :release-trust-policy-invalid
                      (not policy-active?) :release-policy-not-active
                      (not (contains? (get-in policy [:requirements :distribution] {}) distribution)) :release-distribution-not-authorized
                      (not threshold-met?) :release-signature-threshold-not-met
                      :else :release-signature-threshold-met)]
    {:schema-version verification-schema
     :distribution distribution
     :authorization {:status (if authorized? :authorized :missing-or-insufficient)
                     :reason-code reason-code
                     :policy/status (:policy/status policy)
                     :valid-signature-count (count valid-keys)
                     :required-signatures required
                     :trust-policy-id (:policy-id policy)
                     :trust-policy-version (:policy-version policy)
                     :signature-results results}
     :verdict (if authorized? :release-authorized :integrity-verified-distribution)}))

(defn unsigned-verification [distribution policy]
  (verify-authorization {:payload/hash ""} [] distribution policy))
