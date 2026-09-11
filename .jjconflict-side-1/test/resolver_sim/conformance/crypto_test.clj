(ns resolver-sim.conformance.crypto-test
  (:require [clojure.test :refer [deftest is testing]]
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
     :trust-policy/keys {:signer-a {:key/id :key-1 :key/public-key (:public-key-bytes kp)
                                    :key/status :active
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

;; ---------------------------------------------------------------------------
;; AUTH-K1: committed-key verification
;; ---------------------------------------------------------------------------

(defn- attacker-keypair [] (crypto/make-keypair :ed25519))

(deftest substituted-key-with-valid-kprime-signature-fails
  (testing "correct signer id + substituted K' + valid K' signature"
    (let [kp' (attacker-keypair)
          pre (preimage)
          sig (crypto/sign :ed25519 (:private-key-bytes kp') pre)
          r (crypto/verify-signature
             (assoc (base-input)
                    :signature/value sig
                    :signer/public-key (:public-key-bytes kp')))]
      (is (= :fail (:verification/status r)))
      (is (false? (:key-binding/presented-matches? r))
          "presented K' must not equal the committed key"))))

(deftest substituted-key-with-valid-committed-signature-fails
  (testing "correct signer id + substituted K' + valid K signature (crypto valid, binding fails)"
    (let [kp  (keypair)          ; committed key K for signer-a
          kp' (attacker-keypair) ; attacker-presented K'
          pre (preimage)
          sig (crypto/sign :ed25519 (:private-key-bytes kp) pre) ; signed by committed K
          m   (assoc (base-input)
                     :signature/value sig
                     :signer/public-key (:public-key-bytes kp'))
          r   (crypto/verify-signature
               (assoc-in m [:trust-policy/keys :signer-a :key/public-key]
                         (:public-key-bytes kp)))]
      (is (true? (:cryptographically-valid? r))
          "signature verifies against the committed key K")
      (is (false? (:key-binding/presented-matches? r))
          "presented K' != committed K ⇒ fail")
      (is (= :fail (:verification/status r))))))

(deftest missing-committed-public-key-fails-closed
  (testing "committed :key/public-key absent from the trust-policy entry"
    (let [r (crypto/verify-signature
             (update (base-input) :trust-policy/keys
                     (fn [ks] (update ks :signer-a dissoc :key/public-key))))]
      (is (= :fail (:verification/status r)))
      (is (false? (:key-binding/committed? r)))
      (is (false? (:cryptographically-valid? r))))))

(deftest omitted-presented-key-verifies-from-committed-key
  (testing "omitting redundant :signer/public-key still verifies from the committed key"
    (let [r (crypto/verify-signature (dissoc (base-input) :signer/public-key))]
      (is (= :pass (:verification/status r)))
      (is (true? (:key-binding/committed? r)))
      (is (true? (:key-binding/presented-matches? r))))))
