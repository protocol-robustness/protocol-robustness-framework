(ns resolver-sim.commands.run-benchmark
  "Run a benchmark by registered ID or manifest path."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.conservation :as conservation]
            [resolver-sim.benchmark.adapter :as adapter]
            [resolver-sim.benchmark.cli :as benchmark-cli]
            [resolver-sim.benchmark.hardening :as hardening]
            [resolver-sim.benchmark.claim-registry :as claim-registry]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.commands.benchmark-conclusion :as conclusion]
            [resolver-sim.commands.benchmark-inventory :as inventory]
            [resolver-sim.commands.benchmark-orchestration :as orchestration]
            [resolver-sim.commands.benchmark-run :as benchmark-run]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.commands.scenario-registry :as registry]
            [resolver-sim.commands.scenario-safety :as safety]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evidence-config]
            [resolver-sim.evidence.node :as evidence-node]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.io.resource-path :as resource-path]
            [resolver-sim.run.runner-finalization :as runner-finalization]
            [resolver-sim.run.package-index :as package-index]
            [resolver-sim.run.verdict-policy :as verdict-policy]
            [resolver-sim.forensic.source-hash :as source-hash]
            [resolver-sim.run.distribution-provenance :as distribution]
            [resolver-sim.validation.integration.artifact-registry :as artifact-registry]
            [resolver-sim.commands.witness-build :as witness-build]
            [resolver-sim.assurance.witness-verifier :as wv]
            [resolver-sim.util.thread-quiescence :as quiesce])
  (:import [java.nio.file Files StandardCopyOption]))

(declare sha-ref)
(declare claim-registry-input)

(def ^:dynamic ^:private *claim-registry-path*
  "Bound during a benchmark run to the explicitly selected claim registry path
   (CLI/env). Nil means use the standard CLI→env→default resolution."
  nil)

