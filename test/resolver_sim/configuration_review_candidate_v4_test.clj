(ns resolver-sim.configuration-review-candidate-v4-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.deployment.source-identity :as source-identity]
            [scripts.deployment.configuration-review :as review]))

(def candidate-dir "etc/deployments/bounded-configuration-local-candidate-v4")
(def source-lock-path "etc/deployments/baselines/bounded-configuration-release-sources.current.v3.json")
(def source-revision "75c5055b23a2f1b27f43af3696eb4be99f51ed7b")
(def source-tree-sha256 "c39f3aa7a96304d038c5263e811e46f1012831f5a702a2d7f38f332f45701527")
(def approval-principal "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")
(def required-runtime-paths
  #{"deps.edn"
    "bb.edn"
    "packages/accounting-reductions/clj/deps.edn"
    "packages/accounting-reductions/clj/src/resolver_sim/accounting/reductions.clj"})

(defn- sha256-file [path]
  (apply str
         (map #(format "%02x" (bit-and 0xff %))
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (java.nio.file.Files/readAllBytes (.toPath (io/file path)))))))

(deftest current-candidate-v4-locks-runtime-dependency-closure
  (let [root (io/file ".")
        lock (json/read-str (slurp (io/file root source-lock-path)) :key-fn keyword)
        prf (:prf lock)
        locked-paths (set (map :path (:sourceTreeEntries prf)))
        manifest-path (io/file root candidate-dir "review-manifest.v3.edn")
        manifest (edn/read-string (slurp manifest-path))
        packages (edn/read-string (slurp (io/file root candidate-dir "candidate-package-set.edn")))
        binding-check (review/validate-review-bindings manifest packages source-revision approval-principal)
        source-check (source-identity/verify-source-lock
                      {:source-root root
                       :source-lock-path (io/file root source-lock-path)
                       :expected-tree-sha256 source-tree-sha256
                       :expected-parent source-revision})]
    (is (= 930 (:sourceFileCount prf)))
    (is (= 930 (:source-file-count source-check)))
    (is (= source-tree-sha256 (:sourceTreeSha256 prf)))
    (is (every? locked-paths required-runtime-paths))
    (is (= source-tree-sha256 (:manifest/source-tree-sha256 manifest)))
    (is (= "sha256:b25d89eecf2c245c03e49ddd5f78080c183c9c44e66d7a8b9eac0193d953ac56"
           (review/manifest-root manifest)))
    (is (= "714f5414ec2302795cec4d517af52326bcd16c1c05fb3cdad8dc872725d1adb7"
           (sha256-file manifest-path)))
    (is (:valid? binding-check))
    (is (= :content-checked-not-approved (:qualification binding-check)))))
