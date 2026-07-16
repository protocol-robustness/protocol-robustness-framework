(ns resolver-sim.evidence.finalization-signing
  "Protected-runtime signing and externally pinned trust evaluation for
   run-evidence-finalization signatures. Private keys never(ns resolver-sim.evidence.finalization-signing) enter bundles."
  (:require [buddy.core.codecs :as codecs]
              [clojure.data.json :as json]
              [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.evidence.finalization-signature :as envelope]
                        [resolver-sim.evidence.timestamping :as timestamping])
  (:import [java.nio.file Files StandardCopyOption AtomicMoveNotSupportedException]
             [java.io StringReader]
             [java.security KeyFactory]
             [org.bouncycastle.tsp TimeStampResponse]
             [org.bouncycastle.cert X509CertificateHolder]
             [org.bouncycastle.cms.jcajce JcaSimpleSignerInfoVerifierBuilder]
             [org.bouncycastle.openssl PEMParser]
           [java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec]
           [java.util Base64]))

(defprotocol RunFinalizationSigner
  (sign-finalization! [signer payload-hash]))

(def ^:private x509-ed25519-prefix
  (byte-array [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))

(defn- sha256-ref [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (Files/readAllBytes (.toPath (io/file file))))
    (str "sha256:" (format "%064x" (java.math.BigInteger. 1 (.digest digest))))))

(defn validate-signing-config [config]
  (let [signing (:signing config)
        provider (:key-provider signing)
        errors (cond-> []
                 (not (map? config)) (conj :config-not-map)
                 (and (:required? signing) (not= :ed25519 (:algorithm signing))) (conj :unsupported-signing-algorithm)
                 (and (:required? signing) (not (string? (:key-id provider)))) (conj :missing-key-id)
                 (and (:required? signing) (not= :file (:type provider))) (conj :unsupported-key-provider)
                 (and (:required? signing) (not (string? (:path provider)))) (conj :missing-key-path)
                 (and (:required? signing) (not (string? (get-in config [:verification :trusted-registry-path]))))
                 (conj :missing-trusted-registry-path))]
    {:valid? (empty? errors) :errors errors}))

(defn- read-pkcs8-private-key [path]
  (let [text (slurp path)
        body (.replaceAll text "-----BEGIN [^-]+-----|-----END [^-]+-----|\\s" "")
        bytes (.decode (Base64/getDecoder) body)]
    (.generatePrivate (KeyFactory/getInstance "Ed25519") (PKCS8EncodedKeySpec. bytes))))

(defrecord FileSigner [key-id private-key]
  RunFinalizationSigner
  (sign-finalization! [_ payload-hash]
    (envelope/build-envelope payload-hash key-id private-key)))

(defn file-signer! [provider]
  (let [path (:path provider)
        file (io/file path)]
    (when-not (.isFile file)
      (throw (ex-info "Configured signing key is unavailable" {:reason :signing/key-unavailable})))
    (->FileSigner (:key-id provider) (read-pkcs8-private-key path))))

(defn load-trusted-registry! [verification]
  (let [path (:trusted-registry-path verification)
        recorded (:trusted-registry-sha256 verification)
        actual (sha256-ref path)]
    (when (and recorded (not= recorded actual))
      (throw (ex-info "Trusted registry digest mismatch"
                      {:reason :signature/trusted-registry-hash-mismatch})))
    {:registry (edn/read-string (slurp path)) :hash actual :source path}))

(defn- public-key [hex]
  (.generatePublic (KeyFactory/getInstance "Ed25519")
                   (X509EncodedKeySpec. (byte-array (concat x509-ed25519-prefix
                                                              (codecs/hex->bytes hex))))))

(declare evaluate-envelopes)

