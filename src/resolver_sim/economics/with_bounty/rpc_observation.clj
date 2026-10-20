(ns resolver-sim.economics.with-bounty.rpc-observation
  "Independent JSON-RPC checks for a retained native bounty payout.

   An RPC endpoint remains a trust input. This verifier checks receipt/log and
   block consistency and optionally the endpoint's `finalized` head; it is not
   a consensus light client."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.time Duration]
           [java.util HexFormat]))

(def bounty-paid-topic
  "0x6dc490073c04b7752ab94752fb6ab71a1c57314a137ad2ae0446d5a68246aef1")

(defn http-rpc
  "Create a JSON-RPC caller for an explicitly configured endpoint."
  [url]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 5))
                   .build)
        request-id (atom 0)]
    (fn [method params]
      (let [payload (json/write-str {:jsonrpc "2.0" :id (swap! request-id inc)
                                     :method method :params params})
            request (-> (HttpRequest/newBuilder (URI/create url))
                        (.timeout (Duration/ofSeconds 10))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString payload))
                        .build)
            response (.send client request (HttpResponse$BodyHandlers/ofString))
            decoded (json/read-str (.body response) :key-fn keyword)]
        (when-not (= 200 (.statusCode response))
          (throw (ex-info "JSON-RPC HTTP request failed"
                          {:status (.statusCode response) :method method})))
        (when (:error decoded)
          (throw (ex-info "JSON-RPC method failed"
                          {:method method :rpc-error (:error decoded)})))
        (:result decoded)))))

