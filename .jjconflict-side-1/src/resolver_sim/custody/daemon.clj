(ns resolver-sim.custody.daemon
  "AUTH-K4 local custody daemon process.

   A separate process owns the governed private key. The ordinary PRF process
   never loads or receives the raw private key on the authoritative path; it
   talks to this daemon over a Unix domain socket. The daemon signs only a
   closed frozen governed-authority-signing-request.v1 and refuses otherwise
   (no arbitrary signing API).

   This namespace owns the daemon process lifecycle: durable operational
   disablement (survives restart), provisioning, the UDS server loop, and the
   startup keyring load."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.custody.contract :as contract]
            [resolver-sim.custody.daemon-core :as core]
            [resolver-sim.custody.daemon-state :as daemon-state]
            [resolver-sim.signed-external-decision :as sed])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel]
           [org.bouncycastle.crypto.params Ed25519PrivateKeyParameters]))

(def registry-schema "custody-registry.v1")

(declare derived-public-key-hex)

(defn registry-path [dir] (str dir "/registry.edn"))

(defn read-registry [dir]
  (let [p (registry-path dir)]
    (if (.isFile (io/file p))
      (edn/read-string (slurp p))
      {:artifact/schema registry-schema :entries []})))

(defn- write-registry! [dir registry]
  (spit (registry-path dir) (pr-str registry))
  registry)

(defn registry-entry [dir principal-id signing-key-id]
  (some #(when (and (= principal-id (:principal/id %))
                    (= signing-key-id (:signing-key/id %)))
           %)
        (:entries (read-registry dir))))

(defn mark-disabled!
  "Operational disablement (AUTH-K4 safety control, independent of protocol
   revocation). Persisted so it survives daemon restart — restart must never
   silently re-enable a disabled key."
  [dir principal-id signing-key-id reason]
  (let [reg (read-registry dir)
        entry (registry-entry dir principal-id signing-key-id)]
    (when-not entry
      (throw (ex-info "key not provisioned" {:reason :key/not-provisioned})))
    (write-registry! dir
                     (update reg :entries
                             (fn [es] (mapv (fn [e]
                                              (if (and (= principal-id (:principal/id e))
                                                       (= signing-key-id (:signing-key/id e)))
                                                (assoc e :enabled? false :disabled-reason reason)
                                                e))
                                            es))))))

(defn mark-enabled!
  [dir principal-id signing-key-id]
  (let [reg (read-registry dir)
        entry (registry-entry dir principal-id signing-key-id)]
    (when-not entry
      (throw (ex-info "key not provisioned" {:reason :key/not-provisioned})))
    (write-registry! dir
                     (update reg :entries
                             (fn [es] (mapv (fn [e]
                                              (if (and (= principal-id (:principal/id e))
                                                       (= signing-key-id (:signing-key/id e)))
                                                (assoc e :enabled? true :disabled-reason nil)
                                                e))
                                            es))))))

;; ── keyring load (private key lives only in the daemon process) ─────────────

(defn- load-private-key-at-rest
  "Load a held private key from the daemon keystore. Key-at-rest controls
   (permissions, encryption) are documented; V1 stores an unencrypted PKCS#8
   file owned by the daemon user. No raw key ever enters the PRF process."
  [keystore-dir handle]
  (let [path (str keystore-dir "/" handle ".pem")]
    (when (.isFile (io/file path))
      (signing/load-private-key! path nil))))

(defn load-keyring
  "Build the in-memory keyring {[principal signing-key-id] {:private-key
   :public-key-hex :enabled? :reason :generation :custody/handle}} from the
   durable registry + the daemon keystore."
  [dir keystore-dir]
  (let [reg (read-registry dir)]
    (into {}
          (keep (fn [entry]
                  (let [handle (:custody/handle entry)
                        priv (load-private-key-at-rest keystore-dir handle)]
                    (when priv
                      [[(:principal/id entry) (:signing-key/id entry)]
                       {:private-key priv
                        :public-key-hex (:public-key-hex entry)
                        :enabled? (if (contains? entry :enabled?) (:enabled? entry) true)
                        :reason (:disabled-reason entry)
                        :generation (or (:generation entry) 1)
                        :custody/handle handle}])))
                (:entries reg)))))

;; ── provisioning ────────────────────────────────────────────────────────────

