(ns resolver-sim.run.package-index-integrity-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.forensic.execution-dag :as dag]
            [resolver-sim.run.package-index :as package-index]
            [resolver-sim.commands.run-lifecycle :as lifecycle]))

(defn- root [] (.toFile (java.nio.file.Files/createTempDirectory "package-integrity-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- delete-tree! [f] (doseq [x (reverse (file-seq f))] (io/delete-file x true)))
(defn- ref [root path]
  (let [f (io/file root path)]
    {:ref path :sha256 (str "sha256:" (lifecycle/sha256-file f)) :bytes (.length f)}))
(defn- write-dag! [root value]
  (io/file (dag/write-dag! value "test" (str (io/file root "execution")))))
(defn- valid-dag []
  (let [n (dag/make-plan-node {:id "one"})]
    (dag/build-dag [n] [] {:run-id "run-1" :scenario-id "scenario-1" :execution-id "execution:run-1"})))
(defn- write-index! [root artifacts]
  (let [path (io/file root "manifest/run-package-index.json")]
    (package-index/write! path {:run-id "run-1" :scenario-id "scenario-1" :execution-id "execution:run-1"
                                    :run-type :single-scenario :bundle-root-hash "bundle" :artifacts artifacts})
    (spit (io/file root "completion.json")
          (clojure.data.json/write-str
           {"schema_version" "run-completion.v1"
            "run_id" "run-1"
            "lifecycle_status" "completed"
            "run_package_index_ref" "manifest/run-package-index.json"
            "run_package_index_sha256" (str "sha256:" (lifecycle/sha256-file path))
            "run_package_index_bytes" (.length path)}))))

(deftest completion-context-fails-closed
  (let [r (root)]
    (try
      (is (some #(= :package/completion-missing (:code %))
                (:reasons (package-index/resolve-completion-context r))))
      (spit (io/file r "completion.json") "{")
      (is (some #(= :package/completion-invalid (:code %))
                (:reasons (package-index/resolve-completion-context r))))
      (finally (delete-tree! r)))))

(deftest completion-context-validates-index-binding
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (write-index! r {:execution-dag (ref r "execution/execution-dag.json")})
      (let [completion-file (io/file r "completion.json")
            completion (clojure.data.json/read-str (slurp completion-file))]
        (spit completion-file (clojure.data.json/write-str (assoc completion "run_package_index_sha256" "sha256:0000000000000000000000000000000000000000000000000000000000000000")))
        (is (some #(= :package/package-index-hash-mismatch (:code %))
                  (:reasons (package-index/resolve-completion-context r))))
        (spit completion-file (clojure.data.json/write-str (assoc completion "run_package_index_bytes" 0)))
        (is (some #(= :package/package-index-byte-length-mismatch (:code %))
                  (:reasons (package-index/resolve-completion-context r))))
        (spit completion-file (clojure.data.json/write-str (assoc completion "run_package_index_ref" "../outside.json")))
        (is (some #(= :package/package-index-path-invalid (:code %))
                  (:reasons (package-index/resolve-completion-context r)))))
      (finally (delete-tree! r)))))

(deftest completion-context-rejects-committed-invalid-index-json
  (let [r (root)]
    (try
      (let [index-file (io/file r "manifest/run-package-index.json")]
        (.mkdirs (.getParentFile index-file))
        (spit index-file "{")
        (spit (io/file r "completion.json")
              (clojure.data.json/write-str
               {"schema_version" "run-completion.v1"
                "run_id" "run-1"
                "lifecycle_status" "completed"
                "run_package_index_ref" "manifest/run-package-index.json"
                "run_package_index_sha256" (str "sha256:" (lifecycle/sha256-file index-file))
                "run_package_index_bytes" (.length index-file)}))
        (is (some #(= :package/package-index-invalid-json (:code %))
                  (:reasons (package-index/resolve-completion-context r))))
        (is (false? (package-index/integrity-valid? r))))
      (finally (delete-tree! r)))))

(deftest package-integrity-rejects-duplicate-indexed-paths
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (let [entry (ref r "execution/execution-dag.json")]
        (write-index! r {:execution-dag entry :runner-finalization entry})
        (is (some #(= :package/duplicate-artifact-path (:code %))
                  (:reasons (package-index/validate-integrity r)))))
      (finally (delete-tree! r)))))

