(ns resolver-sim.allocation.source-bound-entitlement
  "Pure successor derivation for beneficiary-bound entitlement execution.

   Canonical identities use PRF SHA-256 canonical encoding. Execution
   commitments and the nine-word public projection use EVM keccak/ABI words.
   This namespace has no SP1, deployment, storage, or withdrawal behavior."
  (:refer-clojure :exclude [derive])
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [resolver-sim.allocation.persisted-statement-admission :as persisted]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.math BigInteger]
           [org.bouncycastle.jcajce.provider.digest Keccak$Digest256]))

(def ^:const entitlement-schema "beneficiary-entitlement.v1")
(def ^:const entitlement-set-schema "beneficiary-entitlement-set.v1")
(def ^:const descriptor-schema "entitlement-representation-descriptor.v1")
(def ^:const descriptor-profile "committed-immutable-table-v1")
(def ^:const public-values-schema "prf/source-bound-entitlement-public-values.v1")
(def ^:const public-values-length 288)
(def ^:const salt-bytes 32)

(def ^:const entitlement-domain :beneficiary-entitlement-v1)
(def ^:const entitlement-set-domain :beneficiary-entitlement-set-v1)
(def ^:const descriptor-domain :entitlement-representation-descriptor-v1)
(def ^:const allocation-binding-domain "prf.onchain-shortfall-allocation-binding.v1")
(def ^:const prepared-operation-domain "prf.prepared-operation.v2")
(def ^:const reserve-state-domain "prf.economic-reserve-state.v2")
(def ^:const reserve-state-v3-domain "prf.economic-reserve-state.v3")
(def ^:const claim-leaf-domain "prf.source-bound-entitlement.claim-leaf.v1")
(def ^:const claim-node-domain "prf.source-bound-entitlement.claim-node.v1")
(def ^:const claim-empty-domain "prf.source-bound-entitlement.claim-empty.v1")
(def ^:const commitment-domain "prf.source-bound-entitlement.commitment.v1")
(def ^:const table-node-domain "prf.source-bound-entitlement.table-node.v1")
(def ^:const table-empty-domain "prf.source-bound-entitlement.table-empty.v1")
(def ^:const table-root-domain "prf.source-bound-entitlement.table-root.v1")

(def ^:private max-u256 (bigint (.subtract (.shiftLeft BigInteger/ONE 256) BigInteger/ONE)))

(def representation-descriptor
  {:schema-version descriptor-schema
   :profile descriptor-profile
   :commitment-domain commitment-domain
   :commitment-fields ["domain" "allocation-binding-root" "operation-id"
                       "ordinal" "claim-identity-hash" "beneficiary-address"
                       "amount" "beneficiary-profile" "salt"]
   :commitment-field-types ["bytes32" "bytes32" "bytes32" "uint256"
                            "bytes32" "address" "uint256" "bytes32" "bytes32"]
   :abi-encoding "abi.encode-static-32-byte-words.v1"
   :uint-word-scheme "uint256-big-endian-zero-padded-word.v1"
   :address-word-scheme "address-left-zero-padded-32-byte-word.v1"
   :claim-identity-word-scheme "keccak256-prf-canonical-claim-string-bytes.v1"
   :domain-word-scheme "keccak256-utf8-domain-string-word.v1"
   :abi-word-scheme "ordered-static-32-byte-word-concatenation.v1"
   :canonical-index-mapping "claim-identity-canonical-bytes-ascending.v1"
   :beneficiary-profile-encoding "keccak256-utf8-profile-word.v1"
   :opening-schema "entitlement-opening.bytes32-salt.v1"
   :salt-width-bytes salt-bytes
   :salt-placement "final-abi-word"
   :entitlement-commitment "keccak256-static-abi-v1"
   :claim-leaf-fields ["claim-leaf-domain" "allocation-binding-root" "operation-id"
                       "ordinal" "claim-identity-hash" "beneficiary-address"
                       "amount" "beneficiary-profile-word"]
   :claim-leaf-domain claim-leaf-domain
   :claim-leaf-field-types ["bytes32" "bytes32" "bytes32" "uint256"
                            "bytes32" "address" "uint256" "bytes32"]
   :claim-leaf-scheme "domain-word-plus-ordered-static-abi-words.v1"
   :claim-merkle-scheme "positional-ordered-duplicate-last-merkle.v1"
   :claim-merkle-node-domain claim-node-domain
   :claim-merkle-empty-domain claim-empty-domain
   :table-commitment-scheme "domain-word-plus-ordered-static-abi-words.v1"
   :logical-table-aggregation "ordered-duplicate-last-merkle-then-summary.v1"
   :table-merkle-scheme "positional-ordered-duplicate-last-merkle.v1"
   :table-leaf-placement "commitment-is-leaf-value-no-prehash.v1"
   :table-summary-fields ["table-merkle-root" "entitlement-count" "entitlement-total"]
   :table-summary-scheme "domain-word-plus-three-ordered-static-abi-words.v1"
   :table-node-domain table-node-domain
   :table-empty-domain table-empty-domain
   :table-root-domain table-root-domain
   :consumption-index "ordinal-div-256"
   :consumption-bit-order "lsb0-ordinal-mod-256"})