(defn- quantity [value]
  (when (and (string? value) (re-matches #"0x[0-9a-fA-F]+" value))
    (bigint (java.math.BigInteger. (subs value 2) 16))))

(defn- word [data index]
  (let [hex (subs (str/lower-case data) 2)
        start (* index 64)]
    (when (and (<= (+ start 64) (count hex))
               (re-matches #"[0-9a-f]{64}" (subs hex start (+ start 64))))
      (subs hex start (+ start 64)))))

(defn- address-word [word]
  (when (and word (re-matches #"0{24}[0-9a-f]{40}" word))
    (str "0x" (subs word 24))))

(defn- normalized-address [address]
  (some-> address str/lower-case))

(defn- normalized-bytes32 [root]
  (let [value (some-> root str/lower-case)]
    (when (and value (re-matches #"0x[0-9a-f]{64}" value)) value)))

(defn- code-sha256 [code]
  (when (and (string? code) (re-matches #"0x(?:[0-9a-fA-F]{2})+" code))
    (let [hex-format (HexFormat/of)
          bytes (.parseHex hex-format (subs code 2))
          digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
      (str "sha256:" (.formatHex hex-format digest)))))

(defn- ancestry-to-genesis
  [rpc included-block expected-genesis-hash max-depth]
  (loop [block included-block depth 0]
    (let [number (quantity (:number block))
          hash (some-> (:hash block) str/lower-case)]
      (cond
        (nil? number) nil
        (nil? hash) nil
        (zero? number) (when (= expected-genesis-hash hash) (inc depth))
        (>= depth max-depth) nil
        :else
        (let [parent-hash (some-> (:parentHash block) str/lower-case)
              parent (when parent-hash
                       (rpc "eth_getBlockByHash" [parent-hash false]))]
          (when (and parent
                     (= parent-hash (some-> (:hash parent) str/lower-case))
                     (= (dec number) (quantity (:number parent)))
                     (some? (:parentHash parent)))
            (recur parent (inc depth))))))))

(defn- head-includes-block?
  "Check that the sampled RPC head descends from the exact receipt block.

   A head height greater than the receipt height is not sufficient: a reorg or
   inconsistent RPC response can report a competing branch at that height."
  [rpc head included-hash included-number max-depth]
  (loop [block head depth 0]
    (let [number (quantity (:number block))
          hash (some-> (:hash block) str/lower-case)
          parent-hash (some-> (:parentHash block) str/lower-case)]
      (cond
        (or (nil? number) (nil? hash)) false
        (= number included-number) (= hash included-hash)
        (< number included-number) false
        (>= depth max-depth) false
        (not (and parent-hash (re-matches #"0x[0-9a-f]{64}" parent-hash))) false
        :else
        (let [parent (rpc "eth_getBlockByHash" [parent-hash])]
          (if (and parent
                   (= parent-hash (some-> (:hash parent) str/lower-case))
                   (= (dec number) (quantity (:number parent))))
            (recur parent (inc depth))
            false))))))

(defn- exact-payout-log [receipt observation expected]
  (let [target-index (str "0x" (.toString (java.math.BigInteger/valueOf
                                           (:observation/log-index observation)) 16))
        matches (filter #(= (str/lower-case (:logIndex %)) target-index)
                        (:logs receipt))]
    (when (= 1 (count matches))
      (let [log (first matches)
            topics (mapv str/lower-case (:topics log))
            data (:data log)
            decoded {:beneficiary (some-> (word data 0) address-word)
                     :amount (some-> (word data 1) (java.math.BigInteger. 16) bigint)
                     :sequence (some-> (word data 2) (java.math.BigInteger. 16) bigint)
                     :predecessor-root (some->> (word data 3) (str "0x"))
                     :successor-root (some->> (word data 4) (str "0x"))
                     :reserve-before (some-> (word data 5) (java.math.BigInteger. 16) bigint)
                     :reserve-after (some-> (word data 6) (java.math.BigInteger. 16) bigint)
                     :beneficiary-before (some-> (word data 7) (java.math.BigInteger. 16) bigint)
                     :beneficiary-after (some-> (word data 8) (java.math.BigInteger. 16) bigint)}]
        (when (and (= (normalized-address (:address log))
                      (normalized-address (:contract expected)))
                   (= bounty-paid-topic (first topics))
                   (= (normalized-bytes32 (:payable-id expected)) (nth topics 1 nil))
                   (= (normalized-bytes32 (:operation-root expected)) (nth topics 2 nil))
                   (= (normalized-bytes32 (:funding-source-root expected)) (nth topics 3 nil))
                   (= (:beneficiary decoded)
                      (normalized-address (:beneficiary-address expected)))
                   (= (:amount decoded) (:observation/amount observation))
                   (= (:sequence decoded) (:observation/sequence observation))
                   (= (:reserve-before decoded) (:observation/reserve-before observation))
                   (= (:reserve-after decoded) (:observation/reserve-after observation))
                   (= (:beneficiary-before decoded)
                      (:observation/beneficiary-before observation))
                   (= (:beneficiary-after decoded)
                      (:observation/beneficiary-after observation))
                   (= (:predecessor-root decoded)
                      (normalized-bytes32 (:predecessor-root expected)))
                   (= (:successor-root decoded)
                      (normalized-bytes32 (:successor-root expected))))
          {:log log :decoded decoded})))))

(defn verify-rpc-observation
  "Verify a BountyPaid receipt against JSON-RPC and the caller's expected
   deployment/identity context. `rpc` is a function of (method params).

   Expected context binds chain ID/instance, genesis hash, observation source,
   contract address/runtime digest, payout identifiers, and the required
   endpoint head (`:included` or `:finalized`). Ancestry traversal is bounded by
   `:max-ancestry-depth` (default 512); this profile is intended for local
   qualification, not long public-chain ancestry proofs."
  [rpc observation expected]
  (try
    (let [chain-id (quantity (rpc "eth_chainId" []))
          receipt (rpc "eth_getTransactionReceipt"
                       [(:observation/transaction-hash observation)])
          block-hash (str/lower-case (:observation/block-hash observation))
          block-number (:observation/block-number observation)
          receipt-block-number (quantity (:blockNumber receipt))
          canonical-block (rpc "eth_getBlockByHash" [block-hash false])
          genesis-block (rpc "eth_getBlockByNumber" ["0x0" false])
          block-at-height (rpc "eth_getBlockByNumber"
                               [(str "0x" (.toString (biginteger block-number) 16)) false])
          code (rpc "eth_getCode" [(:contract expected)
                                   (str "0x" (.toString (biginteger block-number) 16))])
          ancestry-count (ancestry-to-genesis
                          rpc canonical-block
                          (some-> (:genesis-hash expected) str/lower-case)
                          (or (:max-ancestry-depth expected) 512))
          log-check (exact-payout-log receipt observation expected)
          receipt-ok? (and (= 1 (quantity (:status receipt)))
                           (= (str/lower-case (:observation/transaction-hash observation))
                              (str/lower-case (:transactionHash receipt)))
                           (= block-hash (str/lower-case (:blockHash receipt)))
                           (= block-number receipt-block-number)
                           (= block-hash (str/lower-case (:hash canonical-block)))
                           (= block-hash (str/lower-case (:hash block-at-height)))
                           (= block-number (quantity (:number canonical-block))))
          context-ok? (and (= chain-id (:chain-id expected))
                           (= (:chain-instance-root expected)
                              (:observation/chain-instance-root observation))
                           (= (some-> (:genesis-hash expected) str/lower-case)
                              (some-> (:observation/genesis-hash observation) str/lower-case)
                              (some-> (:hash genesis-block) str/lower-case))
                           (= (:observation-source-id expected)
                              (:observation/source-id observation))
                           (= (:contract-code-sha256 expected)
                              (:observation/contract-code-sha256 observation)
                              (code-sha256 code))
                           (= (normalized-address (:contract expected))
                              (normalized-address (:observation/contract observation))))
          required-finality (:required-finality expected)
          head-tag (case required-finality
                     :included "latest"
                     :finalized "finalized"
                     nil)
          head (when head-tag (rpc "eth_getBlockByNumber" [head-tag false]))
          head-number (quantity (:number head))
          head-hash (some-> (:hash head) str/lower-case)
          head-at-or-after? (and head-number (>= head-number block-number))
          head-ancestry? (and head-at-or-after?
                              (head-includes-block?
                               rpc head block-hash block-number
                               (or (:max-ancestry-depth expected) 512)))
          finality (when head-ancestry? required-finality)]
      (cond
        (not context-ok?) {:valid? false :reason :rpc-chain-context-mismatch}
        (not receipt-ok?) {:valid? false :reason :rpc-receipt-or-block-mismatch}
        (nil? ancestry-count) {:valid? false :reason :rpc-block-ancestry-mismatch}
        (nil? log-check) {:valid? false :reason :rpc-payout-log-mismatch}
        (not head-at-or-after?) {:valid? false :reason :rpc-finality-not-established}
        (not head-ancestry?) {:valid? false :reason :rpc-head-ancestry-mismatch}
        :else {:valid? true
               :trust-class (if (= :finalized finality)
                              :rpc-finalized-head-attestation
                              :rpc-included-head-attestation)
               :finality finality
               :rpc-head {:kind head-tag
                          :number head-number
                          :hash head-hash}
               :chain-id chain-id
               :chain-instance-root (:chain-instance-root expected)
               :genesis-hash (:genesis-hash expected)
               :observation-source-id (:observation-source-id expected)
               :contract-code-sha256 (:contract-code-sha256 expected)
               :ancestry-block-count ancestry-count
               :block-hash block-hash
               :transaction-hash (:observation/transaction-hash observation)
               :log-index (:observation/log-index observation)
               :event (:decoded log-check)}))
    (catch Exception e
      {:valid? false :reason :rpc-observation-error
       :detail (ex-data e)})))
