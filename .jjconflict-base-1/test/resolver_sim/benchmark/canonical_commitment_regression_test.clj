(ns resolver-sim.benchmark.canonical-commitment-regression-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.evidence.node :as evidence-node]
            [resolver-sim.hash.canonical :as hc]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "benchmark-canonical-commitment-regression-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when (.exists (io/file path))
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

;; Reproduces the writer-boundary commitment procedure (runner.clj):
;; normalize runtime values -> hashable-evidence -> sorted -> :bundle-root
;; domain hash -> install :evidence/hash -> persist -> read back.
(defn- commit-and-load [bundle dir]
  (let [normalized (runner/normalize-runtime-values bundle)
        hashable (into (sorted-map) (integrity/hashable-evidence normalized))
        bundle-root (hc/hash-with-intent {:hash/intent :bundle-root} hashable)
        finalized (assoc normalized :evidence/hash bundle-root)
        path (str (io/file dir "evidence.edn"))]
    (runner/write-evidence finalized path)
    (integrity/read-evidence-bundle path)))

(deftest class-runtime-value-commits-and-recomputes-from-disk
  ;; Regression: a java.lang.Class value embedded in a persisted evidence bundle
  ;; (e.g. :run/manifest :adapter) printed as an opaque Object form instead of a
  ;; portable descriptor, so the committed :bundle-root (hash over the in-memory
  ;; Class) disagreed with recomputation over the persisted file. normalize-runtime-values
  ;; now converts Class -> {:type :class :name ...}, closing the writer/verifier
  ;; divergence.
  (let [dir (temp-dir)
        bundle {:benchmark/id :benchmark/regression-class-roundtrip
                :evidence/commitment-version "bundle-root.v2"
                :run/manifest {:adapter java.lang.String}
                :metrics {:total 1 :passed 1}
                :nested {:another-class java.lang.Integer}}]
    (try
      (testing "a Class value is normalized to a portable descriptor"
        (is (= {:type :class :name "java.lang.String"}
               (runner/normalize-runtime-values java.lang.String))))
      (testing "the persisted bundle recomputes its committed bundle-root"
        (let [loaded (commit-and-load bundle dir)]
          (is (:hash-ok? (integrity/verify-bundle-hash loaded))
              "committed :evidence/hash recomputes from the persisted file even with a Class in :run/manifest")))
      (finally
        (delete-tree! dir)))))

(deftest nonportable-class-does-not-infect-hashable-evidence-after-normalization
  ;; The hashable-evidence projection itself must not contain the raw Class once
  ;; normalized: verify/package-registry recomputation reads the persisted
  ;; (normalized) form, so a raw Class must never be part of the canonical bytes.
  (let [normalized (runner/normalize-runtime-values
                    {:run/manifest {:adapter java.lang.String}
                     :payload java.lang.Long})]
    (is (nil? (some (fn [v]
                      (and (instance? java.lang.Class v) v))
                    (tree-seq coll? seq normalized)))
        "no raw java.lang.Class survives writer normalization")
    (is (= "java.lang.String" (get-in normalized [:run/manifest :adapter :name])))))

(deftest integer-valued-ratio-commits-and-recomputes-from-disk
  ;; Regression: integer-valued clojure.lang.Ratio values (e.g. 100/1) in evidence
  ;; (such as :replay-result shares) are written by ppedn/ppr-str as "100/1" but
  ;; edn reduces them to Long 100 on read. The committed bundle-root was computed
  ;; over the Ratio while verify recomputes over the Long, so the persisted form
  ;; could not recompute. normalize-runtime-values now canonicalizes integer-valued
  ;; ratios to the exact Long the persisted form reads back as.
  (let [dir (temp-dir)
        r100 (clojure.lang.Ratio. (biginteger 100) (biginteger 1))
        r1 (clojure.lang.Ratio. (biginteger 1) (biginteger 1))
        rfrac (clojure.lang.Ratio. (biginteger 100) (biginteger 3))
        bundle {:benchmark/id :benchmark/regression-ratio-roundtrip
                :evidence/commitment-version "bundle-root.v2"
                :run/manifest {:adapter java.lang.String}
                :metrics {:total 1 :passed 1}
                :results [{:replay-result {:states [{:shares r100 :entry-index r1}]}
                           :scenario/artifacts []}]}]
    (try
      (testing "integer-valued ratios normalize to Long, fractional ratios are preserved"
        (is (= 100 (runner/normalize-runtime-values r100)))
        (is (= 1 (runner/normalize-runtime-values r1)))
        (is (= rfrac (runner/normalize-runtime-values rfrac))))
      (testing "a bundle carrying ratios recomputes its committed bundle-root from disk"
        (let [loaded (commit-and-load bundle dir)]
          (is (:hash-ok? (integrity/verify-bundle-hash loaded))
              "persisted ratios recompute the committed bundle-root (no Ratio/Long divergence)")))
      (finally
        (delete-tree! dir)))))

(defn- assurance-body [run-id conclusion-sha conservation-sha]
  {"domain" "prf/benchmark-assurance/v1"
   "schema_version" "benchmark-assurance.v1"
   "run_id" run-id
   "input_set_root" "ipfs-shim://shard/input-a"
   "input_set" [{"path" "scenarios/x.edn" "sha256" "input-shift="}]
   "conclusion" {"ref" "benchmark/conclusion.json" "sha256" conclusion-sha
                 "domain" "prf/benchmark-conclusion/v1"}
   "conservation" {"artifact_sha256" conservation-sha
                   "conservation-output" "conservation-content"}
   "required_artifact_assertions"
   [{"path" "benchmark/conclusion.json" "sha256" conclusion-sha "kind" "conclusion"}
    {"path" "benchmark/assertions/conservation.json" "sha256" conservation-sha "kind" "conservation"}
    {"path" "benchmark/execution/runner-finalization.json" "sha256" "rt-fixed" "kind" "finalization"}]})

(deftest final-ref-assurance-projection-is-reproducible-across-runs
  ;; Regression: final_ref's "assurance_artifact_sha256" component was computed by
  ;; content-assurance-sha, which only dropped the top-level run_id and so still
  ;; embedded run-scoped conclusion.conservation raw-file hashes. It now goes through
  ;; canonical-artifact-content (the same projection used for the conclusion and
  ;; content-registry components), which strips those run-scoped fields explicitly.
  (let [run-a (assurance-body "run-A" "sha256-conclusion-A" "sha256-conservation-A")
        run-b (assurance-body "run-B" "sha256-conclusion-B" "sha256-conservation-B")
        dir (temp-dir)]
    (try
      (let [file-a (io/file dir "a.json")
            file-b (io/file dir "b.json")
            _ (spit file-a (json/write-str run-a))
            _ (spit file-b (json/write-str run-b))
            sha-a (:sha256 (evidence-node/canonical-artifact-content
                            "benchmark/assertions/benchmark-assurance.json" file-a))
            sha-b (:sha256 (evidence-node/canonical-artifact-content
                            "benchmark/assertions/benchmark-assurance.json" file-b))]
        (is (string? sha-a))
        (is (= sha-a sha-b)
            "assurance canonical sha is invariant across run-scoped source hashes and run_id"))
      (finally
        (delete-tree! dir)))))
