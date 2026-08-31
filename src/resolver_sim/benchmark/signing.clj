(ns resolver-sim.benchmark.signing
  (:require [buddy.core.dsa :as dsa]
            [buddy.core.keys :as keys]
            [buddy.core.codecs :as codecs]
            [clojure.string :as str]
            [resolver-sim.logging :as log])
  (:import (org.bouncycastle.crypto.util OpenSSHPrivateKeyUtil OpenSSHPublicKeyUtil PrivateKeyInfoFactory SubjectPublicKeyInfoFactory)
           (org.bouncycastle.crypto.params Ed25519PrivateKeyParameters Ed25519PublicKeyParameters)
           (org.bouncycastle.util.io.pem PemReader)
           (java.io FileReader)
           (java.security KeyFactory)
           (java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec)))

(defn- load-openssh-ed25519-private-key [path]
  (try
    (with-open [reader (PemReader. (FileReader. path))]
      (let [pem-obj (.readPemObject reader)]
        (if (and pem-obj (= "OPENSSH PRIVATE KEY" (.getType pem-obj)))
          (let [params (OpenSSHPrivateKeyUtil/parsePrivateKeyBlob (.getContent pem-obj))]
            (if (instance? Ed25519PrivateKeyParameters params)
              (let [info (PrivateKeyInfoFactory/createPrivateKeyInfo params)
                    spec (PKCS8EncodedKeySpec. (.getEncoded info))
                    kf (KeyFactory/getInstance "Ed25519")]
                (.generatePrivate kf spec))
              (throw (ex-info "Key is not an Ed25519 private key" {:class (class params)}))))
          (throw (ex-info "Not a valid OpenSSH private key" {:path path})))))
    (catch Exception e
      (if (str/includes? (.getMessage e) "encrypted keys not supported")
        (throw (ex-info (str "Encrypted OpenSSH keys are not supported natively.\n"
                             "Please convert your key to unencrypted PKCS#8 format using:\n\n"
                             "  ssh-keygen -p -N \"\" -m pkcs8 -f " path "\n\n"
                             "Note: This will remove the passphrase from the key file.")
                        {:path path}))
        (throw e)))))

(defn parse-ssh-public-key-content
  "Parse an Ed25519 public key from OpenSSH 'ssh-ed25519 <b64>' content.
   Deterministic for a given key; no filesystem path involved."
  [content]
  (let [;; Format: ssh-ed25519 AAA... user@host
        parts (str/split (str/trim content) #"\s+")
        b64 (if (> (count parts) 1) (second parts) (first parts))
        bytes (codecs/b64->bytes b64)
        params (OpenSSHPublicKeyUtil/parsePublicKey bytes)]
    (if (instance? Ed25519PublicKeyParameters params)
      (let [info (SubjectPublicKeyInfoFactory/createSubjectPublicKeyInfo params)
            spec (X509EncodedKeySpec. (.getEncoded info))
            kf (KeyFactory/getInstance "Ed25519")]
        (.generatePublic kf spec))
      (throw (ex-info "Key is not an Ed25519 public key" {:class (class params)})))))

(defn- parse-pkcs8-public-key-content
  "Parse an Ed25519 public key from PKCS#8 'BEGIN PUBLIC KEY' PEM content
   (X.509 SubjectPublicKeyInfo) without touching the filesystem."
  [content]
  (let [body (apply str
                    (keep (fn [line]
                            (let [l (str/trim line)]
                              (when-not (or (str/blank? l)
                                            (str/starts-with? l "-----"))
                                l)))
                          (str/split-lines content)))
        bytes (codecs/b64->bytes body)
        spec (X509EncodedKeySpec. bytes)
        kf (KeyFactory/getInstance "Ed25519")]
    (.generatePublic kf spec)))

(defn parse-public-key-content
  "Parse an Ed25519 public key from its content string (OpenSSH 'ssh-ed25519'
   line or PKCS#8 'BEGIN PUBLIC KEY').  Deterministic for a given key; the
   admission/verification paths use this so they never depend on a local
   filesystem path."
  [content]
  (let [trimmed (str/trim content)]
    (cond
      (str/starts-with? trimmed "ssh-ed25519")
      (parse-ssh-public-key-content trimmed)

      (str/includes? trimmed "BEGIN PUBLIC KEY")
      (parse-pkcs8-public-key-content trimmed)

      :else
      (throw (ex-info "Unsupported public key content format"
                      {:format (some-> trimmed (subs 0 (min 40 (count trimmed))))})))))

(defn- load-private-key [path password]
  (let [content (try (slurp path)
                     (catch Exception e
                       (log/warn! :private-key-read-failed {:path path :error (.getMessage e)})
                       ""))]
    (if (str/includes? content "BEGIN OPENSSH PRIVATE KEY")
      (load-openssh-ed25519-private-key path)
      (keys/private-key path password))))

(defn- load-public-key [path]
  (let [content (try (slurp path)
                     (catch Exception e
                       (log/warn! :public-key-read-failed {:path path :error (.getMessage e)})
                       ""))]
    (parse-public-key-content content)))

(defn load-private-key!
  "Load an Ed25519 private key for an explicitly local signing workflow.
   Callers must not serialize or log the returned key."
  [path password]
  (load-private-key path password))

(defn sign-hash [hash private-key-path password]
  (let [priv-key (load-private-key private-key-path password)
        signature (dsa/sign (codecs/str->bytes hash) {:alg :eddsa :key priv-key})]
    (codecs/bytes->hex signature)))

(defn verify-signature [hash signature-hex public-key-path]
  (let [pub-key (load-public-key public-key-path)
        signature (codecs/hex->bytes signature-hex)]
    (dsa/verify (codecs/str->bytes hash) signature {:alg :eddsa :key pub-key})))

(defn verify-signature-with-public-key
  "Verify an Ed25519 signature against an in-memory public key object.
   No filesystem path involved."
  [hash signature-hex public-key]
  (let [signature (codecs/hex->bytes signature-hex)]
    (dsa/verify (codecs/str->bytes hash) signature {:alg :eddsa :key public-key})))

(defn verify-signature-with-public-key-content
  "Verify an Ed25519 signature against a public key given as content
   (OpenSSH 'ssh-ed25519 <b64>' or PKCS#8).  Machine-independent."
  [hash signature-hex public-key-content]
  (verify-signature-with-public-key
   hash signature-hex (parse-public-key-content public-key-content)))
