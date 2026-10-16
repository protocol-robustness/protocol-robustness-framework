(ns resolver-sim.hash.evm-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.hash.evm :as evm]))

(deftest authority-transition-execution-commitment-solidity-fixture
  (let [mutations [{:kind 0
                    :decision-root (str "0x" (format "%064x" 1))
                    :target-mode 0
                    :target-root (str "0x" (format "%064x" 2))
                    :parent-configuration-root (str "0x" (format "%064x" 3))
                    :new-configuration-root (str "0x" (format "%064x" 4))
                    :parent-verifier-registry-root (str "0x" (format "%064x" 5))
                    :new-verifier-registry-root (str "0x" (format "%064x" 6))
                    :epoch 7
                    :proof-mode 0}]
        mutation-root (evm/authority-mutation-root mutations)]
    (is (= "0xa76b0955b74068a7378c2bfedf83a3b1f2ea1709b2a7df3339dc32b64c8d8dba"
           (evm/hex-ref mutation-root)))
    (is (= "0x5ed3dd088226575602e112bf4cd71cce56bdaf266170c5f4bec1b478c38e1238"
           (evm/hex-ref
            (evm/authority-transition-execution-commitment
             {:chain-id 31337
              :adapter-address "0x1111111111111111111111111111111111111111"
              :instance-root (str "0x" (format "%064x" 10))
              :previous-root (str "0x" (format "%064x" 11))
              :successor-root (str "0x" (format "%064x" 12))
              :policy-root (str "0x" (format "%064x" 13))
              :previous-revision 0
              :successor-revision 1
              :mutation-root (evm/hex-ref mutation-root)
              :decision-preimages-root (str "0x" (format "%064x" 14))}))))))

(deftest eip191-secp256k1-address-recovery-fixture
  (let [fixture (json/read-str (slurp "etc/conformance/fixtures/authority-transition-eip191.v1.json"))]
    (is (= (get fixture "validatorAddress")
           (evm/recover-ethereum-personal-sign-address
            (get fixture "commitment") (get fixture "signature"))))
    (is (thrown? Exception
                 (evm/recover-ethereum-personal-sign-address
                  (str "0x" (apply str (repeat 32 "11"))) "0x0102")))
    (is (thrown? Exception
                 (evm/recover-ethereum-personal-sign-address
                  (get fixture "commitment")
                  (str "0x" (apply str (repeat 64 "f")) (apply str (repeat 64 "f")) "1b"))))))
