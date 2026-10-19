(ns resolver-sim.commands.simulation-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.simulation :as simulation]
            [resolver-sim.core.phases :as phases]
            [resolver-sim.forensic.source-hash :as source-hash]
            [resolver-sim.io.params :as params]))

(defn- sha256-file [^java.io.File file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (java.nio.file.Files/readAllBytes (.toPath file))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) (.digest digest bytes)))))

(defn- delete-tree! [^java.io.File root]
  (when (.exists root)
    (doseq [file (reverse (file-seq root))]
      (io/delete-file file true))))

(deftest simplified-run-records-input-source-runtime-and-output-identities
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "prf-simplified-run-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        input (io/file root "params.edn")
        output (io/file root "run")
        source {:vcs/type :jj
                :vcs/root "."
                :source/commit "test-commit"
                :source/hash "test-source-tree-hash"
                :source/hash-algorithm "source-tree-hash.v1.path-content-sha256"
                :source/hash-roots ["src"]
                :source/dirty? false}]
    (try
      (spit input "{:trials 1}\n")
      (with-redefs [source-hash/source-hash (constantly source)
                    params/validate-and-merge (fn [_] {:trials 1})
                    phases/run-simulation (fn [_ result-dir]
                                            (.mkdirs (io/file result-dir))
                                            (spit (io/file result-dir "summary.edn") "{:trials 1}\n")
                                            {:trials 1 :status :complete})]
        (is (= {:exit-code 0}
               (simulation/simplified {:params (.getPath input)
                                       :output (.getPath output)})))
        (let [record (edn/read-string (slurp (io/file output "selection.edn")))
              artifacts (:artifacts/sha256 record)]
          (is (= :completed (:selection/status record)))
          (is (= ["simulation" "simplified"] (:command/path record)))
          (is (= ["simulation" "simplified" "--params" (.getPath input)
                  "--output" (.getPath output)]
                 (:command/arguments record)))
          (is (= "test-commit" (get-in record [:source/provenance :source/commit])))
          (is (= "test-source-tree-hash" (get-in record [:source/provenance :source/hash])))
          (is (= (sha256-file (io/file output "inputs/params.edn"))
                 (:input/sha256 record)))
          (is (string? (get-in record [:runtime/provenance :runtime/java :version])))
          (is (string? (get-in record [:runtime/provenance :runtime/clojure :version])))
          (is (re-matches #"[0-9a-f]{64}"
                          (get-in record [:runtime/provenance :runtime/classpath :sha256])))
          (is (seq (get-in record [:runtime/provenance :runtime/classpath :entry-identities])))
          (is (every? #(re-matches #"[0-9a-f]{64}" (:content/sha256 %))
                      (get-in record [:runtime/provenance :runtime/classpath :entry-identities])))
          (is (contains? (get-in record [:runtime/provenance :runtime/dependency-config-sha256])
                         "deps.edn"))
          (is (= #{"inputs/params.edn" "simulation-results/summary.edn"}
                 (set (keys artifacts))))
          (is (every? #(re-matches #"[0-9a-f]{64}" %) (vals artifacts)))))
      (finally (delete-tree! root)))))

(deftest failed-simplified-run-retains-runtime-and-partial-output-digests
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "prf-simplified-failure-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        input (io/file root "params.edn")
        output (io/file root "run")]
    (try
      (spit input "{:invalid true}\n")
      (with-redefs [source-hash/source-hash (constantly {:vcs/root "."
                                                         :source/commit "test-commit"})
                    params/validate-and-merge (fn [_] (throw (ex-info "invalid parameters" {})))]
        (is (= {:exit-code 1}
               (simulation/simplified {:params (.getPath input)
                                       :output (.getPath output)})))
        (let [record (edn/read-string (slurp (io/file output "selection.edn")))]
          (is (= :failed (:selection/status record)))
          (is (= "clojure.lang.ExceptionInfo" (:failure/class record)))
          (is (= "invalid parameters" (:failure/message record)))
          (is (string? (get-in record [:runtime/provenance :runtime/java :version])))
          (is (= #{"inputs/params.edn"}
                 (set (keys (:artifacts/sha256 record)))))))
      (finally (delete-tree! root)))))
