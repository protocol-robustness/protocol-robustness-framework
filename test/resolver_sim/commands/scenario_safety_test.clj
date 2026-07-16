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
