(ns resolver-sim.benchmark.verify-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.verify :as verify]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.evidence.node :as evidence-node]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.run.package-index :as package-index]
            [resolver-sim.run.verdict-policy :as verdict-policy]))

(defn- temp-root [] (.toFile (java.nio.file.Files/createTempDirectory "benchmark-verify-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- delete-tree! [root] (doseq [f (reverse (file-seq root))] (io/delete-file f true)))
(defn- write-json! [file value] (io/make-parents file) (spit file (json/write-str value)))
(defn- read-json! [file] (json/read-str (slurp file)))
(defn- sha [file] (str "sha256:" (lifecycle/sha256-file file)))
(def sha-ref sha)
(defn- canonical-sha [file] (lifecycle/sha256-file file))
(defn- entries [root paths]
  (mapv (fn [path] (let [file (io/file root path)] {"path" path "sha256" (lifecycle/sha256-file file)})) paths))

(defn- fixture!
  ([root] (fixture! root :in-band))
  ([root provenance-kw]
   (let [f (fn [path] (io/file root path))
         definition (f "benchmark/definition.edn") plan (f "benchmark/execution-plan.edn")
         scenario-input (f "benchmark/executions/exec-1/input/scenario.edn")
         conclusion (f "benchmark/conclusion.json") conservation (f "benchmark/assertions/conservation.json")
         assurance (f "benchmark/assertions/benchmark-assurance.json") content (f "benchmark/evidence/content-registry.json")
         finalization (f "benchmark/finalization.json") integrity-file (f "benchmark/assertions/canonical-integrity.json")
         deferred (f "benchmark/assertions/forensic-claims-status.json") verdict-policy-file (f "manifest/verdict-policy.json") package-index (f "manifest/run-package-index.json")
         registry (f "manifest/artifacts.json") validation (f "manifest/artifacts-validation.json") completion (f "completion.json")
         evidence-file (f "benchmark/evidence/evidence.edn")]
     (doseq [[file content] [[definition "{:benchmark/id :b}"] [plan "{:executions []}"] [scenario-input "{:scenario/id :s}"] [conclusion "{\"outcome\":\"pass\"}"]]]
       (io/make-parents file) (spit file content))
     (write-json! conservation {"status" "not-exercised" "applicability" {"expected_execution_ids" []} "executions" []})
     (let [inputs [{"logical_id" "benchmark-definition" "source_kind" "benchmark-definition-snapshot" "path" "benchmark/definition.edn" "sha256" (sha definition)}
                   {"logical_id" "benchmark-execution-plan" "source_kind" "execution-plan" "path" "benchmark/execution-plan.edn" "sha256" (sha plan)}
                   {"logical_id" "execution/e1/scenario-input" "source_kind" "execution-input-snapshot" "path" "benchmark/executions/exec-1/input/scenario.edn" "sha256" (sha scenario-input)}]
           input-root (str "sha256:" (canonical/domain-hash "BENCHMARK_INPUT_SET_V1" (vec (sort-by #(get % "path") inputs))))
           evidence-base {:benchmark {:benchmark/id :b}
                          :creation/provenance provenance-kw
                          :source/creation {:provenance provenance-kw}
                          :benchmark/execution-closure {:closure/version 1
                                                        :round-count 1
                                                        :derived-work-count 0
                                                        :closed? true}}
           evidence-hash (hash-ref/sha256-ref
                          (canonical/hash-with-intent {:hash/intent :bundle-root}
                                                      (integrity/hashable-evidence
                                                       (runner/normalize-runtime-values
                                                        (dissoc evidence-base :timestamp)))))
           evidence (assoc evidence-base :evidence/hash evidence-hash)
           creation-provenance-hash (hash-ref/sha256-ref
                                     (canonical/hash-with-intent {:hash/intent :creation-provenance}
                                                                 {:creation/provenance (:creation/provenance evidence)}))
           source-creation-hash (hash-ref/sha256-ref
                                 (canonical/hash-with-intent {:hash/intent :source-creation}
                                                             {:source/creation (:source/creation evidence)}))]
       (io/make-parents evidence-file)
       (spit evidence-file (pr-str evidence))
       (write-json! assurance {"schema_version" "benchmark-assurance.v1" "benchmark_id" "b" "run_id" "r" "lifecycle_status" "completed"
                               "conclusion" {"outcome" "pass"} "input_set" inputs "input_set_root" input-root
                               "conservation" {"artifact_ref" "benchmark/assertions/conservation.json" "artifact_sha256" (sha conservation) "status" "not-exercised"}})
       (write-json! content {"schema_version" "benchmark-content-registry.v1" "artifacts" []})
       (let [projection {"domain" "prf/benchmark-finalization/v1" "benchmark_id" "b"
                         "assurance_artifact_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/assertions/benchmark-assurance.json" assurance)) "conclusion_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/conclusion.json" conclusion))
                         "evidence_content_registry_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/evidence/content-registry.json" content)) "input_set_root" input-root
                         "semantic_composition_root" ""}
             final-ref (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))]
         (write-json! finalization {"schema_version" "benchmark-finalization.v1" "benchmark_id" "b"
                                    "conclusion_sha256" (sha conclusion) "evidence_content_registry_sha256" (sha content)
                                    "input_set_root" input-root "semantic_composition_root" "" "final_ref" final-ref})
         (write-json! integrity-file {"schema_version" "canonical-integrity.v1" "status" "passed"
                                      "benchmark_finalization" {"sha256" (sha finalization)} "benchmark_assurance" {"sha256" (sha assurance)}
                                      "conservation" {"sha256" (sha conservation)} "evidence_content_registry" {"sha256" (sha content)}
                                      "creation_provenance" (name (:creation/provenance evidence)) "creation_provenance_hash" (str creation-provenance-hash)
                                      "source_creation" (name (get-in evidence [:source/creation :provenance])) "source_creation_hash" (str source-creation-hash)})
         (write-json! deferred {"schema_version" "forensic-claims-status.v1" "status" "deferred" "reason_code" "unsigned-forensic-signing-not-configured"})
         (verdict-policy/write! verdict-policy-file
                                (verdict-policy/build {:run-id "r" :run-type "benchmark"
                                                       :policy-id "fixture.v1" :version-id "verdict-policy.v1" :semantic-outcome "pass"
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
                                :bundle-root-hash evidence-hash
                                :artifacts {:runner-finalization {:ref "benchmark/finalization.json" :sha256 (sha finalization)}
                                            :benchmark-finalization {:ref "benchmark/finalization.json" :sha256 (sha finalization)}
                                            :benchmark-assurance {:ref "benchmark/assertions/benchmark-assurance.json" :sha256 (sha assurance)}
                                            :canonical-integrity {:ref "benchmark/assertions/canonical-integrity.json" :sha256 (sha integrity-file)}
                                            :verdict-policy {:ref "manifest/verdict-policy.json" :sha256 (sha verdict-policy-file)}}})
         (let [paths ["benchmark/definition.edn" "benchmark/execution-plan.edn" "benchmark/executions/exec-1/input/scenario.edn" "benchmark/conclusion.json" "benchmark/assertions/conservation.json" "benchmark/assertions/benchmark-assurance.json" "benchmark/evidence/content-registry.json" "benchmark/evidence/evidence.edn" "benchmark/finalization.json" "benchmark/assertions/canonical-integrity.json" "benchmark/assertions/forensic-claims-status.json" "manifest/verdict-policy.json" "manifest/run-package-index.json"]]
           (write-json! registry {"artifacts" (entries root paths)})
           (write-json! validation {"status" "passed"})
           (write-json! completion {"schema_version" "benchmark-completion.v1" "run_type" "benchmark" "benchmark_id" "b" "run_id" "r"
                                    "lifecycle_status" "completed" "semantic_status" "pass" "finalization_ref" "benchmark/finalization.json"
                                    "finalization_sha256" (sha finalization) "final_ref" final-ref "input_set_root" input-root
                                    "semantic_composition_root" ""
                                    "bundle_root_hash" (str evidence-hash)
                                    "closure_commitment" (str (hash-ref/sha256-ref
                                                               (canonical/hash-with-intent {:hash/intent :evidence-content}
                                                                                           (:benchmark/execution-closure evidence))))
                                    "run_package_index_ref" "manifest/run-package-index.json" "run_package_index_sha256" (sha package-index)
                                    "run_package_index_bytes" (.length package-index)
                                    "artifact_registry_sha256" (sha registry) "registry_validation_sha256" (sha validation)}))))
     root)))

(deftest verifier-rejects-tampered-terminal-commitments
  (doseq [[label check tamper!] [["content" "evidence-content-registry-hash" #(spit (io/file % "benchmark/evidence/content-registry.json") "tampered")]
                                 ["integrity" "canonical-integrity" #(write-json! (io/file % "benchmark/assertions/canonical-integrity.json") {"status" "tampered"})]
                                 ["completion" "completion-finalization-hash" #(write-json! (io/file % "completion.json") {"finalization_sha256" "sha256:bad"})]]]
    (let [root (temp-root)]
      (try (fixture! root)
           (tamper! root) (is (false? (get-in (verify/verify! root) ["checks" check])) label)
           (finally (delete-tree! root))))))

(deftest verifier-detected-creation-provenance-commitment
  (testing "positive: valid fixture passes creation-provenance commitment"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (true? (get checks "canonical-integrity-creation-provenance"))
              "creation-provenance commitment should be present and valid")
          (is (true? (get checks "canonical-integrity-source-creation"))
              "source-creation commitment should be present and valid"))
        (finally
          (delete-tree! root)))))
  (testing "negative: delete creation_provenance_hash"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (write-json! (io/file root "benchmark/assertions/canonical-integrity.json")
                     (let [ci (read-json! (io/file root "benchmark/assertions/canonical-integrity.json"))]
                       (dissoc ci "creation_provenance_hash")))
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (false? (get checks "canonical-integrity-creation-provenance"))
              "deleting the commitment hash while provenance string remains should fail")
          (is (false? (get checks "artifact-registry-recalculated"))
              "canonical-integrity artifact hash in registry must catch the file modification"))
        (finally
          (delete-tree! root)))))
  (testing "negative: replace provenance without updating hash"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (write-json! (io/file root "benchmark/assertions/canonical-integrity.json")
                     (let [ci (read-json! (io/file root "benchmark/assertions/canonical-integrity.json"))]
                       (assoc ci "creation_provenance" "out-of-band")))
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (false? (get checks "canonical-integrity-creation-provenance"))
              "provenance changed without updating hash should fail the check"))
        (finally
          (delete-tree! root)))))
  (testing "negative: update both provenance and hash"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (let [ci (read-json! (io/file root "benchmark/assertions/canonical-integrity.json"))
              new-hash (str (hash-ref/sha256-ref
                             (canonical/hash-with-intent {:hash/intent :creation-provenance}
                                                         {:creation/provenance :out-of-band})))]
          (write-json! (io/file root "benchmark/assertions/canonical-integrity.json")
                       (assoc ci "creation_provenance" "out-of-band"
                              "creation_provenance_hash" new-hash))
          (let [checks (get-in (verify/verify! root) ["checks"])]
            (is (false? (get checks "artifact-registry-recalculated"))
                "canonical-integrity.json modified so its registry hash must mismatch")
            (is (false? (get checks "canonical-integrity-creation-provenance"))
                "evidence says in-band but stored hash is for out-of-band")))
        (finally
          (delete-tree! root)))))
  (testing "negative: unsupported provenance value"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (let [ci (read-json! (io/file root "benchmark/assertions/canonical-integrity.json"))]
          (write-json! (io/file root "benchmark/assertions/canonical-integrity.json")
                       (assoc ci "creation_provenance" "unknown")))
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (false? (get checks "canonical-integrity-creation-provenance"))
              "unsupported provenance value with stale hash should fail"))
        (finally
          (delete-tree! root)))))
  (testing "negative: delete source_creation_hash"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (write-json! (io/file root "benchmark/assertions/canonical-integrity.json")
                     (let [ci (read-json! (io/file root "benchmark/assertions/canonical-integrity.json"))]
                       (dissoc ci "source_creation_hash")))
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (false? (get checks "canonical-integrity-source-creation"))
              "deleting source_creation_hash while source_creation string remains should fail")
          (is (false? (get checks "artifact-registry-recalculated"))
              "canonical-integrity artifact hash in registry must catch the file modification"))
        (finally
          (delete-tree! root)))))
  (testing "negative: replace source_creation provenance without updating hash"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (write-json! (io/file root "benchmark/assertions/canonical-integrity.json")
                     (let [ci (read-json! (io/file root "benchmark/assertions/canonical-integrity.json"))]
                       (assoc ci "source_creation" "out-of-band")))
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (false? (get checks "canonical-integrity-source-creation"))
              "provenance changed without updating hash should fail the check"))
        (finally
          (delete-tree! root)))))
  (testing "negative: update both source_creation and hash but evidence disagrees"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (let [ci (read-json! (io/file root "benchmark/assertions/canonical-integrity.json"))
              new-hash (str (hash-ref/sha256-ref
                             (canonical/hash-with-intent {:hash/intent :source-creation}
                                                         {:source/creation {:provenance :out-of-band}})))]
          (write-json! (io/file root "benchmark/assertions/canonical-integrity.json")
                       (assoc ci "source_creation" "out-of-band"
                              "source_creation_hash" new-hash))
          (let [checks (get-in (verify/verify! root) ["checks"])]
            (is (false? (get checks "artifact-registry-recalculated"))
                "canonical-integrity.json modified so its registry hash must mismatch")
            (is (false? (get checks "canonical-integrity-source-creation"))
                "evidence says in-band but stored hash is for out-of-band")))
        (finally
          (delete-tree! root)))))
  (testing "negative: unsupported source_creation value"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (let [ci (read-json! (io/file root "benchmark/assertions/canonical-integrity.json"))]
          (write-json! (io/file root "benchmark/assertions/canonical-integrity.json")
                       (assoc ci "source_creation" "unknown")))
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (false? (get checks "canonical-integrity-source-creation"))
              "unsupported source_creation value with stale hash should fail"))
        (finally
          (delete-tree! root))))))

