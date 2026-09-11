(ns resolver-sim.io.input-source-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.io.input-source :as input]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "input-source-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(deftest resolves-classpath-input-without-a-filesystem-coercion
  (let [source (input/source "classpath:data/fixtures/protocol/kleros.edn")]
    (is (= :classpath (:input/type source)))
    (is (= "kleros.edn" (:input/display-name source)))
    (is (= "resource:data/fixtures/protocol/kleros.edn" (input/loadable-ref source)))
    (is (pos? (alength ^bytes (input/read-bytes source))))
    (is (re-matches #"[0-9a-f]{64}" (input/sha256 source)))))

(deftest snapshots-filesystem-input-with-content-provenance
  (let [root (temp-dir)
        source-file (io/file root "custom.edn")
        snapshot-file (io/file root "run" "inputs" "scenarios" "snapshot.edn")]
    (try
      (spit source-file "{:scenario-id \"custom\"}")
      (let [source (input/source (.getPath source-file))
            provenance (input/snapshot! source snapshot-file)]
        (is (= :file (:input/type source)))
        (is (= (.getPath snapshot-file) (:input/snapshot provenance)))
        (is (= (slurp source-file) (slurp snapshot-file)))
        (is (= (input/sha256 source) (:input/sha256 provenance)))
        (is (= (.length source-file) (:input/bytes provenance))))
      (finally (delete-tree! root)))))
