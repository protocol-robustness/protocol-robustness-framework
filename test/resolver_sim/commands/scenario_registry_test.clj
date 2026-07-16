(ns resolver-sim.commands.scenario-registry-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-registry :as registry]))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))] (io/delete-file file true))))

(defn- root [] (.toFile (java.nio.file.Files/createTempDirectory "registry-finalize-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- write-json! [file value] (.mkdirs (.getParentFile (io/file file))) (spit file (json/write-str value)))

(deftest finalization-refreshes-metadata-and-dependencies
  (let [dir (root)]
    (try
      (spit (io/file dir "payload.json") "payload")
      (write-json! (io/file dir "manifest/artifacts.json")
                   {:root_dir "." :artifacts [{:id "payload" :path "payload.json" :sha256 "old" :bytes 0 :dependencies []}
                                                {:id "manifest" :path "manifest/artifacts.json" :sha256 "old" :bytes 0 :dependencies []}]})
      ;; The registry cannot inventory itself; replace its entry with a normal file.
      (write-json! (io/file dir "manifest/artifacts.json")
                   {:root_dir "." :artifacts [{:id "payload" :path "payload.json" :sha256 "old" :bytes 0 :dependencies []}]})
      (let [result (registry/finalize! dir) entry (first (:artifacts result))]
        (is (= "." (:root_dir result)))
        (is (= "payload.json" (:path entry)))
        (is (= 7 (:bytes entry)))
        (is (re-matches #"[0-9a-f]{64}" (:sha256 entry))))
      (finally (delete-tree! dir)))))

(deftest finalization-rejects-duplicate-and-missing-artifacts-without-mutating-registry
  (doseq [artifacts [[{:id "missing" :path "missing.json"}]
                      [{:id "duplicate" :path "payload.json"}
                       {:id "duplicate" :path "payload.json"}]]]
    (let [dir (root)
          registry-file (io/file dir "manifest/artifacts.json")]
      (try
        (spit (io/file dir "payload.json") "payload")
        (write-json! registry-file {:root_dir "." :artifacts artifacts})
        (let [before (slurp registry-file)]
          (is (thrown? clojure.lang.ExceptionInfo (registry/finalize! dir)))
          (is (= before (slurp registry-file))))
        (finally (delete-tree! dir))))))

(deftest finalization-rejects-escape-and-self-reference
  (doseq [path ["../outside.json" "manifest/artifacts.json"]]
    (let [dir (root)]
      (try
        (write-json! (io/file dir "manifest/artifacts.json") {:artifacts [{:id "bad" :path path}]})
        (is (thrown? clojure.lang.ExceptionInfo (registry/finalize! dir)))
        (finally (delete-tree! dir))))))