(deftest out-of-band-creation-provenance-round-trips-through-verifier
  (testing "positive: out-of-band evidence bundle passes all provenance checks"
    (let [root (temp-root)]
      (try
        (fixture! root :out-of-band)
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (true? (get checks "canonical-integrity-creation-provenance"))
              "out-of-band creation-provenance commitment should verify")
          (is (true? (get checks "canonical-integrity-source-creation"))
              "out-of-band source-creation commitment should verify"))
        (finally
          (delete-tree! root)))))
  (testing "negative: out-of-band hash mismatches when evidence is mutated to in-band"
    (let [root (temp-root)]
      (try
        (fixture! root :out-of-band)
        (let [evidence-file (io/file root "benchmark/evidence/evidence.edn")
              evidence (edn/read-string (slurp evidence-file))]
          (spit evidence-file (pr-str (assoc-in evidence [:creation/provenance] :in-band)))
          (let [checks (get-in (verify/verify! root) ["checks"])]
            (is (false? (get checks "canonical-integrity-creation-provenance"))
                "evidence provenance changed to in-band must fail the out-of-band commitment")))
        (finally
          (delete-tree! root))))))

(deftest write-is-conditionally-idempotent
  (let [root (temp-root)
        file (io/file root "manifest/policy.json")
        artifact (verdict-policy/build
                  {:run-id "r" :run-type "benchmark"
                   :policy-id "fixture.v1" :version-id "v1" :semantic-outcome "pass"
                   :inputs []
                   :registries {"evidence_policy_hash" "x" "claim_definition_registry_hash" "c" "evaluator_registry" "e"}
                   :semantic-environment {"protocol_id" "p" "runner_id" "r"}
                   :evaluator-implementation {"source_tree_hash" "s" "source_tree_hash_algorithm" "v1" "evaluator_id" "e"}
                   :distribution-provenance {"mode" "source-classpath"}})]
    (try
      (io/make-parents file)
      (is (= artifact (verdict-policy/write! file artifact))
          "first write succeeds")
      (is (= artifact (verdict-policy/write! file artifact))
          "rewriting the identical artifact is allowed (idempotent re-run)")
      (is (thrown? clojure.lang.ExceptionInfo
                   (verdict-policy/write! file (assoc artifact "version_id" "v2")))
          "writing a divergent artifact to an existing file refuses")
      (finally (delete-tree! root)))))

