(ns resolver-sim.evidence.timestamping
  "Timestamp proof generation for forensic-grade evidence anchoring.

   Local timestamp proof:
     Hashes the registry data and binds it to a wall-clock time with an
     Ed25519 signature from the framework's own key (self-witness).

   RFC 3161 TSA integration:
     Uses BouncyCastle's TSP API to submit SHA-256 hashes to an RFC 3161
     Time-Stamp Authority. The response token (DER-encoded TimeStampResp)
     is persisted alongside the evidence artifacts and can be independently
     verified using the TSA's certificate.

   The timestamp proof is written as timestamp.json alongside the registry,
   and can be independently verified without access to the signing key."
  (:require [clojure.data.json :as json]
             [clojure.java.io :as io]
             [resolver-sim.benchmark.signing :as signing]
             [resolver-sim.config.hardening :as hardening]
             [resolver-sim.evidence.config :as evcfg]
             [resolver-sim.hash.canonical :as hc]
             [resolver-sim.logging :as log])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Instant)
           (org.bouncycastle.tsp TimeStampRequestGenerator TimeStampResponse)
           (java.security MessageDigest)))

;; ── Local Timestamp Proof ────────────────────────────────────────────────────

(defn- now-iso
  "ISO-8601 instant string."
  []
  (str (Instant/now)))

(defn local-timestamp-proof
  "Produce a self-signed timestamp proof binding the evidence hash to
   a wall-clock time. The proof is signed with the framework's own
   Ed25519 key, providing a self-witnessed temporal anchor.

   The hash can be a registry-hash, cursor-hash, or any content hash
   that should be timestamped.

   Returns {:hash h :timestamp ISO-8601 :signature hex :signer path}
   or nil if no private-key-path is provided."
  [hash & {:keys [private-key-path password]
           :or {password nil}}]
  (when (and hash private-key-path)
    (let [ts (now-iso)
          proof-data {:evidence/hash hash
                      :timestamped-at ts
                      :schema/version "timestamp-proof.v1"}
          proof-hash (hc/hash-with-intent {:hash/intent :evidence-record} proof-data)
          sig (signing/sign-hash proof-hash private-key-path password)]
      (assoc proof-data
             :proof-hash proof-hash
             :signature sig
             :signer private-key-path
             :signed-at ts))))

(defn write-timestamp-proof!
  "Generate a local timestamp proof for the given hash and persist as
   timestamp.json alongside the registry. Returns the path written or nil.
   Registers the artifact in the evidence chain."
  [hash & {:keys [private-key-path password dir]
           :or {password nil}}]
  (when (and hash private-key-path)
    (let [proof (local-timestamp-proof hash
                                       :private-key-path private-key-path
                                       :password password)
          out-dir (or dir (str (evcfg/artifact-dir)))
          f (io/file out-dir "timestamp.json")]
      (.mkdirs (io/file out-dir))
      (spit f (json/write-str proof {:indent true}))
      (println "Wrote timestamp proof: hash" (subs hash 0 16) "...")
      (.getPath f))))

;; ── RFC 3161 TSA Integration ─────────────────────────────────────────────────

(def ^:dynamic *tsa-url*
  "RFC 3161 Time-Stamp Authority URL.
   Set via config or binding to enable external timestamping.
   Example: \"http://timestamp.digicert.com\""
  nil)

(defn- hex->bytes
  "Convert a hex string to a byte array."
  [hex]
  (let [len (quot (count hex) 2)]
    (byte-array (map (fn [i] (Integer/parseInt (subs hex (* i 2) (+ (* i 2) 2)) 16))
                     (range len)))))

(defn- bytes->hex
  "Convert a byte array to a hex string."
  [ba]
  (apply str (map (fn [b] (format "%02x" (bit-and b 0xff))) ba)))

(defn- sha-256-digest
  "Compute SHA-256 digest of a byte array."
  [bytes]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.digest md bytes)))

