(ns resolver-sim.benchmark.distributed.result-store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.distributed.result-store :as store]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "distributed-result-store-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (doseq [file (reverse (file-seq (io/file path)))]
    (io/delete-file file true)))

(defn- root [ch] (str "sha256:" (apply str (repeat 64 ch))))

(defn- result [provenance]
  {:result/root (root "a")
   :result/manifest-root (root "b")
   :sensitivity/provenance provenance
   :result/data {:detached true}})

(deftest filesystem-result-store-is-content-addressed-and-sensitivity-bound
  (let [dir (temp-dir)
        result-store (store/filesystem-store dir)
        public {:sensitivity/level :sensitivity/public :sensitivity/source :worker}
        detached (result public)]
    (try
      (let [receipt (store/put-detached-result! result-store detached)
            verified (store/verify-detached-result!
                      result-store (:result/ref receipt)
                      (merge (select-keys receipt [:result/root :result/manifest-root])
                             {:sensitivity/root (:sensitivity/root receipt)}))]
        (is (= (:result/root detached) (:result/root receipt)))
        (is (= (:result/manifest-root detached) (:result/manifest-root receipt)))
        (is (= (store/sensitivity-root public) (:sensitivity/root receipt)))
        (is (= public (:sensitivity/provenance verified)))
        (is (nil? (store/get-detached-result result-store "../escape")))
        ;; Equal result roots cannot hide contradictory provenance.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incompatible"
                              (store/put-detached-result!
                               result-store
                               (result {:sensitivity/level :sensitivity/internal
                                        :sensitivity/source :other-worker})))))
      (finally (delete-tree! dir)))))