(deftest verifier-rejects-tampered-input-snapshot
  (let [root (temp-root)]
    (try (fixture! root) (spit (io/file root "benchmark/executions/exec-1/input/scenario.edn") "tampered")
         (is (false? (get-in (verify/verify! root) ["checks" "input-set-recalculated"])))
         (finally
           (delete-tree! root)))))
(deftest replace-draft-is-repeatable-and-idempotent
  (let [root (temp-root)
        draft-file (io/file root "manifest/draft-policy.json")]
    (try
      (io/make-parents draft-file)
      (spit draft-file
            (json/write-str {"schema_version" "verdict-policy.v1"
                             "policy_id" "fixture.v1"
                             "run" {"id" "r" "type" "benchmark"}
                             "verdict" {"semantic_outcome" "pass"}
                             "registries" {"evidence_policy_hash" "x"}
                             "evaluator_implementation" {"source_tree_hash" "x"
                                                         "source_tree_hash_algorithm" "v1"}
                             "semantic_environment" {"runner_id" "r" "protocol_id" "p"}
                             "distribution_provenance" {"mode" "source-classpath"}
                             "immutable_inputs" []
                             "version_id" "v1"
                             "policy_sha256" "stale-draft-hash"}))
      (testing "a genuinely finalised artifact (valid self-commitment) refuses"
        (let [finalised (verdict-policy/build-artifact
                         {"schema_version" "verdict-policy.v1"
                          "policy_id" "fixture.v1"
                          "run" {"id" "r" "type" "benchmark"}
                          "verdict" {"semantic_outcome" "pass" "mapping" {"pass" "pass" "fail" "fail"}}
                          "registries" {"evidence_policy_hash" "x"}
                          "evaluator_implementation" {"source_tree_hash" "x"
                                                      "source_tree_hash_algorithm" "v1"
                                                      "evaluator_id" "e"}
                          "semantic_environment" {"runner_id" "r" "protocol_id" "p"}
                          "distribution_provenance" {"mode" "source-classpath"}
                          "immutable_inputs" []
                          "version_id" "v1"})
              fin-file (io/file root "manifest/finalised-policy.json")]
          (io/make-parents fin-file)
          (spit fin-file (json/write-str finalised))
          (is (thrown? clojure.lang.ExceptionInfo
                       (verdict-policy/replace-draft! fin-file (constantly {})))
              "replacing a finalised artifact refuses")))
      (testing "a draft is replaced repeatably and idempotently"
        (let [bump (fn [a] (assoc a "version_id" "v2"))]
          (is (= "v2" (get (verdict-policy/replace-draft! draft-file bump) "version_id"))
              "first replacement applies the draft mutation")
          (is (nil? (get (json/read-str (slurp draft-file)) "policy_sha256"))
              "the written result is still a draft (no valid self-commitment)")
          (let [before (slurp draft-file)]
            (is (= "v2" (get (verdict-policy/replace-draft! draft-file bump) "version_id"))
                "a second replacement with the same f is not refused (repeatable)")
            (is (= before (slurp draft-file))
                "repeat application is idempotent: the file is unchanged"))))
      (finally (delete-tree! root)))))

