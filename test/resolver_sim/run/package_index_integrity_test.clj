(ns resolver-sim.run.package-index-integrity-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.forensic.execution-dag :as dag]
            [resolver-sim.run.package-index :as package-index]
            [resolver-sim.commands.run-lifecycle :as lifecycle]))

(defn- root [] (.toFile (java.nio.file.Files/createTempDirectory "package-integrity-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- delete-tree! [f] (doseq [x (reverse (file-seq f))] (io/delete-file x true)))
(defn- artifact-ref [root path]
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

(defn- sha [file] (lifecycle/sha256-file file))

(defn- registry-entry [root id path]
  (let [file (io/file root path)]
    {:id id :path path :sha256 (sha file) :bytes (.length file)}))

(defn- registry-closure-report [root registry]
  (#'resolver-sim.run.package-index/validate-registry-closure root registry))

(deftest registry-closure-rejects-corrupt-and-terminal-artifacts
  (let [r (root)]
    (try
      (let [payload (io/file r "evidence/payload.json")
            _ (.mkdirs (.getParentFile payload))
            _ (spit payload "original")
            valid (registry-entry r "payload" "evidence/payload.json")]
        (is (:valid? (registry-closure-report r {:artifacts [valid]})))
        (spit payload "modified")
        (is (some #(= :registry-validation/artifact-hash-mismatch (:code %))
                  (:reasons (registry-closure-report r {:artifacts [valid]}))))
        (is (some #(= :registry-validation/artifact-byte-length-mismatch (:code %))
                  (:reasons (registry-closure-report r {:artifacts [(assoc valid :bytes 0)]}))))
        (is (some #(= :registry-validation/duplicate-artifact-id (:code %))
                  (:reasons (registry-closure-report r {:artifacts [valid (assoc valid :path "other.json")]}))))
        (is (some #(= :registry-validation/duplicate-artifact-path (:code %))
                  (:reasons (registry-closure-report r {:artifacts [valid (assoc valid :id "other")]}))))
        (is (some #(= :registry-validation/path-outside-root (:code %))
                  (:reasons (registry-closure-report r {:artifacts [(assoc valid :path "../outside.json")]}))))
        (is (some #(= :registry-validation/terminal-package-artifact-indexed (:code %))
                  (:reasons (registry-closure-report r
                                                     {:artifacts [(assoc valid :path "completion.json")]})))))
      (finally (delete-tree! r)))))

(deftest canonical-assurance-reports-semantic-failures-with-specific-causes
  (let [r (root)]
    (try
      (let [run-final (io/file r "evidence/finalizations/run/evidence-finalization.json")
            runner (io/file r "execution/runner-finalization.json")
            content (io/file r "evidence/content-registry.json")
            assurance (io/file r "manifest/canonical-integrity.json")
            _ (doseq [f [run-final runner content assurance]] (.mkdirs (.getParentFile f)))
            _ (spit run-final "run-final")
            _ (spit runner "runner-final")
            _ (spit content "content-registry")
            artifacts {:run-finalization (artifact-ref r "evidence/finalizations/run/evidence-finalization.json")
                       :runner-finalization (artifact-ref r "execution/runner-finalization.json")
                       :canonical-assurance {:ref "manifest/canonical-integrity.json"}}
            write-assurance! (fn [m] (spit assurance (clojure.data.json/write-str m)))
            base {"schema_version" "canonical-integrity.v1"
                  "assurance_kind" "unsigned-canonical-integrity"
                  "run_id" "run-1"
                  "status" "passed"
                  "run_finalization" {"sha256" (get-in artifacts [:run-finalization :sha256])}
                  "runner_finalization" {"sha256" (get-in artifacts [:runner-finalization :sha256])}
                  "evidence_content_registry" {"ref" "evidence/content-registry.json"
                                               "sha256" (str "sha256:" (sha content))}
                  "checks" {"run_finalization_verified" true
                            "runner_finalization_present" true
                            "pre_assurance_registry_valid" true}}]
        (write-assurance! (assoc base "status" "failed"))
        (let [report (#'resolver-sim.run.package-index/validate-canonical-assurance r artifacts)]
          (is (false? (:valid? report)))
          (is (some #(= :canonical-assurance/not-passed (:code %)) (:reasons report))))
        (write-assurance! (assoc-in base ["evidence_content_registry" "sha256"] "sha256:0000000000000000000000000000000000000000000000000000000000000000"))
        (let [report (#'resolver-sim.run.package-index/validate-canonical-assurance r artifacts)]
          (is (false? (:valid? report)))
          (is (some #(= :canonical-assurance/content-registry-hash-mismatch (:code %)) (:reasons report)))))
      (finally (delete-tree! r)))))

(deftest correctly-indexed-malformed-finalizations-remain-semantic-failures
  (let [r (root)]
    (try
      (let [runner (io/file r "execution/runner-finalization.json")
            run-final (io/file r "evidence/finalizations/run/evidence-finalization.json")
            scenario-final (io/file r "forensic/evidence-finalization.json")]
        (doseq [f [runner run-final scenario-final]] (.mkdirs (.getParentFile f)))
        ;; Each file is valid JSON and its exact bytes are indexed. Failure must
        ;; therefore come from the persisted artifact semantics, not closure IO.
        (doseq [f [runner run-final scenario-final]] (spit f "{}"))
        (write-index! r {:runner-finalization (artifact-ref r "execution/runner-finalization.json")
                         :run-finalization (artifact-ref r "evidence/finalizations/run/evidence-finalization.json")
                         :scenario-finalization (artifact-ref r "forensic/evidence-finalization.json")})
        (let [reasons (:reasons (package-index/validate-integrity r))
              runner-reason (first (filter #(= :package/invalid-runner-finalization (:code %)) reasons))
              run-reason (first (filter #(= :package/invalid-run-finalization (:code %)) reasons))
              scenario-reason (first (filter #(= :package/invalid-scenario-finalization (:code %)) reasons))]
          (is (seq (:causes runner-reason)))
          (is (seq (:causes run-reason)))
          (is (seq (:causes scenario-reason)))
          (is (some #{:unsupported-schema-version} (:causes runner-reason)))
          (is (some #{:unsupported-schema-version} (:causes run-reason)))
          (is (some #{:unsupported-schema-version} (:causes scenario-reason)))))
      (finally (delete-tree! r)))))

(deftest package-index-build-retains-the-pre-package-registry-commitments
  (let [index (package-index/build {:run-id "run-1"
                                    :scenario-id "scenario-1"
                                    :execution-id "execution:run-1"
                                    :artifact-registry {:ref "manifest/artifacts.json"}
                                    :registry-validation {:ref "manifest/artifact-registry-validation.json"}})]
    (is (= "manifest/artifacts.json" (get-in index [:artifacts :artifact-registry :ref])))
    (is (= "manifest/artifact-registry-validation.json"
           (get-in index [:artifacts :registry-validation :ref])))))

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
      (write-index! r {:execution-dag (artifact-ref r "execution/execution-dag.json")})
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

(deftest package-predicates-return-booleans-and-do-not-trust-an-unsealed-index
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (write-index! r {:execution-dag (artifact-ref r "execution/execution-dag.json")})
      (let [completion-file (io/file r "completion.json")
            completion (clojure.data.json/read-str (slurp completion-file))]
        ;; Keep the package index parseable but make completion's byte commitment
        ;; false. The resolver must not expose it as a trusted declaration.
        (spit completion-file (clojure.data.json/write-str
                               (assoc completion "run_package_index_bytes" 0)))
        (let [ctx (package-index/resolve-completion-context r)]
          (is (nil? (get-in ctx [:package-index :index])))
          (is (false? (package-index/complete? r)))
          (is (false? (package-index/integrity-valid? r)))
          (is (false? (package-index/runnable? r)))
          (is (every? boolean? [(package-index/complete? r)
                                (package-index/integrity-valid? r)
                                (package-index/runnable? r)]))))
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
      (let [entry (artifact-ref r "execution/execution-dag.json")]
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
        (write-index! r {:execution-dag (artifact-ref r "execution/execution-dag.json")})
        (is (some #(= code (:code %))
                  (:reasons (package-index/validate-integrity r)))
            (str "explicit " field " must not fall back to the correct-looking path")))
      (finally (delete-tree! r)))))

(deftest completion-run-id-must-match-its-sealed-package-index
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (write-index! r {:execution-dag (artifact-ref r "execution/execution-dag.json")})
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
        (write-index! r {:input-snapshot (artifact-ref r "inputs/scenarios/scenario-1.edn")
                         :execution-dag (artifact-ref r "execution/execution-dag.json")})
        (is (some #(= :package/dag-input-hash-mismatch (:code %))
                  (:reasons (package-index/validate-integrity r)))))
      (finally (delete-tree! r)))))

(deftest package-integrity-reports-dag-semantic-causes
  (let [r (root)]
    (try
      (let [bad (assoc (valid-dag) :dag/root-hash "wrong")
            _ (write-dag! r bad)
            entry (artifact-ref r "execution/execution-dag.json")]
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
      (write-index! r {:execution-dag (artifact-ref r "execution/execution-dag.json")})
      (is (nil? (first (filter #(= :package/invalid-execution-dag (:code %))
                               (:reasons (package-index/validate-integrity r))))))
      (finally (delete-tree! r)))))

(deftest package-integrity-rejects-byte-length-mismatch
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (write-index! r {:execution-dag (update (artifact-ref r "execution/execution-dag.json") :bytes inc)})
      (is (some #(= :package/artifact-length-mismatch (:code %))
                (:reasons (package-index/validate-integrity r))))
      (finally (delete-tree! r)))))

(deftest package-integrity-reports-unreadable-indexed-json
  (let [r (root)]
    (try
      (let [f (io/file r "execution/execution-dag.json")]
        (.mkdirs (.getParentFile f))
        (spit f "{")
        (write-index! r {:execution-dag (artifact-ref r "execution/execution-dag.json")})
        (is (some #(= :package/unreadable-artifact (:code %))
                  (:reasons (package-index/validate-integrity r)))))
      (finally (delete-tree! r)))))

(deftest package-integrity-requires-byte-length-for-required-artifacts
  (let [r (root)]
    (try
      (write-dag! r (valid-dag))
      (let [entry (dissoc (artifact-ref r "execution/execution-dag.json") :bytes)]
        (write-index! r {:execution-dag entry})
        (is (some #(= :package/missing-artifact-byte-length (:code %))
                  (:reasons (package-index/validate-integrity r)))))
      (finally (delete-tree! r)))))
