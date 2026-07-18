(ns resolver-sim.run.canonical-single-scenario-package-test
  "End-to-end canonical single-scenario package assertions."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.scenario-orchestration :as orchestration]
            [resolver-sim.commands.scenario-run :as scenario-run]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
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
              snapshot-sha (get-in index [:artifacts :input-snapshot :sha256])
              dag-file (io/file root (get-in index [:artifacts :execution-dag :ref]))
              dag (json/read-str (slurp dag-file) :key-fn keyword)]
          (is (= (str "sha256:" (lifecycle/sha256-file index-file))
                 (get completion "run_package_index_sha256")))
          (is (= (.length index-file)
                 (get completion "run_package_index_bytes")))
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