(defn- with-authoritative-input
  "Transform a fixture root into an authoritative-composition run by rewriting
   the scenario input to declare :semantic-composition and
   :execution-mode :authoritative, and updating all dependent commitments
   (input_set_root, evidence results, finalization, completion)."
  [root comp-root]
  (let [scenario-path "benchmark/executions/exec-1/input/scenario.edn"
        scenario-file (io/file root scenario-path)
        scenario (assoc (edn/read-string (slurp scenario-file))
                        :semantic-composition {:schema "semantic-composition.v1"
                                               :semantic-composition/root comp-root}
                        :execution-mode :authoritative)]
    (spit scenario-file (pr-str scenario))
    (let [assurance-file (io/file root "benchmark/assertions/benchmark-assurance.json")
          assurance (json/read-str (slurp assurance-file))
          inputs (get assurance "input_set")
          updated-inputs (mapv (fn [entry]
                                 (if (= (get entry "source_kind") "execution-input-snapshot")
                                   (assoc entry "sha256" (sha-ref scenario-file))
                                   entry))
                               inputs)
          input-root (str "sha256:" (canonical/domain-hash "BENCHMARK_INPUT_SET_V1"
                                                           (vec (sort-by #(get % "path")
                                                                         (map #(select-keys % ["logical_id" "source_kind" "path" "sha256"])
                                                                              updated-inputs)))))
          evidence-file (io/file root "benchmark/evidence/evidence.edn")
          evidence (edn/read-string (slurp evidence-file))
          evidence-with-results (assoc evidence :results
                                       [{:semantic-composition-root comp-root}])
          evidence-hash (hash-ref/sha256-ref
                         (canonical/hash-with-intent {:hash/intent :bundle-root}
                                                     (integrity/hashable-evidence
                                                      (runner/normalize-runtime-values
                                                       (dissoc evidence-with-results :timestamp :evidence/hash)))))
          updated-evidence (assoc evidence-with-results :evidence/hash evidence-hash)
          _ (spit evidence-file (pr-str updated-evidence))
          _ (write-json! assurance-file
                         (assoc assurance
                                "input_set" updated-inputs
                                "input_set_root" input-root))]
      (let [fin-file (io/file root "benchmark/finalization.json")
            fin (json/read-str (slurp fin-file))
            projection {"domain" "prf/benchmark-finalization/v1"
                        "benchmark_id" (get fin "benchmark_id")
                        "assurance_artifact_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/assertions/benchmark-assurance.json" (io/file root "benchmark/assertions/benchmark-assurance.json")))
                        "conclusion_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/conclusion.json" (io/file root "benchmark/conclusion.json")))
                        "evidence_content_registry_sha256" (:sha256 (evidence-node/canonical-artifact-content "benchmark/evidence/content-registry.json" (io/file root "benchmark/evidence/content-registry.json")))
                        "input_set_root" input-root
                        "semantic_composition_root" comp-root}
            final-ref (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))
            fin-updated (assoc fin
                               "input_set_root" input-root
                               "semantic_composition_root" comp-root
                               "final_ref" final-ref)]
        (write-json! fin-file fin-updated)
        (let [comp-file (io/file root "completion.json")
              comp (json/read-str (slurp comp-file))
              comp-updated (assoc comp
                                  "input_set_root" input-root
                                  "semantic_composition_root" comp-root
                                  "final_ref" final-ref
                                  "bundle_root_hash" (str evidence-hash)
                                  "finalization_sha256" (sha-ref fin-file))]
          (write-json! comp-file comp-updated))
        (let [int-file (io/file root "benchmark/assertions/canonical-integrity.json")
              int (json/read-str (slurp int-file))
              int-updated (assoc int
                                 "benchmark_finalization" {"sha256" (sha-ref fin-file)}
                                 "benchmark_assurance" {"sha256" (sha-ref (io/file root "benchmark/assertions/benchmark-assurance.json"))}
                                 "evidence_content_registry" {"sha256" (sha-ref (io/file root "benchmark/evidence/content-registry.json"))})]
          (write-json! int-file int-updated))))))

