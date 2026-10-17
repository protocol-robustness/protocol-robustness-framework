(ns resolver-sim.hash.evm
  "Small, explicit EVM ABI-word and Keccak helpers for cross-language PRF/EVM
  commitments. These functions intentionally cover only fixed-width words."
  (:require [resolver-sim.hash.canonical :as canonical])
  (:import [java.math BigInteger]
           [org.bouncycastle.asn1.sec SECNamedCurves]
           [org.bouncycastle.jcajce.provider.digest Keccak$Digest256]))

(defn keccak256-bytes [& parts]
  (let [digest (Keccak$Digest256.)]
    (doseq [part parts]
      (.update digest ^bytes part 0 (alength ^bytes part)))
    (.digest digest)))

(defn- uint-word [value]
  (let [n (bigint value)]
    (when-not (<= 0 n (dec (bigint (.shiftLeft BigInteger/ONE 256))))
      (throw (ex-info "value is outside uint256" {:value value})))
    (let [word (byte-array 32)
          raw (.toByteArray (biginteger n))
          length (min 32 (alength raw))]
      (System/arraycopy raw (- (alength raw) length) word (- 32 length) length)
      word)))

(defn- bytes32-word [value]
  (let [hex (cond
              (and (string? value) (re-matches #"^(sha256|keccak256):[0-9a-f]{64}$" value))
              (subs value (- (count value) 64))
              (and (string? value) (re-matches #"^0x[0-9a-f]{64}$" value)) (subs value 2)
              (and (string? value) (re-matches #"^[0-9a-f]{64}$" value)) value
              :else nil)]
    (when-not hex (throw (ex-info "value is not a canonical bytes32 reference" {:value value})))
    (canonical/hex->bytes hex)))

(defn- address-word [value]
  (when-not (and (string? value) (re-matches #"^0x[0-9a-f]{40}$" value))
    (throw (ex-info "address must be lowercase 20-byte EVM hex" {:value value})))
  (let [word (byte-array 32)
        raw (canonical/hex->bytes (subs value 2))]
    (System/arraycopy raw 0 word 12 20)
    word))

(def ^:private mutation-zero-bytes32 (str "0x" (apply str (repeat 64 "0"))))

(defn authority-mutation-hash
  "Hash one AuthorityMutation struct exactly as Solidity abi.encode(struct).
  Map fields are the lowercase hyphenated EDN counterparts of the Solidity
  camelCase struct fields."
  [{:keys [kind decision-root target-mode target-root parent-configuration-root
           new-configuration-root parent-verifier-registry-root
           new-verifier-registry-root epoch proof-mode]}]
  (keccak256-bytes
   (uint-word (or kind 0))
   (bytes32-word decision-root)
   (uint-word (or target-mode 0))
   (bytes32-word (or target-root mutation-zero-bytes32))
   (bytes32-word (or parent-configuration-root mutation-zero-bytes32))
   (bytes32-word (or new-configuration-root mutation-zero-bytes32))
   (bytes32-word (or parent-verifier-registry-root mutation-zero-bytes32))
   (bytes32-word (or new-verifier-registry-root mutation-zero-bytes32))
   (uint-word (or epoch 0))
   (uint-word (or proof-mode 0))))

(defn authority-mutation-root
  "Order-sensitive root used by the EVM adapter. Callers must supply the
  canonical sort order by decision-root bytes then mutation kind."
  [mutations]
  (reduce (fn [root mutation]
            (keccak256-bytes root (authority-mutation-hash mutation)))
          (keccak256-bytes (.getBytes "PRF_AUTHORITY_MUTATION_LIST_V1" "UTF-8"))
          mutations))

(defn authority-transition-execution-commitment
  "Reproduce SewGovernanceAuthorityAdapter.authorityTransitionExecutionCommitment
  using the fixed-width words in the exact Solidity abi.encode order."
  [{:keys [chain-id adapter-address instance-root previous-root successor-root
           policy-root previous-revision successor-revision mutation-root
           decision-preimages-root]}]
  (keccak256-bytes
   (keccak256-bytes (.getBytes "PRF_AUTHORITY_STATE_TRANSITION_EXECUTION_V1" "UTF-8"))
   (uint-word chain-id)
   (address-word adapter-address)
   (bytes32-word instance-root)
   (bytes32-word previous-root)
   (bytes32-word successor-root)
   (bytes32-word policy-root)
   (uint-word previous-revision)
   (uint-word successor-revision)
   (bytes32-word mutation-root)
   (bytes32-word decision-preimages-root)))

(defn hex-ref [bytes]
  (str "0x" (apply str (map #(format "%02x" (bit-and 0xff %)) bytes))))

(defn recover-ethereum-personal-sign-address
  "Recover a lowercase EVM address from a 65-byte r||s||v signature over the
  EIP-191 personal-sign digest of a bytes32 commitment. This supports the
  validator contract's explicit secp256k1 policy v3; it does not authorize the
  signer or establish transition semantics."
  [commitment signature]
  (when-not (and (string? commitment) (re-matches #"^0x[0-9a-f]{64}$" commitment)
                 (string? signature) (re-matches #"^(0x)?[0-9a-fA-F]{130}$" signature))
    (throw (ex-info "commitment or EVM signature has a non-canonical shape" {})))
  (let [raw (canonical/hex->bytes (if (.startsWith ^String signature "0x") (subs signature 2) signature))
        r (BigInteger. 1 (java.util.Arrays/copyOfRange raw 0 32))
        s (BigInteger. 1 (java.util.Arrays/copyOfRange raw 32 64))
        v (bit-and 0xff (aget raw 64))
        recovery-id (case v 27 0 28 1 nil)
        params (SECNamedCurves/getByName "secp256k1")
        curve (.getCurve params)
        n (.getN params)
        half-n (.shiftRight n 1)
        p (.getCharacteristic (.getField curve))
        x r
        _ (when-not (and recovery-id (pos? (.signum r)) (neg? (.compareTo r n))
                         (pos? (.signum s)) (not (pos? (.compareTo s half-n)))
                         (neg? (.compareTo x p)))
            (throw (ex-info "EVM signature scalars or recovery id are invalid" {})))
        x-bytes (.toByteArray x)
        encoded (byte-array 33)
        _ (aset-byte encoded 0 (byte (+ 2 recovery-id)))
        copy-length (min 32 (alength x-bytes))
        _ (System/arraycopy x-bytes (- (alength x-bytes) copy-length) encoded (- 33 copy-length) copy-length)
        point (.decodePoint curve encoded)
        _ (when-not (.isInfinity (.multiply point n))
            (throw (ex-info "EVM signature recovery point is outside the prime-order subgroup" {})))
        prefix (.getBytes "\u0019Ethereum Signed Message:\n32" "UTF-8")
        digest (keccak256-bytes prefix (canonical/hex->bytes (subs commitment 2)))
        z (BigInteger. 1 digest)
        inverse (.modInverse r n)
        public-key (.normalize (.multiply (.add (.multiply point s)
                                                (.multiply (.getG params) (.mod (.negate z) n))) inverse))
        uncompressed (.getEncoded public-key false)
        address-hash (keccak256-bytes (java.util.Arrays/copyOfRange uncompressed 1 (alength uncompressed)))]
    (str "0x" (apply str (map #(format "%02x" (bit-and 0xff %))
                              (java.util.Arrays/copyOfRange address-hash 12 32))))))
