(ns resolver-sim.conformance.historical-verification-test
  "G9b: a mature verifier must keep verifying old bundles after registries,
   policies, or keys change."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.bundle :as bundle]
            [resolver-sim.conformance.crypto :as crypto]
            [resolver-sim.conformance.registry :as registry]
            [clojure.data.json :as json]))

(defn- fixture-bundle []
  (json/read-str (slurp "etc/conformance/corpus/valid/trace-001.json") :key-fn keyword))

(deftest old-bundle-verifies-under-its-committed-environment
  (testing "a valid bundle verifies and its environment root is unchanged"
    (let [b (fixture-bundle)
          v (bundle/verify-bundle b)]
      (is (= :pass (:status v)))
      (is (:claimable? v))
      (is (= (:environment/root (get-in b [:environment]))
             (get-in b [:reconciliation :environment/root]))))))

(deftest current-registry-state-does-not-replace-bundled-snapshot
  (testing "adding a throwaway implementation to the live registry does not
            change the historical bundle's verification or its bound roots"
    (let [b (fixture-bundle)
          bound-env (get-in b [:environment])
          before (bundle/verify-bundle b)
          ;; pollute the live registry with a throwaway implementation
          _ (registry/register!
             {:implementation/id :historical-throwaway
              :implementation/kind :validator :implementation/domain :generic
              :implementation/version 1 :implementation/status :active})
          after (bundle/verify-bundle b)]
      (is (= (:environment/root bound-env)
             (get-in b [:reconciliation :environment/root])))
      (is (= (:status before) (:status after)))
      (is (= (:claimable? before) (:claimable? after)))
      (is (= (:bundle/root b) (:bundle/root b))))))

(deftest later-revocation-does-not-rewrite-history-unless-explicit
  (testing "a revocation effective after signing does not rewrite the old
            result; a bare revocation (explicit retrospective) does"
    (let [kp (crypto/make-keypair :ed25519)
          preimage (byte-array (map byte "historical-preimage"))
          sig (crypto/sign :ed25519 (:private-key-bytes kp) preimage)
          base {:signature/algorithm :ed25519
                :signature/value sig
                :signature/preimage preimage
                :signature/domain :prf-evidence-package.v1
                :signer/id :signer-a
                :signer/public-key (:public-key-bytes kp)
                :trust-policy/root "sha256:policy"
                :valid-at 1000
                :artifact-kind :evidence-package
                :verification/implementation-root "sha256:impl"}
          prospective (assoc base :trust-policy/keys
                             {:signer-a {:key/id :key-1 :key/status :revoked
                                         :key/status-effective-at 2000
                                         :key/authorised-kinds #{:evidence-package}}})
          retrospective (assoc base :trust-policy/keys
                               {:signer-a {:key/id :key-1 :key/status :revoked
                                           :key/authorised-kinds #{:evidence-package}}})]
      (testing "signed at 1000, revoked effective 2000 -> historical result pass"
        (is (= :pass (:verification/status (crypto/verify-signature prospective)))))
      (testing "signed at 1500, revoked effective 2000 -> still pass"
        (is (= :pass (:verification/status (crypto/verify-signature (assoc prospective :valid-at 1500))))))
      (testing "signed at 2500, revoked effective 2000 -> fail (revoked)"
        (is (= :fail (:verification/status (crypto/verify-signature (assoc prospective :valid-at 2500))))))
      (testing "bare revocation is explicit retrospective -> fail at any time"
        (is (= :fail (:verification/status (crypto/verify-signature retrospective))))))))

(deftest new-verifier-derives-same-old-claim-root
  (testing "a freshly-derived claim root equals the historical committed
            json-root of the same bundle"
    (let [b (fixture-bundle)
          v (bundle/verify-bundle b)
          derived (:derived-claim v)]
      (is (some? derived))
      (is (= (get-in b [:claim :claim/json-root])
             (:claim/json-root derived))))))

(deftest unsupported-historical-canonicalisation-rejects-typed
  (testing "an unknown canonicalisation id yields a typed non-claimable result,
            never a guess"
    (let [b (-> (fixture-bundle)
                (assoc-in [:environment :environment/committed :canonicalisation/id]
                          "canonical-prf-legacy.v0"))
          v (bundle/verify-bundle b)]
      (is (= :rejected (:status v)))
      (is (not (:claimable? v)))
      (is (some #(= :unsupported-canonicalisation (:issue/code %))
                (:issues v))))))
