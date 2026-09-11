(ns resolver-sim.commands.scenario-safety-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-safety :as safety]))

(defn- delete-tree! [path]
  (when (.exists (io/file path)) (doseq [file (reverse (file-seq (io/file path)))] (io/delete-file file true))))

(deftest root-lock-is-exclusive-and-released
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "scenario-lock-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [lock (safety/acquire-lock! root)]
        (is (.exists lock))
        (is (thrown? clojure.lang.ExceptionInfo (safety/acquire-lock! root)))
        (safety/release-lock! lock)
        (is (not (.exists lock)))
        (is (.exists (safety/acquire-lock! root))))
      (finally (delete-tree! root)))))

(deftest public-sensitivity-scan-rejects-secret-like-content
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "scenario-scan-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit (io/file root "world.json") "private_key=not-public")
      (is (thrown? clojure.lang.ExceptionInfo (safety/scan-public-bundle! root)))
      (finally (delete-tree! root)))))

(deftest internal-sensitivity-scan-retains-sanitized-findings
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "scenario-internal-scan-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit (io/file root "world.json") "api_key=must-not-appear-in-report")
      (let [result (safety/scan-internal-bundle! root)]
        (is (= :internal (:profile result)))
        (is (= :internal-retention (:decision result)))
        (is (= 1 (count (:findings result))))
        (is (not (re-find #"must-not-appear-in-report" (pr-str result)))))
      (finally (delete-tree! root)))))

(deftest scan-content-findings-detects-legacy-write-back-key
  (let [body "{:current-amount-write-back-verified? true :allocation/positive-amount-applied? true}"
        findings (safety/scan-content-findings body)]
    (is (some #(= :current-amount-write-back-verified? (:rule/id %)) findings)
        "scanner must detect the legacy v1 overclaiming key")
    (is (every? #(not= "current-amount-write-back-verified?" (:match/value-commitment %)) findings)
        "matched content must not leak into the value-commitment field")))

(deftest scan-content-findings-detects-legacy-key-in-json
  (let [body "{\"current-amount-write-back-verified?\": true}"
        findings (safety/scan-content-findings body)]
    (is (some #(= :current-amount-write-back-verified? (:rule/id %)) findings)
        "scanner matches the legacy key regardless of serialization (JSON vs EDN)")))

(deftest public-scan-blocks-legacy-write-back-key
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "scenario-public-wb-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit (io/file root "evidence.edn") "{:current-amount-write-back-verified? true}")
      (is (thrown? clojure.lang.ExceptionInfo (safety/scan-public-bundle! root))
          "public bundle containing the legacy key must be blocked")
      (finally (delete-tree! root)))))

(deftest internal-scan-retains-legacy-write-back-key
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "scenario-internal-wb-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit (io/file root "evidence.edn") "{:current-amount-write-back-verified? true}")
      (let [result (safety/scan-internal-bundle! root)
            wb-finding (some #(when (= :current-amount-write-back-verified? (:rule/id %)) %)
                             (:findings result))]
        (is (= :internal-retention (:decision result))
            "internal bundle with legacy key is retained, not published")
        (is (some? wb-finding)
            "a structural finding is produced for the legacy key")
        (is (= "v1" (:rule/version wb-finding))
            "the legacy key rule version is v1"))
      (finally (delete-tree! root)))))