(defmacro with-claim-registry
  "Run body with the claim registry selection bound, so every phase (including
   evidence provenance writers) can see which registry governed the run."
  [claim-registry-path & body]
  `(binding [*claim-registry-path* ~claim-registry-path]
     ~@body))

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
    paths/artifacts-registry
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
                            (let [path (str (.relativize root-path (.toPath file)))
                                  content (evidence-node/canonical-artifact-content path file)]
                              {"path" path "sha256" (:sha256 content)
                               "bytes" (:bytes content) "role" (content-role path)})))
                     (remove #(content-registry-exclusions (get % "path")))
                     (sort-by #(get % "path"))
                     vec)
        projection {"domain" "prf/benchmark-content-registry/v1"
                    "benchmark_id" (str (:benchmark/id context))
                    "artifacts" entries
                    "excluded_paths" (vec (sort content-registry-exclusions))}
        value {"schema_version" "benchmark-content-registry.v1"
               "domain" "prf/benchmark-content-registry/v1"
               "benchmark_id" (str (:benchmark/id context))
               "content_scope" "benchmark-evidence-inner-package"
               "hash_algorithm" "sha256"
               "excluded_paths" (vec (sort content-registry-exclusions))
               "artifacts" entries
               "content_root" (hash-ref/sha256-ref (canonical/domain-hash "BENCHMARK_CONTENT_REGISTRY_V1" projection))}
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
                    "assurance_artifact_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/assertions/benchmark-assurance.json" assurance))
                    "conclusion_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/conclusion.json" conclusion-file))
                    "evidence_content_registry_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/evidence/content-registry.json" content-registry))
                    "input_set_root" (get assurance-value "input_set_root")}
        value {"schema_version" "benchmark-finalization.v1"
               "domain" "prf/benchmark-finalization/v1"
               "benchmark_id" (str (:benchmark/id context))
               "run_id" (:run/id context)
               "assurance_artifact" {"ref" "benchmark/assertions/benchmark-assurance.json" "sha256" (sha-ref assurance)}
               "conclusion_sha256" (sha-ref conclusion-file)
               "evidence_content_registry_sha256" (sha-ref content-registry)
               "input_set_root" (get assurance-value "input_set_root")
               "final_ref" (hash-ref/sha256-ref (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))}
        target (io/file (str root) "benchmark/finalization.json")]
    (lifecycle/atomic-json! target value)
    value))

(defn- write-canonical-assurance! [context]
  (let [root (io/file (str (:run/root context)))
        finalization (io/file root "benchmark/finalization.json")
        assurance (io/file root "benchmark/assertions/benchmark-assurance.json")
        conservation (io/file root "benchmark/assertions/conservation.json")
        content (io/file root "benchmark/evidence/content-registry.json")
        witness-path (io/file root "manifest/execution-witness.json")
        run-root-str (str root)
        ts-root (witness-build/configured-root run-root-str)
        witness-exists? (.isFile witness-path)
        ws-required (witness-build/witness-requirement (some? ts-root) witness-exists?)

        ;; Run witness verification when configured and present
        witness-result (case ws-required
                         :required-present
                         (try
                           (witness-build/canonical-witness-verification run-root-str)
                           (catch Exception e
                             {:valid? false
                              :checks [{:check/code :execution-witness/error
                                        :check/status :fail
                                        :check/detail (.getMessage e)}]
                              :pass-count 0 :fail-count 1}))

                         :required-missing
                         {:valid? false
                          :checks [{:check/code :execution-witness/configured-missing
                                    :check/status :fail
                                    :check/detail "Trust-sequence configured but execution witness not found"}]
                          :pass-count 0 :fail-count 1}

                         nil)

        ;; Structured scope from witness verification
        witness-scope (when witness-result
                        (let [cs (:checks witness-result)
                              witness-def-root (try
                                                 (-> (json/read-str (slurp witness-path) :key-fn keyword)
                                                     :procedure-execution-witness/definition-root)
                                                 (catch Exception _ nil))]
                          {:execution-witness
                           {:status (if (:valid? witness-result) :verified :invalid)
                            :check-count (:pass-count witness-result 0)
                            :fail-count (:fail-count witness-result 0)
                            :not-run-count (:not-run-count witness-result 0)
                            :definition
                            {:status (if (some #(= :pass (:check/status %))
                                               (filter #(= :procedure-witness/definition-root-matches (:check/code %)) cs))
                                       :pass :fail)
                             :definition-root witness-def-root}
                            :evidence-chain
                            {:status (if (and (some #(= :evidence-chain/registry-hash-valid (:check/code %))
                                                    (filter #(= :pass (:check/status %)) cs))
                                              (some #(= :evidence-chain/chain-valid (:check/code %))
                                                    (filter #(= :pass (:check/status %)) cs)))
                                       :pass :fail)
                             :registry-verified (some #(= :evidence-chain/registry-hash-valid (:check/code %))
                                                      (filter #(= :pass (:check/status %)) cs))
                             :chain-verified (some #(= :evidence-chain/chain-valid (:check/code %))
                                                   (filter #(= :pass (:check/status %)) cs))
                             :selected-step-count (count (filter #(= :procedure-witness/evidence-resolved (:check/code %)) cs))}
                            :correlation
                            {:status (cond
                                       (and (some #(= :procedure-witness/correlation-internally-consistent (:check/code %))
                                                  (filter #(= :pass (:check/status %)) cs))
                                            (some #(= :procedure-witness/correlation-matches-planned-instance (:check/code %))
                                                  (filter #(= :pass (:check/status %)) cs))) :pass
                                       (some #(= :not-run (:check/status %))
                                             (filter #(= :procedure-witness/correlation-matches-planned-instance (:check/code %)) cs)) :not-verified
                                       :else :fail)
                             :internally-consistent (some #(= :procedure-witness/correlation-internally-consistent (:check/code %))
                                                          (filter #(= :pass (:check/status %)) cs))
                             :matches-expected (some #(= :procedure-witness/correlation-matches-planned-instance (:check/code %))
                                                     (filter #(= :pass (:check/status %)) cs))}
                            :result-binding
                            {:status (if (and (some #(= :procedure-witness/initial-input-matches (:check/code %))
                                                    (filter #(= :pass (:check/status %)) cs))
                                              (some #(= :procedure-witness/final-output-matches-result (:check/code %))
                                                    (filter #(= :pass (:check/status %)) cs)))
                                       :pass :fail)}
                            :runtime-policy-binding
                            {:status :not-verified
                             :reason :operative-policy-not-content-addressed}}}))

        assurance-status (case ws-required
                           :not-required "passed"
                           (:required-present :unexpected-present)
                           (if (:valid? witness-result) "passed" "failed")
                           :required-missing "failed")

        scope-base {"content_integrity" true "evidence_reconciliation" true
                    "operator_identity" false "runtime_isolation" false}
        scope (if witness-scope (assoc scope-base "execution_witness" witness-scope) scope-base)

        limitations ["Unsigned assurance does not establish operator identity or signature trust."
                     "Runtime isolation is outside this assurance scope."
                     (when ts-root "Runtime policy binding is code-backed, not content-addressed.")]
        limitations (remove nil? limitations)

        value (merge {"schema_version" "canonical-integrity.v1"
                      "assurance_kind" "unsigned-canonical-integrity"
                      "run_id" (:run/id context)
                      "benchmark_id" (str (:benchmark/id context))
                      "status" assurance-status
                      "scope" scope
                      "benchmark_finalization" {"ref" "benchmark/finalization.json" "sha256" (sha-ref finalization)}
                      "benchmark_assurance" {"ref" "benchmark/assertions/benchmark-assurance.json" "sha256" (sha-ref assurance)}
                      "conservation" {"ref" "benchmark/assertions/conservation.json" "sha256" (sha-ref conservation)}
                      "evidence_content_registry" {"ref" "benchmark/evidence/content-registry.json" "sha256" (sha-ref content)}
                      "limitations" limitations}
                     (when (and ts-root (.isFile witness-path))
                       {"execution_witness_ref" "manifest/execution-witness.json"
                        "execution_witness_sha256" (sha-ref witness-path)}))
        deferred {"schema_version" "forensic-claims-status.v1" "run_id" (:run/id context)
                  "status" "deferred" "reason_code" "unsigned-forensic-signing-not-configured"
                  "canonical_integrity_ref" "benchmark/assertions/canonical-integrity.json"}
        target (io/file root "benchmark/assertions/canonical-integrity.json")]
    (lifecycle/atomic-json! target value)
    (lifecycle/atomic-json! (io/file root "benchmark/assertions/forensic-claims-status.json") deferred)
    value))

(defn- closure-commitment
  "Stable commitment to the checked one-round closure assertion persisted in
   benchmark evidence. This binds the terminal seal to canonical work closure,
   not merely to the fact that finalization files happened to be written."
  [closure]
  (hash-ref/sha256-ref
   (canonical/hash-with-intent {:hash/intent :evidence-content} closure)))

(defn complete-canonical-benchmark-run-root!
  "Write the irreversible terminal seal for a fully finalized canonical benchmark
   root. All referenced package and registry artifacts must already exist; this
   function deliberately cannot turn a partial root into a completed package."
  [context conclusion]
  (let [root (:run/root context)
        finalization-file (io/file (str root) "benchmark/finalization.json")
        package-index-file (io/file (str root) paths/run-package-index)
        registry (io/file (str root) paths/artifacts-registry)
        validation (io/file (str root) paths/artifacts-validation)
        evidence-file (io/file (str root) "benchmark/evidence/evidence.edn")
        content-registry-file (io/file (str root) "benchmark/evidence/content-registry.json")
        required-files [finalization-file package-index-file registry validation evidence-file content-registry-file]]
    (when-let [missing (first (remove #(.isFile %) required-files))]
      (throw (ex-info "Canonical benchmark root is not ready for completion"
                      {:run-root (str root)
                       :missing-terminal-artifact (.getPath missing)})))
    (let [finalization (json/read-str (slurp finalization-file))
          evidence (edn/read-string (slurp evidence-file))
          content-registry (json/read-str (slurp content-registry-file))
          closure (:benchmark/execution-closure evidence)]
      (lifecycle/complete!
       root
       {:schema_version "benchmark-completion.v1"
        :benchmark_id (str (:benchmark/id context))
        :run_id (:run/id context)
        :run_type "benchmark"
        :lifecycle_status "completed"
        :semantic_status (get conclusion "outcome")
        ;; Direct terminal bindings supplement the package-index and artifact
        ;; registry closure. A copied completion marker or post-seal mutation
        ;; must fail before the package can be treated as canonical.
        :bundle_root_hash (:evidence/hash evidence)
        :artifact_set_root (get content-registry "content_root")
        :closure_commitment (closure-commitment closure)
        :finalization_ref "benchmark/finalization.json"
        :finalization_sha256 (sha-ref finalization-file)
        :final_ref (get finalization "final_ref")
        :run_package_index_ref paths/run-package-index
        :run_package_index_sha256 (sha-ref package-index-file)
        :run_package_index_bytes (.length package-index-file)
        :input_set_root (get finalization "input_set_root")
        :artifact_registry_ref paths/artifacts-registry
        :artifact_registry_sha256 (hash-ref/sha256-ref (lifecycle/sha256-file registry))
        :registry_validation_ref paths/artifacts-validation
        :registry_validation_sha256 (hash-ref/sha256-ref (lifecycle/sha256-file validation))}))))

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
                                "claim_registry_selection" (claim-registry-input context)
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

(defn- write-package-index! [context execution]
  (let [root (io/file (str (:run/root context)))
        ref (fn [path]
              (let [file (io/file root path)]
                {:ref path :sha256 (when (.isFile file) (sha-ref file))}))
        run-root-str (str root)
        ts-root (witness-build/configured-root run-root-str)
        witness-exists? (.isFile (io/file root "manifest/execution-witness.json"))
        ws-required (witness-build/witness-requirement (some? ts-root) witness-exists?)
        witness-artifacts (case ws-required
                            :required-present
                            {:execution-witness (ref "manifest/execution-witness.json")}
                            :required-missing
                            (throw (ex-info "Package index: trust-sequence configured but execution witness missing"
                                            {:trust-sequence-definition-root ts-root}))
                            :unexpected-present
                            (throw (ex-info "Package index: execution witness present but no trust-sequence configured"
                                            {}))
                            :not-required
                            {})]
    (package-index/write!
     (io/file root paths/run-package-index)
     {:run-id (:run/id context)
      :run-type :benchmark
      ;; :bundle-root-hash is the SEMANTIC, reproducible bundle root
      ;; (:evidence/hash). The transport checksum of the persisted evidence
      ;; file lives in the :benchmark-evidence artifact's :sha256 (and in
      ;; runner-finalization :evidence-file-sha256); it is NOT the benchmark
      ;; outcome identity and is not expected to match across original and
      ;; reproduced runs.
      :bundle-root-hash (get-in execution [:evidence :evidence/hash])
      :artifacts (merge {:runner-finalization (ref "benchmark/execution/runner-finalization.json")
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
                         :forensic-status (ref "benchmark/assertions/forensic-claims-status.json")}
                        witness-artifacts)})))

(defn- invoke! [benchmark-id {:keys [output key scenario-output-dir benchmark-index-path execution-plan-path
                                     parallelism chunk-size claimant-parallelism claimant-parallel-threshold
                                     budget quiescence-timeout-seconds]}]
  (let [benchmark-runner (requiring-resolve 'resolver-sim.benchmark.cli/run-and-report)
        write-evidence (requiring-resolve 'resolver-sim.benchmark.runner/write-evidence)
        benchmark-artifact-dir (some-> output io/file .getParent)
        result (binding [chain/*allow-dirty* true
                         evidence-config/*artifact-dir* benchmark-artifact-dir]
                 (benchmark-runner benchmark-id {:output output
                                                 :key key
                                                 :scenario-output-dir scenario-output-dir
                                                 :benchmark-index-path benchmark-index-path
                                                 :execution-plan-path execution-plan-path
                                                 :parallelism parallelism
                                                 :chunk-size chunk-size
                                                 :execution/claimant-parallelism claimant-parallelism
                                                 :execution/claimant-parallel-threshold claimant-parallel-threshold
                                                 :execution/budget budget
                                                 :execution/quiescence-timeout-seconds quiescence-timeout-seconds}))]
    (when-let [evidence (:evidence result)]
      (write-evidence evidence output))
    result))

(defn- finalize-runner! [context execution]
  (let [evidence-file (:benchmark/evidence-file context)
        ;; Two-commitment model: :bundle/root-hash is the SEMANTIC, reproducible
        ;; bundle root (:evidence/hash), while :evidence-file-sha256 is the
        ;; exact-instance TRANSPORT checksum of the persisted evidence.edn file.
        ;; The transport checksum is NOT the benchmark outcome identity and is
        ;; not expected to match across an original and reproduced run.
        semantic-root (get-in execution [:evidence :evidence/hash])
        artifact (runner-finalization/build
                  {:run-id (:run/id context)
                   :runner-selection {:mode :pinned :runner-id :runner/local-clojure}
                   :execution-result {:execution/termination :completed
                                      :semantic/outcome (if (zero? (:exit-code execution)) :pass :fail)
                                      :bundle/root-hash semantic-root
                                      :evidence-file-sha256 (lifecycle/sha256-file evidence-file)}})]
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
    (spit temp (json/write-str value :indent true))
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

(defn- snapshot-claim-registry!
  "Materialize the claim registry selected for this run into the bundle at the
   exact relative path committed in the input set. Without this the input-set
   and verdict-policy verifiers (which require every committed input to exist
   under the run root) can never validate the registry entry."
  [context]
  (let [cli-path (or (:claim-registry/path context) *claim-registry-path*)
        path (claim-registry/claim-registry-path cli-path)
        source (input-source/source path)
        target (io/file (str (:run/root context)) path)]
    (lifecycle/snapshot-input! (:run/root context) source target)))

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
                   :result_sha256 (when summary-path (hash-ref/sha256-ref (lifecycle/sha256-file summary-path)))
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
  (hash-ref/sha256-ref (lifecycle/sha256-file file)))

(defn- input-set-root [inputs]
  (hash-ref/sha256-ref
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
                   "sha256" (sha-ref (:benchmark/plan-file context))}
                  (claim-registry-input context)]
                 (map input-entry (:results evidence))))))

(defn claim-registry-input
  "Commit the actually-selected claim registry as an input-set entry.

   Precedence: explicit CLI path → PRF_BENCHMARKS_CLAIM_REGISTRY → repository
   default. The committed entry carries the selected file's sha256 plus
   :source (:cli | :environment | :default) so two audits cannot run against
   different registries while evidence obscures how the registry was chosen."
  [context]
  (let [cli-path (or (:claim-registry/path context) *claim-registry-path*)
        path (claim-registry/claim-registry-path cli-path)
        source (claim-registry/claim-registry-source cli-path)
        hash (claim-registry/registry-file-sha256 path)]
    {"logical_id" "claim-registry"
     "source_kind" "claim-registry"
     "path" path
     "sha256" hash
     "claim-registry/source" (name source)}))

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
    (spit temp (json/write-str value :indent true))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    value))

(defn run-with-root!
  "Run a canonical benchmark root. Optional overrides replace phase functions
   for integration failure testing without bypassing root ownership/locking.
   When a claim-registry-path was selected for the run, bind it via
   with-claim-registry so evidence commits provenance about the actual file."
  [benchmark-id run-root key sensitivity-profile overrides]
  (let [context (assoc (benchmark-run/build-run-context benchmark-id run-root ".")
                       :sensitivity/profile sensitivity-profile
                       :claim-registry/path *claim-registry-path*
                       :execution/parallelism (or (:execution/parallelism overrides) 1)
                       :execution/chunk-size (or (:execution/chunk-size overrides) 1)
                       ;; Runtime-only settings: do not add these to context files,
                       ;; plan roots, evidence, or package projections.
                       :execution/claimant-parallelism (or (:execution/claimant-parallelism overrides) 1)
                       :execution/claimant-parallel-threshold (or (:execution/claimant-parallel-threshold overrides)
                                                                  (hardening/claimant-parallel-threshold))
                       :execution/budget (:execution/budget overrides)
                       :execution/quiescence-timeout-seconds (hardening/quiescence-timeout-seconds
                                                              (:execution/quiescence-timeout-seconds overrides)))
        lock (lifecycle/acquire-run-lock! (:run/root context) (:run/id context) :benchmark)
        quiescence-failed? (atom false)]
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
                                                                                  :execution-plan-path (str (:benchmark/plan-file context))
                                                                                  :parallelism (:execution/parallelism context)
                                                                                  :chunk-size (:execution/chunk-size context)
                                                                                  :claimant-parallelism (:execution/claimant-parallelism context)
                                                                                  :claimant-parallel-threshold (:execution/claimant-parallel-threshold context)
                                                                                  :budget (:execution/budget context)
                                                                                  :quiescence-timeout-seconds (:execution/quiescence-timeout-seconds context)})]
                                                (when-not (:evidence result)
                                                  (throw (ex-info "Benchmark execution produced no evidence; finalization aborted"
                                                                  {:benchmark benchmark-id :exit-code (:exit-code result)})))
                                                result))
                                   :finalize-runner (fn [_ result] (finalize-runner! context result))
                                   :write-manifest (fn [_ result] (write-run-manifest! context (:evidence result)))
                                   :snapshot-definition (fn [_ result] (snapshot-definition! context (:evidence result))
                                                          (snapshot-claim-registry! context))
                                   :write-conclusion (fn [_ result]
                                                       (write-conservation! context (:evidence result))
                                                       (reset! benchmark-conclusion (conclusion/write! context (:evidence result))))
                                   :write-summary (fn [_ result]
                                                    (write-assurance! context (:evidence result) @benchmark-conclusion)
                                                    (write-summary! context (:evidence result) @benchmark-conclusion))
                                   :scan-sensitivity (fn [_ _] (scan-sensitivity! context))
                                   :write-content-registry (fn [_ _] (write-content-registry! context))
                                   :write-finalization (fn [_ _] (write-finalization! context @benchmark-conclusion))
                                   :build-execution-witness (fn [_ _] (witness-build/build-and-write! context))
                                   :write-canonical-assurance (fn [_ _] (write-canonical-assurance! context))
                                   :write-verdict-policy (fn [_ result] (write-verdict-policy! context (:evidence result) @benchmark-conclusion))
                                   :write-package-index (fn [_ result] (write-package-index! context result))
                                   :build-inventory (fn [_ _] (inventory/build! context))
                                   :finalize-registry (fn [_ _] (registry/finalize! (:run/root context)))
                                   :validate-registry (fn [_ _] (validate-registry! context))
                                   :complete (fn [_ _] (complete-canonical-benchmark-run-root! context @benchmark-conclusion))} overrides))]
        {:exit-code (or (:exit-code execution) 1) :run/id (:run/id context) :run/root (str (:run/root context))})
      (catch clojure.lang.ExceptionInfo e
        (if (quiesce/quiescence-failed? e)
          (do
            (reset! quiescence-failed? true)
            (lifecycle/mark-quiescence-unknown! (:run/root context) (:run/id context) :benchmark
                                                {:quiescence/error (.getMessage e)
                                                 :quiescence/ex-data (ex-data e)})
            {:exit-code 1
             :run/id (:run/id context)
             :run/root (str (:run/root context))
             :command/status :quiescence-failed})
          (throw e)))
      (finally (when-not @quiescence-failed? (lifecycle/release-run-lock! lock (:run/id context)))))))

(defn- external-manifest-ref? [benchmark-id]
  (and (string? benchmark-id)
       (str/ends-with? benchmark-id ".edn")
       (not (str/starts-with? benchmark-id "classpath:"))
       (not (str/starts-with? benchmark-id "resource:"))))

(defn- legacy-scenario-suite-manifest?
  "True when a manifest relies on directory-discovery semantics that cannot
  produce a portable, explicit canonical execution plan."
  [benchmark-id]
  (let [manifest-path (benchmark-cli/resolve-benchmark-manifest benchmark-id)
        manifest (resource-path/edn-read manifest-path)]
    (contains? manifest :scenario-suites)))

(defn- empty-scenario-manifest?
  "True when a manifest resolves to zero scenarios, which would produce
  an empty canonical root if execution proceeded."
  [benchmark-id]
  (let [manifest-path (benchmark-cli/resolve-benchmark-manifest benchmark-id)
        manifest (resource-path/edn-read manifest-path)]
    (try
      (let [default-adapter @(requiring-resolve 'resolver-sim.benchmark.runner/default-adapter)
            scenarios (adapter/load-scenarios default-adapter manifest)]
        (zero? (count scenarios)))
      (catch Exception _ false))))

(defn- resolved-scenario-count
  "Number of scenarios a benchmark manifest resolves to. Used only to derive a
  bounded automatic default scenario-worker parallelism for
  `parallel-benchmark-run` when --parallelism is omitted; an explicit
  --parallelism always overrides. Falls back to 1 so a resolution error can
  never silently select an unbounded worker pool."
  [benchmark-id]
  (try
    (let [manifest-path (benchmark-cli/resolve-benchmark-manifest benchmark-id)
          default-adapter @(requiring-resolve 'resolver-sim.benchmark.runner/default-adapter)
          manifest (resource-path/edn-read manifest-path)]
      (count (adapter/load-scenarios default-adapter manifest)))
    (catch Exception _ 1)))

(defn effective-parallelism
  "Resolve the :execution/parallelism for a canonical run.

   For the `parallel-benchmark-run` command (parallel? true):
     - an explicit --parallelism N is honored exactly (operator choice); it is
       never clamped or reinterpreted,
     - when --parallelism is omitted, a bounded automatic default
       min(max(1, scenario-count), parallel-ceiling) is used, so the implicit
       worker pool never exceeds the ceiling even when the resolved scenario
       count is larger. Callers validate explicit N's positivity separately.
   The plain `run-benchmark` command (parallel? false) uses the supplied value
   or 1. The automatic ceiling is resolved via hardening/parallel-ceiling
   (CLI > env PRF_PARALLEL_CEILING > config :hardening :parallel-ceiling)."
  ([parallel? parallelism-opt scenario-count]
   (effective-parallelism parallel? parallelism-opt scenario-count
                          (hardening/parallel-ceiling)))
  ([parallel? parallelism-opt scenario-count ceiling]
   (if parallel?
     (if (some? parallelism-opt)
       parallelism-opt
       (min (max 1 scenario-count) ceiling))
     (or parallelism-opt 1))))

(defn run
  "Run a benchmark. `--run-root` creates a canonical benchmark-owned bundle;
   `--output` remains the legacy standalone evidence export destination.
   The `parallel-benchmark-run` command path is a bounded capability
   composition that adds local scenario/claimant parallelism to the same
   canonical run-benchmark algorithm (run-with-root! + run->benchmark), so
   its canonical output is invariant to parallelism. When --parallelism is
   omitted the worker pool defaults to a bounded min(scenario-count, ceiling);
   an explicit --parallelism is honored exactly. Both commands accept an
   optional `--execution-budget` bounding TOTAL concurrent execution."
  [{:keys [output key run-root sensitivity-profile claim-registry execution-budget
           parallel-ceiling quiescence-timeout-seconds] :as opts}]
  (let [benchmark-id (or (first (:cmd/args opts))
                         (:benchmark-id opts)
                         (:benchmark opts))
        parallel-command? (= "parallel-benchmark-run" (:cmd/path opts))
        effective-parallelism (effective-parallelism parallel-command?
                                                     (:parallelism opts)
                                                     (resolved-scenario-count benchmark-id)
                                                     (hardening/parallel-ceiling parallel-ceiling))
        ;; Fail closed: validate the selected claim registry up front so a run
        ;; can never proceed (and commit evidence) against an unrunnable or
        ;; invalid auditor-supplied registry. Applies whether the registry came
        ;; from --claim-registry, PRF_BENCHMARKS_CLAIM_REGISTRY, or the default.
        registry-error (try
                         (claim-registry/load-claim-registry claim-registry)
                         nil
                         (catch Exception e
                           {:exit-code 2 :message (str "Claim registry validation failed: " (.getMessage e))}))]
    (cond
      registry-error
      registry-error

      (nil? benchmark-id)
      {:exit-code 2 :message "Usage: prf-runner-sew.jar run-benchmark <benchmark-id> --run-root DIR"}

      (not (contains? #{nil :public :internal} sensitivity-profile))
      {:exit-code 2 :message "Sensitivity profile must be public or internal"}

      (not (and (integer? (or (:parallelism opts) 1))
                (pos? (or (:parallelism opts) 1))
                (integer? (or (:chunk-size opts) 1))
                (pos? (or (:chunk-size opts) 1))
                (integer? (or (:claimant-parallelism opts) 1))
                (pos? (or (:claimant-parallelism opts) 1))
                (integer? (or (:claimant-parallel-threshold opts)
                              (hardening/claimant-parallel-threshold)))
                (pos? (or (:claimant-parallel-threshold opts)
                          (hardening/claimant-parallel-threshold)))
                (integer? (or execution-budget 1))
                (pos? (or execution-budget 1))))
      {:exit-code 2 :message "Parallelism, chunk size, claimant parallelism, claimant threshold, and execution budget must be positive integers"}

      (and run-root output)
      {:exit-code 2 :message "Use --run-root for the canonical benchmark bundle; --output is a separate legacy export command"}

      (and run-root (external-manifest-ref? benchmark-id))
      {:exit-code 2
       :message "Filesystem benchmark manifests are not supported for canonical bundles yet; use a registered benchmark ID or bundled classpath: manifest"}

      (and run-root (legacy-scenario-suite-manifest? benchmark-id))
      {:exit-code 2
       :message "Canonical benchmark bundles do not support legacy :scenario-suites discovery; use an explicit :benchmark/scenario-suite or the legacy --output workflow"}

      (and run-root (empty-scenario-manifest? benchmark-id))
      {:exit-code 1
       :message "Benchmark manifest resolved zero scenarios; canonical bundle was not created"}

      run-root
      (with-claim-registry claim-registry
        (run-with-root! benchmark-id run-root key (or sensitivity-profile :public)
                        {:execution/parallelism effective-parallelism
                         :execution/chunk-size (or (:chunk-size opts) 1)
                         :execution/claimant-parallelism (or (:claimant-parallelism opts) 1)
                         :execution/claimant-parallel-threshold (or (:claimant-parallel-threshold opts)
                                                                    (hardening/claimant-parallel-threshold))
                         :execution/budget execution-budget
                         :execution/quiescence-timeout-seconds (hardening/quiescence-timeout-seconds
                                                                quiescence-timeout-seconds)}))

      output
      (let [result (invoke! benchmark-id {:output output :key key})]
        {:exit-code (or (:exit-code result) 1)})

      :else
      {:exit-code 2 :message "Specify --run-root for a canonical benchmark bundle or --output for a legacy export"})))
