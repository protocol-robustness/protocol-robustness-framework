(ns resolver-sim.commands.run-benchmark
  "Run a benchmark by registered ID or manifest path."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.conservation :as conservation]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.commands.benchmark-conclusion :as conclusion]
            [resolver-sim.commands.benchmark-inventory :as inventory]
            [resolver-sim.commands.benchmark-orchestration :as orchestration]
            [resolver-sim.commands.benchmark-run :as benchmark-run]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.commands.scenario-registry :as registry]
            [resolver-sim.commands.scenario-safety :as safety]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evidence-config]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.run.runner-finalization :as runner-finalization]
            [resolver-sim.run.package-index :as package-index]
            [resolver-sim.run.verdict-policy :as verdict-policy]
            [resolver-sim.forensic.source-hash :as source-hash]
            [resolver-sim.run.distribution-provenance :as distribution]
            [resolver-sim.validation.integration.artifact-registry :as artifact-registry])
  (:import [java.nio.file Files StandardCopyOption]))

(declare sha-ref)

(defn- finalize-registry! [context]
  (inventory/build! context)
  (registry/finalize! (:run/root context)))

(defn- validate-registry! [context]
  (let [registry-file (io/file (str (:manifest/dir context)) "artifacts.json")
        report-file (io/file (str (:manifest/dir context)) "artifacts-validation.json")
        result (artifact-registry/validate-artifact-registry-from-file (.getPath registry-file))]
    (lifecycle/atomic-json! report-file result)
    (when (= :failed (:status result))
      (throw (ex-info "Benchmark artifact registry validation failed" {:registry (.getPath registry-file)})))
    result))

(def ^:private content-registry-exclusions
  #{"benchmark/evidence/content-registry.json"
    "benchmark/finalization.json"
    "benchmark/assertions/canonical-integrity.json"
    "benchmark/assertions/forensic-claims-status.json"
    "manifest/verdict-policy.json"
    paths/artifacts-suffix
    paths/artifacts-validation
    paths/run-package-index
    paths/completion paths/run-state paths/run-lock})

(defn- content-role [path]
  (cond
    (= path "benchmark/definition.edn") "benchmark-definition"
    (= path "benchmark/execution-plan.edn") "execution-plan"
    (= path "manifest/run.json") "run-manifest"
    (= path "benchmark/evidence/evidence.edn") "benchmark-evidence"
    (= path "benchmark/assertions/conservation.json") "conservation-artifact"
    (= path "benchmark/conclusion.json") "conclusion-input"
    (str/starts-with? path "benchmark/executions/") "scenario-evidence"
    :else "other-evidence"))