(defn- keccak-bytes [& parts]
  (let [digest (Keccak$Digest256.)]
    (doseq [part parts]
      (.update digest ^bytes part 0 (alength ^bytes part)))
    (.digest digest)))

(defn- word-uint [value]
  (let [n (bigint value)]
    (when-not (<= 0 n max-u256)
      (throw (ex-info "integer outside uint256" {:value value})))
    (let [word (byte-array 32)
          raw (.toByteArray (biginteger n))
          count (min 32 (alength raw))]
      (System/arraycopy raw (- (alength raw) count) word (- 32 count) count)
      word)))

(defn- fixed-hex-bytes [field value size]
  (when-not (and (string? value)
                 (re-matches (re-pattern (str "0x[0-9a-f]{" (* 2 size) "}")) value))
    (throw (ex-info "invalid fixed-width lowercase hex" {:field field :value value :bytes size})))
  (hc/hex->bytes (subs value 2)))

(defn- root-bytes [field value]
  (let [raw (if (and (string? value) (.startsWith value "sha256:"))
              (subs value 7)
              value)]
    (when-not (and (string? raw) (re-matches #"[0-9a-f]{64}" raw))
      (throw (ex-info "invalid root" {:field field :value value})))
    (hc/hex->bytes raw)))

(defn- address-word [field value]
  (let [raw (fixed-hex-bytes field value 20)
        word (byte-array 32)]
    (when (every? zero? raw)
      (throw (ex-info "zero beneficiary address" {:field field})))
    (System/arraycopy raw 0 word 12 20)
    word))

(defn- bytes32-word [field value]
  (fixed-hex-bytes field value 32))

(defn- padded-address-or-word [field value]
  (cond
    (and (string? value) (re-matches #"0x[0-9a-f]{40}" value)) (address-word field value)
    (and (string? value) (re-matches #"0x[0-9a-f]{64}" value)) (bytes32-word field value)
    :else (throw (ex-info "invalid address/bytes32 word" {:field field :value value}))))

(defn- domain-word [domain]
  (keccak-bytes (hc/utf8-bytes domain)))

(defn- claim-id-bytes [claim-id]
  (when-not (and (string? claim-id) (not-empty claim-id))
    (throw (ex-info "claim identity must be nonempty" {:claim-id claim-id})))
  (hc/canonical-bytes claim-id))

(defn- byte-array-compare [left right]
  (loop [i 0]
    (cond
      (= i (alength ^bytes left)) (- (alength ^bytes left) (alength ^bytes right))
      (= i (alength ^bytes right)) 1
      :else (let [a (bit-and 0xff (aget ^bytes left i))
                  b (bit-and 0xff (aget ^bytes right i))]
              (if (= a b) (recur (inc i)) (- a b))))))

(defn- claim-compare [left right]
  (byte-array-compare (claim-id-bytes left) (claim-id-bytes right)))

(defn- merkle-root [leaves node-domain empty-domain]
  (if (empty? leaves)
    (keccak-bytes (domain-word empty-domain))
    (loop [level (mapv #(if (bytes? %) % (hc/hex->bytes %)) leaves)]
      (if (= 1 (count level))
        (first level)
        (let [even-level (if (odd? (count level)) (conj level (peek level)) level)]
          (recur (mapv (fn [[left right]]
                         (keccak-bytes (domain-word node-domain) left right))
                       (partition 2 even-level))))))))

(defn- canonical-root [domain value]
  (hc/domain-hash domain value))

(defn descriptor-root []
  (canonical-root descriptor-domain representation-descriptor))

(defn- allocation-binding-root [reserve-binding-root realized-statement-root operation-id]
  (canonical-root
   allocation-binding-domain
   {:schema-version "prf/onchain-shortfall-allocation-binding.v1"
    :reserve-binding/root reserve-binding-root
    :realized-statement/root realized-statement-root
    :operation-id operation-id}))

(defn- canonical-member-preimage [member]
  (select-keys member [:schema-version :allocation-binding/root
                       :realized-statement/root :operation-id :claim/id
                       :beneficiary :beneficiary-profile :amount :ordinal]))

(defn entitlement-root [member]
  (canonical-root entitlement-domain (canonical-member-preimage member)))

(defn- canonical-set-preimage [entitlement-set]
  (select-keys entitlement-set [:schema-version :allocation-binding/root
                                :realized-statement/root :operation-id
                                :beneficiary-profile :entitlements
                                :entitlement-count :entitlement-total]))

(defn entitlement-set-root [entitlement-set]
  (canonical-root entitlement-set-domain (canonical-set-preimage entitlement-set)))

(defn- canonical-members [allocation-context decision allocation-binding-root-value statement-root
                          operation-id beneficiary-profile beneficiary-mapping]
  (let [positive (->> (:filled decision)
                      (keep (fn [[claim-id amount]]
                              (when (pos? amount) [claim-id (bigint amount)])))
                      (sort-by first claim-compare)
                      vec)
        duplicate-claims (->> positive (map first) frequencies (filter (fn [[_ n]] (> n 1))) keys seq)
        mapping-claims (map :claim-id beneficiary-mapping)
        duplicate-mappings (->> mapping-claims frequencies (filter (fn [[_ n]] (> n 1))) keys seq)
        by-claim (into {} (map (juxt :claim-id identity)) beneficiary-mapping)
        owner-by-claim (into {} (map (juxt :claim/id :economic-owner-id))
                             (:claimants allocation-context))
        expected (set (map first positive))]
    (when (or duplicate-claims duplicate-mappings)
      (throw (ex-info "duplicate claim identity" {:claims (or duplicate-claims duplicate-mappings)})))
    (when-not (= expected (set mapping-claims))
      (throw (ex-info "beneficiary mapping must exactly cover positive rows"
                      {:expected expected :actual (set mapping-claims)})))
    (doseq [[claim-id beneficiary] (map (juxt :claim-id :beneficiary) beneficiary-mapping)]
      (when-not (= beneficiary (owner-by-claim claim-id))
        (throw (ex-info "beneficiary must match allocation-context economic owner"
                        {:claim-id claim-id
                         :beneficiary beneficiary
                         :economic-owner-id (owner-by-claim claim-id)}))))
    (mapv (fn [ordinal [claim-id amount]]
            (let [beneficiary (:beneficiary (by-claim claim-id))
                  _ (address-word :beneficiary beneficiary)
                  member {:schema-version entitlement-schema
                          :allocation-binding/root (str "sha256:" allocation-binding-root-value)
                          :realized-statement/root statement-root
                          :operation-id operation-id
                          :claim/id claim-id
                          :beneficiary beneficiary
                          :beneficiary-profile beneficiary-profile
                          :amount amount
                          :ordinal ordinal}]
              (assoc member :entitlement/root (entitlement-root member))))
          (range) positive)))

(defn- claim-leaf [member]
  (keccak-bytes
   (domain-word claim-leaf-domain)
   (root-bytes :allocation-binding/root (:allocation-binding/root member))
   (bytes32-word :operation-id (:operation-id member))
   (word-uint (:ordinal member))
   (keccak-bytes (claim-id-bytes (:claim/id member)))
   (address-word :beneficiary (:beneficiary member))
   (word-uint (:amount member))
   (domain-word (:beneficiary-profile member))))

(defn- entitlement-commitment [member salt]
  (keccak-bytes
   (domain-word commitment-domain)
   (root-bytes :allocation-binding/root (:allocation-binding/root member))
   (bytes32-word :operation-id (:operation-id member))
   (word-uint (:ordinal member))
   (keccak-bytes (claim-id-bytes (:claim/id member)))
   (address-word :beneficiary (:beneficiary member))
   (word-uint (:amount member))
   (domain-word (:beneficiary-profile member))
   (fixed-hex-bytes :salt salt salt-bytes)))

(defn- table-root [commitments count total]
  (let [members-root (merkle-root commitments table-node-domain table-empty-domain)]
    (keccak-bytes (domain-word table-root-domain)
                  members-root (word-uint count) (word-uint total))))

(defn- operation-root [prepared]
  (let [fields [[:operation-id :word] [:initiator :address] [:chain-id :uint]
                [:reserve :address] [:token :address] [:allocator :address]
                [:configuration-root :word] [:configuration-epoch :uint]
                [:source-verifier-id :word] [:execution-verifier-id :word]
                [:source-program-vkey :word] [:source-program-elf-sha256 :word]
                [:source-statement-schema :word] [:source-public-values-schema :word]
                [:execution-statement-schema :word] [:execution-public-values-schema :word]
                [:policy-root :word] [:beneficiary-profile :profile]
                [:residual-recipient :address]]
        encode (fn [[field kind]]
                 (let [value (get prepared field)]
                   (case kind
                     :uint (word-uint (bigint value))
                     :address (padded-address-or-word field value)
                     :profile (domain-word value)
                     :word (bytes32-word field value))))]
    (apply keccak-bytes (domain-word prepared-operation-domain) (map encode fields))))

(defn- reserve-commitment [prepared-root prepared state]
  (let [gross (bigint (:gross-balance state))
        reserved (bigint (:reserved-before state))
        protected (bigint (:protected-capital state))
        available (bigint (:available-amount state))
        version (or (:commitment-version state) "v2")
        domain (case version
                 "v2" reserve-state-domain
                 "v3" reserve-state-v3-domain
                 (throw (ex-info "unsupported reserve commitment version" {:version version})))
        policy-root (:policy-root state)]
    (when (or (> (+ reserved protected) gross)
              (not= available (- gross reserved protected)))
      (throw (ex-info "invalid reserve accounting" {})))
    (when (and (= version "v3") (nil? policy-root))
      (throw (ex-info "v3 policy-root required" {})))
    (when (and (= version "v2") policy-root)
      (throw (ex-info "policy-root requires reserve commitment version v3" {})))
    (apply keccak-bytes
           (concat [(domain-word domain)
                    prepared-root
                    (padded-address-or-word :reserve (:reserve prepared))
                    (padded-address-or-word :token (:token prepared))
                    (word-uint gross) (word-uint reserved) (word-uint protected)
                    (word-uint available) (word-uint (bigint (:availability-revision state)))]
                   (when (= version "v3") [(root-bytes :policy-root policy-root)])))))

(defn- public-values [words]
  (let [bytes (hc/concat-byte-arrays words)]
    (when-not (= public-values-length (alength bytes))
      (throw (ex-info "unexpected successor public-values length" {:length (alength bytes)})))
    bytes))

(defn derive
  "Derive all successor semantic and EVM outputs from raw/authenticated inputs.
   No expected output root, aggregate, or public word is accepted."
  [input]
  (let [allowed #{:realized-statement-input :reserve-binding-root :prepared-operation
                  :economic-reserve-state
                  :beneficiary-mapping :openings}
        unknown (set/difference (set (keys input)) allowed)]
    (when (seq unknown)
      (throw (ex-info "unknown successor witness fields" {:fields unknown})))
    (let [{:keys [allocation-context statement decision]}
          (persisted/reconstruct-input (json/write-str (:realized-statement-input input)))
          statement-root (:statement/root statement)
          prepared (:prepared-operation input)
          operation-id (:operation-id prepared)
          profile (:beneficiary-profile prepared)
          _ (when-not (contains? #{"smart-account-v1" "immutable-address-v1"} profile)
              (throw (ex-info "unsupported beneficiary profile" {:profile profile})))
          prepared-root (operation-root prepared)
          reserve-commitment-value (reserve-commitment prepared-root prepared (:economic-reserve-state input))
          binding-root (allocation-binding-root (:reserve-binding-root input) statement-root operation-id)
          members (canonical-members allocation-context decision binding-root statement-root operation-id
                                     profile (:beneficiary-mapping input))
          count (count members)
          total (reduce + 0N (map :amount members))
          expected-available (bigint (get-in input [:economic-reserve-state :available-amount]))
          _ (when (or (zero? count) (not= total expected-available))
              (throw (ex-info "Shortfall entitlement conservation failed"
                              {:count count :total total :available expected-available})))
          entitlement-set {:schema-version entitlement-set-schema
                           :allocation-binding/root (str "sha256:" binding-root)
                           :realized-statement/root statement-root
                           :operation-id operation-id
                           :beneficiary-profile profile
                           :entitlements members
                           :entitlement-count count
                           :entitlement-total total}
          set-root (entitlement-set-root entitlement-set)
          claim-leaves (mapv claim-leaf members)
          claim-root (merkle-root claim-leaves claim-node-domain claim-empty-domain)
          opening-claims (map :claim-id (:openings input))
          duplicate-openings (->> opening-claims frequencies
                                  (filter (fn [[_ n]] (> n 1))) keys seq)
          _ (when duplicate-openings
              (throw (ex-info "duplicate opening claim identity"
                              {:claims duplicate-openings})))
          opening-by-claim (into {} (map (juxt :claim-id :salt)) (:openings input))
          _ (when-not (= (set (map :claim/id members)) (set (keys opening-by-claim)))
              (throw (ex-info "openings must exactly cover entitlements" {})))
          commitments (mapv #(entitlement-commitment % (opening-by-claim (:claim/id %))) members)
          descriptor-root-value (descriptor-root)
          table-root-value (table-root commitments count total)
          schema-word (domain-word public-values-schema)
          words [schema-word prepared-root reserve-commitment-value
                 (hc/hex->bytes binding-root) (hc/hex->bytes descriptor-root-value)
                 table-root-value (word-uint count) (word-uint total) claim-root]
          encoded (public-values words)]
      {:realized-statement-root statement-root
       :allocation-binding-root binding-root
       :operation-id operation-id
       :entitlement-set entitlement-set
       :canonical-entitlement-set-root set-root
       :entitlement-representation-descriptor representation-descriptor
       :entitlement-representation-descriptor-root descriptor-root-value
       :allocation-claim-leaves (mapv hc/bytes->hex claim-leaves)
       :allocation-claim-root (hc/bytes->hex claim-root)
       :evm-entitlement-commitments (mapv hc/bytes->hex commitments)
       :evm-entitlement-table-root (hc/bytes->hex table-root-value)
       :entitlement-count count
       :entitlement-total total
       :prepared-operation-root (hc/bytes->hex prepared-root)
       :reserve-state-commitment (hc/bytes->hex reserve-commitment-value)
       :schema-word (hc/bytes->hex schema-word)
       :public-words (mapv hc/bytes->hex words)
       :public-values (hc/bytes->hex encoded)})))

(defn derive-controlled-source
  "Derive the successor projection only when the prepared operation commits to
   the reconstructed realized-statement root. This controlled-source profile
   is suitable for fixed conformance inputs, not production claims authority."
  [input]
  (let [derived (derive input)
        statement-root (:realized-statement-root derived)
        digest (when (and (string? statement-root)
                          (re-matches #"[0-9a-f]{64}" statement-root))
                 statement-root)
        expected (when digest (str "0x" digest))
        committed (get-in input [:prepared-operation :policy-root])]
    (when-not (= expected committed)
      (throw (ex-info "prepared-operation policy-root must equal realized statement root"
                      {:expected expected :actual committed})))
    derived))

(defn fixture-projection
  "Human-reviewable JSON-compatible projection used by Clojure/Rust vectors."
  [derived]
  {:realized-statement-root (:realized-statement-root derived)
   :allocation-binding-root (:allocation-binding-root derived)
   :operation-id (:operation-id derived)
   :ordered-entitlements
   (mapv (fn [member]
           {:allocation-binding-root (:allocation-binding/root member)
            :realized-statement-root (:realized-statement/root member)
            :operation-id (:operation-id member)
            :claim-id (:claim/id member)
            :beneficiary (:beneficiary member)
            :beneficiary-profile (:beneficiary-profile member)
            :amount (str (:amount member))
            :ordinal (str (:ordinal member))
            :entitlement-root (:entitlement/root member)})
         (get-in derived [:entitlement-set :entitlements]))
   :canonical-entitlement-set-root (:canonical-entitlement-set-root derived)
   :entitlement-representation-descriptor-root
   (:entitlement-representation-descriptor-root derived)
   :allocation-claim-leaves (:allocation-claim-leaves derived)
   :allocation-claim-root (:allocation-claim-root derived)
   :evm-entitlement-commitments (:evm-entitlement-commitments derived)
   :evm-entitlement-table-root (:evm-entitlement-table-root derived)
   :entitlement-count (str (:entitlement-count derived))
   :entitlement-total (str (:entitlement-total derived))
   :prepared-operation-root (:prepared-operation-root derived)
   :reserve-state-commitment (:reserve-state-commitment derived)
   :schema-word (:schema-word derived)
   :public-words (:public-words derived)
   :public-values (:public-values derived)})
