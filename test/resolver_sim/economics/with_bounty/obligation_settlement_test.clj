(ns resolver-sim.economics.with-bounty.obligation-settlement-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.economics.with-bounty.fixtures :as fixtures]
            [resolver-sim.economics.with-bounty.obligation-settlement :as settlement]
            [resolver-sim.economics.with-bounty.rpc-observation :as rpc-observation]))

(defn- hex-word [n]
  (format "%064x" (biginteger n)))

(defn- rpc-fixture [required-finality]
  (let [contract "0x000000000000000000000000000000000000b017"
        beneficiary "0x000000000000000000000000000000000000a11c"
        blocks (mapv (fn [n]
                       {:number (str "0x" (.toString (biginteger n) 16))
                        :hash (format "0x%064x" (inc n))
                        :parentHash (format "0x%064x" n)})
                     (range 12))
        block-by-hash (into {} (map (juxt :hash identity) blocks))
        genesis-hash (:hash (first blocks))
        receipt-block (nth blocks 10)
        code "0x60006000"
        code-sha256 "sha256:8610831f75a6b7da7c428c7c490db797438e90a401d6fa31ffa5fe8ac1470f84"
        payable-id (str "0x" (apply str (repeat 64 "1")))
        operation-root (str "0x" (apply str (repeat 64 "2")))
        funding-root (str "0x" (apply str (repeat 64 "3")))
        before-root (str "0x" (apply str (repeat 64 "4")))
        after-root (str "0x" (apply str (repeat 64 "5")))
        data (str "0x" (apply str (repeat 24 "0")) (subs beneficiary 2)
                  (hex-word 70) (hex-word 17) (subs before-root 2) (subs after-root 2)
                  (hex-word 100) (hex-word 30) (hex-word 5) (hex-word 75))
        receipt {:status "0x1" :transactionHash "0xtx"
                 :blockNumber (:number receipt-block) :blockHash (:hash receipt-block)
                 :logs [{:address contract :logIndex "0x2" :data data
                         :topics [rpc-observation/bounty-paid-topic
                                  payable-id operation-root funding-root]}]}
        event-observation {:observation/contract contract
                           :observation/chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                           :observation/genesis-hash genesis-hash
                           :observation/contract-code-sha256 code-sha256
                           :observation/source-id "test-rpc-observer"
                           :observation/transaction-hash "0xtx"
                           :observation/block-number 10
                           :observation/block-hash (:hash receipt-block)
                           :observation/log-index 2
                           :observation/amount 70
                           :observation/sequence 17
                           :observation/reserve-before 100
                           :observation/reserve-after 30
                           :observation/beneficiary-before 5
                           :observation/beneficiary-after 75}
        expected {:chain-id 31337 :chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                  :genesis-hash genesis-hash :contract-code-sha256 code-sha256
                  :observation-source-id "test-rpc-observer"
                  :contract contract :payable-id payable-id
                  :operation-root operation-root :funding-source-root funding-root
                  :beneficiary-address beneficiary :predecessor-root before-root
                  :successor-root after-root :required-finality required-finality}
        rpc (fn [method params]
              (case method
                "eth_chainId" "0x7a69"
                "eth_getTransactionReceipt" receipt
                "eth_getBlockByHash" (get block-by-hash (first params))
                "eth_getBlockByNumber" (case (first params)
                                          "finalized" (nth blocks 10)
                                          "latest" (nth blocks 11)
                                          (nth blocks (Integer/parseInt
                                                       (subs (first params) 2) 16)))
                "eth_getCode" code))]
    {:rpc rpc :observation event-observation :expected expected
     :receipt receipt}))

(deftest rpc-observation-checks-receipt-event-canonical-block-and-finality
  (let [{:keys [rpc observation expected]} (rpc-fixture :finalized)
        result (rpc-observation/verify-rpc-observation rpc observation expected)]
    (is (:valid? result))
    (is (= :rpc-finalized-head-attestation (:trust-class result)))
    (is (= 70 (get-in result [:event :amount])))))

