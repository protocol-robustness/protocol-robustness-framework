(ns resolver-sim.deployment.transition-validator-key-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [scripts.deployment.transition-validator-key-check :as sut]))

(def policy-address "0xc78884593859e84524e41ce94cce38fbcb58c5f8")
(def expected-id "11111111-1111-4111-8111-111111111111")

(defn- identity-result [overrides]
  (sut/evaluate-binding
   (merge {:policy-key-id "bounded-local-transition-validator-v1"
           :policy-address policy-address
           :keystore-id expected-id
           :expected-keystore-id expected-id
           :derived-address policy-address}
          overrides)))

(deftest matching-keystore-id-and-policy-address-pass
  (let [result (identity-result {})]
    (is (= "PASS" (:status result)))
    (is (= "bounded-local-transition-validator-v1" (:policyKeyId result)))
    (is (= expected-id (:keystoreId result)))
    (is (false? (:privateKeyPrinted result)))
    (is (false? (:signatureCreated result)))))

(deftest mismatched-policy-address-fails
  (let [result (identity-result {:derived-address "0x0000000000000000000000000000000000000001"})]
    (is (= "FAIL" (:status result)))
    (is (false? (get-in result [:checks :derived-address-matches-policy])))))

(deftest mismatched-keystore-uuid-fails-even-if-address-matches
  (let [result (identity-result {:keystore-id "22222222-2222-4222-8222-222222222222"})]
    (is (= "FAIL" (:status result)))
    (is (false? (get-in result [:checks :keystore-id-matches-expected])))))

(deftest claimed-keystore-address-must-match-derived-address
  (let [result (identity-result {:keystore-address "0000000000000000000000000000000000000001"})]
    (is (= "FAIL" (:status result)))
    (is (false? (get-in result [:checks :keystore-address-matches-derived])))))

(deftest malformed-uuid-or-address-fails-closed
  (doseq [overrides [{:keystore-id "not-a-uuid"}
                     {:expected-keystore-id "not-a-uuid"}
                     {:policy-address "0x1234"}
                     {:derived-address "not-an-address"}]]
    (is (= "FAIL" (:status (identity-result overrides))))))

(deftest extracts-policy-from-candidate-package-wrappers
  (testing "local-authority-policy package wrapper"
    (let [policy {:authority/transition-validator-key-id "validator-id"
                  :authority/transition-validator-address policy-address}]
      (is (= policy (let [value {:local-authority-policy
                                 {:package/schema "prf-local-authority-policy-package.v3"
                                  :local-authority-policy policy}}]
                      (#'sut/transition-policy value)))))))
