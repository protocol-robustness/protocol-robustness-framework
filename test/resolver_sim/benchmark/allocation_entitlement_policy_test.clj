(ns resolver-sim.benchmark.allocation-entitlement-policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.allocation-entitlement-policy :as policy]
            [resolver-sim.benchmark.authority-semantics-policy :as semantics-policy]
            [resolver-sim.benchmark.governed-authority-semantics :as semantics]
            [resolver-sim.genesis :as genesis]))

(defn- hash-ref [ch]
  (str "sha256:" (apply str (take 64 (repeat ch)))))

(def entitlement-policy
  (policy/build-policy
   {:allocation-policy/root (hash-ref "a")
    :asset/root (hash-ref "b")
    :protocol-instance/root (hash-ref "c")
    :custody-subject/root (hash-ref "d")
    :custody-scope/root (hash-ref "e")
    :allocation-entitlement/profile :allocation-entitlement/fixed-domain-v1}))

(deftest allocation-entitlement-policy-is-closed-and-self-rooted
  (is (:valid? (policy/validate-policy entitlement-policy)))
  (is (false? (:valid? (policy/validate-policy (assoc entitlement-policy :unknown true)))))
  (is (false? (:valid? (policy/validate-policy
                        (assoc entitlement-policy
                               :allocation-entitlement-policy/root (hash-ref "f"))))))
  (testing "native realization and allocation population are outside this authority"
    (is (false? (:valid? (policy/validate-policy
                          (assoc entitlement-policy :realized-allocation/root (hash-ref "g"))))))))

(deftest chain-configuration-v3-is-closed-and-preserves-earlier-versions
  (let [authority-policy
        (semantics-policy/build-policy
         {:authority-semantics/root
          (:governed-authority-semantics/root semantics/default-semantics)})
        configuration
        (assoc genesis/chain-configuration-fixture
               :configuration/schema genesis/chain-configuration-v3-schema
               :authority-semantics-policy/root
               (:authority-semantics-policy/root authority-policy)
               :allocation-entitlement-policy/root
               (:allocation-entitlement-policy/root entitlement-policy))]
    (is (:valid? (genesis/validate-chain-configuration-v3 configuration)))
    (is (string? (genesis/chain-configuration-root configuration)))
    (is (false? (:valid? (genesis/validate-chain-configuration-v3
                          (assoc configuration :unknown/root (hash-ref "h"))))))
    (is (:valid? (genesis/validate-chain-configuration genesis/chain-configuration-fixture)))))
