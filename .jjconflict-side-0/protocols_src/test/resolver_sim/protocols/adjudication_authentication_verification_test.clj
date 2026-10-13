(ns resolver-sim.protocols.adjudication-authentication-verification-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.adjudication-authentication-verification :as authentication]))

(defn- root [x]
  (str "sha256:" (hc/domain-hash :evidence-record {:x x})))

(defn- input []
  {:authentication-verification/ruling-root (root :ruling)
   :authentication-verification/authentication-root (root :authentication)
   :authentication-verification/adjudication-instance-root (root :instance)
   :authentication-verification/issuer "0xAdjudicator"
   :authentication-verification/method :ethereum-signature
   :authentication-verification/credential-material-root (root :credential)
   :authentication-verification/external-subject "0xAdjudicator"
   :authentication-verification/provider-evidence-root (root :provider-evidence)
   :authentication-verification/observation-root (root :observation)
   :authentication-verification/observed-at 1000
   :authentication-verification/result :authenticated})

(deftest verification-is-closed-rooted-and-substitution-sensitive
  (let [artifact (authentication/build-authentication-verification (input))]
    (is (authentication/valid-authentication-verification? artifact))
    (doseq [field [:authentication-verification/ruling-root
                   :authentication-verification/adjudication-instance-root
                   :authentication-verification/credential-material-root
                   :authentication-verification/external-subject
                   :authentication-verification/provider-evidence-root
                   :authentication-verification/method]]
      (is (not (authentication/valid-authentication-verification?
                (assoc artifact field (if (= field :authentication-verification/method)
                                        :multisig
                                        "mutated"))))))
    (is (not (authentication/valid-authentication-verification?
              (assoc artifact :authentication-verification/external-subject "0xOther"))))
    (is (not (authentication/valid-authentication-verification?
              (assoc artifact :authentication-verification/freeform "uncommitted"))))))

(deftest verification-root-cannot-be-substituted-independently
  (let [artifact (authentication/build-authentication-verification (input))
        corrupted (assoc artifact :authentication-verification/root (root :other-root))]
    (is (authentication/valid-authentication-verification? artifact))
    (is (not (authentication/valid-authentication-verification? corrupted)))
    (is (contains? (set (:errors (authentication/validate-authentication-verification corrupted)))
                   :authentication-verification-root-mismatch))))