(defn provision-key!
  "Provision/import a private key into custody.

   generate/import private key → derive public key → bind custody handle →
   require derived public key == committed public key (from the authoritative
   signer-key-set material) → persist custody metadata → operationally enable.

   Provisioning never grants protocol authority; it only makes the key
   available for custody use. A provisioned key that is K2-ineligible is still
   refused at sign time."
  [dir keystore-dir private-key-path handle principal-id signing-key-id committed-public-key-hex]
  (let [priv (signing/load-private-key! private-key-path nil)
        derived (derived-public-key-hex priv)]
    (when-not (= committed-public-key-hex derived)
      (throw (ex-info "derived public key does not match committed key"
                      {:reason :key/public-mismatch :derived derived :committed committed-public-key-hex})))
    ;; persist private key at rest (daemon-owned keystore)
    (.mkdirs (io/file keystore-dir))
    (spit (str keystore-dir "/" handle ".pem") (slurp private-key-path))
    (let [reg (read-registry dir)
          entry {:principal/id principal-id
                 :signing-key/id signing-key-id
                 :custody/handle handle
                 :public-key-hex committed-public-key-hex
                 :enabled? true
                 :generation (inc (or (some :generation (:entries reg)) 0))}
          reg (update reg :entries
                      (fn [es]
                        (conj (vec (remove #(and (= principal-id (:principal/id %))
                                                 (= signing-key-id (:signing-key/id %)))
                                           es))
                              entry)))]
      (write-registry! dir reg)
      entry)))

(defn- derived-public-key-hex
  "Derive the 32-byte Ed25519 public key hex from a PKCS#8 private key (RFC 8410:
   the PKCS#8 private-key octet string is exactly the 32-byte seed)."
  [private-key]
  (let [seed (take-last 32 (.getEncoded private-key))
        priv-params (Ed25519PrivateKeyParameters. (byte-array seed) 0)
        pub-params (.generatePublicKey priv-params)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.getEncoded pub-params)))))

;; ── UDS wire protocol (newline-delimited EDN) ───────────────────────────────

(defn- frame [s] (pr-str s))

(defn- write-frame! [out x]
  (.write out (.getBytes (str (frame x) "\n") "UTF-8"))
  (.flush out))

(defn- read-frame [in]
  (let [sb (StringBuilder.)]
    (loop [ch (.read in)]
      (cond
        (= -1 ch) nil
        (= (int \newline) ch) (edn/read-string (str sb))
        :else (do (.append sb (char ch)) (recur (.read in)))))))

(defn handle-request!
  "Handle a single custody request. Dispatches on a closed request framing:
     - a frozen governed-authority-signing-request.v1 → sign-or-refuse;
     - {:custody/op :submit-successor ...} → independently validated state
       advancement (AUTH-K4-S). There is no set-head/trust-snapshot operation."
  [mirror-dir keyring request]
  (if (= :submit-successor (:custody/op request))
    (let [result (daemon-state/submit-candidate-successor!
                  mirror-dir (:expected-head request)
                  (:candidate-envelope request) (:candidate-material request)
                  (:transition-definition request)
                  (:predecessor-bundle request) (:authorised-outcome request))]
      (if (:valid? result)
        {:custody/result :advanced :head (:head result) :sequence (:sequence result)}
        {:custody/result :refused :reason (:reason result)}))
    (let [mirror (daemon-state/read-mirror mirror-dir)
          decision (core/signing-decision request mirror keyring)]
      (if (= :refuse (:action decision))
        (contract/refused-response (:reason decision))
        (try
          (let [signature-hex (sed/ed25519-sign-bytes (:digest decision) (:private-key decision))
                handle (:custody/handle (core/held-key-for keyring (:principal/id request) (:signing-key/id request)))]
            (contract/signing-response :signed (:signing-request/root decision) signature-hex handle))
          (catch Exception _
            (contract/refused-response :signing/failed)))))))

(defn serve!
  "Accept a single connection on a UDS ServerSocketChannel and handle one
   request, then respond. Returns the response. Used by the daemon loop."
  [^ServerSocketChannel server mirror-dir keyring]
  (with-open [conn (.accept server)]
    (let [in (Channels/newInputStream conn)
          out (Channels/newOutputStream conn)]
      (if-let [request (read-frame in)]
        (let [response (handle-request! mirror-dir keyring request)]
          (write-frame! out response)
          response)
        (do (write-frame! out (contract/refused-response :request/malformed))
            nil)))))

(defn run-daemon!
  "Main entry point for the custody daemon process.
   Args: <socket-path> <registry-dir> <keystore-dir> <mirror-dir>.
   Loads the daemon-owned authoritative-state mirror + the keyring, then serves
   signing and state-update requests over the UDS socket until interrupted."
  [& [socket-path registry-dir keystore-dir mirror-dir]]
  (let [keyring (load-keyring registry-dir keystore-dir)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (.bind server (UnixDomainSocketAddress/of socket-path))
    (try
      (loop []
        (serve! server mirror-dir keyring)
        (recur))
      (finally (.close server)))))