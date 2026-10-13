(ns resolver-sim.allocation.realized-execution
  "Rust/SP1-compatible `realized-allocation-execution.v1` companion validation
   and EVM projection. This derives bytes only; it does not authorize EVM
   execution or accept host-computed projection values as companion input."
  (:require [clojure.set :as set]
            [resolver-sim.allocation.proof-admission :as proof-admission]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc])
  (:import [org.bouncycastle.jcajce.provider.digest Keccak$Digest256]))

(def ^:const schema-version "realized-allocation-execution.v1")
(def ^:const execution-profile :allocation-proof/largest-remainder-deferred-pro-rata.v1)
(def ^:const public-values-schema "realized-allocation-execution-public-values.v1")
(def ^:const public-values-length 384)
(def ^:const operation-binding-domain "prf.realized-allocation-execution.operation-binding.v1")
(def ^:const claim-leaf-domain "prf.realized-allocation-execution.claim-leaf.v1")
(def ^:const claim-node-domain "prf.realized-allocation-execution.claim-node.v1")
(def ^:const claim-root-empty-domain "prf.realized-allocation-execution.claim-root-empty.v1")

(def ^:private max-u64 (bigint "18446744073709551615"))
(def ^:private max-i64 (bigint "9223372036854775807"))
(def ^:private companion-fields
  #{:schema-version :claim-recipient-mapping :token-address :execution-target})
(def ^:private target-fields
  #{:allocator-address :chain-id :operation-id :available-amount})

(defn- canonical-u64-string?
  [value]
  (and (string? value)
       (boolean (re-matches #"0|[1-9][0-9]*" value))
       (<= (bigint value) max-u64)))

(defn- bytes32-hex?
  [value]
  (boolean (and (string? value)
                (re-matches #"0x[0-9a-f]{64}" value))))

(defn- bytes-compare
  [left right]
  (loop [index 0]
    (cond
      (= index (alength left)) (- (alength left) (alength right))
      (= index (alength right)) (- (alength left) (alength right))
      :else (let [a (bit-and 0xff (aget left index))
                  b (bit-and 0xff (aget right index))]
              (if (= a b)
                (recur (inc index))
                (- a b))))))

(defn- claim-id-compare
  [left right]
  (bytes-compare (hc/utf8-bytes left) (hc/utf8-bytes right)))

(defn- canonical-mappings?
  [mappings]
  (= mappings (vec (sort-by :claim/id claim-id-compare mappings))))

(defn- valid-mapping?
  [{:claim/keys [id] :keys [recipient]}]
  (and (string? id) (not= "" id) (genesis/valid-evm-address? recipient)))

(defn- exact-filled-amount?
  [amount]
  (and (integer? amount) (<= 0 amount max-i64)))

(defn- parse-u64
  [value]
  (when (canonical-u64-string? value) (bigint value)))

(defn validate-companion
  "Validate exactly the Rust execution-companion wire shape. Chain ID and
   amounts remain canonical decimal strings to preserve the JSON wire contract.
   Mapping completeness relative to positive-filled rows is checked by
   `project-execution`."
  [companion]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (when-not (map? companion)
      (report! "execution companion must be a map"))
    (when (map? companion)
      (let [actual (set (keys companion))]
        (when (seq (set/difference actual companion-fields))
          (report! "execution companion contains unknown fields"))
        (when (seq (set/difference companion-fields actual))
          (report! "execution companion is missing required fields")))
      (when-not (= schema-version (:schema-version companion))
        (report! "schema-version must be realized-allocation-execution.v1"))
      (when-not (genesis/valid-evm-address? (:token-address companion))
        (report! "token-address must be a non-zero canonical EVM address"))
      (let [target (:execution-target companion)]
        (when-not (map? target)
          (report! "execution-target must be a map"))
        (when (map? target)
          (let [actual (set (keys target))]
            (when (seq (set/difference actual target-fields))
              (report! "execution-target contains unknown fields"))
            (when (seq (set/difference target-fields actual))
              (report! "execution-target is missing required fields")))
          (when-not (genesis/valid-evm-address? (:allocator-address target))
            (report! "allocator-address must be a non-zero canonical EVM address"))
          (when-not (and (canonical-u64-string? (:chain-id target))
                         (pos? (parse-u64 (:chain-id target))))
            (report! "chain-id must be a nonzero canonical uint64 decimal string"))
          (when-not (bytes32-hex? (:operation-id target))
            (report! "operation-id must be a canonical lowercase 0x bytes32"))
          (when-not (canonical-u64-string? (:available-amount target))
            (report! "available-amount must be a canonical uint64 decimal string"))))
      (let [mappings (:claim-recipient-mapping companion)]
        (when-not (vector? mappings)
          (report! "claim-recipient-mapping must be a vector"))
        (when (vector? mappings)
          (when-not (every? #(and (map? %)
                                  (= #{:claim/id :recipient} (set (keys %)))
                                  (valid-mapping? %))
                            mappings)
            (report! "each mapping must contain only a nonempty string claim/id and non-zero canonical recipient"))
          (when-not (= (count mappings) (count (set (map :claim/id mappings))))
            (report! "claim-recipient-mapping claim/id values must be unique"))
          (when-not (canonical-mappings? mappings)
            (report! "claim-recipient-mapping must be strictly ordered by UTF-8 claim/id bytes")))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn companion-valid?
  [companion]
  (:valid? (validate-companion companion)))

(defn- keccak-bytes
  [& parts]
  (let [digest (Keccak$Digest256.)]
    (doseq [part parts] (.update digest ^bytes part 0 (alength ^bytes part)))
    (.digest digest)))

(defn- word-uint
  [value]
  (let [word (byte-array 32)
        raw (.toByteArray (biginteger value))
        count (min 32 (alength raw))]
    (System/arraycopy raw (- (alength raw) count) word (- 32 count) count)
    word))

(defn- word-address
  [address]
  (let [word (byte-array 32)
        raw (hc/hex->bytes (subs address 2))]
    (System/arraycopy raw 0 word 12 20)
    word))

(defn- raw-bytes32
  [value]
  (hc/hex->bytes (subs value 2)))

(defn- domain-word
  [domain]
  (keccak-bytes (hc/utf8-bytes domain)))

(defn- bytes->hex
  [bytes]
  (hc/bytes->hex bytes))

(defn operation-binding
  "Keccak256 ABI operation binding over W(domain), U(chainId), A(allocator),
   A(token), operationId, and U(availableAmount), in that exact order."
  [companion]
  (let [target (:execution-target companion)]
    (bytes->hex
     (keccak-bytes (domain-word operation-binding-domain)
                   (word-uint (parse-u64 (:chain-id target)))
                   (word-address (:allocator-address target))
                   (word-address (:token-address companion))
                   (raw-bytes32 (:operation-id target))
                   (word-uint (parse-u64 (:available-amount target)))))))

(defn execution-claim-leaf
  "Rust-compatible keccak256 ABI leaf for one positive-filled claim row."
  [{:keys [operation-binding claim/id recipient filled]}]
  (bytes->hex
   (keccak-bytes (domain-word claim-leaf-domain)
                 (hc/hex->bytes operation-binding)
                 (keccak-bytes (hc/utf8-bytes id))
                 (word-address recipient)
                 (word-uint filled))))

(defn execution-claim-root
  "Ordered duplicate-last binary Merkle root used by the Rust/SP1 execution
   contract. Empty membership has its separately domain-separated root."
  [leaves]
  (if (empty? leaves)
    (bytes->hex (keccak-bytes (domain-word claim-root-empty-domain)))
    (loop [level (mapv hc/hex->bytes leaves)]
      (if (= 1 (count level))
        (bytes->hex (first level))
        (let [even-level (if (odd? (count level))
                           (conj level (peek level))
                           level)]
          (recur (mapv (fn [[left right]]
                         (keccak-bytes (domain-word claim-node-domain) left right))
                       (partition 2 even-level))))))))

(defn- executable-rows
  [decision]
  (->> (:filled decision)
       (keep (fn [[claim-id filled]]
               (when (and (string? claim-id) (exact-filled-amount? filled) (pos? filled))
                 {:claim/id claim-id :filled filled})))
       (sort-by :claim/id claim-id-compare)
       vec))

(defn- projection-errors
  [statement-value decision companion]
  (let [rows (executable-rows decision)
        mapping-ids (mapv :claim/id (:claim-recipient-mapping companion))
        row-ids (mapv :claim/id rows)
        total (reduce + 0 (map :filled rows))
        target (:execution-target companion)]
    (cond-> []
      (not (companion-valid? companion))
      (conj :invalid-companion)
      (not (proof-admission/supported-decision? decision))
      (conj :unsupported-realization-profile)
      (not= statement/schema-version (:schema-version statement-value))
      (conj :invalid-statement-schema)
      (not (every? exact-filled-amount? (vals (:filled decision))))
      (conj :filled-amount-outside-current-i64-range)
      (not= row-ids mapping-ids)
      (conj :claim-recipient-mapping-not-exact)
      (not= total (parse-u64 (:available-amount target)))
      (conj :available-amount-mismatch))))

(defn public-values
  "Exact 384-byte static ABI tuple emitted by the Rust/SP1 execution program."
  [{:keys [statement-root operation-binding claim-root token-address allocator-address
           chain-id operation-id available-amount allocation-count total-allocated residual-amount]}]
  (let [bytes (hc/concat-byte-arrays
               [(domain-word public-values-schema)
                (hc/hex->bytes statement-root)
                (hc/hex->bytes operation-binding)
                (hc/hex->bytes claim-root)
                (word-address token-address)
                (word-address allocator-address)
                (word-uint chain-id)
                (raw-bytes32 operation-id)
                (word-uint available-amount)
                (word-uint allocation-count)
                (word-uint total-allocated)
                (word-uint residual-amount)])]
    (when-not (= public-values-length (alength bytes))
      (throw (ex-info "unexpected realized execution public-value length" {:length (alength bytes)})))
    bytes))

(defn project-execution
  "Validate and derive the exact Rust/SP1 V1 execution projection from an
   independently built statement and an admitted realization decision."
  [{:keys [statement decision companion]}]
  (let [errors (projection-errors statement decision companion)]
    (when (seq errors)
      (throw (ex-info (str "invalid realized allocation execution projection: " errors)
                      {:type :invalid-realized-allocation-execution :errors errors})))
    (let [binding (operation-binding companion)
          recipient-by-claim (into {} (map (juxt :claim/id :recipient))
                                   (:claim-recipient-mapping companion))
          rows (mapv #(assoc % :recipient (get recipient-by-claim (:claim/id %)))
                     (executable-rows decision))
          leaves (mapv #(execution-claim-leaf (assoc % :operation-binding binding)) rows)
          claim-root (execution-claim-root leaves)
          target (:execution-target companion)
          result {:execution/schema-version schema-version
                  :execution/profile execution-profile
                  :statement/root (:statement/root statement)
                  :execution/operation-binding binding
                  :execution/claim-rows rows
                  :execution/claim-leaves leaves
                  :execution/claim-root claim-root
                  :execution/token-address (:token-address companion)
                  :execution/allocator-address (:allocator-address target)
                  :execution/chain-id (parse-u64 (:chain-id target))
                  :execution/operation-id (:operation-id target)
                  :execution/available-amount (parse-u64 (:available-amount target))
                  :execution/allocation-count (count rows)
                  :execution/total-allocated (reduce + 0 (map :filled rows))
                  :execution/residual-amount 0}]
      (assoc result :execution/public-values
             (public-values {:statement-root (:statement/root result)
                             :operation-binding (:execution/operation-binding result)
                             :claim-root (:execution/claim-root result)
                             :token-address (:execution/token-address result)
                             :allocator-address (:execution/allocator-address result)
                             :chain-id (:execution/chain-id result)
                             :operation-id (:execution/operation-id result)
                             :available-amount (:execution/available-amount result)
                             :allocation-count (:execution/allocation-count result)
                             :total-allocated (:execution/total-allocated result)
                             :residual-amount (:execution/residual-amount result)})))))