(defn- write-finalization-with-root!
  "Write finalization and completion JSON with a given composition root,
   recomputing final_ref accordingly. Does NOT update input_set or scenario input."
  [root comp-root]
  (let [fin-file (io/file root "benchmark/finalization.json")
        comp-file (io/file root "completion.json")
        fin (json/read-str (slurp fin-file))
        strip-prefix (fn [s] (if (and (string? s) (str/starts-with? s "sha256:"))
                               (subs s 7)
                               s))
        projection {"domain" "prf/benchmark-finalization/v1"
                    "benchmark_id" (get fin "benchmark_id")
                    "assurance_artifact_sha256" (strip-prefix (get fin "assurance_artifact_sha256"))
                    "conclusion_sha256" (strip-prefix (get fin "conclusion_sha256"))
                    "evidence_content_registry_sha256" (strip-prefix (get fin "evidence_content_registry_sha256"))
                    "input_set_root" (get fin "input_set_root")
                    "semantic_composition_root" comp-root}
        final-ref (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))]
    (write-json! fin-file (assoc fin "semantic_composition_root" comp-root "final_ref" final-ref))
    (let [comp (json/read-str (slurp comp-file))]
      (write-json! comp-file (assoc comp "semantic_composition_root" comp-root "final_ref" final-ref)))))

