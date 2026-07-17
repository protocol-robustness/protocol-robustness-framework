(ns resolver-sim.benchmark.verify
  "Read-only verification of canonical benchmark terminal commitments."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.conservation :as conservation]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as canonical]))

(defn- read-json [file] (json/read-str (slurp file)))
(defn- sha-ref [file] (str "sha256:" (lifecycle/sha256-file file)))

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

(defn verify! [run-root]
  (try
    (let [root (io/file run-root)
          completion-file (io/file root "completion.json")
          finalization-file (io/file root "benchmark/finalization.json")
          assurance-file (io/file root "benchmark/assertions/benchmark-assurance.json")
          conservation-file (io/file root "benchmark/assertions/conservation.json")
          registry-file (io/file root "manifest/artifacts.json")
          validation-file (io/file root "manifest/artifacts-validation.json")]
      (when-not (every? #(.isFile %) [completion-file finalization-file assurance-file conservation-file registry-file validation-file])
        (throw (ex-info "Benchmark terminal artifact is missing" {:run-root run-root})))
      (let [completion (read-json completion-file)
            finalization (read-json finalization-file)
            assurance (read-json assurance-file)
            conservation (read-json conservation-file)
            registry (read-json registry-file)
            recalculated-conservation (recalculate-conservation root conservation)
            inputs (get assurance "input_set")
            projection {"domain" "prf/benchmark-finalization/v1"
                        "benchmark_id" (get finalization "benchmark_id")
                        "run_id" (get finalization "run_id")
                        "assurance_artifact_sha256" (sha-ref assurance-file)
                        "conclusion_sha256" (get finalization "conclusion_sha256")
                        "artifact_registry_sha256" (sha-ref registry-file)
                        "registry_validation_sha256" (sha-ref validation-file)
                        "input_set_root" (get assurance "input_set_root")}
            expected-final-ref (str "sha256:" (canonical/domain-hash "BENCHMARK_FINALIZATION_V1" projection))
            checks {"completion-finalization-hash" (= (get completion "finalization_sha256") (sha-ref finalization-file))
                    "registry-hash" (= (get finalization "artifact_registry_sha256") (sha-ref registry-file))
                    "validation-hash" (= (get finalization "registry_validation_sha256") (sha-ref validation-file))
                    "artifact-registry-recalculated" (registry-artifacts-valid? root registry)
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
