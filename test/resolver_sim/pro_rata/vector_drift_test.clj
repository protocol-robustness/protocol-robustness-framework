(ns resolver-sim.pro-rata.vector-drift-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [scripts.conformance.pro-rata-vector-drift :as drift]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "prf-vector-drift-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-vectors! [directory vectors]
  (doseq [[filename value] vectors]
    (spit (io/file directory filename) (pr-str value)))
  directory)

(defn- delete-tree! [file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)] (delete-tree! child)))
  (.delete file))

(defn- with-vector-dirs [reference-vectors mirror-vectors f]
  (let [reference (temp-dir)
        mirror (temp-dir)]
    (try
      (write-vectors! reference reference-vectors)
      (write-vectors! mirror mirror-vectors)
      (f reference mirror)
      (finally
        (delete-tree! reference)
        (delete-tree! mirror)))))

(deftest vector-mirror-comparison-is-exact-and-identity-aware
  (let [a {:vector/id :test/a :expected {:root "aa"}}
        b {:vector/id :test/b :expected {:root "bb"}}
        vectors {"a.edn" a "b.edn" b}]
    (testing "identical committed files pass"
      (with-vector-dirs vectors vectors
        (fn [reference mirror]
          (is (= vectors (drift/compare-vector-directories reference mirror (set (keys vectors))))))))
    (testing "altered, missing, and unexpected files fail"
      (with-vector-dirs vectors (assoc vectors "b.edn" (assoc-in b [:expected :root] "changed"))
        (fn [reference mirror]
          (is (thrown? clojure.lang.ExceptionInfo
                       (drift/compare-vector-directories reference mirror (set (keys vectors)))))))
      (with-vector-dirs vectors {"a.edn" a}
        (fn [reference mirror]
          (is (thrown? clojure.lang.ExceptionInfo
                       (drift/compare-vector-directories reference mirror (set (keys vectors)))))))
      (with-vector-dirs vectors (assoc vectors "extra.edn" a)
        (fn [reference mirror]
          (is (thrown? clojure.lang.ExceptionInfo
                       (drift/compare-vector-directories reference mirror (set (keys vectors))))))))
    (testing "duplicate canonical vector identities fail"
      (let [duplicate {"a.edn" a "b.edn" (assoc b :vector/id :test/a)}]
        (with-vector-dirs duplicate duplicate
          (fn [reference mirror]
            (is (thrown? clojure.lang.ExceptionInfo
                         (drift/compare-vector-directories reference mirror (set (keys duplicate)))))))))))

(deftest allocation-output-requires-exact-record-arity
  (let [vectors {"allocation-001.edn"
                 {:vector/id :allocation-001
                  :expected {:request/hash "request-root" :allocation/hash "result-root"}}
                 "negative-allocation-001.edn"
                 {:mutations [{:mutation/id :underflow
                               :expected/reason :underflow}]}}
        records [["PRF_VECTOR_DRIFT_V1" "allocation" "allocation-001"
                  "request-root" "result-root"]
                 ["PRF_VECTOR_DRIFT_V1" "allocation-rejection" "underflow" "underflow"]]
        check (ns-resolve 'scripts.conformance.pro-rata-vector-drift 'check-allocation-output!)]
    (testing "the frozen positive and rejection records pass"
      (is (nil? (check records vectors))))
    (testing "extra fields in positive and rejection records fail"
      (is (thrown? clojure.lang.ExceptionInfo
                   (check (update records 0 conj "unexpected") vectors)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (check (update records 1 conj "unexpected") vectors))))))

(deftest transition-output-requires-exact-frozen-record-identities
  (let [vectors {"canonical-transition-001.edn"
                 {:expected {:roots {:state-before/root "before"
                                     :effects/root "effects"
                                     :state-after/root "after"
                                     :transition/root "transition"}}}
                 "negative-transition-001.edn"
                 {:mutations [{:mutation/id :underflow
                               :expected/reason :underflow}]}}
        canonical ["PRF_VECTOR_DRIFT_V1" "transition" "canonical-transition-001"
                   "before" "effects" "after" "transition"]
        rejection ["PRF_VECTOR_DRIFT_V1" "transition-rejection" "underflow" "underflow"]]
    (testing "the frozen positive and rejection records pass"
      (is (nil? (drift/check-transition-output! [canonical rejection] vectors)))
      (testing "an extra positive transition record is rejected"
        (is (thrown? clojure.lang.ExceptionInfo
                     (drift/check-transition-output!
                      [canonical rejection
                       ["PRF_VECTOR_DRIFT_V1" "transition" "unexpected" "a" "b" "c" "d"]]
                      vectors))))
      (testing "an extra rejection record is rejected"
        (is (thrown? clojure.lang.ExceptionInfo
                     (drift/check-transition-output!
                      [canonical rejection
                       ["PRF_VECTOR_DRIFT_V1" "transition-rejection" "unexpected"
                        "portable-contract/other"]]
                      vectors))))
      (testing "extra fields in a rejection record are rejected"
        (is (thrown? clojure.lang.ExceptionInfo
                     (drift/check-transition-output!
                      [canonical (conj rejection "unexpected")]
                      vectors)))))))

(deftest tree-fingerprint-covers-rust-sources-and-requires-inputs
  (let [root (temp-dir)
        src-dir (doto (io/file root "rust/src") .mkdirs)
        tests-dir (doto (io/file root "rust/tests") .mkdirs)
        source (io/file src-dir "lib.rs")
        other-source (io/file src-dir "other.rs")
        test-source (io/file tests-dir "vectors.rs")
        required (io/file root "manifest.edn")]
    (try
      (spit source "pub fn result() -> u8 { 1 }")
      (spit other-source "pub fn unrelated() -> u8 { 0 }")
      (spit test-source "#[test] fn emits_vector() {}")
      (spit required "{}")
      (let [required-paths ["manifest.edn" "rust/src/lib.rs"]
            initial (drift/tree-fingerprint root required-paths)]
        (spit test-source "#[test] fn emits_vector() { assert!(true); }")
        (is (not= initial (drift/tree-fingerprint root required-paths))
            "Rust test source that emits checked records contributes to the fingerprint")
        (is (thrown? clojure.lang.ExceptionInfo
                     (drift/tree-fingerprint root ["manifest.edn" "rust/src/missing.rs"]))
            "missing required Rust source files fail closed")
        (io/delete-file source)
        (is (thrown? clojure.lang.ExceptionInfo
                     (drift/tree-fingerprint root required-paths))
            "deleting a specifically required Rust source fails even if other sources remain")
        (is (thrown? clojure.lang.ExceptionInfo
                     (drift/tree-fingerprint root ["missing-manifest.edn"]))
            "missing required files fail closed"))
      (finally (delete-tree! root)))))
