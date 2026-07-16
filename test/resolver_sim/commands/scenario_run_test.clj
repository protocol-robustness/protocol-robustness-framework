(ns resolver-sim.commands.scenario-run-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-run :as scenario-run]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "scenario-run-state-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn- request [root]
  {:scenario/ref "scenarios/edn/fixture.edn"
   :run/root (.getPath root)
   :report-format :standard
   :sensitivity/profile :public})

(deftest build-run-context-rejects-nonfresh-roots
  (doseq [[state prepare!] [[:completed #(spit (io/file % "completion.json") "{}")]
                            [:incomplete #(spit (io/file % ".run-state") "{}")]
                            [:unrelated #(spit (io/file % "unrelated.txt") "do not reuse")]]]
    (let [root (temp-dir)]
      (try
        (prepare! root)
        (try
          (scenario-run/build-run-context (request root) {:project-root "."})
          (is false (str "Expected " (name state) " root to be rejected"))
          (catch clojure.lang.ExceptionInfo error
            (is (= "Run root must be absent or empty" (.getMessage error)))
            (is (= state (:run/root-state (ex-data error))))))
        (finally (delete-tree! root))))))