(defn- write-content-registry! [context]
  (let [root (io/file (str (:run/root context)))
        root-path (.toPath root)
        entries (->> (file-seq root)
                     (filter #(.isFile %))
                     (map (fn [file]
                            (let [path (str (.relativize root-path (.toPath file)))]
                              {"path" path "sha256" (sha-ref file)
                               "bytes" (.length file) "role" (content-role path)})))
                     (remove #(content-registry-exclusions (get % "path")))
                     (sort-by #(get % "path"))
                     vec)
        projection {"domain" "prf/benchmark-content-registry/v1"
                    "benchmark_id" (str (:benchmark/id context))
                    "run_id" (:run/id context)
                    "artifacts" entries
                    "excluded_paths" (vec (sort content-registry-exclusions))}
        value {"schema_version" "benchmark-content-registry.v1"
               "domain" "prf/benchmark-content-registry/v1"
               "benchmark_id" (str (:benchmark/id context))
               "run_id" (:run/id context)
               "content_scope" "benchmark-evidence-inner-package"
               "hash_algorithm" "sha256"
               "excluded_paths" (vec (sort content-registry-exclusions))
               "artifacts" entries
               "content_root" (str "sha256:" (canonical/domain-hash "BENCHMARK_CONTENT_REGISTRY_V1" projection))}
        target (io/file root "benchmark/evidence/content-registry.json")]
    (lifecycle/atomic-json! target value)
    value))

(defn- write-finalization! [context conclusion]
  (let [root (:run/root context)
        content-registry (io/file (str root) "benchmark/evidence/content-registry.json")
        assurance (io/file (str root) "benchmark/assertions/benchmark-assurance.json")
        conclusion-file (io/file (str root) "benchmark/conclusion.json")
        assurance-value (json/read-str (slurp assurance))
        projection {"domain" "prf/benchmark-finalization/v1"
                    "benchmark_id" (str (:benchmark/id context))
                    "run_id" (:run/id context)
                    "assurance_artifact_sha256" (sha-ref assurance)
                    "conclusion_sha256" (sha-ref conclusion-file)
                    "evidence_content_registry_sha256" (sha-ref content-registry)
                    "input_set_root" (get assurance-value "input_set_root")}
        value {"schema_version" "benchmark-finalization.v1"
               "domain" "prf/benchmark-finalization/v1"
               "benchmark_id" (str (:benchmark/id context))
               "run_id" (:run/id context)
               "assurance_artifact" {"ref" "benchmark/assertions/benchmark-assurance.json" "sha256" (sha-ref assurance)}
               "conclusion_sha256" (sha-ref conclusion-file)
               "evidence_content_registry_sha256" (sha-ref content-registry)
               "input_set_root" (get assurance-value "input_set_root")
               "final_ref" (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))}
        target (io/file (str root) "benchmark/finalization.json")]
    (lifecycle/atomic-json! target value)
    value))

(defn- write-canonical-assurance! [context]
  (let [root (io/file (str (:run/root context)))
        finalization (io/file root "benchmark/finalization.json")
        assurance (io/file root "benchmark/assertions/benchmark-assurance.json")
        conservation (io/file root "benchmark/assertions/conservation.json")
        content (io/file root "benchmark/evidence/content-registry.json")
        value {"schema_version" "canonical-integrity.v1"
               "assurance_kind" "unsigned-canonical-integrity"
               "run_id" (:run/id context)
               "benchmark_id" (str (:benchmark/id context))
               "status" "passed"
               "scope" {"content_integrity" true "evidence_reconciliation" true
                        "operator_identity" false "runtime_isolation" false}
               "benchmark_finalization" {"ref" "benchmark/finalization.json" "sha256" (sha-ref finalization)}
               "benchmark_assurance" {"ref" "benchmark/assertions/benchmark-assurance.json" "sha256" (sha-ref assurance)}
               "conservation" {"ref" "benchmark/assertions/conservation.json" "sha256" (sha-ref conservation)}
               "evidence_content_registry" {"ref" "benchmark/evidence/content-registry.json" "sha256" (sha-ref content)}
               "limitations" ["Unsigned assurance does not establish operator identity or signature trust."
                              "Runtime isolation is outside this assurance scope."]}
        deferred {"schema_version" "forensic-claims-status.v1" "run_id" (:run/id context)
                  "status" "deferred" "reason_code" "unsigned-forensic-signing-not-configured"
                  "canonical_integrity_ref" "benchmark/assertions/canonical-integrity.json"}
        target (io/file root "benchmark/assertions/canonical-integrity.json")]
    (lifecycle/atomic-json! target value)
    (lifecycle/atomic-json! (io/file root "benchmark/assertions/forensic-claims-status.json") deferred)
    value))

(defn complete-canonical-benchmark-run-root!
  "Write the irreversible terminal seal for a fully finalized canonical benchmark
   root. All referenced package and registry artifacts must already exist; this
   function deliberately cannot turn a partial root into a completed package."
  [context conclusion]
  (let [root (:run/root context)
        finalization-file (io/file (str root) "benchmark/finalization.json")
        package-index-file (io/file (str root) paths/run-package-index)
        registry (io/file (str root) paths/artifacts-suffix)
        validation (io/file (str root) paths/artifacts-validation)
        required-files [finalization-file package-index-file registry validation]]
    (when-let [missing (first (remove #(.isFile %) required-files))]
      (throw (ex-info "Canonical benchmark root is not ready for completion"
                      {:run-root (str root)
                       :missing-terminal-artifact (.getPath missing)})))
    (let [finalization (json/read-str (slurp finalization-file))]
      (lifecycle/complete!
       root
       {:schema_version "benchmark-completion.v1"
        :benchmark_id (str (:benchmark/id context))
        :run_id (:run/id context)
        :run_type "benchmark"
        :lifecycle_status "completed"
        :semantic_status (get conclusion "outcome")
        :finalization_ref "benchmark/finalization.json"
        :finalization_sha256 (sha-ref finalization-file)
        :final_ref (get finalization "final_ref")
        :run_package_index_ref paths/run-package-index
        :run_package_index_sha256 (sha-ref package-index-file)
        :run_package_index_bytes (.length package-index-file)
        :input_set_root (get finalization "input_set_root")
        :artifact_registry_ref paths/artifacts-suffix
        :artifact_registry_sha256 (str "sha256:" (lifecycle/sha256-file registry))
        :registry_validation_ref paths/artifacts-validation
        :registry_validation_sha256 (str "sha256:" (lifecycle/sha256-file validation))}))))

(defn- write-verdict-policy! [context evidence conclusion]
  (let [root (io/file (str (:run/root context)))
        assurance (json/read-str (slurp (io/file root "benchmark/assertions/benchmark-assurance.json")))
        artifact (verdict-policy/build
                  {:run-id (:run/id context)
                   :run-type "benchmark"
                   :policy-id "canonical-benchmark-verdict.v1"
                   :version-id "verdict-policy.v1"
                   :semantic-outcome (get conclusion "outcome")
                   :inputs (get assurance "input_set")
                   :registries {"evidence_policy_hash" "benchmark-evidence-policy.v1"
                                "claim_definition_registry_hash" "benchmark-claim-registry.v1"
                                "evaluator_registry" "resolver-sim.benchmark.claims/evaluator-registry.v1"}
                   :semantic-environment {"protocol_id" "benchmark"
                                          "runner_id" "runner/local-clojure"
                                          "benchmark_id" (str (:benchmark/id context))
                                          "execution_plan_sha256" (verdict-policy/sha-ref (io/file root "benchmark/execution-plan.edn"))}
                   :evaluator-implementation (let [source (source-hash/source-hash)]
                                               {"source_tree_hash" (str (or (:source/hash source) "unavailable"))
                                                "source_tree_hash_algorithm" (str (or (:source/hash-algorithm source) source-hash/source-tree-hash-algorithm))
                                                "source_roots" (vec (or (:source/included-roots source) []))
                                                "evaluator_id" "resolver-sim.benchmark.claims/evaluator-registry.v1"})
                   :distribution-provenance (distribution/distribution-identity)})]
    (verdict-policy/write! (io/file root "manifest/verdict-policy.json") artifact)))

(defn- write-package-index! [context]
  (let [root (io/file (str (:run/root context)))
        ref (fn [path]
              (let [file (io/file root path)]
                {:ref path :sha256 (when (.isFile file) (sha-ref file))}))]
    (package-index/write!
     (io/file root paths/run-package-index)
     {:run-id (:run/id context)
      :run-type :benchmark
      :bundle-root-hash (sha-ref (io/file root "benchmark/evidence/evidence.edn"))
      :artifacts {:runner-finalization (ref "benchmark/execution/runner-finalization.json")
                  :benchmark-definition (ref "benchmark/definition.edn")
                  :execution-plan (ref "benchmark/execution-plan.edn")
                  :benchmark-index (ref "benchmark/index.edn")
                  :benchmark-evidence (ref "benchmark/evidence/evidence.edn")
                  :content-registry (ref "benchmark/evidence/content-registry.json")
                  :benchmark-conclusion (ref "benchmark/conclusion.json")
                  :benchmark-conservation (ref "benchmark/assertions/conservation.json")
                  :benchmark-finalization (ref "benchmark/finalization.json")
                  :benchmark-assurance (ref "benchmark/assertions/benchmark-assurance.json")
                  :canonical-integrity (ref "benchmark/assertions/canonical-integrity.json")
                  :verdict-policy (ref "manifest/verdict-policy.json")
                  :forensic-status (ref "benchmark/assertions/forensic-claims-status.json")}})))

(defn- invoke! [benchmark-id {:keys [output key scenario-output-dir benchmark-index-path execution-plan-path]}]
  (let [benchmark-runner (requiring-resolve 'resolver-sim.benchmark.cli/run-and-report)
        write-evidence (requiring-resolve 'resolver-sim.benchmark.runner/write-evidence)
        benchmark-artifact-dir (some-> output io/file .getParent)
        result (binding [chain/*allow-dirty* true
                         evidence-config/*artifact-dir* benchmark-artifact-dir]
                 (benchmark-runner benchmark-id {:output output
                                                 :key key
                                                 :scenario-output-dir scenario-output-dir
                                                 :benchmark-index-path benchmark-index-path
                                                 :execution-plan-path execution-plan-path}))]
    (when-let [evidence (:evidence result)]
      (write-evidence evidence output))
    result))

(defn- finalize-runner! [context execution]
  (let [evidence-file (:benchmark/evidence-file context)
        artifact (runner-finalization/build
                  {:run-id (:run/id context)
                   :runner-selection {:mode :pinned :runner-id :runner/local-clojure}
                   :execution-result {:execution/termination :completed
                                      :semantic/outcome (if (zero? (:exit-code execution)) :pass :fail)
                                      :cli/exit-code (:exit-code execution)
                                      :bundle/root-hash (lifecycle/sha256-file evidence-file)}})]
    (runner-finalization/write!
     (io/file (str (:run/root context)) "benchmark/execution/runner-finalization.json")
     artifact)))

(defn- write-run-manifest! [context evidence]
  (let [target (io/file (str (:manifest/dir context)) "run.json")
        temp (io/file (str (.getPath target) ".tmp"))
        value {"schema_version" "benchmark-run-manifest.v1"
               "run" {"id" (:run/id context) "type" "benchmark"
                      "sensitivity_profile" (name (:sensitivity/profile context))}
               "benchmark" {"id" (str (get-in evidence [:benchmark :benchmark/id]))
                            "manifest_source" (get-in evidence [:run/manifest :benchmark/manifest-source])}
               "layout" {"definition" "benchmark/definition.edn"
                         "execution_plan" "benchmark/execution-plan.edn"
                         "index" "benchmark/index.edn"
                         "evidence" "benchmark/evidence/evidence.edn"
                         "summary" "benchmark/summary.json"
                         "conclusion" "benchmark/conclusion.json"}}]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str value))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    value))

(defn- scan-sensitivity! [context]
  (let [result (if (= :public (:sensitivity/profile context))
                 (safety/scan-public-bundle! (:run/root context))
                 (safety/scan-internal-bundle! (:run/root context)))]
    (safety/write-sensitivity-report! (:manifest/dir context) result)))

(defn- snapshot-definition! [context evidence]
  (let [ref (get-in evidence [:run/manifest :benchmark/manifest-source])]
    (when-not ref (throw (ex-info "Benchmark evidence omitted manifest source" {})))
    (lifecycle/snapshot-input! (:run/root context) (input-source/source ref) (:benchmark/definition-file context))))

(defn- write-conservation! [context evidence]
  (let [root (.toPath (io/file (str (:run/root context))))
        results (:results evidence)
        entry (fn [result]
                (let [execution-id (:execution/id result)
                      invariant (some #(when (= :conservation-of-funds (:id %)) %) (:invariant-results result))
                      summary-path (get-in result [:scenario/artifacts :scenario/summary])
                      relative (when summary-path
                                 (str (.relativize root (.toPath (io/file summary-path)))))]
                  {:execution_id execution-id
                   :result_ref relative
                   :result_sha256 (when summary-path (str "sha256:" (lifecycle/sha256-file summary-path)))
                   :invariant_id "conservation-of-funds"
                   :status (cond
                             (nil? invariant) :incomplete
                             (= :pass (:result invariant)) :pass
                             :else :fail)}))
        artifact (conservation/project {:benchmark-id (str (get-in evidence [:benchmark :benchmark/id]))
                                        :run-id (:run/id context)
                                        :expected-execution-ids (mapv :execution/id results)
                                        :executions (mapv entry results)})
        target (io/file (str (:run/root context)) "benchmark/assertions/conservation.json")]
    (lifecycle/atomic-json! target artifact)
    artifact))

(defn- sha-ref [file]
  (str "sha256:" (lifecycle/sha256-file file)))

(defn- input-set-root [inputs]
  (str "sha256:"
       (canonical/domain-hash "BENCHMARK_INPUT_SET_V1"
                              (vec (sort-by #(get % "path")
                                            (map #(select-keys % ["logical_id" "source_kind" "path" "sha256"])
                                                 inputs))))))

(defn- input-set [context evidence]
  (let [root (.toPath (io/file (str (:run/root context))))
        relative-path (fn [file]
                        (str (.relativize root (.toPath (io/file file)))))
        input-entry (fn [result]
                      (let [file (get-in result [:scenario/artifacts :scenario/input-path])]
                        (when-not file
                          (throw (ex-info "Benchmark execution omitted its input snapshot"
                                          {:execution/id (:execution/id result)})))
                        {"logical_id" (str "execution/" (:execution/id result) "/scenario-input")
                         "source_kind" "execution-input-snapshot"
                         "path" (relative-path file)
                         "sha256" (sha-ref file)}))]
    (vec (concat [{"logical_id" "benchmark-definition"
                   "source_kind" "benchmark-definition-snapshot"
                   "path" "benchmark/definition.edn"
                   "sha256" (sha-ref (:benchmark/definition-file context))}
                  {"logical_id" "benchmark-execution-plan"
                   "source_kind" "execution-plan"
                   "path" "benchmark/execution-plan.edn"
                   "sha256" (sha-ref (:benchmark/plan-file context))}]
                 (map input-entry (:results evidence))))))

(defn- write-assurance! [context evidence conclusion]
  (let [root (:run/root context)
        refs [{"role" "benchmark-definition" "path" "benchmark/definition.edn" "file" (:benchmark/definition-file context)}
              {"role" "execution-plan" "path" "benchmark/execution-plan.edn" "file" (:benchmark/plan-file context)}
              {"role" "benchmark-conclusion" "path" "benchmark/conclusion.json" "file" (:benchmark/conclusion-file context)}
              {"role" "benchmark-conservation" "path" "benchmark/assertions/conservation.json"
               "file" (io/file (str root) "benchmark/assertions/conservation.json")}]
        assertions (mapv (fn [{:strs [role path file]}]
                           {"role" role "path" path "sha256" (sha-ref file) "status" "present-and-valid"}) refs)
        conservation (last assertions)
        inputs (input-set context evidence)
        value {"schema_version" "benchmark-assurance.v1"
               "benchmark_id" (str (:benchmark/id context))
               "run_id" (:run/id context)
               "lifecycle_status" "completed"
               "benchmark_definition" (first assertions)
               "execution_plan" (second assertions)
               "conclusion" (assoc (nth assertions 2) "outcome" (get conclusion "outcome"))
               "conservation" {"status" (get (clojure.data.json/read-str (slurp (io/file (str root) "benchmark/assertions/conservation.json"))) "status")
                               "artifact_ref" "benchmark/assertions/conservation.json"
                               "artifact_sha256" (get conservation "sha256")
                               "required_by_claims" (->> (get-in evidence [:benchmark :benchmark/claims])
                                                         (map #(if (map? %) (:claim/id %) %))
                                                         (filter #{:claim/funds-conserved :claim/slashing-conservation :claim/no-unauthorized-release})
                                                         (mapv name))}
               "input_set" inputs
               "input_set_root" (input-set-root inputs)
               "required_artifact_assertions" assertions}
        target (io/file (str root) "benchmark/assertions/benchmark-assurance.json")]
    (lifecycle/atomic-json! target value)
    value))

(defn- write-summary! [context evidence conclusion]
  (let [target (io/file (str (:benchmark/summary-file context)))
        temp (io/file (str (.getPath target) ".tmp"))
        bm-def (get evidence :benchmark {})
        suite-provider (get bm-def :benchmark/suite-provider)
        value {"schema_version" "benchmark-summary.v1"
               "run_id" (:run/id context)
               "benchmark_id" (str (get-in evidence [:benchmark :benchmark/id]))
               "benchmark_owner" "prf-core"
               "suite_provider" (when suite-provider
                                  (str (:provider/id suite-provider)))
               "suite_id" (when suite-provider
                            (str (:suite/id suite-provider)))
               "conclusion" (select-keys conclusion ["outcome" "reason"])
               "metrics" (:metrics evidence)
               "execution_count" (count (:results evidence))
               "index_ref" "benchmark/index.edn"}]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str value))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    value))

(defn run-with-root!
  "Run a canonical benchmark root. Optional overrides replace phase functions
   for integration failure testing without bypassing root ownership/locking."
  [benchmark-id run-root key sensitivity-profile overrides]
  (let [context (assoc (benchmark-run/build-run-context benchmark-id run-root ".")
                       :sensitivity/profile sensitivity-profile)
        lock (lifecycle/acquire-run-lock! (:run/root context) (:run/id context) :benchmark)]
    (try
      (benchmark-run/initialize! context)
      (let [benchmark-conclusion (atom nil)
            {:keys [execution]} (orchestration/run!
                                 context
                                 (merge
                                  {:execute (fn [_]
                                              (let [result (invoke! benchmark-id {:output (str (:benchmark/evidence-file context))
                                                                                  :key key
                                                                                  :scenario-output-dir (str (:benchmark/executions-dir context))
                                                                                  :benchmark-index-path (str (:benchmark/index-file context))
                                                                                  :execution-plan-path (str (:benchmark/plan-file context))})]
                                                (when-not (:evidence result)
                                                  (throw (ex-info "Benchmark execution produced no evidence; finalization aborted"
                                                                  {:benchmark benchmark-id :exit-code (:exit-code result)})))
                                                result))
                                   :finalize-runner (fn [_ result] (finalize-runner! context result))
                                   :write-manifest (fn [_ result] (write-run-manifest! context (:evidence result)))
                                   :snapshot-definition (fn [_ result] (snapshot-definition! context (:evidence result)))
                                   :write-conclusion (fn [_ result]
                                                       (write-conservation! context (:evidence result))
                                                       (reset! benchmark-conclusion (conclusion/write! context (:evidence result))))
                                   :write-summary (fn [_ result]
                                                    (write-assurance! context (:evidence result) @benchmark-conclusion)
                                                    (write-summary! context (:evidence result) @benchmark-conclusion))
                                   :scan-sensitivity (fn [_ _] (scan-sensitivity! context))
                                   :write-content-registry (fn [_ _] (write-content-registry! context))
                                   :write-finalization (fn [_ _] (write-finalization! context @benchmark-conclusion))
                                   :write-canonical-assurance (fn [_ _] (write-canonical-assurance! context))
                                   :write-verdict-policy (fn [_ result] (write-verdict-policy! context (:evidence result) @benchmark-conclusion))
                                   :write-package-index (fn [_ _] (write-package-index! context))
                                   :build-inventory (fn [_ _] (inventory/build! context))
                                   :finalize-registry (fn [_ _] (registry/finalize! (:run/root context)))
                                   :validate-registry (fn [_ _] (validate-registry! context))
                                   :complete (fn [_ _] (complete-canonical-benchmark-run-root! context @benchmark-conclusion))} overrides))]
        {:exit-code (or (:exit-code execution) 1) :run/id (:run/id context) :run/root (str (:run/root context))})
      (finally (lifecycle/release-run-lock! lock)))))

(defn- external-manifest-ref? [benchmark-id]
  (and (string? benchmark-id)
       (str/ends-with? benchmark-id ".edn")
       (not (str/starts-with? benchmark-id "classpath:"))
       (not (str/starts-with? benchmark-id "resource:"))))

(defn run
  "Run a benchmark. `--run-root` creates a canonical benchmark-owned bundle;
   `--output` remains the legacy standalone evidence export destination."
  [{:keys [output key run-root sensitivity-profile] :as opts}]
  (let [benchmark-id (or (first (:cmd/args opts))
                         (:benchmark-id opts)
                         (:benchmark opts))]
    (cond
      (nil? benchmark-id)
      {:exit-code 2 :message "Usage: prf-runner-sew.jar run-benchmark <benchmark-id> --run-root DIR"}

      (not (contains? #{nil :public :internal} sensitivity-profile))
      {:exit-code 2 :message "Sensitivity profile must be public or internal"}

      (and run-root output)
      {:exit-code 2 :message "Use --run-root for the canonical benchmark bundle; --output is a separate legacy export command"}

      (and run-root (external-manifest-ref? benchmark-id))
      {:exit-code 2
       :message "Filesystem benchmark manifests are not supported for canonical bundles yet; use a registered benchmark ID or bundled classpath: manifest"}

      run-root
      (run-with-root! benchmark-id run-root key (or sensitivity-profile :public) {})

      output
      (let [result (invoke! benchmark-id {:output output :key key})]
        {:exit-code (or (:exit-code result) 1)})

      :else
      {:exit-code 2 :message "Specify --run-root for a canonical benchmark bundle or --output for a legacy export"})))
