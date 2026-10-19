(ns resolver-sim.configuration-review-candidate-v6-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.deployment.source-identity :as source-identity]
            [scripts.deployment.configuration-review :as review]))

(def candidate-dir "etc/deployments/bounded-configuration-local-candidate-v6")
(def source-lock-path "etc/deployments/baselines/bounded-configuration-release-sources.current.v5.json")
(def source-revision "eb6da5102d14ca4080fe08df7b6c349311ea943c")
(def source-tree-sha256 "1ce2108ce7c2cda3e1ff7cdcfea351b4b2f5a689622aae0fa05b3a950f429523")
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

(deftest current-candidate-v6-locks-runtime-dependency-closure
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
    (is (= 932 (:sourceFileCount prf)))
    (is (= 932 (:source-file-count source-check)))
    (is (= source-tree-sha256 (:sourceTreeSha256 prf)))
    (is (contains? locked-paths "etc/deployments/baselines/prf-evm-deployable-export-normalization-followup.patch"))
    (is (every? locked-paths required-runtime-paths))
    (is (= source-tree-sha256 (:manifest/source-tree-sha256 manifest)))
    (is (= "sha256:01d108c28db1d40d64f0de67cdab8e2e05f5cb6a745e38e9069b55061de4b63c"
           (review/manifest-root manifest)))
    (is (= "3e92400f70aa917a60cff88c41f5a8dfbe223b5b247522ca622eb62b8a61dbc2"
           (sha256-file manifest-path)))
    (is (:valid? binding-check))
    (is (= :content-checked-not-approved (:qualification binding-check)))))
