(ns resolver-sim.build.default-attestation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [buddy.core.codecs :as codecs]
            [resolver-sim.build.default-attestation :as att]
            [resolver-sim.build.default-attestation-cli :as att-cli]
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

(deftest build-attestation-rejects-inconsistent-smoke-at-construction
  (testing "a supplied smoke that does not assert a passed native-command-resolution run is rejected at build"
    (let [root (temp-dir)
          jar (io/file root "prf.jar")
          _ (spit jar "JAR payload")
          definition (att/default-build-definition "." :prf)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not assert a passed native-command-resolution"
                            (att/build-attestation
                             {:definition definition
                              :jar-file jar
                              :smoke {:smoke/status :failed
                                      :smoke/route :native-command-resolution}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not assert a passed native-command-resolution"
                            (att/build-attestation
                             {:definition definition
                              :jar-file jar
                              :smoke {:smoke/status :passed
                                      :smoke/route :bb-parity}}))))))

(deftest build-attestation-rejects-smoke-log-hash-mismatch-at-construction
  (testing "a smoke log whose sha256 does not match the captured file is rejected at build"
    (let [root (temp-dir)
          jar (io/file root "prf.jar")
          _ (spit jar "JAR payload")
          log (io/file root "portability-smoke.log")
          _ (spit log "packaged JAR smoke passed")
          definition (att/default-build-definition "." :prf)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not match the captured log file"
                            (att/build-attestation
                             {:definition definition
                              :jar-file jar
                              :smoke {:smoke/status :passed
                                      :smoke/route :native-command-resolution
                                      :smoke/log {:path (.getName log)
                                                  :sha256 "sha256:forged"}}}))))))

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

(deftest smoke-assertions-require-emitted-pass-lines
  (testing "the required PASS assertions must be present in the smoke output"
    (let [good-output (str "PASS: framework-only JAR has the unified CLI and does not advertise Sew commands\n"
                           "PASS: full Sew JAR runs bundled scenario and benchmark without CWD scatter\n"
                           "PASS: completion records commit to final registry and validation report hashes\n"
                           "PASS: built Sew JAR verifies completed scenario evidence-chain and benchmark assurance bundles\n"
                           "PASS: each JAR resolves every declared native command; external wrappers are checked by bb-task parity\n")]
      (is (true? (att-cli/smoke-output-assertions-hold? good-output))))
    (testing "an exit-0-style output missing the assertions does not pass"
      (is (false? (att-cli/smoke-output-assertions-hold?
                   "some build output but no PASS assertion lines")))
      (is (false? (att-cli/smoke-output-assertions-hold?
                   "PASS: only one assertion present\n"))))))

(deftest bundle-write-read-roundtrip-verifies
  (let [root (temp-dir)
        jar (io/file root "prf.jar")
        _ (spit jar "roundtrip JAR payload")
        bundle (verified-bundle jar)
        path (io/file root "bundle.edn")
        _ (att/write-bundle! bundle (.getPath path))
        re-read (att/read-bundle (.getPath path))
        result (att/verify-bundle re-read root)]
    (is (= (:bundle/root-hash bundle) (:bundle/root-hash re-read))
        "EDN round-trip is lossless for the bundle root")
    (is (:verified? result))
    (is (= :integrity-verified-build (:classification result)))))
