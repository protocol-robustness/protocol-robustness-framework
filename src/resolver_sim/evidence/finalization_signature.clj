(ns resolver-sim.evidence.finalization-signature
  "(ns resolver-sim.evidence.finalization-signature)Detached Ed25519 signatures for evidence-finalization.v2 payloads."
  (:require [buddy.core.codecs :as codecs]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.nio.file Files StandardCopyOption AtomicMoveNotSupportedException]
           [java.security Signature PublicKey PrivateKey]
           [java.util UUID]))

(def schema-version "attestation-signature.v1")
(def payload-type "application/vnd.prf.evidence-finalization.v2+json")
(def ^:private sha256-ref-pattern #"^sha256:[0-9a-f]{64}$")
(def ^:private signature-hex-pattern #"^[0-9a-f]{128}$")

(defn signing-bytes [payload-hash]
  (.getBytes (str payload-type "\n" payload-hash) "UTF-8"))

(defn validate-envelope [envelope]
  (let [errors (cond-> []
                 (not= schema-version (:schema-version envelope)) (conj :unsupported-schema-version)
                 (not= payload-type (get-in envelope [:payload :payload-type])) (conj :unsupported-payload-type)
                 (not (re-matches sha256-ref-pattern (get-in envelope [:payload :payload-hash]))) (conj :invalid-payload-hash)
                 (not= "Ed25519" (get-in envelope [:signature :algorithm])) (conj :unsupported-algorithm)
                 (not (string? (get-in envelope [:signature :key-id]))) (conj :invalid-key-id)
                 (not (re-matches signature-hex-pattern (get-in envelope [:signature :value]))) (conj :invalid-signature-value))]
    {:valid? (empty? errors) :errors errors}))

(defn build-envelope
  ([payload-hash key-id private-key]
   (build-envelope payload-hash key-id private-key nil))
  ([payload-hash key-id private-key subject]
   (when-not (re-matches sha256-ref-pattern payload-hash)
     (throw (ex-info "Expected typed SHA-256 payload hash" {:payload-hash payload-hash})))
   (let [signer (Signature/getInstance "Ed25519")]
     (.initSign signer ^PrivateKey private-key)
     (.update signer (signing-bytes payload-hash))
     {:schema-version schema-version
      :payload (merge {:schema-version "evidence-finalization.v2"
                       :payload-type payload-type
                       :payload-hash payload-hash
                       :subject {:kind "run-evidence-finalization.v1"
                                 :hash payload-hash}}
                      (when subject {:subject subject}))
      :signature {:algorithm "Ed25519"
                  :key-id key-id
                  :value (codecs/bytes->hex (.sign signer))}})))

(defn verify-envelope [envelope public-key]
  (let [shape (validate-envelope envelope)]
    (if-not (:valid? shape)
      (assoc shape :reason :malformed-envelope)
      (try
        (let [verifier (Signature/getInstance "Ed25519")]
          (.initVerify verifier ^PublicKey public-key)
          (.update verifier (signing-bytes (get-in envelope [:payload :payload-hash])))
          (if (.verify verifier (codecs/hex->bytes (get-in envelope [:signature :value])))
            {:valid? true :key-id (get-in envelope [:signature :key-id])}
            {:valid? false :reason :invalid-signature}))
        (catch Exception e {:valid? false :reason :signature-verification-error
                            :detail (.getMessage e)})))))

(defn- atomic-write! [path content]
  (let [target (io/file path)
        temp (io/file (.getParentFile target) (str "." (.getName target) ".tmp-" (UUID/randomUUID)))]
    (.mkdirs (.getParentFile target))
    (spit temp content)
    (try
      (Files/move (.toPath temp) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (catch AtomicMoveNotSupportedException _
        (Files/move (.toPath temp) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (.getPath target)))

(defn write-envelope!
  ([path payload-hash key-id private-key]
   (write-envelope! path payload-hash key-id private-key nil))
  ([path payload-hash key-id private-key subject]
   (let [envelope (build-envelope payload-hash key-id private-key subject)
         validation (validate-envelope envelope)]
     (when-not (:valid? validation)
       (throw (ex-info "Generated finalization signature envelope is invalid" validation)))
     {:path (atomic-write! path (json/write-str envelope {:indent true}))
      :envelope envelope})))