(def ^:private safe-key-id-pattern #"^[A-Za-z0-9][A-Za-z0-9._-]*$")

(defn- signature-path! [signatures-dir key-id]
  (when-not (and (string? key-id) (re-matches safe-key-id-pattern key-id))
    (throw (ex-info "Signature key ID is not path-safe" {:reason :signature/unsafe-key-id})))
  (io/file signatures-dir (str key-id ".attestation-signature.json")))

(defn sign-persisted-finalization!
  "Sign exact persisted finalization bytes, verify the detached envelope against
   an external trusted registry, and refuse to overwrite a distinct envelope."
  [{:keys [finalization-path signatures-dir signer trusted-registry policy]}]
  (let [payload-hash (sha256-ref finalization-path)
        key-id (:key-id signer)
        path (signature-path! signatures-dir key-id)
        subject {:kind "run-evidence-finalization.v1"
                 :hash payload-hash
                 :path "evidence/finalizations/run/evidence-finalization.json"}
        generated (sign-finalization! signer payload-hash)
        generated (assoc-in generated [:payload :subject] subject)
        existing (when (.isFile path) (json/read-str (slurp path) :key-fn keyword))]
    (when (and existing (not= existing generated))
      (throw (ex-info "Refusing to overwrite a distinct finalization signature"
                      {:reason :signature/envelope-already-exists :path (str path)})))
    (let [written (if existing
                    {:path (.getPath path) :envelope existing}
                    (let [validation (envelope/validate-envelope generated)]
                      (when-not (:valid? validation)
                        (throw (ex-info "Generated finalization signature envelope is invalid" validation)))
                      {:path (let [parent (.getParentFile path)]
                               (.mkdirs parent)
                               (spit path (json/write-str generated {:indent true}))
                               (.getPath path))
                       :envelope generated}))
          persisted (json/read-str (slurp (:path written)) :key-fn keyword)
          evaluation (evaluate-envelopes [persisted] trusted-registry policy)]
      (assoc written :payload-hash payload-hash :verification evaluation))))

(defn load-tsa-registry! [timestamp-config]
  (let [path (:trusted-tsa-registry-path timestamp-config)
        expected (:trusted-tsa-registry-sha256 timestamp-config)
        actual (sha256-ref path)]
    (when-not (= expected actual)
      (throw (ex-info "Trusted TSA registry digest mismatch"
                      {:reason :timestamp/trusted-registry-hash-mismatch})))
    {:registry (edn/read-string (slurp path)) :hash actual :source path}))

(declare verify-rfc3161-receipt!)

(defn evaluate-timestamp-receipt
  "Evaluate detached receipt metadata against a pinned TSA registry. An active
   authority must provide its pinned signer certificate and the caller must
   provide the receipt path for cryptographic token verification."
  [receipt tsa-registry]
  (let [authority (some #(when (= (:tsa-url receipt) (:url %)) %) (:authorities tsa-registry))]
    (cond
      (not (:imprint-valid? receipt)) {:status :requirement/present-invalid
                                       :reason :timestamp/imprint-mismatch}
      (nil? authority) {:status :requirement/present-untrusted
                        :reason :timestamp/unknown-authority}
      (not= :active (:status authority)) {:status :requirement/present-untrusted
                                           :reason :timestamp/inactive-authority}
      (not (string? (:certificate-pem authority))) {:status :requirement/present-untrusted
                                                     :reason :timestamp/missing-pinned-certificate}
      (or (nil? (:receipt-path receipt)) (nil? (:signature-hash receipt)))
      {:status :requirement/present-invalid :reason :timestamp/missing-receipt-material}
      :else
      (let [verified (verify-rfc3161-receipt! {:receipt-path (:receipt-path receipt)
                                               :signature-hash (:signature-hash receipt)
                                               :authority authority})]
        (assoc verified :status (if (:valid? verified)
                                  :requirement/satisfied
                                  :requirement/present-invalid))))))

(defn verify-rfc3161-receipt!
  "Verify a receipt's message imprint and RFC 3161 token signature against the
   explicitly pinned TSA signer certificate in an active registry authority.
   The pinned certificate is the initial trust anchor; chain/path building can
   later extend this representation without weakening current verification."
  [{:keys [receipt-path signature-hash authority]}]
  (try
    (let [response (TimeStampResponse. (Files/readAllBytes (.toPath (io/file receipt-path))) )
          token (.getTimeStampToken response)
          info (.getTimeStampInfo token)
          expected (.digest (java.security.MessageDigest/getInstance "SHA-256")
                            (.getBytes signature-hash "UTF-8"))
          imprint-valid? (java.util.Arrays/equals expected (.getMessageImprintDigest info))
          holder (with-open [parser (PEMParser. (StringReader. (:certificate-pem authority)))]
                   (let [value (.readObject parser)]
                     (if (instance? X509CertificateHolder value) value
                         (throw (ex-info "Pinned TSA certificate is not X.509" {})))))
          verifier (.build (JcaSimpleSignerInfoVerifierBuilder.) holder)]
      (.validate token verifier)
      {:valid? imprint-valid?
       :status (if imprint-valid? :requirement/satisfied :requirement/present-invalid)
       :timestamp/gen-time (str (.getGenTime info))
       :timestamp/serial (str (.getSerialNumber info))
       :reason (when-not imprint-valid? :timestamp/imprint-mismatch)})
    (catch Exception e
      {:valid? false :status :requirement/present-invalid
       :reason :timestamp/token-signature-invalid :detail (.getMessage e)})))

(defn request-rfc3161-receipt!
  "Obtain a detached RFC 3161 receipt over a signature artifact digest. The
   returned status proves message-imprint binding only; TSA trust is evaluated
   separately once a trusted TSA registry is configured."
  [{:keys [signature-path timestamps-dir tsa-url]}]
  (let [signature-hash (sha256-ref signature-path)
        response (timestamping/tsa-request signature-hash :tsa-url tsa-url)]
    (if-let [error (:error response)]
      {:valid? false :reason :timestamp/request-failed :detail error}
      (let [receipt-name (str (subs signature-hash 7) ".rfc3161.tsr")
            receipt-path (io/file timestamps-dir receipt-name)
            metadata-path (io/file timestamps-dir (str (subs signature-hash 7) ".timestamp.json"))
            receipt-bytes (codecs/hex->bytes (:response-hex response))
            imprint (timestamping/verify-tsa-token (:response-hex response) signature-hash)
            metadata {:schema-version "run-finalization-timestamp-receipt.v1"
                      :receipt-kind "rfc3161"
                      :signature-hash signature-hash
                      :tsa-url tsa-url
                      :imprint-valid? (:valid imprint)
                      :claimed-time (:time imprint)
                      :serial (:serial imprint)}]
        (.mkdirs (io/file timestamps-dir))
        (with-open [out (io/output-stream receipt-path)] (.write out receipt-bytes))
        (spit metadata-path (json/write-str metadata {:indent true}))
        {:valid? (boolean (:valid imprint))
         :trust-status :untrusted-pending-tsa-registry
         :receipt-path (.getPath receipt-path)
         :metadata-path (.getPath metadata-path)
         :metadata metadata}))))

(defn write-timestamp-verification-report! [path trusted-registry-hash verification]
  (let [target (io/file path)
        temp (io/file (.getParentFile target) (str "." (.getName target) ".tmp-" (java.util.UUID/randomUUID)))
        report {:schema-version "run-finalization-timestamp-verification.v1"
                :trusted-tsa-registry-hash trusted-registry-hash
                :verification verification}]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str report {:indent true}))
    (try
      (Files/move (.toPath temp) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (catch AtomicMoveNotSupportedException _
        (Files/move (.toPath temp) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    {:path (.getPath target) :report report}))

(defn write-verification-report!
  "Persist public detached-signature verification results. This report is not
   signed content and must be inventoried only by the outer package layer."
  [path payload-hash trusted-registry-hash verification]
  (let [report {:schema-version "run-finalization-signature-verification.v1"
                :payload-type envelope/payload-type
                :payload-hash payload-hash
                :trusted-registry-hash trusted-registry-hash
                :verification verification}
        target (io/file path)
        temp (io/file (.getParentFile target) (str "." (.getName target) ".tmp-" (java.util.UUID/randomUUID)))]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str report {:indent true}))
    (try
      (Files/move (.toPath temp) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (catch AtomicMoveNotSupportedException _
        (Files/move (.toPath temp) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    {:path (.getPath target) :report report}))

(defn evaluate-envelopes [envelopes trusted-registry policy]
  (let [keys-by-id (into {} (map (juxt :key-id identity) (:keys trusted-registry)))
        evaluated (mapv (fn [env]
                          (let [key (get keys-by-id (get-in env [:signature :key-id]))
                                crypto (if key (envelope/verify-envelope env (public-key (:public-key key)))
                                           {:valid? false :reason :unknown-key})
                                trusted? (and (:valid? crypto) (= :active (:status key))
                                              (contains? (set (:roles key)) (:signer-role policy)))]
                            {:key-id (get-in env [:signature :key-id]) :crypto crypto :trusted? trusted?})) envelopes)
        trusted-ids (set (map :key-id (filter :trusted? evaluated)))
        minimum (or (get-in policy [:threshold :minimum]) 1)
        satisfied? (>= (count trusted-ids) minimum)]
    {:valid? satisfied?
     :cryptographically-valid-count (count (filter #(get-in % [:crypto :valid?]) evaluated))
     :trusted-valid-count (count trusted-ids)
     :required-count minimum
     :signatures evaluated
     :reasons (cond-> [] (not satisfied?) (conj {:code :signature/threshold-not-met
                                                   :required minimum :observed (count trusted-ids)}))}))
