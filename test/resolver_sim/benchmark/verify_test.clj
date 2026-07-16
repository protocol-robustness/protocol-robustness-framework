(ns resolver-sim.benchmark.verify-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.verify :as verify]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as canonical]))

(defn- temp-root [] (.toFile (java.nio.file.Files/createTempDirectory "benchmark-verify-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- delete-tree! [root] (doseq [f (reverse (file-seq root))] (io/delete-file f true)))
(defn- write-json! [file value] (io/make-parents file) (spit file (json/write-str value)))
(defn- sha [file] (str "sha256:" (lifecycle/sha256-file file)))

(defn- fixture! [root]
  (let [assurance (io/file root "benchmark/assertions/benchmark-assurance.json")
        conservation (io/file root "benchmark/assertions/conservation.json")
        registry (io/file root "manifest/artifacts.json")
        validation (io/file root "manifest/artifacts-validation.json")
        conclusion (io/file root "benchmark/conclusion.json")
        finalization (io/file root "benchmark/finalization.json")
        completion (io/file root "completion.json")
        definition (io/file root "benchmark/definition.edn")
        plan (io/file root "benchmark/execution-plan.edn")
        scenario-input (io/file root "benchmark/executions/exec-1/input/scenario.edn")]
    (doseq [[file content] [[definition "{:benchmark/id :b}"]
                            [plan "{:executions []}"]
                            [scenario-input "{:scenario/id :s}"]]]
      (io/make-parents file)
      (spit file content))
    (write-json! conservation {"status" "not-exercised"
                               "applicability" {"expected_execution_ids" []}
                               "executions" []})
    (write-json! registry {"artifacts" []})
    (write-json! validation {"status" "passed"})
    (write-json! conclusion {"outcome" "pass"})
    (let [inputs [{"logical_id" "benchmark-definition" "source_kind" "benchmark-definition-snapshot"
                   "path" "benchmark/definition.edn" "sha256" (sha definition)}
                  {"logical_id" "benchmark-execution-plan" "source_kind" "execution-plan"
                   "path" "benchmark/execution-plan.edn" "sha256" (sha plan)}
                  {"logical_id" "execution/e1/scenario-input" "source_kind" "execution-input-snapshot"
                   "path" "benchmark/executions/exec-1/input/scenario.edn" "sha256" (sha scenario-input)}]
          input-set-root (str "sha256:" (canonical/domain-hash "BENCHMARK_INPUT_SET_V1"
                                                                   (vec (sort-by #(get % "path") inputs))))]
      (write-json! assurance {"input_set" inputs
                              "input_set_root" input-set-root
                              "conservation" {"artifact_ref" "benchmark/assertions/conservation.json"
                                              "artifact_sha256" (sha conservation)
                                              "status" "not-exercised"}})
      (let [projection {"domain" "prf/benchmark-finalization/v1" "benchmark_id" "b" "run_id" "r"
                        "assurance_artifact_sha256" (sha assurance) "conclusion_sha256" (sha conclusion)
                        "artifact_registry_sha256" (sha registry) "registry_validation_sha256" (sha validation)
                        "input_set_root" input-set-root}
            final-ref (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))]
        (write-json! finalization {"benchmark_id" "b" "run_id" "r" "conclusion_sha256" (sha conclusion)
                                   "artifact_registry_sha256" (sha registry) "registry_validation_sha256" (sha validation)
                                   "input_set_root" input-set-root "final_ref" final-ref})
        (write-json! completion {"finalization_sha256" (sha finalization) "input_set_root" input-set-root "final_ref" final-ref})))
    root))

(deftest verifier-rejects-tampered-terminal-commitments
  (doseq [[label expected-check tamper!]
          [["registry" "registry-hash"
            #(write-json! (io/file % "manifest/artifacts.json") {"artifacts" ["tampered"]})]
           ["validation" "validation-hash"
            #(write-json! (io/file % "manifest/artifacts-validation.json") {"status" "tampered"})]
           ["assurance" "final-ref"
            #(write-json! (io/file % "benchmark/assertions/benchmark-assurance.json")
                          {"input_set" [] "input_set_root" "sha256:tampered"
                           "conservation" {"artifact_ref" "benchmark/assertions/conservation.json"
                                           "artifact_sha256" "sha256:tampered" "status" "fail"}})]
           ["conservation" "conservation-assurance"
            #(write-json! (io/file % "benchmark/assertions/conservation.json")
                          {"status" "fail" "applicability" {"expected_execution_ids" []} "executions" []})]
           ["finalization" "completion-finalization-hash"
            #(write-json! (io/file % "benchmark/finalization.json")
                          {"benchmark_id" "b" "run_id" "r" "final_ref" "sha256:tampered"})]
           ["completion-final-ref" "final-ref"
            #(write-json! (io/file % "completion.json")
                          {"finalization_sha256" "sha256:bad" "input_set_root" "sha256:inputs" "final_ref" "sha256:bad"})]]]
    (let [root (temp-root)]
      (try
        (fixture! root)
        (is (= "passed" (get (verify/verify! root) "status")) label)
        (tamper! root)
        (let [result (verify/verify! root)]
          (is (= "failed" (get result "status")) label)
          (is (false? (get-in result ["checks" expected-check])) label))
        (finally (delete-tree! root))))))

(deftest verifier-rejects-tampered-input-snapshot
  (let [root (temp-root)]
    (try
      (fixture! root)
      (spit (io/file root "benchmark/executions/exec-1/input/scenario.edn") "{:scenario/id :tampered}")
      (let [result (verify/verify! root)]
        (is (= "failed" (get result "status")))
        (is (false? (get-in result ["checks" "input-set-recalculated"]))))
      (finally (delete-tree! root)))))
