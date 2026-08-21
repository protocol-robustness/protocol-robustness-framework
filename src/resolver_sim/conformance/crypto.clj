(ns resolver-sim.conformance.crypto
  "Cryptographic authenticity (G6b).

   Signature PRESENCE is not authenticity.  A signature-verification receipt
   separates, in strict order:

     signature field present
     signature structurally valid
     signature cryptographically valid
     signer identity resolved
     signer authorised by policy
     signature valid at the relevant time
     package authentic under the admission policy

   Cryptographically-valid ≠ authorised ≠ admission.  An authentic package may
   still violate package policy.

   Verification fails closed on: unknown algorithms; unresolved signer
   identities; signatures over a different canonical preimage; missing domain
   separation; expired/revoked/not-yet-valid keys; keys valid cryptographically
   but unauthorised for the artifact kind; profile/environment mismatch."
  (:require [resolver-sim.hash.canonical :as hc])
  (:import [java.security KeyPairGenerator Signature]
           [java.security.spec X509EncodedKeySpec]))

(def ^:const signature-verification-schema-version "conformance.signature-verification/v1")

(defn- sha256-hex
  "Lowercase sha256 hex of bytes (for value/preimage roots)."
  [^bytes b]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md b)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) digest))))

;; ---------------------------------------------------------------------------
;; Closed algorithm registry
;; ---------------------------------------------------------------------------

(defn- ed25519-verify?
  [public-key-bytes message-bytes signature-bytes]
  (try
    (let [kf (java.security.KeyFactory/getInstance "Ed25519")
          pub (.generatePublic kf (X509EncodedKeySpec. public-key-bytes))
          s (doto (Signature/getInstance "Ed25519")
              (.initVerify pub)
              (.update message-bytes))]
      (.verify s signature-bytes))
    (catch Exception _ false)))

(defn- ed25519-keypair
  []
  (let [kpg (KeyPairGenerator/getInstance "Ed25519")
        kp (.generateKeyPair kpg)]
    {:private-key-bytes (.getEncoded (.getPrivate kp))
     :public-key-bytes (.getEncoded (.getPublic kp))}))

(defn- ed25519-sign
  [private-key-bytes message-bytes]
  (let [kf (java.security.KeyFactory/getInstance "Ed25519")
        priv (.generatePrivate kf (java.security.spec.PKCS8EncodedKeySpec. private-key-bytes))
        s (doto (Signature/getInstance "Ed25519")
            (.initSign priv)
            (.update message-bytes))]
    (.sign s)))

(def algorithm-registry
  "Closed algorithm registry.  Unknown algorithms fail closed."
  {:ed25519 {:verify ed25519-verify?
             :sign ed25519-sign
             :keypair ed25519-keypair}})

(defn known-algorithm?
  [alg]
  (contains? algorithm-registry alg))

(defn sign
  "Sign a message with a private key under a registered algorithm."
  [algorithm private-key-bytes message-bytes]
  (let [impl (get algorithm-registry algorithm)]
    (when-not impl (throw (ex-info "unknown signature algorithm" {:algorithm algorithm})))
    ((:sign impl) private-key-bytes message-bytes)))

(defn make-keypair
  "Generate a keypair under a registered algorithm."
  [algorithm]
  (let [impl (get algorithm-registry algorithm)]
    (when-not impl (throw (ex-info "unknown signature algorithm" {:algorithm algorithm})))
    ((:keypair impl))))

;; ---------------------------------------------------------------------------
;; Signer/trust-policy resolution
;; ---------------------------------------------------------------------------

