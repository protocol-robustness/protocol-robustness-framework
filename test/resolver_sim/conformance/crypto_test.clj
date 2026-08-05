(ns resolver-sim.conformance.crypto-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.conformance.crypto :as crypto]))

(defn- keypair [] (crypto/make-keypair :ed25519))
(defn- preimage [] (byte-array (map byte "canonical-preimage")))

(defn- base-input []
  (let [kp (keypair)
        pre (preimage)
        sig (crypto/sign :ed25519 (:private-key-bytes kp) pre)]
    {:subject/id "pkg-1" :subject/root "sha256:pkg"
     :signature/algorithm :ed25519 :signature/value sig :signature/preimage pre
     :signature/domain :prf-evidence-package.v1
     :signer/id :signer-a :signer/public-key (:public-key-bytes kp)
     :trust-policy/root "sha256:policy"
     :trust-policy/keys {:signer-a {:key/id :key-1 :key/status :active
                                    :key/authorised-kinds #{:evidence-package}}}
     :valid-at 1000 :artifact-kind :evidence-package
     :verification/implementation-root "sha256:impl"}))

(deftest valid-signature-verifies
  (let [r (crypto/verify-signature (base-input))]
    (is (= :pass (:verification/status r)))
    (is (true? (:cryptographically-valid? r)))
    (is (true? (:authorised? r)))
    (is (= "conformance.signature-verification/v1" (:signature-verification/schema-version r)))
    (is (string? (:receipt/root r)))
    (is (crypto/verification-passed? r))))

(deftest wrong-preimage-fails-closed
  (let [r (crypto/verify-signature
           (assoc (base-input) :signature/preimage (byte-array (map byte "tampered"))))]
    (is (= :fail (:verification/status r)))
    (is (false? (:cryptographically-valid? r)))))

(deftest unauthorised-key-fails-closed
  (let [r (crypto/verify-signature (assoc (base-input) :artifact-kind :research-conclusion))]
    (is (= :fail (:verification/status r)))
    (is (true? (:cryptographically-valid? r))) ; crypto valid but unauthorised
    (is (false? (:authorised? r)))))

(deftest revoked-key-fails-closed
  (let [r (crypto/verify-signature
           (assoc-in (base-input) [:trust-policy/keys :signer-a :key/status] :revoked))]
    (is (= :fail (:verification/status r)))
    (is (= :revoked (:key-status r)))))

(deftest expired-and-not-yet-valid-keys-fail
  (let [expired (assoc-in (base-input) [:trust-policy/keys :signer-a :key/valid-until] 500)
        early (assoc-in (base-input) [:trust-policy/keys :signer-a :key/valid-from] 2000)]
    (is (= :fail (:verification/status (crypto/verify-signature expired))))
    (is (= :fail (:verification/status (crypto/verify-signature early))))))

(deftest unknown-algorithm-fails-closed
  (is (= :fail (:verification/status
                (crypto/verify-signature (assoc (base-input) :signature/algorithm :rsa)))))
  (is (not (crypto/known-algorithm? :rsa)))
  (is (crypto/known-algorithm? :ed25519)))

(deftest unresolved-signer-fails-closed
  (is (= :fail (:verification/status
                (crypto/verify-signature (assoc (base-input) :signer/id :signer-unknown))))))

(deftest domain-mismatch-fails-closed
  (is (= :fail (:verification/status
                (crypto/verify-signature (assoc (base-input) :signature/domain :other-domain))))))
