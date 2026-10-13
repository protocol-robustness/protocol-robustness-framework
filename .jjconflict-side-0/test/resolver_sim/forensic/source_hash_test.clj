(ns resolver-sim.forensic.source-hash-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.forensic.source-hash :as sut]
            [resolver-sim.vcs :as vcs]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "source-hash-test-"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn- delete-tree! [root]
  (let [f (io/file root)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^java.io.File child)))))

(defn- with-temp-tree [f]
  (let [root (temp-dir)]
    (try
      (f root)
      (finally
        (delete-tree! root)))))

(deftest source-tree-hash-detects-same-size-edits
  (with-temp-tree
    (fn [root]
      (let [path "src/example.clj"]
        (write-file! root path "(def value 1)\n")
        (let [before (sut/source-tree-hash* root ["src"])]
          (write-file! root path "(def value 2)\n")
          (is (not= before (sut/source-tree-hash* root ["src"]))))))))

(deftest source-tree-hash-detects-file-renames
  (with-temp-tree
    (fn [root]
      (let [old-file (write-file! root "src/alpha.clj" "(def value 1)\n")
            before (sut/source-tree-hash* root ["src"])
            new-file (io/file root "src/bravo.clj")]
        (.renameTo old-file new-file)
        (is (not= before (sut/source-tree-hash* root ["src"])))))))

(deftest source-tree-hash-includes-files-after-the-thousandth-entry
  (with-temp-tree
    (fn [root]
      (doseq [idx (range 1001)]
        (write-file! root (format "src/f%04d.clj" idx) (str "(def v" idx " 1)\n")))
      (let [before (sut/source-tree-hash* root ["src"])]
        (write-file! root "src/f1000.clj" "(def v1000 2)\n")
        (is (not= before (sut/source-tree-hash* root ["src"])))))))

(deftest source-tree-hash-respects-configured-roots
  (with-temp-tree
    (fn [root]
      (write-file! root "src/included.clj" "(def included 1)\n")
      (write-file! root "notes/ignored.clj" "(def ignored 1)\n")
      (let [before (sut/source-tree-hash* root ["src"])]
        (write-file! root "notes/ignored.clj" "(def ignored 2)\n")
        (is (= before (sut/source-tree-hash* root ["src"])))
        (is (= ["src"] (sut/included-source-roots root ["src" "missing"])))))))

(deftest vcs-code-hash-uses-shared-source-tree-hash
  (with-temp-tree
    (fn [root]
      (write-file! root "src/shared.clj" "(def shared 1)\n")
      (with-redefs [vcs/root (constantly root)
                    sut/source-roots (constantly ["src"])]
        (is (= (sut/source-tree-hash* root ["src"])
               (vcs/code-hash)))))))

(deftest default-source-roots-cover-replay-critical-surfaces
  (is (= ["src"
          "protocols_src"
          "benchmarks"
          "data/concepts"
          "scenarios"
          "suites"
          "resources"]
         sut/default-source-roots)))
