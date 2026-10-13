(ns resolver-sim.evidence.deployment-bound-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.deployment-bound :as sut]))

(def schema "0x876f2aea9124d79f41dbdd389d498430987fa33bbb34408989849c478a7c2bde")
(def operation "0x809b4fbdb632eba4671e5d5f35a37ff12aa4ae28e1ec84aece40972b1138755d")
(def prepared "0xe0c2dc5821ca3b6ec330d8af7bcb407400b19bff4d8ee08f727970494dd85eb2")
(def reserve "0xe04f4989c32dfde256d9c2ba9f4187e64910999506bb0528b3fda7c33a8f3aee")
(def token "0x367761085bf3c12e5da2df99ac6e1a824612b8fb")

(defn w [n] (str "0x" (format "%064x" n)))
(def pv (str schema (subs prepared 2) (subs reserve 2)
             (subs (w 0) 2)
             (apply str (repeat 64 "4"))
             (apply str (repeat 64 "5"))
             (subs (w 3) 2) (subs (w 70) 2) (apply str (repeat 64 "6"))))
(def proof (str "0x" (apply str (repeat 64 "a"))))
(defn sha [s] (sut/sha256-hex (.getBytes s "UTF-8")))
(def proof-sha (str "0x" (sut/sha256-hex
                          (byte-array (map (fn [[a b]]
                                             (unchecked-byte (Integer/parseInt (str a b) 16)))
                                           (partition 2 (subs proof 2)))))))
(def tx-hash "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def block-hash "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn tx [function args]
  {"function" function "arguments" args
   "transaction" {"hash" tx-hash "chainId" "0x7a69"
                  "input" (if (= "withdraw(bytes32,(uint256,bytes,address,uint256,bytes32),bytes32[],bool,bytes32[],uint8)"
                                 function) "0x7c365406" "0xf4f5d5c9")
                  "to" "0x1111111111111111111111111111111111111111"}})
(defn receipt [hash logs]
  {"transactionHash" hash "status" "0x1" "blockHash" block-hash "blockNumber" "0xc" "logs" logs})
(defn payout-log [to amount]
  {"address" token "topics" ["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
                             (str "0x" (apply str (repeat 24 "0")) "4c2f7092c2ae51d986befee378e50bd4db99c901")
                             (str "0x" (apply str (repeat 24 "0")) (subs to 2))]
   "data" (w amount) "transactionHash" tx-hash "blockHash" block-hash "blockNumber" "0xc"})
(defn group [transactions receipts] {"transactions" transactions "receipts" receipts})

(defn fixture-input []
  (let [manifest0 {"schema" "prf-evm.gate-e-entitlement-deployment.v1" "chainId" 31337
                   "localOnly" {"executionPublicValuesSchema" "0x08"}
                   "programId" "source-bound-entitlement-sp1-program.v1"
                   "programVKey" "0x00d90efa52d17e54670a4248f001e4da443a938f12c55d0d414c125ba68087b9"
                   "programElfSha256" "0x636330de8e7af3485aba50bd3ca4b5e024cc4f977a2bf0f2c005f711e5ffacf8"
                   "publicValuesSchema" schema "operationId" operation
                   "preparedOperationRoot" prepared "reserveStateCommitment" reserve
                   "beneficiaryProfile" "smart-account-v1" "configurationEpoch" 0
                   "policyRoot" "0x0909090909090909090909090909090909090909090909090909090909090909"
                   "reserveBindingRoot" "0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                   "economicReserveState" {"availabilityRevision" "0" "availableAmount" "70"
                                           "grossBalance" "80" "protectedCapital" "10" "reservedBefore" "0"}
                   "configurationRoot" "0x0101010101010101010101010101010101010101010101010101010101010101"
                   "contracts" {"token" token
                                "allocator" "0x4c2f7092c2ae51d986befee378e50bd4db99c901"
                                "reserve" "0x7a9ec1d04904907de0ed7b6839ccdd59c3716ac9"}}
        manifest-digest (sut/recompute-gate-e-manifest-digest manifest0)
        manifest (assoc manifest0 "manifestDigest" manifest-digest)
        witness {"prepared-operation" {"operation-id" operation "chain-id" "31337"
                                       "configuration-root" (get manifest "configurationRoot")
                                       "configuration-epoch" "0" "beneficiary-profile" "smart-account-v1"
                                       "policy-root" (get manifest "policyRoot") "allocator" "0x4c2f7092c2ae51d986befee378e50bd4db99c901"
                                       "reserve" "0x7a9ec1d04904907de0ed7b6839ccdd59c3716ac9"
                                       "token" token "source-program-vkey" (get manifest "programVKey")
                                       "source-program-elf-sha256" (get manifest "programElfSha256")}
                 "economic-reserve-state" {"availability-revision" "0" "available-amount" "70"
                                           "gross-balance" "80" "protected-capital" "10" "reserved-before" "0"}
                 "reserve-binding-root" (str "sha256:" (subs (get manifest "reserveBindingRoot") 2))}
        manifest-hash (sha "manifest")
        witness-hash (sha "witness")
        fixture-hash (sha "fixture")
        fixture {"proof" proof "proof_system" "groth16" "program_id" (get manifest "programId")
                 "vkey" (get manifest "programVKey") "elf_sha256" (get manifest "programElfSha256")
                 "public_values_schema" "prf/source-bound-entitlement-public-values.v1"
                 "public_values" pv "source" {"witness_sha256" (str "0x" witness-hash)}
                 "gate_e" {"manifest_digest" manifest-digest "operation_id" operation
                           "prepared_operation_root" prepared "reserve_state_commitment" reserve
                           "source_bundle_sha256" (str "0x" (sha "bundle"))
                           "proof_sha256" proof-sha}}
        provenance {"witness_sha256" (str "sha256:" witness-hash) "manifest_digest" manifest-digest
                    "bundle_sha256" (str "sha256:" (sha "bundle"))}
        report {"byte_identical_artifacts" {"manifest" {"sha256" manifest-hash}
                                            "witness" {"sha256" witness-hash}
                                            "proof_fixture" {"sha256" fixture-hash}}
                "foundry_fork_tests" {"status" "pass"}}
        admission (tx "admitStatement(bytes32,bytes32,bytes,bytes)"
                      ["0x02" "0x03" proof pv])
        admission-receipt (receipt tx-hash [])
        payouts [["0x1111111111111111111111111111111111111111" 35]
                 ["0x2222222222222222222222222222222222222222" 21]
                 ["0x3333333333333333333333333333333333333333" 14]]
        withdraws (mapv (fn [i _]
                          (assoc (tx "withdraw(bytes32,(uint256,bytes,address,uint256,bytes32),bytes32[],bool,bytes32[],uint8)" [])
                                 "hash" (str "0x" (format "%064x" (inc i)))))
                        (range) payouts)
        withdrawal-receipts (mapv (fn [i [to amount]]
                                    (let [hash (str "0x" (format "%064x" (inc i)))]
                                      (receipt hash [(assoc (payout-log to amount) "transactionHash" hash)])))
                                  (range) payouts)]
    {:report report :manifest manifest :witness witness :fixture fixture :provenance provenance
     :fork-test-log "Ran 4 tests for GateE test\n4 tests passed, 0 failed, 0 skipped\n"
     :final-state (str "reserve_balance=10\nallocator_balance=0\nbeneficiary_1111_balance=35\n"
                       "beneficiary_2222_balance=21\nbeneficiary_3333_balance=14\nfinal_block=15\n")
     :broadcasts {:deploy (group [] []) :run (group [admission] [admission-receipt])
                  :activate (group [] []) :withdrawal (group withdraws withdrawal-receipts)}
     :artifact-hashes {:manifest manifest-hash :witness witness-hash :fixture fixture-hash}}))

