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

(def erc20-transfer-topic
  "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")

(def bounty-token-paid-topic
  "0xe1290cddcd8f1e03f4969eb3b2c88c000dd8cd4dc5668255fae0bb0a6d84201c")

(defn http-rpc
  "Create a JSON-RPC caller for an explicitly configured endpoint.

   Cleartext HTTP is limited to loopback for local development. Remote
   testnet/mainnet endpoints must use HTTPS so RPC credentials and responses
   are not exposed to a passive network observer."
  [url]
  (let [uri (URI/create url)
        scheme (some-> (.getScheme uri) str/lower-case)
        host (some-> (.getHost uri) str/lower-case)
        loopback? (contains? #{"localhost" "127.0.0.1" "::1" "[::1]"} host)
        endpoint-ok? (and host
                          (or (= "https" scheme)
                              (and (= "http" scheme) loopback?)))
        _ (when-not endpoint-ok?
            (throw (ex-info "RPC endpoint must use HTTPS, except for loopback development"
                            {:reason :insecure-rpc-endpoint
                             :scheme scheme :host host})))
        client (-> (HttpClient/newBuilder)
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
        (let [parent (rpc "eth_getBlockByHash" [parent-hash false])]
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

(defn- valid-evm-address? [address]
  (and (string? address)
       (re-matches #"0x[0-9a-fA-F]{40}" address)
       (not= "0x0000000000000000000000000000000000000000"
             (str/lower-case address))))

(defn- token-funding-source-state [rpc payout-contract funding-source-root block-tag]
  (let [root (normalized-bytes32 funding-source-root)
        argument (when root (subs root 2))
        source-data (when argument
                      (rpc "eth_call"
                           [{:to payout-contract
                             :data (str "0xa99900e6" argument)} block-tag]))
        token-data (when argument
                     (rpc "eth_call"
                          [{:to payout-contract
                            :data (str "0x176db5fc" argument)} block-tag]))
        authority-data (rpc "eth_call"
                            [{:to payout-contract :data "0x01d8a6c8"} block-tag])
        source-funder (when (and (string? source-data)
                                 (re-matches #"0x[0-9a-fA-F]{256}" source-data))
                        (address-word (word source-data 0)))
        original-amount (when source-funder (quantity (str "0x" (word source-data 1))))
        available (when source-funder (quantity (str "0x" (word source-data 2))))
        sequence (when source-funder (quantity (str "0x" (word source-data 3))))
        reserve-token (when (and (string? token-data)
                                 (re-matches #"0x[0-9a-fA-F]{64}" token-data))
                        (address-word (word token-data 0)))
        funding-authority (when (and (string? authority-data)
                                     (re-matches #"0x[0-9a-fA-F]{64}" authority-data))
                            (address-word (word authority-data 0)))]
    {:source-funder source-funder
     :original-amount original-amount
     :available available
     :sequence sequence
     :reserve-token reserve-token
     :funding-authority funding-authority}))

(defn- exact-erc20-transfer-log [receipt transfer expected]
  (let [target-index (:log-index transfer)
        transfer-logs
        (keep (fn [log]
                (let [topics (mapv str/lower-case (:topics log))
                      from (some-> (nth topics 1 nil) (subs 2) address-word)
                      to (some-> (nth topics 2 nil) (subs 2) address-word)
                      data (:data log)
                      amount-word (word data 0)
                      amount (when (and amount-word
                                        (not (word data 1)))
                               (bigint (java.math.BigInteger. amount-word 16)))]
                  (when (and (= (normalized-address (:token-contract expected))
                                (normalized-address (:address log)))
                             (= erc20-transfer-topic (first topics))
                             (= 3 (count topics))
                             (= (normalized-address (:sender expected)) from)
                             (= (normalized-address (:beneficiary expected)) to)
                             amount)
                    {:log log :from from :beneficiary to :amount amount
                     :log-index (quantity (:logIndex log))})))
              (:logs receipt))
        selected (filter #(= target-index (:log-index %)) transfer-logs)]
    (when (and (= 1 (count transfer-logs))
               (= 1 (count selected))
               (= (:amount expected) (:amount (first selected))))
      (first selected))))

(defn- exact-bounty-token-paid-log [receipt expected transfer-log]
  (let [payable-id (normalized-bytes32 (:payable-id expected))
        operation-root (normalized-bytes32 (:operation-root expected))
        funding-source-root (normalized-bytes32 (:funding-source-root expected))
        matches
        (keep (fn [log]
                (let [topics (mapv str/lower-case (:topics log))
                      data (:data log)
                      words (when (and (string? data)
                                       (re-matches #"0x[0-9a-fA-F]{640}" data))
                              (mapv #(word data %) (range 10)))
                      decoded {:token (some-> (nth words 0 nil) address-word)
                               :beneficiary (some-> (nth words 1 nil) address-word)
                               :amount (some-> (nth words 2 nil) (java.math.BigInteger. 16) bigint)
                               :sequence (some-> (nth words 3 nil) (java.math.BigInteger. 16) bigint)
                               :predecessor-root (some->> (nth words 4 nil) (str "0x"))
                               :successor-root (some->> (nth words 5 nil) (str "0x"))
                               :reserve-before (some-> (nth words 6 nil) (java.math.BigInteger. 16) bigint)
                               :reserve-after (some-> (nth words 7 nil) (java.math.BigInteger. 16) bigint)
                               :beneficiary-before (some-> (nth words 8 nil) (java.math.BigInteger. 16) bigint)
                               :beneficiary-after (some-> (nth words 9 nil) (java.math.BigInteger. 16) bigint)}]
                  (when (and (= 4 (count topics))
                             (= bounty-token-paid-topic (first topics))
                             (= payable-id (nth topics 1 nil))
                             (= operation-root (nth topics 2 nil))
                             (= funding-source-root (nth topics 3 nil))
                             (= (normalized-address (:sender expected))
                                (normalized-address (:address log)))
                             (= (normalized-address (:token-contract expected))
                                (:token decoded))
                             (= (normalized-address (:beneficiary expected))
                                (:beneficiary decoded))
                             (= (:amount expected) (:amount decoded))
                             (pos? (:sequence decoded))
                             (re-matches #"0x[0-9a-f]{64}"
                                         (:predecessor-root decoded))
                             (re-matches #"0x[0-9a-f]{64}"
                                         (:successor-root decoded))
                             (= (:amount decoded)
                                (- (:reserve-before decoded) (:reserve-after decoded)))
                             (= (:amount decoded)
                                (- (:beneficiary-after decoded) (:beneficiary-before decoded))))
                    {:log log :decoded decoded})))
              (:logs receipt))]
    (when (and (= 1 (count matches))
               (not (:removed (:log (first matches))))
               (> (quantity (get-in (first matches) [:log :logIndex]))
                  (:log-index transfer-log)))
      (first matches))))

(defn verify-erc20-transfer
  "Verify an exact ERC-20 Transfer event using a configured JSON-RPC source.

   `transfer` contains transaction hash, block number/hash and log index.
   `expected` pins chain ID and instance root, genesis hash, token contract and
   runtime digest, sender, beneficiary, base-unit amount, and required head
   (`:included` or `:finalized`). This checks RPC consistency, canonical block
   ancestry, token code and the exact event, but is not a consensus light client
   and does not protect against a dishonest RPC source."
  [rpc transfer expected]
  (try
    (let [shape? (and (map? transfer)
                      (= #{:transaction-hash :block-number :block-hash :log-index}
                         (set (keys transfer)))
                      (string? (:transaction-hash transfer))
                      (re-matches #"0x[0-9a-fA-F]{64}" (:transaction-hash transfer))
                      (integer? (:block-number transfer))
                      (not (neg? (:block-number transfer)))
                      (string? (:block-hash transfer))
                      (re-matches #"0x[0-9a-fA-F]{64}" (:block-hash transfer))
                      (integer? (:log-index transfer))
                      (not (neg? (:log-index transfer))))
          expected? (and (map? expected)
                         (integer? (:chain-id expected))
                         (pos? (:chain-id expected))
                         (string? (:chain-instance-root expected))
                         (re-matches #"sha256:[0-9a-f]{64}" (:chain-instance-root expected))
                         (string? (:genesis-hash expected))
                         (re-matches #"0x[0-9a-fA-F]{64}" (:genesis-hash expected))
                         (valid-evm-address? (:token-contract expected))
                         (string? (:token-code-sha256 expected))
                         (re-matches #"sha256:[0-9a-f]{64}" (:token-code-sha256 expected))
                         (valid-evm-address? (:sender expected))
                         (valid-evm-address? (:beneficiary expected))
                         (string? (:payout-code-sha256 expected))
                         (re-matches #"sha256:[0-9a-f]{64}" (:payout-code-sha256 expected))
                         (normalized-bytes32 (:payable-id expected))
                         (normalized-bytes32 (:operation-root expected))
                         (normalized-bytes32 (:funding-source-root expected))
                         (integer? (:asset-decimals expected))
                         (<= 0 (:asset-decimals expected) 36)
                         (integer? (:amount expected))
                         (pos? (:amount expected))
                         (contains? #{:included :finalized} (:required-finality expected)))
          _ (when-not (and shape? expected?)
              (throw (ex-info "invalid ERC-20 transfer context"
                              {:reason :invalid-erc20-transfer-context})))
          tx-hash (str/lower-case (:transaction-hash transfer))
          block-hash (str/lower-case (:block-hash transfer))
          block-number (:block-number transfer)
          receipt (rpc "eth_getTransactionReceipt" [tx-hash])
          receipt-block-number (quantity (:blockNumber receipt))
          canonical-block (rpc "eth_getBlockByHash" [block-hash false])
          block-at-height (rpc "eth_getBlockByNumber"
                               [(str "0x" (.toString (biginteger block-number) 16)) false])
          genesis-block (rpc "eth_getBlockByNumber" ["0x0" false])
          code (rpc "eth_getCode" [(:token-contract expected)
                                   (str "0x" (.toString (biginteger block-number) 16))])
          payout-code (rpc "eth_getCode" [(:sender expected)
                                          (str "0x" (.toString (biginteger block-number) 16))])
          decimals-result (rpc "eth_call"
                               [{:to (:token-contract expected)
                                 :data "0x313ce567"}
                                (str "0x" (.toString (biginteger block-number) 16))])
          asset-decimals (when (and (string? decimals-result)
                                    (re-matches #"0x[0-9a-fA-F]{64}" decimals-result))
                           (quantity decimals-result))
          payout-state (rpc "eth_call"
                            [{:to (:sender expected)
                              :data (str "0xd59387f1" (subs (:payable-id expected) 2))}
                             (str "0x" (.toString (biginteger block-number) 16))])
          funding-source-state (token-funding-source-state
                                rpc (:sender expected) (:funding-source-root expected)
                                (str "0x" (.toString (biginteger block-number) 16)))
          payable-lifecycle (when (and (string? payout-state)
                                       (re-matches #"0x[0-9a-fA-F]{64}" payout-state))
                              (quantity payout-state))
          chain-id (quantity (rpc "eth_chainId" []))
          ancestry-count (ancestry-to-genesis
                          rpc canonical-block
                          (str/lower-case (:genesis-hash expected))
                          (or (:max-ancestry-depth expected) 512))
          head-tag (case (:required-finality expected)
                     :included "latest"
                     :finalized "finalized")
          head (rpc "eth_getBlockByNumber" [head-tag false])
          head-number (quantity (:number head))
          head-hash (some-> (:hash head) str/lower-case)
          log-check (exact-erc20-transfer-log receipt transfer expected)
          settlement-log-check (exact-bounty-token-paid-log receipt expected log-check)
          receipt-ok? (and (= 1 (quantity (:status receipt)))
                           (= tx-hash (some-> (:transactionHash receipt) str/lower-case))
                           (= block-hash (some-> (:blockHash receipt) str/lower-case))
                           (= block-number receipt-block-number)
                           (= block-hash (some-> (:hash canonical-block) str/lower-case))
                           (= block-hash (some-> (:hash block-at-height) str/lower-case))
                           (= block-number (quantity (:number canonical-block))))
          context-ok? (and (= chain-id (:chain-id expected))
                           (= (str/lower-case (:genesis-hash expected))
                              (some-> (:hash genesis-block) str/lower-case))
                           (= (:asset-decimals expected) asset-decimals)
                           (= (:payout-code-sha256 expected) (code-sha256 payout-code))
                           (= 2 payable-lifecycle)
                           (valid-evm-address? (:funding-authority funding-source-state))
                           (= (:funding-authority funding-source-state)
                              (:source-funder funding-source-state))
                           (= (normalized-address (:token-contract expected))
                              (:reserve-token funding-source-state))
                           (pos? (:sequence funding-source-state 0))
                           (pos? (:original-amount funding-source-state 0))
                           (<= (+ (:available funding-source-state 0)
                                  (:amount expected))
                               (:original-amount funding-source-state 0))
                           (= (:token-code-sha256 expected) (code-sha256 code)))
          head-at-or-after? (and head-number (>= head-number block-number))
          head-ancestry? (and head-at-or-after?
                              (head-includes-block?
                               rpc head block-hash block-number
                               (or (:max-ancestry-depth expected) 512)))]
      (cond
        (not context-ok?) {:valid? false :reason :erc20-chain-or-token-context-mismatch}
        (not receipt-ok?) {:valid? false :reason :erc20-receipt-or-canonical-block-mismatch}
        (nil? ancestry-count) {:valid? false :reason :erc20-included-block-ancestry-mismatch}
        (nil? log-check) {:valid? false :reason :erc20-transfer-log-mismatch}
        (nil? settlement-log-check) {:valid? false :reason :erc20-settlement-event-mismatch}
        (not head-at-or-after?) {:valid? false :reason :erc20-finality-not-established}
        (not head-ancestry?) {:valid? false :reason :erc20-head-ancestry-mismatch}
        :else {:valid? true
               :trust-class (if (= :finalized (:required-finality expected))
                              :rpc-finalized-head-attestation
                              :rpc-included-head-attestation)
               :finality (:required-finality expected)
               :rpc-head {:kind head-tag :number head-number :hash head-hash}
               :chain-id chain-id
               :chain-instance-root (:chain-instance-root expected)
               :genesis-hash (:genesis-hash expected)
               :token-contract (:token-contract expected)
               :token-code-sha256 (:token-code-sha256 expected)
               :asset-decimals asset-decimals
               :payout-code-sha256 (:payout-code-sha256 expected)
               :payable-id (:payable-id expected)
               :operation-root (:operation-root expected)
               :funding-source-root (:funding-source-root expected)
               :funding-source-state funding-source-state
               :payable-lifecycle payable-lifecycle
               :settlement-event (:decoded settlement-log-check)
               :sender (:from log-check)
               :beneficiary (:beneficiary log-check)
               :amount (:amount log-check)
               :transaction-hash tx-hash
               :block-number block-number
               :block-hash block-hash
               :log-index (:log-index transfer)
               :ancestry-block-count ancestry-count}))
    (catch Exception e
      {:valid? false :reason :erc20-rpc-observation-error
       :detail (ex-data e)})))