(deftest phase2c-semantic-composition-root-in-finalization
  (testing "authoritative finalization carries semantic-composition-root"
    (let [root (temp-root)]
      (try (fixture! root)
           (with-authoritative-input root "sha256:abc123")
           (let [fin (read-json! (io/file root "benchmark/finalization.json"))]
             (is (= "sha256:abc123" (get fin "semantic_composition_root"))
                 "finalization carries the composition root"))
           (finally (delete-tree! root))))))

(deftest phase2c-semantic-composition-root-in-completion
  (testing "authoritative completion carries semantic-composition-root"
    (let [root (temp-root)]
      (try (fixture! root)
           (with-authoritative-input root "sha256:abc123")
           (let [comp (read-json! (io/file root "completion.json"))]
             (is (= "sha256:abc123" (get comp "semantic_composition_root"))
                 "completion carries the composition root"))
           (finally (delete-tree! root))))))

(deftest phase2c-composition-root-mismatch-fails-verification
  (testing "finalization and completion disagree on composition-root"
    (let [root (temp-root)]
      (try (fixture! root)
           (with-authoritative-input root "sha256:abc123")
           (let [comp-file (io/file root "completion.json")
                 comp (read-json! comp-file)]
             (write-json! comp-file (assoc comp "semantic_composition_root" "sha256:xyz")))
           (let [checks (get-in (verify/verify! root) ["checks"])]
             (is (false? (get checks "semantic-composition-root"))
                 "mismatched roots between finalization and completion fail")
             (is (true? (get checks "final-ref"))
                 "final_ref still matches (only completion's root field was tampered, not final_ref)"))
           (finally (delete-tree! root))))))

