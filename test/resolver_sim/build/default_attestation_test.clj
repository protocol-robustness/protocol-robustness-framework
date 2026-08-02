(ns resolver-sim.build.default-attestation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [resolver-sim.build.default-attestation :as att]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "default-build-attestation-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- verified-bundle [jar]
  (let [definition (att/default-build-definition "." :prf)
        attestation (att/build-attestation
                     {:definition definition
                      :jar-file jar
                      :builder-identity {:builder/id "test-builder"}
                      :smoke {:smoke/status :passed
                              :smoke/route :native-command-resolution}})]
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
