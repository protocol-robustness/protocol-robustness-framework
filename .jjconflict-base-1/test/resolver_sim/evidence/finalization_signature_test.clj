(ns resolver-sim.evidence.finalization-signature-test
  (:require [buddy.core.codecs :as codecs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.finalization-signature :as signature]
            [resolver-sim.evidence.finalization-signing :as signing])
  (:import [java.security KeyPairGenerator]))

(def payload-hash "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(deftest detached-finalization-envelope-authenticates-type-and-payload-digest
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        envelope (signature/build-envelope payload-hash "test-finalizer" (.getPrivate pair))]
    (is (:valid? (signature/validate-envelope envelope)))
    (is (:valid? (signature/verify-envelope envelope (.getPublic pair))))
    (is (= :invalid-signature
           (:reason (signature/verify-envelope
                     (assoc-in envelope [:payload :payload-hash]
                               "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                     (.getPublic pair)))))))

(deftest persisted-finalization-signing-requires-a-trusted-distinct-key
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        encoded (.getEncoded (.getPublic pair))
        public-hex (codecs/bytes->hex (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded)))
        dir (str (.toFile (java.nio.file.Files/createTempDirectory
                           "finalization-signature"
                           (make-array java.nio.file.attribute.FileAttribute 0))))
        finalization-file (io/file dir "evidence-finalization.json")
        _ (spit finalization-file "{\"schema-version\":\"evidence-finalization.v2\"}")
        signer (signing/->FileSigner "release-2026-01" (.getPrivate pair))
        trusted {:keys [{:key-id "release-2026-01"
                         :public-key public-hex
                         :status :active
                         :roles #{:release-authority}}]}
        result (signing/sign-persisted-finalization!
                {:finalization-path finalization-file
                 :signatures-dir (io/file dir "signatures")
                 :signer signer
                 :trusted-registry trusted
                 :policy {:signer-role :release-authority
                          :threshold {:minimum 1}}})
        report (signing/write-verification-report!
                (io/file dir "reports" "signature-verification.json")
                (:payload-hash result) "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                (:verification result))]
    (is (.isFile (io/file (:path result))))
    (is (.isFile (io/file (:path report))))
    (is (:valid? (:verification result)))
    (is (= 1 (get-in result [:verification :trusted-valid-count])))
    (is (false? (:valid? (signing/evaluate-envelopes
                          [(:envelope result)]
                          {:keys []}
                          {:signer-role :release-authority :threshold {:minimum 1}}))))
    (is (false? (:valid? (signing/evaluate-envelopes
                          [(:envelope result)]
                          (assoc-in trusted [:keys 0 :roles] #{:operator})
                          {:signer-role :release-authority :threshold {:minimum 1}}))))
    (is (false? (:valid? (signing/evaluate-envelopes
                          [(:envelope result) (:envelope result)]
                          trusted
                          {:signer-role :release-authority :threshold {:minimum 2}}))))))
