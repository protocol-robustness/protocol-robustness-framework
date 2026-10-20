(ns resolver-sim.configuration-review-candidate-v7-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.deployment.source-identity :as source-identity]
            [scripts.deployment.configuration-review :as review]))

(def candidate-dir "etc/deployments/bounded-configuration-local-candidate-v7")
(def source-lock-path "etc/deployments/baselines/bounded-configuration-release-sources.current.v6.json")
(def source-revision "896ab2ca29d178d9b3993750d4fb774deaf984c4")
(def source-tree-sha256 "6ffee509341fb888235d4bb3f0ef2cee466f29fb8ac4433fb3c892f077f2ae89")
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

(deftest current-candidate-v7-locks-runtime-dependency-closure
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
    (is (= 933 (:sourceFileCount prf)))
    (is (= 933 (:source-file-count source-check)))
    (is (= source-tree-sha256 (:sourceTreeSha256 prf)))
    (is (every? locked-paths ["etc/deployments/baselines/prf-evm-deployable-export-normalization-followup.patch"
                             "etc/deployments/baselines/protocol-deployments-v0.4.2-replay-fixture.patch"]))
    (is (every? locked-paths required-runtime-paths))
    (is (= source-tree-sha256 (:manifest/source-tree-sha256 manifest)))
    (is (= "sha256:4a58d73213ff165e9e34fbfcd2694c029e681dbccdc0420bc0c0b3ea0c519f27"
           (review/manifest-root manifest)))
    (is (= "070562e4edd09d07fa0fc625d3e4108ec6a7038a098f55cc86d77bc99fc15b55"
           (sha256-file manifest-path)))
    (is (:valid? binding-check))
    (is (= :content-checked-not-approved (:qualification binding-check)))))
