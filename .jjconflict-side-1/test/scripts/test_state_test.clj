(ns scripts.test-state-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [scripts.test-state :as state]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "test-state-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (doseq [file (reverse (file-seq (io/file path)))]
    (io/delete-file file true)))

(defn- with-temp-state [f]
  (let [root (temp-dir)
        dir (.getPath root)]
    (try
      (with-redefs [state/state-dir dir
                    state/state-file (str dir "/test-state.edn")
                    state/tmp-file (str dir "/test-state.edn.tmp")
                    state/lock-file (str dir "/test-state.lock")]
        (f))
      (finally (delete-tree! root)))))

(deftest concurrent-state-writes-are-parseable-and-atomic
  (with-temp-state
    #(let [writes (doall (for [n (range 20)]
                           (future (state/write-state! {:command ["test" (str n)]
                                                        :failed-nses [(symbol (str "example." n))]}))))]
       (doseq [write writes] @write)
       (let [result (state/read-state)]
         (is (= 1 (:schema-version result)))
         (is (:completed? result))
         (is (= 2 (count (:command result))))
         (is (= 1 (count (:failed-test-namespaces result))))))))
