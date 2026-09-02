(ns resolver-sim.benchmark.runner-artifact-manifest-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.distributed.artifact-manifest :as artifact-manifest]
            [resolver-sim.benchmark.runner :as runner]))

(deftest staged-artifact-manifest-has-the-canonical-derived-root
  (let [dir (doto (java.io.File/createTempFile "benchmark-artifacts-" "")
              (.delete)
              (.mkdirs))
        _ (spit (io/file dir "result.edn") "{:result :ok}")
        manifest (#'runner/artifact-manifest-for-dir (.getPath dir) nil)]
    (is (= artifact-manifest/schema (:artifact/manifest-version manifest)))
    (is (artifact-manifest/verify-rooted-manifest manifest))
    (is (= (:artifact-manifest/root manifest)
           (artifact-manifest/manifest-root manifest)))))
