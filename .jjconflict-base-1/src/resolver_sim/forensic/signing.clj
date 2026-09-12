(ns resolver-sim.forensic.signing
  "Sign pre-run commitments and post-run evidence roots using Ed25519.
   Wraps buddy-core signing with forensic schema and file I/O.
   Used by bb sign:* tasks and by the scenario runner pipeline."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [buddy.core.keys :as bk]
            [buddy.core.dsa :as bs])
  (:import [java.time Instant]))

(def ^:const signature-schema-version "signature.v1")

(defn- sha256-hex
  "SHA-256 hex digest of a string."
  [s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (.update d (.getBytes s "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- resolve-key
  "Load an Ed25519 private key from path, env var PRF_SIGNING_KEY, or default path."
  [key-path]
  (let [path (or key-path
                 (System/getenv "PRF_SIGNING_KEY")
                 "signing-key.pem")]
    (when (.exists (io/file path))
      (bk/private-key path))))

(defn sign-map
  "Sign a map by computing its SHA-256 and signing with Ed25519.
   key-path — path to PEM-encoded Ed25519 private key (or PRF_SIGNING_KEY env).
   Returns a signature record map with :signed-hash, :signature, :key-id."
  [data-to-sign & [key-path]]
  (if-let [priv (resolve-key key-path)]
    (let [preimage (pr-str (sort-by key data-to-sign))
          data-hash (sha256-hex preimage)
          raw-sig (bs/sign data-hash {:key priv :alg :ed25519})
          sig-b64 (.encodeToString (java.util.Base64/getEncoder) raw-sig)]
      {:signature/schema-version signature-schema-version
       :signature/signed-hash data-hash
       :signature/value sig-b64
       :signature/key-id (or key-path (System/getenv "PRF_SIGNING_KEY"))
       :signature/algorithm "ed25519"
       :signature/created-at (str (Instant/now))})
    (do (.println *err* "WARN: No signing key found. Set PRF_SIGNING_KEY or provide key-path.")
        nil)))

(defn write-signature!
  "Write a signature record as JSON next to the signed artifact.
   If artifact-path is \"results/runs/x/pre-run-commitment.json\",
   writes \"results/runs/x/pre-run-commitment.sig.json\""
  [artifact-path signature]
  (when signature
    (let [sig-path (str (clojure.string/replace artifact-path #"\.json$" "") ".sig.json")]
      (spit sig-path (json/write-str signature {:indent true}))
      sig-path)))

(defn sign-and-write!
  "Convenience: sign a data map and write the signature alongside the data file.
   Returns {:artifact-path, :sig-path, :signed-hash}."
  [artifact-path data-map & [key-path]]
  (let [sig (sign-map data-map key-path)
        sig-path (when sig (write-signature! artifact-path sig))]
    {:artifact-path artifact-path
     :sig-path sig-path
     :signed-hash (:signature/signed-hash sig)}))
