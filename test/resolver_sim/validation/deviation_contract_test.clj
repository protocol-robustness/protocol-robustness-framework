(ns resolver-sim.validation.deviation-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.deviation-contract :as dc]))

(deftest builtin-contracts-resolve-via-default-registry
  (testing "backward-compatible single-arg lookups still resolve builtin contracts"
    (is (= :partial-fill/claimant-monotonicity
           (:contract/id (dc/get-contract :partial-fill/claimant-monotonicity))))
    (is (= :partial-fill/claimant-split-merge-sybil
           (:contract/id (dc/get-contract :partial-fill/claimant-split-merge-sybil))))
    (is (contains? (dc/deviations-in-contract :partial-fill/claimant-split-merge-sybil) :sybil))
    (is (dc/contract-generates-deviation? :partial-fill/claimant-split-merge-sybil :split))
    (is (= [:partial-fill/claimant-split-merge-sybil]
           (mapv :contract/id (dc/contracts-for-deviations [:split]))))))

(deftest explicit-registry-resolves-extension-contracts
  (let [custom-contract {:contract/id :my-proto/withholding-v1
                         :contract/version 1
                         :mechanism :yield/partial-fill
                         :actor/type :claimant
                         :reference-action :submit-claim-amount
                         :deviation-generators [:delay-request :inflate]
                         :utility-model :utility/token-linear-v1
                         :parameter-scope {:claim-count-max 5}
                         :epsilon 0
                         :exclusions [:timing-manipulation]}
        registry (dc/build-deviation-contract-registry
                  :sources [{:origin :protocol :entries [custom-contract]}])]
    (testing "extension contract resolves against the explicit registry"
      (is (= custom-contract (dc/resolve-deviation-contract registry :my-proto/withholding-v1)))
      (is (= #{:delay-request :inflate}
             (dc/deviations-in-contract registry :my-proto/withholding-v1)))
      (is (= [custom-contract] (dc/contracts-for-deviations registry [:delay-request])))
      (is (dc/contract-generates-deviation? registry :my-proto/withholding-v1 :delay-request)))
    (testing "the explicit registry does not shadow the builtin one"
      (is (nil? (dc/resolve-deviation-contract registry :partial-fill/claimant-monotonicity))
          "a protocol registry built standalone contains only its own contracts")
      (is (some? (dc/get-contract :partial-fill/claimant-monotonicity))
          "the builtin default registry still resolves framework contracts"))))

(deftest protocol-registry-can-compose-on-builtin
  (let [custom-contract {:contract/id :my-proto/withholding-v1
                         :contract/version 1
                         :mechanism :yield/partial-fill
                         :actor/type :claimant
                         :reference-action :submit-claim-amount
                         :deviation-generators [:delay-request]
                         :utility-model :utility/token-linear-v1
                         :parameter-scope {:claim-count-max 5}
                         :epsilon 0
                         :exclusions []}
        registry (dc/build-deviation-contract-registry
                  :sources [{:origin :framework :entries (vals dc/registered-contracts)}
                            {:origin :protocol :entries [custom-contract]}])]
    (is (some? (dc/get-contract registry :partial-fill/claimant-monotonicity)))
    (is (some? (dc/resolve-deviation-contract registry :my-proto/withholding-v1)))
    (is (re-matches #"[0-9a-f]{64}" (:root registry)))))

(deftest deviation-contract-registry-is-rooted-and-order-independent
  (let [reg-a (dc/build-deviation-contract-registry
               :sources [{:origin :framework :entries (vals dc/registered-contracts)}])
        reg-b (dc/build-deviation-contract-registry
               :sources [{:origin :framework
                          :entries [(dc/get-contract :partial-fill/claimant-monotonicity)
                                    (dc/get-contract :partial-fill/claimant-split-merge-sybil)]}])]
    (is (= (:root reg-a) (:root reg-b))
        "same contracts in any order produce the same registry root")))