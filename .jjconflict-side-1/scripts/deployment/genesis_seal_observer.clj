(ns scripts.deployment.genesis-seal-observer
  "Read-only independent JSON-RPC observer for PRF chain-instance genesis.
  Every getter and code read is pinned to one observed block number."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [buddy.core.codecs :as codecs]
            [resolver-sim.genesis :as genesis])
  (:import [java.math BigInteger]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [org.bouncycastle.jcajce.provider.digest Keccak$Digest256]))

(def ^:private client (HttpClient/newHttpClient))
(def ^:private counter (atom 0))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn- keccak-ref [bytes]
  (let [digest (Keccak$Digest256.)]
    (.update digest ^bytes bytes)
    (str "keccak256:" (hex (.digest digest)))))

(defn- rpc! [url method params]
  (let [id (swap! counter inc)
        body (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params})
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "content-type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
        parsed (json/read-str (.body response))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "JSON-RPC returned non-200 status" {:status (.statusCode response) :method method})))
    (when-let [error (get parsed "error")]
      (throw (ex-info "JSON-RPC returned an error" {:method method :error error})))
    (when-not (= id (get parsed "id"))
      (throw (ex-info "JSON-RPC response id mismatch" {:expected id :actual (get parsed "id")})))
    (get parsed "result")))

(defn- selector [signature]
  (let [digest (Keccak$Digest256.)]
    (.update digest (.getBytes signature StandardCharsets/UTF_8))
    (str "0x" (subs (hex (.digest digest)) 0 8))))

