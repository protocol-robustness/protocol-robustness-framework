(ns resolver-sim.commands.run-benchmark
  "Run a benchmark by registered ID or manifest path."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
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

(defn- write-finalization! [context conclusion]
  (let [root (:run/root context)
        registry (io/file (str root) "manifest/artifacts.json")
        validation (io/file (str root) "manifest/artifacts-validation.json")
        assurance (io/file (str root) "benchmark/assertions/benchmark-assurance.json")
        conclusion-file (io/file (str root) "benchmark/conclusion.json")
        assurance-value (json/read-str (slurp assurance))
        projection {"domain" "prf/benchmark-finalization/v1"
                    "benchmark_id" (str (:benchmark/id context))
                    "run_id" (:run/id context)
                    "assurance_artifact_sha256" (sha-ref assurance)
                    "conclusion_sha256" (sha-ref conclusion-file)
                    "artifact_registry_sha256" (sha-ref registry)
                    "registry_validation_sha256" (sha-ref validation)
                    "input_set_root" (get assurance-value "input_set_root")}
        value {"schema_version" "benchmark-finalization.v1"
               "domain" "prf/benchmark-finalization/v1"
               "benchmark_id" (str (:benchmark/id context))
               "run_id" (:run/id context)
               "assurance_artifact" {"ref" "benchmark/assertions/benchmark-assurance.json" "sha256" (sha-ref assurance)}
               "conclusion_sha256" (sha-ref conclusion-file)
               "artifact_registry_sha256" (sha-ref registry)
               "registry_validation_sha256" (sha-ref validation)
               "input_set_root" (get assurance-value "input_set_root")
               "final_ref" (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))}
        target (io/file (str root) "benchmark/finalization.json")]
    (lifecycle/atomic-json! target value)
    value))

(defn- complete! [context conclusion]
  (let [root (:run/root context)
        finalization (write-finalization! context conclusion)
        registry (io/file (str root) "manifest/artifacts.json")
        validation (io/file (str root) "manifest/artifacts-validation.json")
        finalization-file (io/file (str root) "benchmark/finalization.json")]
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
      :input_set_root (get finalization "input_set_root")
      :artifact_registry_ref "manifest/artifacts.json"
      :artifact_registry_sha256 (str "sha256:" (lifecycle/sha256-file registry))
      :registry_validation_ref "manifest/artifacts-validation.json"
      :registry_validation_sha256 (str "sha256:" (lifecycle/sha256-file validation))})))

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
        value {"schema_version" "benchmark-summary.v1"
               "run_id" (:run/id context)
               "benchmark_id" (str (get-in evidence [:benchmark :benchmark/id]))
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
                                  :write-manifest (fn [_ result] (write-run-manifest! context (:evidence result)))
                                  :snapshot-definition (fn [_ result] (snapshot-definition! context (:evidence result)))
                                  :write-conclusion (fn [_ result]
                                                                                        (write-conservation! context (:evidence result))
                                                                                        (reset! benchmark-conclusion (conclusion/write! context (:evidence result))))
                                  :write-summary (fn [_ result]
                                                                                     (write-assurance! context (:evidence result) @benchmark-conclusion)
                                                                                     (write-summary! context (:evidence result) @benchmark-conclusion))
                                  :scan-sensitivity (fn [_ _] (scan-sensitivity! context))
                                  :build-inventory (fn [_ _] (inventory/build! context))
                                  :finalize-registry (fn [_ _] (registry/finalize! (:run/root context)))
                                  :validate-registry (fn [_ _] (validate-registry! context))
                                  :complete (fn [_ _] (complete! context @benchmark-conclusion))}
                                  overrides))]
        {:exit-code (or (:exit-code execution) 1) :run/id (:run/id context) :run/root (str (:run/root context))})
      (finally (lifecycle/release-run-lock! lock)))))

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

      run-root
      (run-with-root! benchmark-id run-root key (or sensitivity-profile :public) {})

      output
      (let [result (invoke! benchmark-id {:output output :key key})]
        {:exit-code (or (:exit-code result) 1)})

      :else
      {:exit-code 2 :message "Specify --run-root for a canonical benchmark bundle or --output for a legacy export"})))