(deftest package-integrity-reconciles-explicit-dag-identities
  (let [r (root)]
    (try
      (doseq [[field value code] [[:run/id "different-run" :package/run-id-mismatch]
                                  [:scenario/id "different-scenario" :package/scenario-id-mismatch]
                                  [:execution/id "execution:different" :package/execution-id-mismatch]]]
        (write-dag! r (assoc (valid-dag) field value))
        (write-index! r {:execution-dag (ref r "execution/execution-dag.json")})
        (is (some #(= code (:code %))
                  (:reasons (package-index/validate-integrity r)))
            (str "explicit " field " must not fall back to the correct-looking path")))
      (finally (delete-tree! r)))))

(deftest completion-run-id-must-match-its-sealed-package-index
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (write-index! r {:execution-dag (ref r "execution/execution-dag.json")})
      (let [completion-file (io/file r "completion.json")
            completion (clojure.data.json/read-str (slurp completion-file))]
        (spit completion-file (clojure.data.json/write-str (assoc completion "run_id" "other-run")))
        (is (some #(= :package/run-id-mismatch (:code %))
                  (:reasons (package-index/validate-integrity r)))))
      (finally (delete-tree! r)))))

(deftest package-integrity-reconciles-indexed-input-snapshot-with-dag
  (let [r (root)]
    (try
      (let [snapshot (io/file r "inputs/scenarios/scenario-1.edn")]
        (.mkdirs (.getParentFile snapshot))
        (spit snapshot "{:scenario-id \"scenario-1\"}")
        (write-dag! r (valid-dag))
        (write-index! r {:input-snapshot (ref r "inputs/scenarios/scenario-1.edn")
                         :execution-dag (ref r "execution/execution-dag.json")})
        (is (some #(= :package/dag-input-hash-mismatch (:code %))
                  (:reasons (package-index/validate-integrity r)))))
      (finally (delete-tree! r)))))

(deftest package-integrity-reports-dag-semantic-causes
  (let [r (root)]
    (try
      (let [bad (assoc (valid-dag) :dag/root-hash "wrong")
            _ (write-dag! r bad)
            entry (ref r "execution/execution-dag.json")]
        (write-index! r {:execution-dag entry})
        (let [report (package-index/validate-integrity r)
              failure (first (filter #(= :package/invalid-execution-dag (:code %)) (:reasons report)))]
          (is (some? failure))
          (is (some #(= :execution-dag/root-hash-mismatch (:code %)) (:causes failure)))))
      (finally (delete-tree! r)))))

(deftest package-integrity-accepts-a-semantically-valid-dag
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (write-index! r {:execution-dag (ref r "execution/execution-dag.json")})
      (is (nil? (first (filter #(= :package/invalid-execution-dag (:code %))
                               (:reasons (package-index/validate-integrity r))))))
      (finally (delete-tree! r)))))

(deftest package-integrity-rejects-byte-length-mismatch
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (write-index! r {:execution-dag (update (ref r "execution/execution-dag.json") :bytes inc)})
      (is (some #(= :package/artifact-length-mismatch (:code %))
                (:reasons (package-index/validate-integrity r))))
      (finally (delete-tree! r)))))

(deftest package-integrity-reports-unreadable-indexed-json
  (let [r (root)]
    (try
      (let [f (io/file r "execution/execution-dag.json")]
        (.mkdirs (.getParentFile f))
        (spit f "{")
        (write-index! r {:execution-dag (ref r "execution/execution-dag.json")})
        (is (some #(= :package/unreadable-artifact (:code %))
                  (:reasons (package-index/validate-integrity r)))))
      (finally (delete-tree! r)))))

(deftest package-integrity-requires-byte-length-for-required-artifacts
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (let [entry (dissoc (ref r "execution/execution-dag.json") :bytes)]
        (write-index! r {:execution-dag entry})
        (is (some #(= :package/missing-artifact-byte-length (:code %))
                  (:reasons (package-index/validate-integrity r)))))
      (finally (delete-tree! r)))))
