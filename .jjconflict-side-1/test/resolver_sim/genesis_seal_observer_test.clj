(ns resolver-sim.genesis-seal-observer-test
  (:require [clojure.test :refer [deftest is]]
            [scripts.deployment.genesis-seal-observer :as observer]))

(deftest observer-uses-canonical-ethereum-getter-selectors
  (let [selector #'observer/selector]
    (is (= "0x4338a541" (selector "executionChainId()")))
    (is (= "0xdde4432d" (selector "settlementChainId()")))
    (is (= "0x2aea4d21" (selector "sealer()")))
    (is (= "0x4268767d" (selector "authorityAdapter()")))
    (is (= "0xc93270a3" (selector "protocolGenesisRoot()")))
    (is (= "0x25e2fe8e" (selector "initialAuthorityStateRoot()")))
    (is (= "0x0b9b7475" (selector "initialConfigurationRoot()")))
    (is (= "0xe57e77c4" (selector "isSealedState()")))
    (is (= "0x82c3030e" (selector "chainInstanceGenesisRoot()")))
    (is (= "0x374101fb" (selector "genesisRuntimeCodeHash()")))
    (is (= "0x7dd78811" (selector "authorityAdapterRuntimeCodeHash()")))
    (is (= "0xb3aa72cc" (selector "authorityStateRoot()")))))

(deftest observer-rechecks-canonical-block-hash-after-pinned-reads
  (let [block-hash-a (str "0x" (apply str (repeat 64 "a")))
        block-hash-b (str "0x" (apply str (repeat 64 "b")))
        header-a {"number" "0x51" "hash" block-hash-a}
        block-reads (atom 0)
        fake-rpc (fn [_ method params]
                   (case method
                     "eth_chainId" "0x7a69"
                     "eth_getBlockByNumber"
                     (if (= "latest" (first params))
                       header-a
                       (do (swap! block-reads inc) header-a))
                     "eth_getCode" "0x6001"
                     "eth_call" (str "0x" (apply str (repeat 64 "0")))
                     (throw (ex-info "unexpected RPC method" {:method method :params params}))))
        observe #(with-redefs-fn {(ns-resolve 'scripts.deployment.genesis-seal-observer 'rpc!) fake-rpc}
                   (fn [] (observer/observe-chain-instance
                           "http://127.0.0.1:8545"
                           "0x1111111111111111111111111111111111111111"
                           "0x2222222222222222222222222222222222222222")))
        result (observe)]
    (is (= (str "keccak256:" (subs block-hash-a 2)) (:observation/block-hash result)))
    ;; Make the final pinned-height read report a replacement hash.
    (reset! block-reads 0)
    (let [reorg-rpc (fn [url method params]
                      (let [response (fake-rpc url method params)]
                        (if (and (= method "eth_getBlockByNumber")
                                 (not= "latest" (first params))
                                 (= 2 @block-reads))
                          (assoc response "hash" block-hash-b)
                          response)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"canonical observation block changed during pinned readback"
                            (with-redefs-fn {(ns-resolve 'scripts.deployment.genesis-seal-observer 'rpc!) reorg-rpc}
                              (fn [] (observer/observe-chain-instance
                                      "http://127.0.0.1:8545"
                                      "0x1111111111111111111111111111111111111111"
                                      "0x2222222222222222222222222222222222222222"))))))))

(deftest seal-transaction-observer-decodes-rpc-quantities-and-binds-receipt-block
  (let [genesis-address "0x1111111111111111111111111111111111111111"
        authority-address "0x2222222222222222222222222222222222222222"
        transaction-hash (str "0x" (apply str (repeat 64 "5")))
        block-hash (str "0x" (apply str (repeat 64 "a")))
        changed-block-hash (str "0x" (apply str (repeat 64 "b")))
        selector (#'observer/selector "sealGenesis(bytes32,bytes32,bytes32)")
        tx {"from" "0x3333333333333333333333333333333333333333"
            "to" genesis-address
            "input" (str selector (apply str (repeat 192 "0")))
            "blockNumber" "0x53"}
        receipt-hash (atom block-hash)
        receipt {"transactionHash" transaction-hash "blockNumber" "0x53"
                 "blockHash" block-hash "status" "0x1"}
        block-reads (atom 0)
        header {"number" "0x53" "hash" block-hash}
        fake-rpc (fn [_ method _params]
                   (case method
                     "eth_getTransactionByHash" tx
                     "eth_getTransactionReceipt" (assoc receipt "blockHash" @receipt-hash)
                     "eth_chainId" "0x7a69"
                     "eth_getBlockByNumber" (do (swap! block-reads inc) header)
                     "eth_getCode" "0x6001"
                     "eth_call" (str "0x" (apply str (repeat 64 "0")))
                     (throw (ex-info "unexpected RPC method" {:method method}))))
        observe #(with-redefs-fn {(ns-resolve 'scripts.deployment.genesis-seal-observer 'rpc!) fake-rpc}
                   (fn [] (observer/observe-seal-transaction
                           "http://127.0.0.1:8545" transaction-hash genesis-address authority-address)))
        result (observe)]
    (is (= 83 (:transaction/block-number (:transaction result))))
    (is (= 83 (:receipt/block-number (:receipt result))))
    (is (= :success (:receipt/status (:receipt result))))
    (is (= ["0000000000000000000000000000000000000000000000000000000000000000"
            "0000000000000000000000000000000000000000000000000000000000000000"
            "0000000000000000000000000000000000000000000000000000000000000000"]
           (:args (:transaction result))))
    (reset! receipt-hash changed-block-hash)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"receipt block hash differs from canonical block readback"
                          (observe)))))
