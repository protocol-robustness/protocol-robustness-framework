(ns resolver-sim.commands.benchmark-run-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.benchmark-run :as benchmark-run]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "benchmark-run-state-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(deftest benchmark-context-rejects-nonfresh-roots
  (doseq [[expected prepare!] [[:completed #(spit (io/file % "completion.json") "{}")]
                               [:incomplete #(spit (io/file % ".run-state") "{}")]
                               [:unrelated #(spit (io/file % "notes.txt") "keep")]]]
    (let [root (temp-dir)]
      (try
        (prepare! root)
        (try
          (benchmark-run/build-run-context "sew/example" (.getPath root) ".")
          (is false (str "Expected " (name expected) " root rejection"))
          (catch clojure.lang.ExceptionInfo error
            (is (= "Benchmark run root must be absent or empty" (.getMessage error)))
            (is (= expected (:run/root-state (ex-data error))))))
        (finally (delete-tree! root))))))