(defn- word [url address signature block-tag]
  (let [data (rpc! url "eth_call" [{"to" address "data" (selector signature)} block-tag])]
    (when-not (and (string? data) (re-matches #"^0x[0-9a-fA-F]{64}$" data))
      (throw (ex-info "eth_call did not return one ABI word" {:address address :signature signature :data data})))
    (subs data 2)))

(defn- word-bytes32 [url address signature block-tag]
  (str "sha256:" (str/lower-case (word url address signature block-tag))))

(defn- word-keccak256 [url address signature block-tag]
  (str "keccak256:" (str/lower-case (word url address signature block-tag))))

(defn- word-address [url address signature block-tag]
  (str "0x" (subs (str/lower-case (word url address signature block-tag)) 24)))

(defn- word-uint [url address signature block-tag]
  (bigint (BigInteger. (word url address signature block-tag) 16)))

(defn- rpc-quantity [value]
  (when-not (and (string? value) (re-matches #"(?i)^0x[0-9a-f]+$" value))
    (throw (ex-info "JSON-RPC quantity is malformed" {:quantity value})))
  (BigInteger. (subs value 2) 16))

(defn- word-bool [url address signature block-tag]
  (not (zero? (BigInteger. (word url address signature block-tag) 16))))

(defn observe-chain-instance
  "Read genesis and authority adapter at one fixed latest block. Runtime code
  identities use EVM Keccak-256 and are returned in canonical PRF reference
  form. The observer refuses other chain IDs and contracts with empty code."
  ([rpc-url genesis-address authority-address]
   (observe-chain-instance rpc-url genesis-address authority-address nil))
  ([rpc-url genesis-address authority-address pinned-block-number]
  (when-not (and (genesis/valid-evm-address? genesis-address)
                 (genesis/valid-evm-address? authority-address))
    (throw (ex-info "observer requires canonical nonzero contract addresses" {})))
  (let [chain-id (rpc-quantity (rpc! rpc-url "eth_chainId" []))]
    (when-not (= (BigInteger. "31337") chain-id)
      (throw (ex-info "genesis observer is restricted to local chain 31337" {:chain-id chain-id})))
    (let [latest (when-not pinned-block-number (rpc! rpc-url "eth_getBlockByNumber" ["latest" false]))
          block-tag (if pinned-block-number
                      (str "0x" (.toString (BigInteger. (str pinned-block-number)) 16))
                      (get latest "number"))
          block-number (rpc-quantity block-tag)
          pinned-block (rpc! rpc-url "eth_getBlockByNumber" [block-tag false])
          code (fn [address]
                 (let [value (rpc! rpc-url "eth_getCode" [address block-tag])]
                   (when-not (and (string? value) (re-matches #"^0x(?:[0-9a-fA-F]{2})+$" value))
                     (throw (ex-info "deployed contract has empty or malformed runtime code" {:address address :code value})))
                   (codecs/hex->bytes (subs value 2))))
          genesis-code (code genesis-address)
          authority-code (code authority-address)
          g #(word-bytes32 rpc-url genesis-address % block-tag)
          ga #(word-address rpc-url genesis-address % block-tag)
          gu #(word-uint rpc-url genesis-address % block-tag)
          ab #(word-bytes32 rpc-url authority-address % block-tag)]
      (when-not (and (= block-tag (get pinned-block "number"))
                     (re-matches #"^0x[0-9a-fA-F]{64}$" (or (get pinned-block "hash") ""))
                     (or pinned-block-number
                         (and (= block-tag (get latest "number"))
                              (= (str/lower-case (or (get latest "hash") ""))
                                 (str/lower-case (get pinned-block "hash")))))
                     )
        (throw (ex-info "pinned observation block identity changed" {:requested block-tag :latest latest :pinned pinned-block})))
      (let [observed {:execution/chain-id (bigint chain-id)
                      :settlement/chain-id (gu "settlementChainId()")
                      :genesis/address (str/lower-case genesis-address)
                      :genesis/runtime-code-keccak256 (keccak-ref genesis-code)
                      :genesis/execution-chain-id (gu "executionChainId()")
                      :genesis/settlement-chain-id (gu "settlementChainId()")
                      :genesis/sealer-address (ga "sealer()")
                      :genesis/authority-adapter (ga "authorityAdapter()")
                      :genesis/protocol-genesis-root (g "protocolGenesisRoot()")
                      :genesis/initial-authority-state-root (g "initialAuthorityStateRoot()")
                      :genesis/initial-configuration-root (g "initialConfigurationRoot()")
                      :genesis/stored-runtime-code-keccak256 (word-keccak256 rpc-url genesis-address "genesisRuntimeCodeHash()" block-tag)
                      :genesis/stored-authority-adapter-runtime-code-keccak256 (word-keccak256 rpc-url genesis-address "authorityAdapterRuntimeCodeHash()" block-tag)
                      :genesis/sealed? (word-bool rpc-url genesis-address "isSealedState()" block-tag)
                      :genesis/chain-instance-genesis-root (g "chainInstanceGenesisRoot()")
                      :authority/address (str/lower-case authority-address)
                      :authority/runtime-code-keccak256 (keccak-ref authority-code)
                      :authority/protocol-genesis-root (ab "protocolGenesisRoot()")
                      :authority/state-root (ab "authorityStateRoot()")
                      :authority/constructor-protocol-genesis-root nil
                      :authority/constructor-initial-state-root nil
                      :observation/block-number (bigint block-number)
                      :observation/block-hash (str "keccak256:" (subs (str/lower-case (get pinned-block "hash")) 2))}]
        ;; Constructor values are read from the genesis immutables and cross-bound
        ;; against the adapter's live values; nil placeholders are never emitted.
        (let [final-block (rpc! rpc-url "eth_getBlockByNumber" [block-tag false])]
          (when-not (= (str/lower-case (get pinned-block "hash"))
                       (str/lower-case (or (get final-block "hash") "")))
            (throw (ex-info "canonical observation block changed during pinned readback"
                            {:block-number block-tag :initial-hash (get pinned-block "hash")
                             :final-hash (get final-block "hash")})))
          (assoc observed
                 :authority/constructor-protocol-genesis-root (:genesis/protocol-genesis-root observed)
                 :authority/constructor-initial-state-root (:genesis/initial-authority-state-root observed))))))))

(defn observe-seal-transaction
  "Fetch and decode a seal transaction and receipt from JSON-RPC, then read
  post-seal state at the exact receipt block. Reject pending or malformed
  transactions and non-canonical receipt/block bindings."
  [rpc-url transaction-hash genesis-address authority-address]
  (when-not (and (string? transaction-hash)
                 (re-matches #"^0x[0-9a-fA-F]{64}$" transaction-hash))
    (throw (ex-info "seal observer requires a canonical transaction hash" {})))
  (let [tx (rpc! rpc-url "eth_getTransactionByHash" [transaction-hash])
        receipt (rpc! rpc-url "eth_getTransactionReceipt" [transaction-hash])
        signature "sealGenesis(bytes32,bytes32,bytes32)"
        expected-selector (selector signature)
        input (some-> (get tx "input") str/lower-case)
        tx-block (get tx "blockNumber")
        receipt-block (get receipt "blockNumber")
        receipt-hash (get receipt "blockHash")]
    (when-not (and tx receipt (= tx-block receipt-block)
                   (= (str/lower-case transaction-hash) (str/lower-case (get receipt "transactionHash")))
                   (string? receipt-hash)
                   (re-matches #"^0x[0-9a-fA-F]{64}$" receipt-hash)
                   (string? (get tx "from"))
                   (string? (get tx "to"))
                   (string? input)
                   (re-matches (re-pattern (str "^" expected-selector "[0-9a-f]{192}$")) input))
      (throw (ex-info "transaction is pending, malformed or not bound to its receipt"
                      {:transaction tx :receipt receipt})))
    (let [block-number (rpc-quantity tx-block)
          block-hash (str "keccak256:" (subs (str/lower-case receipt-hash) 2))
          observation (observe-chain-instance rpc-url genesis-address authority-address block-number)
          transaction {:transaction/hash (str/lower-case transaction-hash)
                       :from (str/lower-case (get tx "from"))
                       :target (str/lower-case (get tx "to"))
                       :function "sealGenesis"
                       :args [(subs input 10 74) (subs input 74 138) (subs input 138 202)]
                       :transaction/block-number (bigint block-number)
                       :transaction/block-hash block-hash}
          receipt-record {:receipt/status (if (= "0x1" (str/lower-case (get receipt "status"))) :success :failed)
                          :receipt/transaction-hash (str/lower-case transaction-hash)
                          :receipt/block-number (bigint block-number)
                          :receipt/block-hash block-hash}]
      (when-not (= block-hash (:observation/block-hash observation))
        (throw (ex-info "receipt block hash differs from canonical block readback"
                        {:receipt-hash block-hash :observed-hash (:observation/block-hash observation)})))
      {:transaction transaction :receipt receipt-record :sealed-observation observation})))

(defn -main [& args]
  (case (first args)
    "seal-result"
    (let [[_ rpc-url transaction-hash genesis-address authority-address & extra] args]
      (when (or (nil? rpc-url) (nil? transaction-hash) (nil? genesis-address)
                (nil? authority-address) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.genesis-seal-observer seal-result <rpc-url> <transaction-hash> <genesis-address> <authority-adapter-address>"))
        (System/exit 2))
      (prn (observe-seal-transaction rpc-url transaction-hash genesis-address authority-address)))
    (let [[rpc-url genesis-address authority-address & extra] args]
      (when (or (nil? rpc-url) (nil? genesis-address) (nil? authority-address) (seq extra))
        (binding [*out* *err*]
          (println "Usage: clojure -M:test -m scripts.deployment.genesis-seal-observer <rpc-url> <genesis-address> <authority-adapter-address>"))
        (System/exit 2))
      (prn (assoc (observe-chain-instance rpc-url genesis-address authority-address)
                  :observation/schema "prf-chain-instance-live-observation.v1")))))