(deftest rpc-observation-rejects-chain-mismatch-and-unfinalized-head
  (let [{:keys [rpc observation expected]} (rpc-fixture :finalized)
        wrong-chain (fn [method params]
                      (if (= method "eth_chainId") "0x1" (rpc method params)))
        shallow-head (fn [method params]
                       (if (and (= method "eth_getBlockByNumber")
                                (= "finalized" (first params)))
                         {:number "0x9"}
                         (rpc method params)))]
    (is (= :rpc-chain-context-mismatch
           (:reason (rpc-observation/verify-rpc-observation
                     wrong-chain observation expected))))
    (is (= :rpc-finality-not-established
           (:reason (rpc-observation/verify-rpc-observation
                     shallow-head observation expected))))))

(deftest rpc-observation-binds-genesis-contract-code-source-and-ancestry
  (let [{:keys [rpc observation expected]} (rpc-fixture :finalized)
        bad-genesis (fn [method params]
                      (if (and (= method "eth_getBlockByNumber")
                               (= "0x0" (first params)))
                        {:number "0x0" :hash (format "0x%064x" 99)}
                        (rpc method params)))
        bad-code (fn [method params]
                   (if (= method "eth_getCode") "0x60016000" (rpc method params)))
        bad-parent (fn [method params]
                     (if (= method "eth_getBlockByHash")
                       (let [block (rpc method params)]
                         (if (= "0xa" (:number block))
                           (assoc block :parentHash (format "0x%064x" 99))
                           block))
                       (rpc method params)))
        bad-source (assoc observation :observation/source-id "other-rpc")]
    (is (= :rpc-chain-context-mismatch
           (:reason (rpc-observation/verify-rpc-observation
                     bad-genesis observation expected))))
    (is (= :rpc-chain-context-mismatch
           (:reason (rpc-observation/verify-rpc-observation
                     bad-code observation expected))))
    (is (= :rpc-chain-context-mismatch
           (:reason (rpc-observation/verify-rpc-observation
                     rpc bad-source expected))))
    (is (= :rpc-block-ancestry-mismatch
           (:reason (rpc-observation/verify-rpc-observation
                     bad-parent observation expected))))))

(defn- case-artifacts []
  (let [payable (bp/build-bounty-payable
                 {:payable/id "payable-test"
                  :distribution-root "sha256:distribution"
                  :beneficiary "researcher/alice"
                  :amount 70
                  :lifecycle :backed})
        backing (bpb/build-bounty-payable-backing
                 {:payable-root (:payable/hash payable)
                  :payable-id (:payable/id payable)
                  :distribution-root (:payable/distribution-root payable)
                  :amount 70
                  :source-allocations {:reserve 70}
                  :context {:funding/token :token/usdc
                            :funding/source-root "sha256:source"
                            :funding/operation-root "sha256:operation"}})
        settled (bp/transition-payable-lifecycle payable :settled)
        payout-receipt (fixtures/payout-receipt payable backing)]
    {:payable-before payable
     :payable-after settled
     :backing backing
     :payout-receipt payout-receipt
     :payout-trust-policy fixtures/payout-trust-policy
     :observation (fixtures/chain-observation
                   {:observation/payable-id "payable-test"
                    :observation/sequence 17})
     :observation-trust-policy fixtures/chain-observer-trust-policy
     :expected-context {:chain-id 31337
                        :contract "0x000000000000000000000000000000000000b017"
                        :chain-instance-root (str "sha256:" (apply str (repeat 64 "b")))
                        :genesis-hash (str "0x" (apply str (repeat 64 "0")))
                        :contract-code-sha256 (str "sha256:" (apply str (repeat 64 "a")))
                        :observation-source-id "test-rpc-observer"
                        :asset :token/usdc
                        :funding-source-root "sha256:source"}}))

(deftest canonical-settlement-replays-from-payable-and-retained-evidence
  (let [inputs (case-artifacts)
        artifact (settlement/build-settlement inputs)
        result (settlement/verify-settlement (assoc inputs :settlement artifact))]
    (is (= "obligation-settlement.v1" (:settlement/schema artifact)))
    (is (= 70 (:settlement/paid artifact)))
    (is (= 17 (:settlement/sequence artifact)))
    (is (= 0 (:settlement/remaining-liability artifact)))
    (is (string? (:settlement/root artifact)))
    (is (:valid? result))
    (is (= :rpc-observation-required
           (:reason (settlement/verify-settlement
                     (assoc inputs :settlement artifact
                            :require-rpc-observation? true)))))))

