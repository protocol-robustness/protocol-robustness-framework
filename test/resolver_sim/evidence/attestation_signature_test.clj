(ns resolver-sim.evidence.attestation-signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [buddy.core.codecs :as codecs]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-signature :as sig])
  (:import [java.security KeyPairGenerator Signature]))

(defn- attestation []
  (att/build-attestation {:type :ci-runner :id :ci-validation}
                         {:type :evidence-node :hash "sha256:abc"}
                         :verified
                         {:signed-at "2026-01-01T00:00:00Z"}))

(defn- envelope [a]
  {:signature/version "attestation-signature.v1"
   :algorithm :ed25519
   :key-id "test-key-2026"
   :signature-encoding :hex
   :signature-bytes (apply str (repeat 128 "a"))
   :payload-hash (sig/signing-payload-hash a)})

(deftest v1-envelope-validates-before-crypto
  (let [a (attestation)
        result (sig/validate-envelope-for-attestation
                (assoc a :attestation/signature (envelope a)))]
    (is (:valid? result))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:payload-hash result)))))

(deftest payload-tampering-invalidates-envelope
  (let [a (attestation)
        signed (assoc a :attestation/signature (envelope a))
        tampered (assoc signed :attestation/signed-at "2026-01-02T00:00:00Z")
        result (sig/validate-envelope-for-attestation tampered)]
    (is (false? (:valid? result)))
    (is (= [:payload-hash-mismatch] (:errors result)))))

(deftest malformed-v1-binary-fields-are-rejected-before-crypto
  (let [a (attestation)
        base (envelope a)]
    (doseq [[field value expected] [[:signature-bytes "ABC" :invalid-signature-bytes]
                                    [:payload-hash "sha256:ABC" :invalid-payload-hash]
                                    [:algorithm :rsa :unsupported-algorithm]
                                    [:signature-encoding :base64 :unsupported-signature-encoding]]]
      (testing (name field)
        (let [result (sig/validate-signature-envelope (assoc base field value))]
          (is (false? (:valid? result)))
          (is (some #{expected} (:errors result))))))))

(defn- signed-fixture []
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        a (attestation)
        payload-hash (sig/signing-payload-hash a)
        signer (Signature/getInstance "Ed25519")
        _ (.initSign signer (.getPrivate pair))
        _ (.update signer (.getBytes payload-hash "UTF-8"))
        envelope {:signature/version "attestation-signature.v1" :algorithm :ed25519
                  :key-id "test-key" :signature-encoding :hex
                  :signature-bytes (codecs/bytes->hex (.sign signer))
                  :payload-hash payload-hash}
        public-raw (let [encoded (.getEncoded (.getPublic pair))]
                     (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded)))
        registry {:attestors [{:id :ci-validation :status :active
                               :keys [{:key-id "test-key" :algorithm :ed25519
                                       :public-key-encoding :hex
                                       :public-key (codecs/bytes->hex public-raw)
                                       :status :active :valid-from "2025-01-01T00:00:00Z"}]}]}]
    [(assoc a :attestation/signature envelope) registry]))

(deftest verifies-v1-ed25519-signature-against-trusted-registry
  (let [[a registry] (signed-fixture)]
    (is (:valid? (sig/verify-attestation-signature a registry)))
    (is (= :malformed-signature
           (:reason (sig/verify-attestation-signature
                     (assoc a :attestation/claim-result :rejected) registry))))))

(deftest invalid-ed25519-signature-is-rejected
  (let [[a registry] (signed-fixture)
        tampered (assoc-in a [:attestation/signature :signature-bytes]
                           (apply str (repeat 128 "0")))
        result (sig/verify-attestation-signature tampered registry)]
    (is (false? (:valid? result)))
    (is (= :invalid-signature (:reason result)))))

(deftest legacy-envelope-is-not-v1
  (let [result (sig/validate-signature-envelope
                {:algorithm :ed25519 :public-key-id "legacy" :signature-bytes "deadbeef"})]
    (is (false? (:valid? result)))
    (is (= [:legacy-envelope] (:errors result)))))
