(ns resolver-sim.build.default-attestation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [buddy.core.codecs :as codecs]
            [resolver-sim.build.default-attestation :as att]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.run.release-attestation :as release]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "default-build-attestation-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- verified-bundle [jar]
  (let [log (io/file (.getParentFile jar) "portability-smoke.log")
        _ (spit log "packaged JAR smoke passed")
        definition (att/default-build-definition "." :prf)
        attestation (att/build-attestation
                     {:definition definition
                      :jar-file jar
                      :builder-identity {:builder/id "test-builder"}
                      :smoke {:smoke/status :passed
                              :smoke/route :native-command-resolution
                              :smoke/log {:path (.getName log)
                                          :sha256 (hash-ref/sha256-ref-file (.getPath log))}}})]
    (att/build-attestation-bundle {:definition definition
                                   :attestation attestation})))

(deftest default-build-definition-binds-concrete-inputs
  (let [definition (att/default-build-definition "." :sew)]
    (is (att/valid-definition? definition))
    (is (= :sew (:build/variant definition)))
    (is (= ["clojure" "-T:build" "uberjar" ":variant" "sew"]
           (:build/command definition)))
    (is (some #(= "resources/prf/sew-release-corpus.edn" (:path %))
              (:build/inputs definition)))
    (is (true? (get-in definition [:build/packaged-jar-smoke :required?])))))

(deftest build-attestation-verifies-artifact-and-required-smoke
  (let [root (temp-dir)
        jar (io/file root "prf.jar")
        _ (spit jar "test JAR payload")
        bundle (verified-bundle jar)
        result (att/verify-bundle bundle root)]
    (is (:verified? result))
    (is (= :integrity-verified-build (:classification result)))
    (is (every? #(= :pass (:check/status %)) (:checks result)))))

(deftest build-attestation-fails-closed-for-missing-or-invalid-smoke
  (let [root (temp-dir)
        jar (io/file root "prf.jar")
        _ (spit jar "test JAR payload")
        definition (att/default-build-definition "." :prf)
        attestation (att/build-attestation
                     {:definition definition
                      :jar-file jar
                      :smoke nil})
        bundle (att/build-attestation-bundle {:definition definition
                                               :attestation attestation})
        result (att/verify-bundle bundle root)]
    (is (false? (:verified? result)))
    (is (some #(and (= :packaged-jar-smoke (:check/id %))
                    (= :fail (:check/status %)))
              (:checks result)))))

(defn- raw-public-hex [public-key]
  (let [encoded (.getEncoded public-key)
        raw (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded))]
    (codecs/bytes->hex raw)))

(deftest signed-build-bundle-verifies-against-explicit-policy
  (let [root (temp-dir)
        jar (io/file root "prf.jar")
        _ (spit jar "signed JAR payload")
        bundle (verified-bundle jar)
        pair (.generateKeyPair (java.security.KeyPairGenerator/getInstance "Ed25519"))
        policy {:schema-version "prf-release-trust-policy.v1"
                :policy-id :release/test
                :policy-version 1
                :trusted-keys [{:key-id "release-1" :status :active
                                :public-key (raw-public-hex (.getPublic pair))}]
                :requirements {:distribution {:prf {:minimum-valid-signatures 1}}}
                :canonicalization {:payload-profile "prf-release-attestation-payload.v1"}}
        payload (att/release-payload-for-bundle bundle :prf {:release/id "test"})
        signature (release/sign-payload payload (.getPrivate pair) "release-1")
        signed (att/attach-release-authorization bundle payload [signature])
        result (att/verify-bundle signed root {:distribution :prf
                                                :trust-policy policy
                                                :require-release-authorization? true})]
    (is (:verified? result))
    (is (= :release-authorized-build (:classification result)))))

(deftest signed-build-bundle-rejects-insufficient-or-tampered-authorization
  (let [root (temp-dir)
        jar (io/file root "prf.jar")
        _ (spit jar "signed JAR payload")
        bundle (verified-bundle jar)
        pair (.generateKeyPair (java.security.KeyPairGenerator/getInstance "Ed25519"))
        policy {:schema-version "prf-release-trust-policy.v1"
                :policy-id :release/test
                :policy-version 1
                :trusted-keys [{:key-id "release-1" :status :active
                                :public-key (raw-public-hex (.getPublic pair))}]
                :requirements {:distribution {:prf {:minimum-valid-signatures 2}}}
                :canonicalization {:payload-profile "prf-release-attestation-payload.v1"}}
        payload (att/release-payload-for-bundle bundle :prf {:release/id "test"})
        signature (release/sign-payload payload (.getPrivate pair) "release-1")
        signed (att/attach-release-authorization bundle payload [signature])
        result (att/verify-bundle signed root {:distribution :prf
                                                :trust-policy policy
                                                :require-release-authorization? true})]
    (is (false? (:verified? result)))
    (is (some #(and (= :release-authorization (:check/id %))
                    (= :fail (:check/status %)))
              (:checks result)))))

(deftest build-attestation-detects-jar-byte-tampering
  (let [root (temp-dir)
        jar (io/file root "prf.jar")
        _ (spit jar "original JAR payload")
        bundle (verified-bundle jar)
        _ (spit jar "tampered JAR payload")
        result (att/verify-bundle bundle root)]
    (is (false? (:verified? result)))
    (is (some #(and (= :jar-bytes (:check/id %))
                    (= :fail (:check/status %)))
              (:checks result)))))
