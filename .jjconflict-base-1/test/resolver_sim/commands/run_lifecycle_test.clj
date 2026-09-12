(ns resolver-sim.commands.run-lifecycle-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.io.input-source])
  (:import [java.nio.file Files Paths]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "run-lifecycle-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(deftest root-state-classifies-fresh-and-existing-roots
  (let [root (temp-dir)]
    (try
      (is (= :empty (lifecycle/root-state (.toPath root))))
      (spit (io/file root "note.txt") "unrelated")
      (is (= :unrelated (lifecycle/root-state (.toPath root))))
      (io/delete-file (io/file root "note.txt"))
      (spit (io/file root ".run-state") "{}")
      (is (= :incomplete (lifecycle/root-state (.toPath root))))
      (spit (io/file root "completion.json") "{}")
      (is (= :completed (lifecycle/root-state (.toPath root))))
      (finally (delete-tree! root)))))

(deftest fresh-root-rejection-includes-machine-readable-state
  (let [root (temp-dir)]
    (try
      (spit (io/file root "completion.json") "{}")
      (try
        (lifecycle/require-fresh-root! (.toPath root) :benchmark)
        (is false "Expected completed root rejection")
        (catch clojure.lang.ExceptionInfo error
          (is (= "Benchmark run root must be absent or empty" (.getMessage error)))
          (is (= :benchmark (:run/type (ex-data error))))
          (is (= :completed (:run/root-state (ex-data error))))))
      (finally (delete-tree! root)))))

(deftest snapshot-input-is-contained-and-records-relative-provenance
  (let [root (temp-dir)
        source-file (java.io.File/createTempFile "run-lifecycle-input-" ".edn")]
    (try
      (spit source-file "{:scenario/id :fixture}")
      (let [source (resolver-sim.io.input-source/source (.getPath source-file))
            target (io/file root "inputs/fixture.edn")
            provenance (lifecycle/snapshot-input! root source target)]
        (is (.exists target))
        (is (= "inputs/fixture.edn" (:input/snapshot-relative provenance)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"escapes run root"
                              (lifecycle/snapshot-input! root source "/tmp/escaping-fixture.edn"))))
      (finally
        (io/delete-file source-file true)
        (delete-tree! root)))))

(deftest root-lock-is-exclusive-and-released
  (let [root (temp-dir)]
    (try
      (let [lock (lifecycle/acquire-run-lock! root "run-one" :benchmark)]
        (is (.exists (io/file root ".run.lock")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"already in use"
                              (lifecycle/acquire-run-lock! root "run-two" :benchmark)))
        (lifecycle/release-run-lock! lock)
        (is (not (.exists (io/file root ".run.lock"))))
        (let [second-lock (lifecycle/acquire-run-lock! root "run-two" :benchmark)]
          (is (.exists second-lock))
          (lifecycle/release-run-lock! second-lock)))
      (finally (delete-tree! root)))))

(deftest atomic-json-running-state-and-completion-form-a-lifecycle
  (let [root (temp-dir)
        json-file (io/file root "manifest/value.json")]
    (try
      (lifecycle/atomic-json! json-file {"version" 1})
      (lifecycle/atomic-json! json-file {"version" 2})
      (is (= {"version" 2} (json/read-str (slurp json-file))))
      (is (not (.exists (io/file root "manifest/value.json.tmp"))))
      (lifecycle/mark-running! root "run-test" :benchmark)
      (is (.exists (io/file root ".run-state")))
      (let [completion (lifecycle/complete! root {"run_id" "run-test" "lifecycle_status" "completed"})]
        (is (= "completed" (get completion "lifecycle_status")))
        (is (.exists (io/file root "completion.json")))
        (is (not (.exists (io/file root ".run-state"))))
        (is (= (lifecycle/sha256-file (io/file root "completion.json"))
               (lifecycle/sha256-file (io/file root "completion.json")))))
      (finally (delete-tree! root)))))
