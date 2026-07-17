(ns resolver-sim.scenario.verify
  "Read-only verification of a completed canonical scenario evidence bundle."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.finalization :as finalization]
            [resolver-sim.validation.integration.artifact-registry :as artifact-registry]))

(defn- read-json [file]
  (json/read-str (slurp file) :key-fn keyword))

(defn- sha-ref [file]
  (str "sha256:" (lifecycle/sha256-file file)))

(defn- files-named [root name]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(= name (.getName %)))
       (sort-by #(.getPath %))
       vec))


(defn- evidence-files [root]
  (->> (file-seq (io/file root "scenarios"))
       (filter #(.isFile %))
       (filter #(and (= "event-evidence" (.getName (.getParentFile %)))
                     (.endsWith (.getName %) ".json")))
       (sort-by #(.getPath %))
       vec))

(defn- evidence-hashes [files]
  (set (map (fn [file]
              (let [record (read-json file)
                    hash (:evidence/hash record)]
                (finalization/sha256-ref hash)))
            files)))

(defn verify! [run-root]
  (try
    (let [root (io/file run-root)
          completion-file (io/file root "completion.json")
          registry-file (io/file root "manifest/artifacts.json")
          validation-file (io/file root "manifest/artifact-registry-validation.json")
          run-finalization-file (io/file root "evidence/finalizations/run/evidence-finalization.json")]
      (when-not (every? #(.isFile %) [completion-file registry-file validation-file run-finalization-file])
        (throw (ex-info "Scenario terminal artifact is missing" {:run-root run-root})))
      (let [completion (read-json completion-file)
            registry (read-json registry-file)
            persisted-validation (read-json validation-file)
            recalculated-validation (artifact-registry/validate-artifact-registry-from-file (.getPath registry-file))
            run-finalization (read-json run-finalization-file)
            scenario-files (files-named (io/file root "scenarios") "evidence-finalization.json")
            scenario-finalizations (mapv read-json scenario-files)
            event-records (mapv read-json (evidence-files root))
            records-by-scenario (group-by :scenario/id event-records)
            chain-results (mapv (fn [scenario-finalization]
                                  (let [scenario-id (get-in scenario-finalization [:subject :scenario-id])
                                        result (chain/verify-scenario-chain
                                                (get records-by-scenario scenario-id [])
                                                :scenario-id scenario-id)]
                                    {:scenario-id scenario-id
                                     :result result
                                     :declared-head (get-in scenario-finalization [:evidence :chain :head :hash])}))
                                scenario-finalizations)
            scenario-hashes (set (map sha-ref scenario-files))
            declared-scenario-hashes (set (map #(get-in % [:finalization :sha256])
                                               (get-in run-finalization [:evidence :scenario-finalizations])))
            event-hashes (evidence-hashes (evidence-files root))
            declared-event-hashes (set (get-in run-finalization [:evidence :declared-evidence-hashes]))
            registry-paths (set (map :path (:artifacts registry)))
            relative-run-finalization "evidence/finalizations/run/evidence-finalization.json"
                        diagnostic-file (io/file root "manifest/diagnostic-summary.json")
                        relative-scenario-finalizations
            (set (map #(str (.relativize (.toPath root) (.toPath %))) scenario-files))
            checks {"completion-lifecycle" (= "completed" (:lifecycle_status completion))
                    "registry-validation-report" (= "passed" (:status persisted-validation))
                                        "artifact-registry-recalculated" (= :passed (:status recalculated-validation))
                    "run-finalization-structural" (:valid? (finalization/validate-finalization run-finalization))
                    "run-finalization-verified" (= "verified" (get-in run-finalization [:verification :status]))
                    "scenario-finalizations-present" (boolean (seq scenario-files))
                    "scenario-finalizations-structural" (every? :valid? (map finalization/validate-finalization scenario-finalizations))
                                        "scenario-chains-recalculated" (every? (fn [{:keys [result declared-head]}]
                                                                                   (and (= :verified (:chain/status result))
                                                                                        (= declared-head
                                                                                           (finalization/sha256-ref (:chain/head-hash result)))))
                                                                                 chain-results)
                                        "scenario-finalization-set" (= scenario-hashes declared-scenario-hashes)
                    "event-evidence-set" (= event-hashes declared-event-hashes)
                    "finalizations-registered" (every? registry-paths
                                                                         (conj relative-scenario-finalizations relative-run-finalization))
                                        "diagnostic-summary" (and (.isFile diagnostic-file)
                                                                   (contains? registry-paths "manifest/diagnostic-summary.json"))}]
        {"schema_version" "scenario-verification.v1"
         "status" (if (every? true? (vals checks)) "passed" "failed")
         "checks" checks
         "run_id" (:run_id completion)}))
    (catch Exception error
      {"schema_version" "scenario-verification.v1"
       "status" "failed"
       "checks" {"terminal-artifacts-readable" false}
       "error" (.getMessage error)})))