(deftest phase2c-unchanged-runs-remain-stable
  (testing "a stable authoritative run verifies successfully"
    (let [root (temp-root)]
      (try (fixture! root)
           (with-authoritative-input root "sha256:abc123")
           (let [checks (get-in (verify/verify! root) ["checks"])]
             (is (true? (get checks "authoritative-composition-presence"))
                 "authoritative run passes presence check")
             (is (true? (get checks "final-ref"))
                 "final_ref matches for stable authoritative run")
             (is (true? (get checks "semantic-composition-root"))
                 "composition roots are consistent"))
           (finally (delete-tree! root))))))

(deftest phase2c-authoritative-presence-rejects-empty
  (testing "evidence with composition but finalization has empty root -> fail"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (with-authoritative-input root "sha256:abc123")
        (let [evidence-file (io/file root "benchmark/evidence/evidence.edn")
              evidence (edn/read-string (slurp evidence-file))
              stripped-evidence (-> evidence
                                    (dissoc :results))]
          (spit evidence-file (pr-str stripped-evidence)))
        (let [checks (get-in (verify/verify! root) ["checks"])]
          (is (false? (get checks "authoritative-composition-presence"))
              "input_set declares authoritative but evidence root stripped -> presence check fails")
          (is (false? (get checks "final-ref"))
              "final_ref recomputed from stripped evidence (nil -> \"\") differs from finalization (has real root)"))
        (finally (delete-tree! root)))))

  (deftest phase2c-anti-transplant-composition-A-not-B
    (testing "evidence carrying composition-root-A cannot be relabeled as composition-B"
      (let [root-a (temp-root)
            root-b (temp-root)]
        (try
          (fixture! root-a)
          (with-authoritative-input root-a "sha256:composition-A-root")
          (let [checks-a (get-in (verify/verify! root-a) ["checks"])]
            (is (true? (get checks-a "authoritative-composition-presence"))
                "composition-A root presence passes for legitimate A run")
            (is (true? (get checks-a "final-ref"))
                "final_ref matches for legitimate A run (all commitments consistent)")
            (is (true? (get checks-a "semantic-composition-root"))
                "composition root matches between finalization and completion for A"))

          (fixture! root-b)
          (with-authoritative-input root-b "sha256:composition-A-root")
          (write-finalization-with-root! root-b "sha256:composition-B-root")
          (let [checks-b (get-in (verify/verify! root-b) ["checks"])]
            (is (false? (get checks-b "final-ref"))
                "final_ref mismatch: evidence-derived root (A) != finalization-claimed (B)")
            (is (false? (get checks-b "authoritative-composition-presence"))
                "presence check catches transplant: evidence-root (A) != finalization-root (B)")
            (is (true? (get checks-b "semantic-composition-root"))
                "finalization and completion both carry B root — that check passes; final-ref + presence fail"))
          (finally (do (delete-tree! root-a)
                       (delete-tree! root-b))))))))

