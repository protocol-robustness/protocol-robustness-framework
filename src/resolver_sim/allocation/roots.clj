(ns resolver-sim.allocation.roots
  "Root and digest construction for the allocation kernel.

   All roots are SHA-256 domain-separated hashes over the canonical binary
   encoding (CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI). The Clojure and Rust
   implementations must produce the same exact bytes.

   The result root is a Merkle tree over result leaves using the existing PRF
   Merkle convention (EVIDENCE_COMMITMENT_SPEC_V1):
     leaf  = SHA256(EVIDENCE_MERKLE_LEAF_V1  || leaf_digest)
     node  = SHA256(EVIDENCE_MERKLE_NODE_V1  || left || right)
   An odd level duplicates its final node. Leaf digests are themselves
   domain-separated hashes under :result-root."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private merkle-leaf-tag (.getBytes "EVIDENCE_MERKLE_LEAF_V1" "UTF-8"))
(def ^:private merkle-node-tag (.getBytes "EVIDENCE_MERKLE_NODE_V1" "UTF-8"))

(defn- hex->raw
  "Convert a 0x-prefixed or bare 64-char hex string to raw 32 bytes."
  [hex-str]
  (let [hex (if (str/starts-with? hex-str "0x") (subs hex-str 2) hex-str)]
    (byte-array (map #(Integer/parseInt % 16)
                     (map #(apply str %) (partition 2 hex))))))

(defn- concat-bytes
  [& bas]
  (let [out (byte-array (reduce + (map count bas)))]
    (loop [idx 0, bas bas]
      (if (seq bas)
        (let [ba (first bas)]
          (System/arraycopy ba 0 out idx (count ba))
          (recur (+ idx (count ba)) (rest bas)))
        out))))

(defn merkle-leaf
  "Raw 32-byte Merkle leaf hash: SHA256(EVIDENCE_MERKLE_LEAF_V1 || digest)."
  [^bytes digest]
  (hc/hash-bytes (concat-bytes merkle-leaf-tag digest)))

(defn merkle-node
  "Raw 32-byte internal node hash: SHA256(EVIDENCE_MERKLE_NODE_V1 || left || right)."
  [^bytes left ^bytes right]
  (hc/hash-bytes (concat-bytes merkle-node-tag left right)))

(defn- bytes->bare-hex
  "Lowercase 64-char hex encoding (no 0x prefix), consistent with domain-hash."
  [^bytes ba]
  (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) ba)))

(defn merkle-root
  "Compute the Merkle root over a sequence of raw 32-byte digests using the
   existing PRF convention. An odd level duplicates its final node."
  [raw-digests]
  (loop [level (vec raw-digests)]
    (cond
      (empty? level)
      (throw (ex-info "Empty Merkle tree" {}))

      (= 1 (count level))
      (bytes->bare-hex (first level))

      :else
      (let [level (if (odd? (count level))
                    (conj level (last level))
                    level)
            next-level (mapv (fn [[l r]] (merkle-node l r))
                             (partition 2 level))]
        (recur next-level)))))

(defn claimant-set-root
  "Domain-separated hash of the canonically ordered claimant set."
  [context]
  (hc/domain-hash :claimant-set (:claimants context)))

(defn outcome-set-root
  "Domain-separated hash of the canonically ordered outcome set."
  [context]
  (hc/domain-hash :outcome-set (:outcomes context)))

(defn proposed-rates-root
  "Domain-separated hash of the proposed rates in outcome canonical order."
  [context]
  (hc/domain-hash :proposed-rates
                  (mapv (fn [rate-entry]
                          {:outcome/id (:outcome/id rate-entry)
                           :numerator (get-in rate-entry [:rate :numerator])
                           :denominator (get-in rate-entry [:rate :denominator])})
                        (:proposed-rates context))))

(defn selected-outcome-hash
  "Domain-separated hash of a selected outcome."
  [outcome]
  (hc/domain-hash :selected-outcome outcome))

(defn result-leaf
  "One result Merkle leaf value tree. Commits round/context identity, claim ID,
   beneficiary (economic owner), input amount, input weight, final allocation,
   selected outcome ID, and result status."
  [{:keys [context-hash input-amount input-weight final-allocation
           selected-outcome-id result-status] :as leaf}]
  {:round/context-hash context-hash
   :claim/id (:claim/id leaf)
   :beneficiary (:beneficiary leaf)
   :input-amount input-amount
   :input-weight input-weight
   :final-allocation final-allocation
   :selected-outcome-id selected-outcome-id
   :result-status result-status})

(defn result-leaf-digest
  "Raw 32-byte digest of a result leaf under the :result-root domain."
  [leaf]
  (let [hex (hc/domain-hash :result-root leaf)]
    (hex->raw hex)))

(defn result-merkle-root
  "Merkle root over result leaves in canonical claimant ordering."
  [result-leaves]
  (merkle-root (mapv result-leaf-digest result-leaves)))

(defn certificate-assertions-digest
  "Digest committing to the allocation context hash, the ordered assertion
   results, the selected outcome, the result root, totals, and the kernel
   version. No diagnostics, timestamps, paths, runtime versions, or proof stubs
   are included."
  [{:keys [allocation-context-hash assertions selected-outcome-id
           selected-outcome-index result-root total-allocated residual-capacity
           allocation-kernel-version]}]
  (hc/domain-hash :certificate-assertions
                  {:allocation-context-hash allocation-context-hash
                   :assertions assertions
                   :selected-outcome-id selected-outcome-id
                   :selected-outcome-index selected-outcome-index
                   :result-root result-root
                   :total-allocated total-allocated
                   :residual-capacity residual-capacity
                   :allocation-kernel-version allocation-kernel-version}))
