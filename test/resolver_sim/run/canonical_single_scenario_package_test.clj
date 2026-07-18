(ns resolver-sim.run.canonical-single-scenario-package-test
  "End-to-end canonical single-scenario package assertions."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-orchestration :as orchestration]
            [resolver-sim.commands.scenario-run :as scenario-run]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.evidence.finalization :as finalization]
            [resolver-sim.run.package-index :as package-index]))

(defn- temp-root []
  (.toFile (java.nio.file.Files/createTempDirectory
            "canonical-single-scenario-package-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (io/delete-file file true)))

(defn- sha-and-bytes [file]
  {:sha256 (lifecycle/sha256-file file)
   :bytes (.length file)})

(defn- context [root scenario-ref]
  (scenario-run/build-run-context
   {:scenario/ref scenario-ref
    :run/root (.getPath root)
    :report-format :standard
    :sensitivity/profile :internal}
   {:project-root "."}))

(defn- artifact-ref [root path]
  (let [file (io/file root path)]
    {:ref path
     :sha256 (str "sha256:" (lifecycle/sha256-file file))
     :bytes (.length file)}))

(defn- json-key [key]
  (if (keyword? key)
    (if-let [namespace (namespace key)]
      (str namespace "/" (name key))
      (name key))
    (str key)))

(defn- write-json! [file value]
  (spit file (json/write-str value :key-fn json-key)))

(defn- reseal-index-and-completion! [root index]
  (let [index-file (io/file root "manifest/run-package-index.json")
        completion-file (io/file root "completion.json")
        completion (json/read-str (slurp completion-file))]
    (package-index/write! index-file index)
    (spit completion-file
          (json/write-str
           (assoc completion
                  "run_package_index_sha256" (str "sha256:" (lifecycle/sha256-file index-file))
                  "run_package_index_bytes" (.length index-file))))))

(deftest canonical-semantic-pass-produces-a-sealed-runnable-package
  (let [root (temp-root)]
    (try
      (let [result (orchestration/run-scenario!
                    (context root "scenarios/edn/DR-A-001-capacity-exhaustion-grief.edn"))
            index-file (io/file root "manifest/run-package-index.json")
            completion-file (io/file root "completion.json")]
        (is (= :completed (:command/status result)))
        (is (.isFile index-file))
        (is (.isFile completion-file))
        (is (true? (package-index/complete? root)))
        (is (true? (package-index/integrity-valid? root)))
        (is (true? (package-index/runnable? root)))
        (is (true? (package-index/semantic-pass? root)))
        ;; Unsigned canonical assurance proves content integrity only; it is not
        ;; signer/operator authorization for release eligibility.
        (is (false? (package-index/release-eligible? root)))
        (let [completion (json/read-str (slurp completion-file))
              index (json/read-str (slurp index-file) :key-fn keyword)
              summary (json/read-str (slurp (io/file root "manifest/summary.json")))
              snapshot-sha (get-in index [:artifacts :input-snapshot :sha256])
              dag-file (io/file root (get-in index [:artifacts :execution-dag :ref]))
              dag (json/read-str (slurp dag-file) :key-fn keyword)]
          (is (= (str "sha256:" (lifecycle/sha256-file index-file))
                 (get completion "run_package_index_sha256")))
          (is (= (.length index-file)
                 (get completion "run_package_index_bytes")))
          (is (= "scenario-value-at-risk.v1"
                 (get-in summary ["value_at_risk" "schema_version"])))
          (is (= "available" (get-in summary ["value_at_risk" "status"])))
          (is (seq (get-in summary ["value_at_risk" "declared_protected_amount" "by_unit"])))
          ;; The input commitment is from the exact snapshotted scenario bytes,
          ;; carried as a formatted SHA-256 reference into the persisted DAG.
          (is (re-matches #"sha256:[0-9a-f]{64}" snapshot-sha))
          (is (every? #(= snapshot-sha (get-in % [:node/input-hashes :scenario/source-hash]))
                      (:dag/nodes dag))))
        (is (not (.exists (io/file root ".run-state")))))
      (finally (delete-tree! root)))))

(deftest canonical-completion-freezes-authoritative-closure-and-allows-only-cleanup
  (let [root (temp-root)]
    (try
      (let [result (orchestration/run-scenario!
                    (context root "scenarios/edn/DR-A-001-capacity-exhaustion-grief.edn"))
            index-file (io/file root "manifest/run-package-index.json")
            completion-file (io/file root "completion.json")
            index (json/read-str (slurp index-file) :key-fn keyword)
            registry-file (io/file root "manifest/artifacts.json")
            registry (json/read-str (slurp registry-file) :key-fn keyword)
            indexed-files (map #(io/file root (:ref %)) (vals (:artifacts index)))
            authoritative-files (conj (vec indexed-files) index-file completion-file)
            before (into {} (map (fn [file] [(.getPath file) (sha-and-bytes file)]) authoritative-files))]
        (is (= :completed (:command/status result)))
        ;; The final pre-package registry is intentionally non-circular.
        (is (not-any? #{"manifest/run-package-index.json" "completion.json"}
                      (map :path (:artifacts registry))))
        ;; Post-completion package validation is read-only. Repeated validation
        ;; must not rewrite the frozen closure or completion's index binding.
        (is (:valid? (package-index/validate-integrity root)))
        (is (true? (package-index/runnable? root)))
        (is (= before
               (into {} (map (fn [file] [(.getPath file) (sha-and-bytes file)]) authoritative-files))))
        (is (not (.exists (io/file root ".run-state"))))
        (is (true? (package-index/complete? root))))
      (finally (delete-tree! root)))))

(deftest sealed-byte-valid-package-rejects-registry-validation-for-different-registry-bytes
  (let [root (temp-root)]
    (try
      (orchestration/run-scenario!
       (context root "scenarios/edn/DR-A-001-capacity-exhaustion-grief.edn"))
      (let [index-file (io/file root "manifest/run-package-index.json")
            validation-path "manifest/artifact-registry-validation.json"
            validation-file (io/file root validation-path)
            index (json/read-str (slurp index-file) :key-fn keyword)
            validation (json/read-str (slurp validation-file) :key-fn keyword)
            ;; Keep the report well-formed and accepted-looking, but make its
            ;; explicit registry commitment name different bytes. Reseal the
            ;; package index and completion so this is semantic—not byte—damage.
            _ (write-json! validation-file
                           (assoc validation :registry/sha256
                                  "sha256:0000000000000000000000000000000000000000000000000000000000"))
            mutated-index (assoc-in index [:artifacts :registry-validation]
                                    (artifact-ref root validation-path))]
        (reseal-index-and-completion! root mutated-index)
        (let [report (package-index/validate-integrity root)
              failure (first (filter #(= :package/invalid-registry-validation (:code %))
                                     (:reasons report)))]
          (is (false? (:valid? report)))
          (is (false? (package-index/runnable? root)))
          (is (some? failure))
          (is (some #(= :registry-validation/registry-hash-mismatch (:code %))
                    (:causes failure)))
          ;; Completion remains a valid exact-byte seal for the mutated index;
          ;; it cannot turn a semantically inconsistent package into a valid one.
          (is (empty? (:reasons (package-index/resolve-completion-context root))))))
      (finally (delete-tree! root)))))

(deftest sealed-byte-valid-package-rejects-canonical-assurance-wrong-finalization-commitment
  (let [root (temp-root)]
    (try
      (orchestration/run-scenario!
       (context root "scenarios/edn/DR-A-001-capacity-exhaustion-grief.edn"))
      (let [index-file (io/file root "manifest/run-package-index.json")
            assurance-path "manifest/canonical-integrity.json"
            assurance-file (io/file root assurance-path)
            index (json/read-str (slurp index-file) :key-fn keyword)
            assurance (json/read-str (slurp assurance-file) :key-fn keyword)
            wrong-hash "sha256:0000000000000000000000000000000000000000000000000000000000000000"
            ;; The assurance remains schema-shaped and accepted-looking. The
            ;; re-sealed outer bytes are valid; only its finalization commitment
            ;; is contradictory.
            _ (write-json! assurance-file (assoc-in assurance [:run_finalization :sha256] wrong-hash))
            mutated-index (assoc-in index [:artifacts :canonical-assurance]
                                    (artifact-ref root assurance-path))]
        (reseal-index-and-completion! root mutated-index)
        (let [report (package-index/validate-integrity root)
              failure (first (filter #(= :package/invalid-canonical-assurance (:code %))
                                     (:reasons report)))]
          (is (false? (:valid? report)))
          (is (false? (package-index/runnable? root)))
          (is (some? failure))
          (is (some #(= :canonical-assurance/run-finalization-mismatch (:code %))
                    (:causes failure)))
          (is (empty? (:reasons (package-index/resolve-completion-context root))))))
      (finally (delete-tree! root)))))

(deftest sealed-byte-valid-package-rejects-uncommitted-scenario-finalization
  (let [root (temp-root)]
    (try
      (orchestration/run-scenario!
       (context root "scenarios/edn/DR-A-001-capacity-exhaustion-grief.edn"))
      (let [index-file (io/file root "manifest/run-package-index.json")
            index (json/read-str (slurp index-file) :key-fn keyword)
            run-final-path (get-in index [:artifacts :run-finalization :ref])
            run-final-file (io/file root run-final-path)
            run-final (json/read-str (slurp run-final-file) :key-fn keyword)
            wrong-hash "sha256:0000000000000000000000000000000000000000000000000000000000000000"
            changed (assoc-in run-final [:evidence :scenario-finalizations 0 :finalization :sha256] wrong-hash)
            ;; Recompute the run-finalization's own declared member set. This
            ;; keeps its persisted semantic report valid while its member now
            ;; commits to a different scenario-finalization artifact.
            changed (assoc-in changed [:evidence :scenario-finalization-set]
                              (finalization/build-hash-set [wrong-hash]))
            _ (write-json! run-final-file changed)
            mutated-index (assoc-in index [:artifacts :run-finalization]
                                    (artifact-ref root run-final-path))]
        (reseal-index-and-completion! root mutated-index)
        (let [report (package-index/validate-integrity root)
              reconciliation (:reconciliation-report report)]
          (is (false? (:valid? report)))
          (is (true? (get-in report [:run-finalization-report :valid?])))
          (is (false? (:valid? reconciliation)))
          (is (some #(= :package/scenario-finalization-not-committed (:code %))
                    (:reasons reconciliation)))
          (is (some #(= :package/scenario-finalization-not-committed (:code %))
                    (:reasons report)))
          (is (false? (package-index/runnable? root)))
          (is (empty? (:reasons (package-index/resolve-completion-context root))))))
      (finally (delete-tree! root)))))

(deftest canonical-semantic-failure-produces-a-sealed-runnable-package
  (let [root (temp-root)]
    (try
      (let [result (orchestration/run-scenario!
                    (context root "scenarios/edn/DR-N-002-reversal-slash-appeal-rejected.edn"))
            index-file (io/file root "manifest/run-package-index.json")
            completion-file (io/file root "completion.json")]
        ;; The replay reaches an unsuppressed invariant violation, so this is a
        ;; semantic failure rather than an aborted execution or package failure.
        (is (= :completed (:command/status result)))
        (is (= :fail (:scenario/outcome result)))
        (is (= 1 (:exit-code result)))
        (is (.isFile index-file))
        (is (.isFile completion-file))
        (is (true? (package-index/complete? root)))
        (is (true? (package-index/integrity-valid? root)))
        (is (true? (package-index/runnable? root)))
        (is (false? (package-index/semantic-pass? root)))
        (is (false? (package-index/release-eligible? root)))
        (let [completion (json/read-str (slurp completion-file))
              index (json/read-str (slurp index-file) :key-fn keyword)
              runner-file (io/file root (get-in index [:artifacts :runner-finalization :ref]))
              scenario-final-file (io/file root (get-in index [:artifacts :scenario-finalization :ref]))
              runner (json/read-str (slurp runner-file) :key-fn keyword)
              scenario-final (json/read-str (slurp scenario-final-file) :key-fn keyword)]
          ;; A semantic invariant failure is still a completed execution; it is
          ;; neither an abort nor a package-integrity failure.
          (is (= "fail" (get completion "semantic_status")))
          (is (= "completed" (get-in runner [:execution/result :execution/termination])))
          (is (= "fail" (get-in runner [:execution/result :semantic/outcome])))
          (is (= "completed" (get-in scenario-final [:execution :status]))))
        (is (not (.exists (io/file root ".run-state")))))
      (finally (delete-tree! root)))))