(deftest phase2c-composition-substitution-A-evidence-as-B
  (testing "committed scenario declares root A but evidence relabeled as root B -> derivation check fails"
    (let [root (temp-root)]
      (try
        (fixture! root)
        (with-authoritative-input root "sha256:composition-A-root")
        ;; Verify baseline: stable authoritative-A run passes
        (let [checks-a (get-in (verify/verify! root) ["checks"])]
          (is (true? (get checks-a "composition-root-derivation"))
              "legitimate A run: derivation check passes (expected=A, evidence=A)"))

        ;; Substitution attack: rewrite evidence, finalization, and completion
        ;; to carry root B without touching the committed scenario input.
        ;; The input_set_root still commits the original scenario (root A),
        ;; so input-set-root passes and composition-root-derivation must fail.
        (let [evidence-file (io/file root "benchmark/evidence/evidence.edn")
              evidence (edn/read-string (slurp evidence-file))
              _ (spit evidence-file
                      (pr-str (assoc evidence :results [{:semantic-composition-root "sha256:composition-B-root"}])))]
          ;; write-finalization-with-root! updates finalization + completion + final_ref
          ;; to carry comp-root, but does NOT touch evidence or input_set.
          (write-finalization-with-root! root "sha256:composition-B-root")
          (let [checks-b (get-in (verify/verify! root) ["checks"])]
            (is (false? (get checks-b "composition-root-derivation"))
                "substitution attack: derivation check fails (expected=A from scenario, evidence=B)")
            (is (false? (get checks-b "final-ref"))
                "substitution attack: final_ref fails (evidence hash changed, final_ref stale)")))
        (finally (delete-tree! root))))))