(deftest settlement-rejects-tampered-observation-and-conservation
  (let [inputs (case-artifacts)
        artifact (settlement/build-settlement inputs)
        tampered-observation (update (:observation inputs)
                                     :observation/beneficiary-after inc)
        tampered-inputs (assoc inputs :observation tampered-observation)
        bad-delta (fixtures/chain-observation
                   {:observation/payable-id "payable-test"
                    :observation/reserve-after 31})]
    (is (not (:valid? (settlement/verify-settlement
                       (assoc inputs :settlement artifact
                              :observation tampered-observation)))))
    (is (= :reserve-delta-mismatch
           (:reason (settlement/verify-chain-observation
                     bad-delta fixtures/chain-observer-trust-policy))))
    (is (not (:valid? (settlement/verify-settlement
                       (assoc tampered-inputs :settlement artifact)))))))

(deftest settlement-rejects-untrusted-observer-and-evidence-substitution
  (let [inputs (case-artifacts)
        artifact (settlement/build-settlement inputs)
        untrusted (assoc inputs :observation-trust-policy {:trusted-keys []})
        substituted (assoc artifact :settlement/paid 69)]
    (is (thrown? clojure.lang.ExceptionInfo (settlement/build-settlement untrusted)))
    (is (not (:valid? (settlement/verify-settlement
                       (assoc inputs :settlement substituted)))))))

(deftest settlement-rejects-substituted-payout-receipts
  (let [inputs (case-artifacts)
        payable (:payable-before inputs)
        backing (:backing inputs)
        wrong-receipts
        [(fixtures/payout-receipt payable backing {:beneficiary "researcher/mallory"})
         (fixtures/payout-receipt payable backing {:token :token/other})
         (fixtures/payout-receipt payable backing {:payable-id "payable-other"})
         (fixtures/payout-receipt payable backing {:operation-root "sha256:other-operation"})
         (fixtures/payout-receipt payable backing {:amount 69})]]
    (doseq [receipt wrong-receipts]
      (is (thrown? clojure.lang.ExceptionInfo
                   (settlement/build-settlement (assoc inputs :payout-receipt receipt)))))))

(deftest settlement-rejects-wrong-chain-asset-recipient-and-cross-claim-bindings
  (let [inputs (case-artifacts)
        bad-observations
        [{:observation/chain-id 1}
         {:observation/contract "0xwrong"}
         {:observation/chain-instance-root (str "sha256:" (apply str (repeat 64 "c")))}
         {:observation/genesis-hash (str "0x" (apply str (repeat 64 "1")))}
         {:observation/contract-code-sha256 (str "sha256:" (apply str (repeat 64 "c")))}
         {:observation/source-id "unapproved-observer"}
         {:observation/asset :token/other}
         {:observation/beneficiary "researcher/other"}
         {:observation/operation-root "sha256:other-operation"}
         {:observation/funding-source-root "0xother-source"}
         {:observation/prf-funding-source-root "sha256:other-source"}
         {:observation/payable-id "payable-other"}]]
    (doseq [override bad-observations]
      (is (thrown? clojure.lang.ExceptionInfo
                   (settlement/build-settlement
                    (assoc inputs :observation
                           (fixtures/chain-observation
                            (merge {:observation/payable-id "payable-test"} override)))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (settlement/build-settlement
                  (assoc inputs :payout-receipt
                         (fixtures/payout-receipt (:payable-before inputs)
                                                  (:backing inputs)
                                                  {:amount 69})))))))

(deftest settlement-requires-independent-payout-and-chain-observer-keys
  (let [inputs (case-artifacts)
        same-key-inputs (assoc inputs
                               :observation
                               (fixtures/payout-signed-chain-observation
                                {:observation/payable-id "payable-test"
                                 :observation/sequence 17})
                               :observation-trust-policy
                               fixtures/chain-observer-policy-using-payout-key)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (settlement/build-settlement same-key-inputs)))))