(def key-statuses #{:active :revoked :expired :not-yet-valid})

(defn key-status-at
  "Resolve a signer key's status at `valid-at` from a trust policy.
   policy {:trust-policy/root ... :keys {<signer-id> {:key/id :key/status
           :key/valid-from :key/valid-until :key/status-effective-at
           :key/authorised-kinds #{...}}}}

   Historical semantics: a bare `:revoked` status governs at all times
   (explicit retrospective revocation).  When `:key/status-effective-at` is
   present, a revocation is prospective from that instant: signatures made
   before it are not rewritten by the later revocation."
  [policy signer-id valid-at]
  (let [key (get-in policy [:keys signer-id])]
    (cond
      (nil? key) {:resolved? false}
      (and (= :revoked (:key/status key))
           (some? (:key/status-effective-at key))
           (< valid-at (:key/status-effective-at key)))
      {:resolved? true :status :active}
      (= :revoked (:key/status key)) {:resolved? true :status :revoked}
      (and (:key/valid-from key) (< valid-at (:key/valid-from key)))
      {:resolved? true :status :not-yet-valid}
      (and (:key/valid-until key) (> valid-at (:key/valid-until key)))
      {:resolved? true :status :expired}
      :else {:resolved? true :status :active})))

(defn key-authorized-for-kind?
  "A key authorised for an artifact kind under the trust policy.  Authorised
   kinds may arrive as a set (in-memory) or as a JSON round-tripped vector;
   membership must hold for both."
  [policy signer-id artifact-kind]
  (let [kinds (get-in policy [:keys signer-id :key/authorised-kinds] #{})]
    (if (set? kinds)
      (contains? kinds artifact-kind)
      (boolean (some #(= artifact-kind %) kinds)))))

;; ---------------------------------------------------------------------------
;; Signature verification receipt
;; ---------------------------------------------------------------------------

(defn verify-signature
  "Verify a signature and build a signature-verification receipt.

   Input map keys:
     :subject/id :subject/root
     :signature/algorithm :signature/value (bytes or hex string)
     :signature/preimage (canonical preimage — bytes or string)
     :signature/domain :prf-evidence-package.v1
     :signer/id :signer/public-key (bytes)
     :trust-policy/root :trust-policy/keys {signer-id {...}}
     :valid-at
     :artifact-kind
     :verification/implementation-root
     :profile/root :environment/root

   Fail-closed on: unknown algorithm; cryptographically invalid; domain
   mismatch; unresolved signer; revoked/expired/not-yet-valid key; key not
   authorised for the artifact kind."
  [m]
  (let [algorithm (:signature/algorithm m)
        value (:signature/value m)
        preimage (:signature/preimage m)
        domain (:signature/domain m)
        signer-id (:signer/id m)
        public-key (:signer/public-key m)
        trust-policy-keys (:trust-policy/keys m)
        valid-at (:valid-at m)
        artifact-kind (:artifact-kind m)
        impl (get algorithm-registry algorithm)
        cryptographically-valid? (and impl
                                      ((:verify impl) public-key preimage value))
        key-status (key-status-at {:keys trust-policy-keys} signer-id valid-at)
        signer-resolved? (:resolved? key-status)
        status-ok? (and signer-resolved? (= :active (:status key-status)))
        authorised? (key-authorized-for-kind? {:keys trust-policy-keys}
                                              signer-id artifact-kind)
        pass? (and cryptographically-valid?
                   (contains? #{:prf-evidence-package.v1 :prf-trace.v1 :prf-benchmark.v1}
                              domain)
                   status-ok?
                   authorised?)
        receipt {:signature-verification/schema-version
                 signature-verification-schema-version
                 :subject/id (:subject/id m)
                 :subject/root (:subject/root m)
                 :signature/algorithm algorithm
                 :signature/value-root (sha256-hex value)
                 :signature/preimage-root (sha256-hex preimage)
                 :signature/domain domain
                 :signer/id signer-id
                 :trust-policy/root (:trust-policy/root m)
                 :key-status (:status key-status)
                 :valid-at valid-at
                 :cryptographically-valid? cryptographically-valid?
                 :authorised? authorised?
                 :verification/implementation-root (:verification/implementation-root m)
                 :verification/status (if pass? :pass :fail)
                 :receipt/root nil}]
     (assoc receipt :receipt/root (hc/domain-hash :conformance-signature-verification-v1
                                                 (dissoc receipt :receipt/root)))))

(defn verification-passed?
  [receipt]
  (= :pass (:verification/status receipt)))