(defn tsa-request
  "Submit a SHA-256 hash to an RFC 3161 TSA and return the response token.

   Uses BouncyCastle's TSP API to construct a proper DER-encoded TimeStampReq
   (SHA-256 digest, requesting the TSA's certificate in the response).

   Returns {:token-hex <hex-encoded-DER-response>
            :tsa <url>
            :hash <hash>
            :time <ISO-8601-gen-time>
            :serial <TSA-serial-number>}
   or {:error <message>}."
  [hash & {:keys [tsa-url timeout-ms]
            :or {tsa-url *tsa-url*
                 timeout-ms (hardening/value :tsa-timeout-ms {:fallback 30000})}}]
  (if-not tsa-url
    {:error "No TSA URL configured. Set *tsa-url* or pass :tsa-url."}
    (try
      (let [digest (sha-256-digest (.getBytes hash "UTF-8"))
            gen (TimeStampRequestGenerator.)
            _ (.setCertReq gen true)
            request (.generate gen "2.16.840.1.101.3.4.2.1" digest)
            req-bytes (.getEncoded request)
            client (HttpClient/newHttpClient)
            request-http (.. (HttpRequest/newBuilder)
                             (uri (URI. tsa-url))
                             (header "Content-Type" "application/timestamp-query")
                             (header "Content-Transfer-Encoding" "base64")
                             (timeout (java.time.Duration/ofMillis timeout-ms))
                             (POST (HttpRequest$BodyPublishers/ofByteArray req-bytes))
                             (build))
            http-response (.send client request-http (HttpResponse$BodyHandlers/ofByteArray))
            status (.statusCode http-response)
            resp-bytes (.body http-response)]
        (if (= 200 status)
          (try
            (let [resp (TimeStampResponse. resp-bytes)
                  token (.getTimeStampToken resp)
                  ts-info (.getTimeStampInfo token)
                  gen-time (.getGenTime ts-info)
                  serial (.getSerialNumber ts-info)]
              {:response-hex (bytes->hex resp-bytes)
               :token-status (.getStatus resp)
               :tsa tsa-url
               :hash hash
               :time (str gen-time)
               :serial (str serial)})
            (catch Exception e
              {:response-hex (bytes->hex resp-bytes)
               :tsa tsa-url
               :hash hash
               :error (str "Failed to parse TSA response: " (.getMessage e))}))
          {:error (str "TSA returned status " status)
           :body (bytes->hex resp-bytes)
           :tsa tsa-url
           :hash hash}))
      (catch javax.net.ssl.SSLHandshakeException e
        {:error (str "TSA SSL handshake failed (expired certificate?): " (.getMessage e))
         :tsa tsa-url :hash hash})
      (catch java.net.ConnectException e
        {:error (str "TSA connection refused: " (.getMessage e))
         :tsa tsa-url :hash hash})
      (catch java.net.http.HttpTimeoutException e
        {:error (str "TSA request timed out: " (.getMessage e))
         :tsa tsa-url :hash hash})
      (catch Exception e
        {:error (.getMessage e) :tsa tsa-url :hash hash}))))

(defn- verify-tsa-digest
  "Verify that a TSA response's message imprint matches the original hash.
   Returns true if the digest matches, false otherwise."
  [resp-bytes expected-hash]
  (try
    (let [resp (TimeStampResponse. resp-bytes)
          token (.getTimeStampToken resp)
          ts-info (.getTimeStampInfo token)
          imprint (.getMessageImprintDigest ts-info)
          expected (sha-256-digest (.getBytes expected-hash "UTF-8"))]
      (java.util.Arrays/equals imprint expected))
    (catch Exception _ false)))

(defn write-tsa-timestamp!
  "Request an RFC 3161 timestamp for the registry hash and persist as sidecar
   artifacts in a time-stamping-authority/ subdirectory.

   Stores:
     time-stamping-authority/tsa-response.tsr  — raw DER TimeStampResp token
     time-stamping-authority/tsa-request.tsq   — raw DER TimeStampReq (for independent verification)
     time-stamping-authority/tsa.json          — metadata envelope with verification result

   The registry hash commits to all artifact entries, so the TSA token
   anchors the entire run. The sidecar pattern avoids circularity: the
   TSA artifacts are NOT part of the registry being timestamped.

   Returns {:tsa-envelope <metadata> :tsr-path <path> :tsa-path <path> :tsq-path <path>}
   or {:error <message>}."
  [registry-hash & {:keys [tsa-url dir timeout-ms provider-name]
                    :or {timeout-ms (hardening/value :tsa-timeout-ms {:fallback 30000})
                         provider-name (some-> (or tsa-url *tsa-url*)
                                               (java.net.URI.)
                                               (.getHost))}}]
  (let [url (or tsa-url *tsa-url*)]
    (if-not url
      {:error "No TSA URL configured. Set *tsa-url* or pass :tsa-url."}
      (let [digest (sha-256-digest (.getBytes registry-hash "UTF-8"))
            gen (TimeStampRequestGenerator.)
            _ (.setCertReq gen true)
            request (.generate gen "2.16.840.1.101.3.4.2.1" digest)
            req-bytes (.getEncoded request)
            client (HttpClient/newHttpClient)
            request-http (.. (HttpRequest/newBuilder)
                             (uri (URI. url))
                             (header "Content-Type" "application/timestamp-query")
                             (header "Content-Transfer-Encoding" "base64")
                             (timeout (java.time.Duration/ofMillis timeout-ms))
                             (POST (HttpRequest$BodyPublishers/ofByteArray req-bytes))
                             (build))]
        (try
          (let [http-response (.send client request-http (HttpResponse$BodyHandlers/ofByteArray))
                status (.statusCode http-response)
                resp-bytes (.body http-response)]
            (if (= 200 status)
              (try
                (let [resp (TimeStampResponse. resp-bytes)
                      token (.getTimeStampToken resp)
                      ts-info (.getTimeStampInfo token)
                      gen-time (.getGenTime ts-info)
                      serial (.getSerialNumber ts-info)
                      out-dir (or dir (str (evcfg/artifact-dir)))
                      tsa-dir (io/file out-dir "time-stamping-authority")
                      tsr-path (str (.getPath tsa-dir) "/tsa-response.tsr")
                      tsq-path (str (.getPath tsa-dir) "/tsa-request.tsq")
                      verified? (verify-tsa-digest resp-bytes registry-hash)
                      envelope {:tsa/input-kind :registry/hash
                                :registry/hash registry-hash
                                :timestamp/provider (name provider-name)
                                :timestamp/provider-url url
                                :timestamp/gen-time (str gen-time)
                                :timestamp/serial (str serial)
                                :timestamp/token-path "tsa-response.tsr"
                                :timestamp/request-path "tsa-request.tsq"
                                :timestamp/token-hash (bytes->hex (sha-256-digest resp-bytes))
                                :timestamp/verified? verified?}
                      tsa-path (str (.getPath tsa-dir) "/tsa.json")]
                  (.mkdirs tsa-dir)
                  (io/copy resp-bytes (io/file tsr-path))
                  (io/copy req-bytes (io/file tsq-path))
                  (spit (io/file tsa-path) (json/write-str envelope {:indent true}))
                  (println (str "TSA timestamp obtained: " registry-hash " @ " gen-time
                                " [verified: " verified? "]"))
                  {:tsa-envelope envelope
                   :tsr-path tsr-path
                   :tsq-path tsq-path
                   :tsa-path tsa-path})
                (catch Exception e
                  {:error (str "Failed to parse TSA response: " (.getMessage e))
                   :provider-url url :hash registry-hash}))
              {:error (str "TSA returned status " status)
               :provider-url url :hash registry-hash}))
          (catch javax.net.ssl.SSLHandshakeException e
            {:error (str "TSA SSL handshake failed: " (.getMessage e))
             :provider-url url :hash registry-hash})
          (catch java.net.ConnectException e
            {:error (str "TSA connection refused: " (.getMessage e))
             :provider-url url :hash registry-hash})
          (catch java.net.http.HttpTimeoutException e
            {:error (str "TSA request timed out: " (.getMessage e))
             :provider-url url :hash registry-hash})
          (catch Exception e
            {:error (.getMessage e) :provider-url url :hash registry-hash}))))))

(defn verify-tsa-token-from-file
  "Verify a TSA token file against a registry hash.
   Reads time-stamping-authority/tsa-response.tsr and tsa.json from dir.
   Returns the updated envelope with :timestamp/verified? set."
  [registry-hash & {:keys [dir]
                    :or {dir (str (evcfg/artifact-dir))}}]
  (let [tsa-dir (io/file dir "time-stamping-authority")
        tsr-file (io/file tsa-dir "tsa-response.tsr")
        tsa-file (io/file tsa-dir "tsa.json")]
    (if-not (.exists tsr-file)
      {:error "tsa-response.tsr not found" :dir (str tsa-dir)}
      (try
        (let [resp-bytes (java.nio.file.Files/readAllBytes (.toPath tsr-file))
              resp (TimeStampResponse. resp-bytes)
              token (.getTimeStampToken resp)
              ts-info (.getTimeStampInfo token)
              imprint (.getMessageImprintDigest ts-info)
              expected (sha-256-digest (.getBytes registry-hash "UTF-8"))
              digest-match (java.util.Arrays/equals imprint expected)
              envelope (when (.exists tsa-file)
                         (try (json/read-str (slurp tsa-file) :key-fn keyword)
                              (catch Exception e
                                (log/warn! "Failed to read TSA envelope" {:path (str tsa-file) :error (.getMessage e)})
                                nil)))]
          {:timestamp/verified? digest-match
           :timestamp/gen-time (str (.getGenTime ts-info))
           :timestamp/serial (str (.getSerialNumber ts-info))
           :timestamp/digest-match? digest-match
           :tsa/input-kind :registry/hash
           :registry/hash registry-hash
           :envelope envelope})
        (catch Exception e
          {:error (str "Failed to verify TSA token: " (.getMessage e))
           :timestamp/verified? false})))))

(defn verify-tsa-token
  "Verify an RFC 3161 timestamp response against the original hash.

   response-hex is the hex-encoded full TimeStampResp DER (from :response-hex
   in the tsa-request result). hash is the original SHA-256 hex string that
   was timestamped.

   Returns {:valid true/false :time <gen-time> :serial <serial> :hash <hash>}
   or {:error <message>}.

   Note: This verifies that the message imprint in the timestamp matches the
   original hash. It does NOT verify the TSA's certificate chain — that
   requires the TSA's root CA certificate obtained separately."
  [response-hex hash]
  (try
    (let [resp-bytes (hex->bytes response-hex)
          resp (TimeStampResponse. resp-bytes)
          token (.getTimeStampToken resp)
          ts-info (.getTimeStampInfo token)
          imprint (.getMessageImprintDigest ts-info)
          expected (sha-256-digest (.getBytes hash "UTF-8"))
          digest-match (java.util.Arrays/equals imprint expected)]
      (if digest-match
        {:valid true
         :time (str (.getGenTime ts-info))
         :serial (str (.getSerialNumber ts-info))
         :hash hash
         :algorithm (str (.getAlgorithm (.getHashAlgorithm ts-info)))}
        {:valid false
         :error "Message imprint digest does not match the original hash"
         :hash hash}))
    (catch Exception e
      {:error (str "Failed to verify TSA token: " (.getMessage e))})))
