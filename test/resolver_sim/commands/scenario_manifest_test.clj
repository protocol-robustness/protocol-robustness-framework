(ns resolver-sim.commands.scenario-manifest-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-manifest :as manifest]))

(defn- delete-tree! [path] (when (.exists (io/file path)) (doseq [f (reverse (file-seq (io/file path)))] (io/delete-file f true))))
(deftest writes-enriched-manifest-with-propagated-run-id
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "manifest-" (make-array java.nio.file.attribute.FileAttribute 0)))
        dir (io/file root "manifest")]
    (try
      (.mkdirs dir)
      (spit (io/file dir "run-enrichment.json") "{\"execution\":{\"dag-path\":\"scenarios/s/execution/execution-dag.json\"}}")
      (manifest/write! {:manifest/dir (.getPath dir) :run/id "run-1" :scenario/ref "scenarios/S01.edn"}
                       {:exit-code 0 :duration-ms 12})
      (let [run (json/read-str (slurp (io/file dir "run.json")))
            summary (json/read-str (slurp (io/file dir "summary.json")))]
        (is (= "run-1" (get-in run ["run" "id"])))
        (is (= "complete" (get-in run ["run" "status"])))
        (is (= "scenarios/s/execution/execution-dag.json" (get-in run ["execution" "dag-path"])))
        (is (= "pass" (get-in summary ["run" "overall_status"])))
        (is (.isFile (io/file dir "claimable-classification.json"))))
      (finally (delete-tree! root)))))