(deftest retained-records-reconstruct-without-trusting-verification-labels
  (let [input (assoc-in (fixture-input) [:report "verification"] {"status" "verified"})
        result (sut/validate-records input)]
    (is (:valid? result))
    (is (true? (get-in result [:checks :manifest-digest-recomputed])))
    (is (true? (get-in result [:checks :admission-call-carried-exact-proof-and-public-values])))
    (is (= 3 (get-in result [:summary :successful-payout-count])))))

(deftest changed-artifact-manifest-and-operation-substitution-reject
  (let [changed-hash (update-in (fixture-input) [:artifact-hashes :fixture] (constantly "changed"))
        substituted-manifest (assoc-in (fixture-input) [:manifest "configurationRoot"] "0xff")
        substituted-operation (assoc-in (fixture-input) [:fixture "gate_e" "operation_id"] "0xdead")
        substituted-proof (assoc-in (fixture-input) [:fixture "proof"] (str "0x" (apply str (repeat 64 "b"))))
        forged-label (-> (fixture-input)
                         (assoc-in [:fixture "gate_e" "operation_id"] "0xdead")
                         (assoc-in [:report "verification" "status"] "verified"))
        substituted-reserve (assoc-in (fixture-input) [:witness "economic-reserve-state" "available-amount"] "71")]
    (is (false? (:valid? (sut/validate-records changed-hash))))
    (is (false? (:valid? (sut/validate-records substituted-manifest))))
    (is (false? (:valid? (sut/validate-records substituted-operation))))
    (is (false? (:valid? (sut/validate-records substituted-proof))))
    (is (false? (:valid? (sut/validate-records forged-label))))
    (is (false? (:valid? (sut/validate-records substituted-reserve))))))

(deftest receipt-chain-and-block-context-substitution-reject
  (let [bad-chain (assoc-in (fixture-input) [:broadcasts :run "transactions" 0 "transaction" "chainId"] "0x1")
        bad-block (assoc-in (fixture-input) [:broadcasts :withdrawal "receipts" 0 "blockHash"] "0xcc")]
    (is (false? (:valid? (sut/validate-records bad-chain))))
    (is (false? (:valid? (sut/validate-records bad-block))))))

(deftest payout-conflicts-and-incomplete-evidence-are-not-success
  (let [bad-payout (assoc-in (fixture-input) [:broadcasts :withdrawal "receipts" 0 "logs" 0 "data"] (w 36))
        missing-admission (assoc-in (fixture-input) [:broadcasts :run "transactions"] [])
        forged-only (assoc (fixture-input) :artifact-hashes {:manifest "x" :witness "x" :fixture "x"})
        mislabelled (assoc-in (fixture-input)
                              [:broadcasts :withdrawal "transactions" 0 "transaction" "input"]
                              "0xf4f5d5c9")]
    (is (false? (:valid? (sut/validate-records bad-payout))))
    (is (false? (:valid? (sut/validate-records missing-admission))))
    (is (false? (:valid? (sut/validate-records forged-only))))
    (is (false? (:valid? (sut/validate-records mislabelled)))
        "a function label cannot turn a configuration call into a withdrawal")))
