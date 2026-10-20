(ns resolver-sim.configuration-review-candidate-v5-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.deployment.source-identity :as source-identity]
            [scripts.deployment.configuration-review :as review]))

(def candidate-dir "etc/deployments/bounded-configuration-local-candidate-v5")
(def source-lock-path "etc/deployments/baselines/bounded-configuration-release-sources.current.v4.json")
(def source-revision "01837e6237c625a3d23bdeafc8075232a2dfabd0")
(def source-tree-sha256 "708569f9856817c155ad04ab947609cd63dabf659bbe1f4c54bd1404f1d2488d")
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

(deftest current-candidate-v5-locks-runtime-dependency-closure
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
    (is (= 931 (:sourceFileCount prf)))
    (is (= 931 (:source-file-count source-check)))
    (is (= source-tree-sha256 (:sourceTreeSha256 prf)))
    (is (every? locked-paths required-runtime-paths))
    (is (= source-tree-sha256 (:manifest/source-tree-sha256 manifest)))
    (is (= "sha256:a0905e8b132a5e6b260e27f7d9188b6fb8afb7d56a7b6ae0e2d93627fd1f678d"
           (review/manifest-root manifest)))
    (is (= "6beb6f8236d21ce57f4065549d8e6efc49ebfcc3fdccd08d0ba452c6917edadd"
           (sha256-file manifest-path)))
    (is (:valid? binding-check))
    (is (= :content-checked-not-approved (:qualification binding-check)))))
