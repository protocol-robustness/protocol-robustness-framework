(ns resolver-sim.benchmark.distributed.artifact-manifest-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.distributed.artifact-manifest :as sut]))

(def manifest
  {:artifact/manifest-version "benchmark-artifact-manifest.v1"
   :artifacts [{:artifact/relative-path "a.edn" :artifact/sha256 "a" :artifact/byte-count 1}
               {:artifact/relative-path "b.edn" :artifact/sha256 "b" :artifact/byte-count 2
                :artifact/semantic-root "semantic"}]})

(deftest rooted-manifest-is-derived-and-tamper-sensitive
  (let [rooted (sut/rooted-manifest manifest)]
    (is (= sut/schema (:artifact/manifest-version rooted)))
    (is (sut/verify-rooted-manifest rooted))
    (is (= rooted (sut/rooted-manifest manifest)))
    (is (not (sut/verify-rooted-manifest
              (assoc-in rooted [:artifacts 0 :artifact/byte-count] 3))))
    (is (not (sut/verify-rooted-manifest
              (assoc rooted :artifact-manifest/root "sha256:bad"))))))
