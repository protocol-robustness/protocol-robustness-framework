(ns resolver-sim.benchmark.verify
  "Read-only verification of canonical benchmark terminal commitments."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.conservation :as conservation]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.run.package-index :as package-index]
            [resolver-sim.run.verdict-policy :as verdict-policy]))

(defn- read-json [file] (json/read-str (slurp file)))
(defn- sha-ref [file] (hash-ref/sha256-ref (lifecycle/sha256-file file)))

(defn- input-set-root [inputs]
  (str "sha256:"
       (canonical/domain-hash "BENCHMARK_INPUT_SET_V1"
                              (vec (sort-by #(get % "path")
                                            (map #(select-keys % ["logical_id" "source_kind" "path" "sha256"])
                                                 inputs))))))

(defn- validate-input-set [root inputs]
  (let [root-path (.toPath (io/file root))]
    (every? true?
            (map (fn [entry]
                   (let [path (get entry "path")
                         file (io/file root path)
                         resolved (.normalize (.toPath file))]
                     (and (string? path)
                          (.startsWith resolved root-path)
                          (.isFile file)
                          (= (get entry "sha256") (sha-ref file)))))
                 inputs))))

(defn- registry-sha256-matches? [expected file]
  ;; Artifact registries store the digest value; finalization objects use a
  ;; sha256: reference. Accept the registry's canonical bare form while still
  ;; requiring an exact digest for the file on disk.
  (= expected (lifecycle/sha256-file file)))

(defn- registry-artifacts-valid? [root registry]
  (let [root-path (.toPath (io/file root))]
    (every? true?
            (map (fn [entry]
                   (let [path (when (map? entry) (get entry "path"))]
                     (if-not (string? path)
                       false
                       (let [file (io/file root path)
                             resolved (.normalize (.toPath file))]
                         (and (.startsWith resolved root-path)
                              (.isFile file)
                              (registry-sha256-matches? (get entry "sha256") file))))))
                 (get registry "artifacts" [])))))

