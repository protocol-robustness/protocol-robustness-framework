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
