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

;; ── Generic envelope core ─────────────────────────────────────────────────────
;;
;; Wire format shared by every detached attestation in the repo:
;;   Ed25519 over UTF-8 bytes of  "<payload-type>\n<payload-hash>"
;;   where payload-hash is a typed "sha256:<64 hex>" reference.
;;
;; The fixed constants above describe the evidence-finalization profile; other
;; producers (e.g. benchmark final-evidence attestation) reuse these generic
;; functions with their own schema-version/payload-type so there is exactly one
;; signature convention, not one per subsystem.

(def ^:private sha256-ref-pattern #"^sha256:[0-9a-f]{64}$")
(def ^:private signature-hex-pattern #"^[0-9a-f]{128}$")

(defn signing-bytes* [payload-type payload-hash]
  (.getBytes (str payload-type "\n" payload-hash) "UTF-8"))

(defn validate-envelope*
  "Validate a detached signature envelope against an explicit profile."
  [{:keys [envelope-schema-version payload-type subject-schema-version]} envelope]
  (let [errors (cond-> []
                 (not= envelope-schema-version (:schema-version envelope)) (conj :unsupported-schema-version)
                 (not= payload-type (get-in envelope [:payload :payload-type])) (conj :unsupported-payload-type)
                 (not (re-matches sha256-ref-pattern (get-in envelope [:payload :payload-hash]))) (conj :invalid-payload-hash)
                 (not= "Ed25519" (get-in envelope [:signature :algorithm])) (conj :unsupported-algorithm)
                 (not (string? (get-in envelope [:signature :key-id]))) (conj :invalid-key-id)
                 (not (re-matches signature-hex-pattern (get-in envelope [:signature :value]))) (conj :invalid-signature-value)
                 (when subject-schema-version
                   (not= subject-schema-version (get-in envelope [:payload :subject :kind]))) (conj :unsupported-subject-kind)]
    {:valid? (empty? errors) :errors errors})))

(defn build-envelope*
  "Build a detached Ed25519 envelope under an explicit profile. `config`:
   {:envelope-schema-version :payload-type :payload-schema-version
    :subject-kind} — see validate-envelope* for consumed keys."
  ([config payload-hash key-id private-key]
   (build-envelope* config payload-hash key-id private-key nil))
  ([{:keys [envelope-schema-version payload-type payload-schema-version subject-kind]}
    payload-hash key-id private-key subject]
   (when-not (re-matches sha256-ref-pattern payload-hash)
     (throw (ex-info "Expected typed SHA-256 payload hash" {:payload-hash payload-hash})))
   (let [signer (Signature/getInstance "Ed25519")]
     (.initSign signer ^PrivateKey private-key)
     (.update signer (signing-bytes* payload-type payload-hash))
     {:schema-version envelope-schema-version
      :payload (merge {:schema-version payload-schema-version
                       :payload-type payload-type
                       :payload-hash payload-hash
                       :subject {:kind subject-kind
                                 :hash payload-hash}}
                      (when subject {:subject subject}))
      :signature {:algorithm "Ed25519"
                  :key-id key-id
                  :value (codecs/bytes->hex (.sign signer))}})))

(defn verify-envelope*
  "Verify a detached envelope produced by build-envelope* under the same
   profile config. The signing bytes are derived from the envelope's own
   declared payload-type, so verification never re-guesses the profile."
  [{:keys [envelope-schema-version] :as _config} envelope public-key]
  (let [shape (validate-envelope* _config envelope)]
    (if-not (:valid? shape)
      (assoc shape :reason :malformed-envelope)
      (try
        (let [verifier (Signature/getInstance "Ed25519")]
          (.initVerify verifier ^PublicKey public-key)
          (.update verifier
                   (signing-bytes* (get-in envelope [:payload :payload-type])
                                   (get-in envelope [:payload :payload-hash])))
          (if (.verify verifier (codecs/hex->bytes (get-in envelope [:signature :value])))
            {:valid? true :key-id (get-in envelope [:signature :key-id])}
            {:valid? false :reason :invalid-signature}))
        (catch Exception e {:valid? false :reason :signature-verification-error
                            :detail (.getMessage e)}))))

(defn signing-bytes [payload-hash]
  (signing-bytes* payload-type payload-hash))

(defn validate-envelope [envelope]
  (validate-envelope* {:envelope-schema-version schema-version
                       :payload-type payload-type
                       :subject-schema-version "run-evidence-finalization.v1"}
                      envelope))

(defn build-envelope
  ([payload-hash key-id private-key]
   (build-envelope payload-hash key-id private-key nil))
  ([payload-hash key-id private-key subject]
   (build-envelope* {:envelope-schema-version schema-version
                     :payload-type payload-type
                     :payload-schema-version "evidence-finalization.v2"
                     :subject-kind "run-evidence-finalization.v1"}
                    payload-hash key-id private-key subject)))

(defn verify-envelope [envelope public-key]
  (verify-envelope* {:envelope-schema-version schema-version
                     :payload-type payload-type}
                    envelope public-key))

;; ── evidence-finalization.v2 profile (original, delegating) ──────────────────

(defn- sha256-ref [file]

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