(defn- content-registry-valid? [root content-registry]
  ;; Legacy v1 fixtures may contain only an artifact list. Newly emitted v1
  ;; registries carry the complete scope and content-root commitment below.
  (let [artifacts (get content-registry "artifacts" [])
        complete? (every? #(contains? content-registry %)
                          ["domain" "benchmark_id" "run_id" "content_scope"
                           "hash_algorithm" "excluded_paths" "content_root"])]
    (if-not complete?
      true
      (let [paths (map #(get % "path") artifacts)
            root-path (.toPath (io/file root))
            projection {"domain" (get content-registry "domain")
                        "benchmark_id" (get content-registry "benchmark_id")
                        "run_id" (get content-registry "run_id")
                        "artifacts" artifacts
                        "excluded_paths" (vec (sort (get content-registry "excluded_paths" [])))}
            expected-root (str "sha256:" (canonical/domain-hash "BENCHMARK_CONTENT_REGISTRY_V1" projection))]
        (and (= "benchmark-content-registry.v1" (get content-registry "schema_version"))
             (= "prf/benchmark-content-registry/v1" (get content-registry "domain"))
             (= "benchmark-evidence-inner-package" (get content-registry "content_scope"))
             (= "sha256" (get content-registry "hash_algorithm"))
             (= paths (sort paths))
             (= (count paths) (count (set paths)))
             (= expected-root (get content-registry "content_root"))
             (every? (fn [entry]
                       (let [path (get entry "path")
                             file (io/file root path)
                             resolved (.normalize (.toPath file))]
                         (and (string? path) (.startsWith resolved root-path)
                              (.isFile file)
                              (= (get entry "sha256") (sha-ref file))
                              (= (get entry "bytes") (.length file))
                              (string? (get entry "role")))))
                     artifacts))))))

(defn- recalculate-conservation [root artifact]
  (let [expected (get-in artifact ["applicability" "expected_execution_ids"])
        entries (get artifact "executions")
        observed (mapv (fn [entry]
                         (let [file (io/file root (get entry "result_ref"))
                               summary (edn/read-string (slurp file))
                               invariant (some #(when (= :conservation-of-funds (:id %)) %) (:invariant-results summary))]
                           {:execution_id (get entry "execution_id")
                            :result_ref (get entry "result_ref")
                            :result_sha256 (get entry "result_sha256")
                            :status (cond
                                      (nil? invariant) :incomplete
                                      (= :pass (:result invariant)) :pass
                                      :else :fail)
                            :hash-valid? (= (get entry "result_sha256") (sha-ref file))}))
                       entries)
        status (conservation/aggregate-status expected observed)]
    {:status (name status)
     :ids-match? (= (set expected) (set (map :execution_id observed)))
     :hashes-valid? (every? :hash-valid? observed)}))

(defn- verify-execution-closure [root registry]
  (let [plan-file (io/file root "benchmark/execution-plan.edn")
        index-file (io/file root "benchmark/index.edn")
        registry-paths (set (map #(get % "path") (get registry "artifacts" [])))]
    (if-not (every? #(.isFile %) [plan-file index-file])
      {:valid? false :reason :missing-plan-or-index}
      (let [plan (:executions (edn/read-string (slurp plan-file)))
            observed (:executions (edn/read-string (slurp index-file)))
            plan-by-id (into {} (map (juxt :execution/id identity) plan))
            observed-ids (map :execution/id observed)
            planned-ids (map :execution/id plan)
            rows-valid? (every? (fn [entry]
                                  (let [planned (get plan-by-id (:execution/id entry))
                                        directory (:execution/directory planned)
                                        summary (str "benchmark/executions/" directory "/execution-summary.edn")]
                                    (and planned
                                         (= (:execution/descriptor planned) (:execution/descriptor entry))
                                         (.isFile (io/file root summary))
                                         (contains? registry-paths summary))))
                                observed)]
        {:valid? (and (= (count planned-ids) (count (set planned-ids)))
                      (= (set planned-ids) (set observed-ids))
                      (= (count observed-ids) (count (set observed-ids)))
                      rows-valid?)}))))

(defn verify! [run-root]
  (try
    (let [root (io/file run-root)
          completion-file (io/file root paths/completion)
          finalization-file (io/file root "benchmark/finalization.json")
          assurance-file (io/file root "benchmark/assertions/benchmark-assurance.json")
          conservation-file (io/file root "benchmark/assertions/conservation.json")
          registry-file (io/file root paths/artifacts-registry)
          validation-file (io/file root paths/artifacts-validation)
          content-registry-file (io/file root "benchmark/evidence/content-registry.json")
          canonical-integrity-file (io/file root "benchmark/assertions/canonical-integrity.json")
          forensic-status-file (io/file root "benchmark/assertions/forensic-claims-status.json")
          verdict-policy-file (io/file root "manifest/verdict-policy.json")]
      (when-not (every? #(.isFile %) [completion-file finalization-file assurance-file conservation-file registry-file validation-file content-registry-file canonical-integrity-file forensic-status-file verdict-policy-file])
        (throw (ex-info "Benchmark terminal artifact is missing" {:run-root run-root})))
      (let [completion (read-json completion-file)
            finalization (read-json finalization-file)
            assurance (read-json assurance-file)
            conservation (read-json conservation-file)
            registry (read-json registry-file)
            content-registry (try (read-json content-registry-file) (catch Exception _ nil))
            canonical-integrity (read-json canonical-integrity-file)
            forensic-status (read-json forensic-status-file)
            verdict-policy-artifact (read-json verdict-policy-file)
            verdict-policy-verification (verdict-policy/verify! root verdict-policy-artifact "benchmark" (get completion "run_id"))
            package-context (package-index/resolve-completion-context run-root)
            package-closure (when (get-in package-context [:completion-report :valid?])
                              (package-index/validate-benchmark-package-closure
                               run-root (:completion package-context) (get-in package-context [:package-index :index])))
            registry-paths (set (map #(get % "path") (get registry "artifacts" [])))
            execution-closure (verify-execution-closure root registry)
            recalculated-conservation (recalculate-conservation root conservation)
            inputs (get assurance "input_set")
            projection {"domain" "prf/benchmark-finalization/v1"
                        "benchmark_id" (get finalization "benchmark_id")
                        "run_id" (get finalization "run_id")
                        "assurance_artifact_sha256" (sha-ref assurance-file)
                        "conclusion_sha256" (get finalization "conclusion_sha256")
                        "evidence_content_registry_sha256" (sha-ref content-registry-file)
                        "input_set_root" (get assurance "input_set_root")}
            expected-final-ref (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))
            checks {"completion-first-package-index" (and (get-in package-context [:completion-report :valid?])
                                                          (:valid? package-closure))
                    "completion-finalization-hash" (= (get completion "finalization_sha256") (sha-ref finalization-file))
                    "completion-lifecycle" (= "completed" (get completion "lifecycle_status"))
                    "completion-semantic-outcome" (= (get completion "semantic_status") (get-in assurance ["conclusion" "outcome"]))
                    "completion-registry-hash" (= (get completion "artifact_registry_sha256") (sha-ref registry-file))
                    "completion-validation-hash" (= (get completion "registry_validation_sha256") (sha-ref validation-file))
                    "evidence-content-registry-hash" (= (get finalization "evidence_content_registry_sha256") (sha-ref content-registry-file))
                    "content-registry-recalculated" (and content-registry (content-registry-valid? root content-registry))
                    "artifact-registry-recalculated" (registry-artifacts-valid? root registry)
                    "execution-plan-index-closure" (:valid? execution-closure)
                    "input-set-root" (and (= (get assurance "input_set_root") (get finalization "input_set_root"))
                                          (= (get finalization "input_set_root") (get completion "input_set_root")))
                    "input-set-recalculated" (and (vector? inputs)
                                                  (validate-input-set root inputs)
                                                  (= (get assurance "input_set_root") (input-set-root inputs)))
                    "conservation-assurance" (and (= (get-in assurance ["conservation" "artifact_ref"]) "benchmark/assertions/conservation.json")
                                                  (= (get-in assurance ["conservation" "artifact_sha256"]) (sha-ref conservation-file))
                                                  (= (get-in assurance ["conservation" "status"]) (get conservation "status")))
                    "conservation-recalculated" (and (= (get conservation "status") (:status recalculated-conservation))
                                                     (:ids-match? recalculated-conservation)
                                                     (:hashes-valid? recalculated-conservation))
                    "canonical-integrity" (and (= "canonical-integrity.v1" (get canonical-integrity "schema_version"))
                                               (= "passed" (get canonical-integrity "status"))
                                               (= (sha-ref finalization-file) (get-in canonical-integrity ["benchmark_finalization" "sha256"]))
                                               (= (sha-ref assurance-file) (get-in canonical-integrity ["benchmark_assurance" "sha256"]))
                                               (= (sha-ref conservation-file) (get-in canonical-integrity ["conservation" "sha256"]))
                                               (= (sha-ref content-registry-file) (get-in canonical-integrity ["evidence_content_registry" "sha256"])))
                    "forensic-status-deferred" (and (= "forensic-claims-status.v1" (get forensic-status "schema_version"))
                                                    (= "deferred" (get forensic-status "status"))
                                                    (= "unsigned-forensic-signing-not-configured" (get forensic-status "reason_code")))
                    "verdict-policy" (:valid? verdict-policy-verification)
                    "final-ref" (and (= expected-final-ref (get finalization "final_ref"))
                                     (= expected-final-ref (get completion "final_ref")))}]
        {"schema_version" "benchmark-verification.v1"
         "status" (if (every? true? (vals checks)) "passed" "failed")
         "checks" checks
         "final_ref" expected-final-ref}))
    (catch Exception error
      {"schema_version" "benchmark-verification.v1"
       "status" "failed"
       "checks" {"terminal-artifacts-readable" false}
       "error" (.getMessage error)})))
