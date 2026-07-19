(ns resolver-sim.benchmark.verify-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.verify :as verify]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as canonical]
                        [resolver-sim.run.package-index :as package-index]
                        [resolver-sim.run.verdict-policy :as verdict-policy]))

(defn- temp-root [] (.toFile (java.nio.file.Files/createTempDirectory "benchmark-verify-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- delete-tree! [root] (doseq [f (reverse (file-seq root))] (io/delete-file f true)))
(defn- write-json! [file value] (io/make-parents file) (spit file (json/write-str value)))
(defn- sha [file] (str "sha256:" (lifecycle/sha256-file file)))
(defn- entries [root paths]
  (mapv (fn [path] (let [file (io/file root path)] {"path" path "sha256" (lifecycle/sha256-file file)})) paths))

(defn- fixture! [root]
  (let [f (fn [path] (io/file root path))
        definition (f "benchmark/definition.edn") plan (f "benchmark/execution-plan.edn")
        scenario-input (f "benchmark/executions/exec-1/input/scenario.edn")
        conclusion (f "benchmark/conclusion.json") conservation (f "benchmark/assertions/conservation.json")
        assurance (f "benchmark/assertions/benchmark-assurance.json") content (f "benchmark/evidence/content-registry.json")
        finalization (f "benchmark/finalization.json") integrity (f "benchmark/assertions/canonical-integrity.json")
        deferred (f "benchmark/assertions/forensic-claims-status.json") verdict-policy-file (f "manifest/verdict-policy.json") package-index (f "manifest/run-package-index.json")
        registry (f "manifest/artifacts.json") validation (f "manifest/artifacts-validation.json") completion (f "completion.json")]
    (doseq [[file content] [[definition "{:benchmark/id :b}"] [plan "{:executions []}"] [scenario-input "{:scenario/id :s}"] [conclusion "{\"outcome\":\"pass\"}"]]]
      (io/make-parents file) (spit file content))
    (write-json! conservation {"status" "not-exercised" "applicability" {"expected_execution_ids" []} "executions" []})
    (let [inputs [{"logical_id" "benchmark-definition" "source_kind" "benchmark-definition-snapshot" "path" "benchmark/definition.edn" "sha256" (sha definition)}
                  {"logical_id" "benchmark-execution-plan" "source_kind" "execution-plan" "path" "benchmark/execution-plan.edn" "sha256" (sha plan)}
                  {"logical_id" "execution/e1/scenario-input" "source_kind" "execution-input-snapshot" "path" "benchmark/executions/exec-1/input/scenario.edn" "sha256" (sha scenario-input)}]
          input-root (str "sha256:" (canonical/domain-hash "BENCHMARK_INPUT_SET_V1" (vec (sort-by #(get % "path") inputs))))]
      (write-json! assurance {"schema_version" "benchmark-assurance.v1" "benchmark_id" "b" "run_id" "r" "lifecycle_status" "completed"
                              "conclusion" {"outcome" "pass"} "input_set" inputs "input_set_root" input-root
                              "conservation" {"artifact_ref" "benchmark/assertions/conservation.json" "artifact_sha256" (sha conservation) "status" "not-exercised"}})
      (write-json! content {"schema_version" "benchmark-content-registry.v1" "artifacts" []})
      (let [projection {"domain" "prf/benchmark-finalization/v1" "benchmark_id" "b" "run_id" "r"
                        "assurance_artifact_sha256" (sha assurance) "conclusion_sha256" (sha conclusion)
                        "evidence_content_registry_sha256" (sha content) "input_set_root" input-root}
            final-ref (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))]
        (write-json! finalization {"schema_version" "benchmark-finalization.v1" "benchmark_id" "b" "run_id" "r"
                                   "conclusion_sha256" (sha conclusion) "evidence_content_registry_sha256" (sha content)
                                   "input_set_root" input-root "final_ref" final-ref})
        (write-json! integrity {"schema_version" "canonical-integrity.v1" "status" "passed"
                                "benchmark_finalization" {"sha256" (sha finalization)} "benchmark_assurance" {"sha256" (sha assurance)}
                                "conservation" {"sha256" (sha conservation)} "evidence_content_registry" {"sha256" (sha content)}})
        (write-json! deferred {"schema_version" "forensic-claims-status.v1" "status" "deferred" "reason_code" "unsigned-forensic-signing-not-configured"})
                (verdict-policy/write! verdict-policy-file
                                       (verdict-policy/build {:run-id "r" :run-type "benchmark"
                                                              :policy-id "fixture.v1" :semantic-outcome "pass"
                                                              :inputs inputs
                                                              :registries {"evidence_policy_hash" "fixture-evidence-policy"
                                                                           "claim_definition_registry_hash" "fixture-claims"
                                                                           "evaluator_registry" "fixture-evaluator"}
                                                              :semantic-environment {"protocol_id" "benchmark" "runner_id" "fixture-runner"}
                                                                                                                    :evaluator-implementation {"source_tree_hash" "fixture-source-tree"
                                                                                                                                               "source_tree_hash_algorithm" "fixture.v1"
                                                                                                                                               "evaluator_id" "fixture-evaluator"}
                                                                                                                                                                                                     :distribution-provenance {"mode" "source-classpath"
                                                                                                                                                                                                                               "reason" "fixture"}}))
                (package-index/write! package-index
                              {:run-id "r"
                               :run-type :benchmark
                               :bundle-root-hash (sha content)
                               :artifacts {:runner-finalization {:ref "benchmark/finalization.json" :sha256 (sha finalization)}
                                           :benchmark-finalization {:ref "benchmark/finalization.json" :sha256 (sha finalization)}
                                           :benchmark-assurance {:ref "benchmark/assertions/benchmark-assurance.json" :sha256 (sha assurance)}
                                           :canonical-integrity {:ref "benchmark/assertions/canonical-integrity.json" :sha256 (sha integrity)}
                                                                                      :verdict-policy {:ref "manifest/verdict-policy.json" :sha256 (sha verdict-policy-file)}}})
        (let [paths ["benchmark/definition.edn" "benchmark/execution-plan.edn" "benchmark/executions/exec-1/input/scenario.edn" "benchmark/conclusion.json" "benchmark/assertions/conservation.json" "benchmark/assertions/benchmark-assurance.json" "benchmark/evidence/content-registry.json" "benchmark/finalization.json" "benchmark/assertions/canonical-integrity.json" "benchmark/assertions/forensic-claims-status.json" "manifest/verdict-policy.json" "manifest/run-package-index.json"]]
          (write-json! registry {"artifacts" (entries root paths)})
          (write-json! validation {"status" "passed"})
          (write-json! completion {"schema_version" "benchmark-completion.v1" "run_type" "benchmark" "benchmark_id" "b" "run_id" "r"
                                     "lifecycle_status" "completed" "semantic_status" "pass" "finalization_ref" "benchmark/finalization.json"
                                     "finalization_sha256" (sha finalization) "final_ref" final-ref "input_set_root" input-root
                                     "run_package_index_ref" "manifest/run-package-index.json" "run_package_index_sha256" (sha package-index)
                                     "run_package_index_bytes" (.length package-index)
                                     "artifact_registry_sha256" (sha registry) "registry_validation_sha256" (sha validation)}))))
    root))

(deftest verifier-rejects-tampered-terminal-commitments
  (doseq [[label check tamper!] [["content" "evidence-content-registry-hash" #(spit (io/file % "benchmark/evidence/content-registry.json") "tampered")]
                                  ["integrity" "canonical-integrity" #(write-json! (io/file % "benchmark/assertions/canonical-integrity.json") {"status" "tampered"})]
                                  ["completion" "completion-finalization-hash" #(write-json! (io/file % "completion.json") {"finalization_sha256" "sha256:bad"})]]]
    (let [root (temp-root)]
      (try (fixture! root)
           (tamper! root) (is (false? (get-in (verify/verify! root) ["checks" check])) label)
           (finally (delete-tree! root))))))

(deftest verifier-rejects-tampered-input-snapshot
  (let [root (temp-root)]
    (try (fixture! root) (spit (io/file root "benchmark/executions/exec-1/input/scenario.edn") "tampered")
         (is (false? (get-in (verify/verify! root) ["checks" "input-set-recalculated"])))
         (finally (delete-tree! root)))))
