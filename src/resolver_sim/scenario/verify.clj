(ns resolver-sim.scenario.verify
  "Read-only verification of a completed canonical scenario evidence bundle."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.finalization :as finalization]
            [resolver-sim.evidence.node :as evidence-node]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.run.verdict-policy :as verdict-policy]
            [resolver-sim.validation.integration.artifact-registry :as artifact-registry]
            [resolver-sim.yield.partial-fill :as partial-fill]))

(defn- read-json [file]
  (json/read-str (slurp file) :key-fn keyword))

(defn- sha-ref [file]
  (hash-ref/sha256-ref (lifecycle/sha256-file file)))

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
              (finalization/sha256-ref (:evidence/hash (read-json file))))
            files)))

(defn- enum-keyword [value]
  (when value (keyword value)))

(defn- restore-claim-keys [claim-map]
  (into {} (map (fn [[key value]] [(if (keyword? key) (name key) key) value]) claim-map)))

(defn- normalize-replay-decision
  "Restore enum keywords after JSON replay persistence so the canonical decision
   preimage is identical to the replay-time artifact preimage."
  [decision]
  (-> decision
      (update :requested restore-claim-keys)
      (update :filled restore-claim-keys)
      (update :deferred restore-claim-keys)
      (update :haircut restore-claim-keys)
      (update :unrealized restore-claim-keys)
      (assoc :artifact/kind :yield/partial-fill-decision)
      (update :decision/source enum-keyword)
      (update :module/id enum-keyword)
      (update :token enum-keyword)
      (update :settlement-mode enum-keyword)
      (update :allocation/scope enum-keyword)
      (update-in [:allocation/domain :module/id] enum-keyword)
      (update-in [:allocation/domain :token] enum-keyword)
      (update-in [:policy :mode] enum-keyword)
      (update-in [:policy :rounding-policy] enum-keyword)
      (update-in [:policy :unrealized-yield-treatment] enum-keyword)
      (update-in [:policy :residual-treatment] enum-keyword)
      (update-in [:policy :post-partial-fill-accrual] enum-keyword)
      (update-in [:policy :fill-order] #(mapv enum-keyword %))
      (update-in [:evidence :fill-mode] enum-keyword)
      (update-in [:evidence :rounding-policy] enum-keyword)
      (update-in [:evidence :fill-order] #(mapv enum-keyword %))))

(defn- replay-partial-fill-decisions [root]
  (->> (for [file (file-seq (io/file root "scenarios"))
             :when (= "replay-output.json" (.getName file))]
         (vals (get-in (read-json file)
                       [:run/scenario-results 0 :world :yield/partial-fill-decisions]
                       {})))
       (apply concat)
       vec))

(defn- projection-partial-fill-decisions [root]
  (->> (for [file (file-seq (io/file root "scenarios"))
             :when (= "partial-fill-decisions.json" (.getName file))]
         (:decisions (read-json file)))
       (apply concat)
       vec))

(defn- verified-decision [persisted]
  (let [normalized (normalize-replay-decision persisted)
        preimage (:decision/preimage persisted)
        base (if preimage (edn/read-string preimage) (dissoc normalized :decision/id :decision/hash))]
    (assoc base :decision/id (:decision/id persisted) :decision/hash (:decision/hash persisted))))

(defn- verify-partial-fill-artifacts [root]
  (let [decisions (mapv verified-decision (replay-partial-fill-decisions root))
        projections (projection-partial-fill-decisions root)
        by-id (into {} (map (juxt :decision_id identity) projections))]
    (if (empty? decisions)
      {:applicable? false :valid? (empty? projections)}
      (let [decision-valid?
            (every? (fn [decision]
                      (let [base (dissoc decision :decision/id :decision/hash)
                            expected-hash (str "sha256:"
                                               (canonical/hash-with-intent {:hash/intent :evidence-record} base))
                            projection (get by-id (:decision/id decision))
                             closed-form (try
                                           (partial-fill/partial-fill-closed-form-checks decision)
                                           (catch clojure.lang.ExceptionInfo e
                                             (:check-results (ex-data e))))]
                         (and projection
                              (= expected-hash (:decision/hash decision))
                              (= (:decision/hash decision) (:decision_sha256 projection))
                              (= (reduce + 0 (vals (:requested decision))) (:total_requested projection))
                              (= (reduce + 0 (vals (:filled decision))) (:total_filled projection))
                              (= (reduce + 0 (vals (:deferred decision))) (:total_deferred projection))
                              (every? #(not= :fail (:status %)) closed-form))))
                    decisions)]
        {:applicable? true
         :valid? (and decision-valid? (= (count decisions) (count projections)))}))))

(defn- verify-fraud-group-slash-artifacts [root]
  (let [replay-files (files-named (io/file root "scenarios") "replay-output.json")
        projection-files (files-named (io/file root "scenarios") "fraud-group-slash-allocation.json")
        replay-slashes (->> replay-files
                            (mapcat #(vals (get-in (read-json %) [:run/scenario-results 0 :world :pending-fraud-slashes] {})))
                            (filter #(= "fraud-group" (name (:slash/kind %))))
                            (sort-by :slash/id)
                            vec)
        projections (->> projection-files (mapcat #(:slashes (read-json %))) (sort-by :slash_id) vec)]
    (if (empty? replay-slashes)
      {:applicable? false :valid? (empty? projections)}
      {:applicable? true
       :valid? (and (= (count replay-slashes) (count projections))
                    (every? true?
                            (map (fn [slash projection]
                                   (and (= (:slash/id slash) (:slash_id projection))
                                        (= (:liable-group/id slash) (:liable_group_id projection))
                                        (= (:liable-group/member-snapshot-hash slash)
                                           (:member_snapshot_hash projection))
                                        (= (:members slash) (:member_snapshot projection))
                                        (= (:fraud-incident-ref slash) (:fraud_incident_ref projection))
                                        (= (get-in slash [:allocation :allocations]) (:execution_rows projection))
                                        (= (:amount slash) (+ (get-in projection [:totals :allocated])
                                                              (get-in projection [:totals :allocation_unmet])))
                                        (= (get-in projection [:totals :uncollected])
                                           (+ (get-in projection [:totals :stayed])
                                              (get-in projection [:totals :unpaid])))
                                        (:allocation_not_above_obligation (:reconciles projection))
                                        (:allocated_plus_allocation_unmet_equals_obligation (:reconciles projection))
                                        (:paid_plus_stayed_plus_unpaid_equals_allocated (:reconciles projection))
                                        (:stayed_plus_unpaid_equals_uncollected (:reconciles projection))))
                                 replay-slashes projections)))})))

(defn- verify-pro-rata-mechanism-nodes
  "Validate the first-class pro-rata mechanism-node contract when present.
   Older nodes remain readable; only the versioned contract emitted by current
   producers is required to carry these package-verifiable bindings."
  [root event-records]
  (let [files (->> (file-seq (io/file root "scenarios"))
                   (filter #(.isFile %))
                   (filter #(and (= "evidence-nodes" (.getName (.getParentFile %))
                                    (.endsWith (.getName %) ".edn"))))
                   vec)
        loaded (mapv (fn [file]
                       (try {:file file :node (edn/read-string (slurp file))}
                            (catch Exception _ {:file file :error :unreadable})))
                     files)
        nodes (map :node (remove :error loaded))
        known-hashes (set (keep :node-hash nodes))
        mechanism-nodes (filter #(= "pro-rata-mechanism-node.v1"
                                    (get-in % [:extensions :mechanism/node-schema-version]))
                                nodes)
        event-hashes (set (map :evidence/hash event-records))
        valid-node? (fn [node]
                      (and (:valid? (evidence-node/validate-node node :known-parent-hashes known-hashes))
                           (= :mechanism/pro-rata-allocation
                              (get-in node [:extensions :mechanism/id]))
                           (= 1 (get-in node [:extensions :mechanism/version]))
                           (contains? event-hashes
                                      (get-in node [:extensions :pro-rata/evidence-hash]))
                           (string? (get-in node [:extensions :pro-rata/allocation-result-hash]))
                           (string? (get-in node [:extensions :pro-rata/artifact-hash]))))]
    {:applicable? (boolean (seq mechanism-nodes))
     :valid? (and (empty? (filter :error loaded))
                  (every? valid-node? mechanism-nodes))
     :node-count (count mechanism-nodes)}))

(defn- verify-canonical-integrity [root completion]
  (let [integrity-file (io/file root "manifest/canonical-integrity.json")
        deferred-file (io/file root "manifest/forensic-claims-status.json")
        finalization-file (io/file root "evidence/finalizations/run/evidence-finalization.json")
        content-registry-file (io/file root "evidence/content-registry.json")]
    (if-not (every? #(.isFile %) [integrity-file deferred-file finalization-file content-registry-file])
      {:valid? false :reason :missing-assurance-artifact
       :checks {"assurance-files-readable" false}}
      (let [integrity (read-json integrity-file)
            deferred (read-json deferred-file)
            sub-checks {"schema-version" (= "canonical-integrity.v1" (:schema_version integrity))
                        "assurance-kind" (= "unsigned-canonical-integrity" (:assurance_kind integrity))
                        "status" (= "passed" (:status integrity))
                        "run-id" (= (:run_id completion) (:run_id integrity))
                        "run-finalization-match" (= (sha-ref finalization-file) (get-in integrity [:run_finalization :sha256]))
                        "content-registry-match" (= (sha-ref content-registry-file) (get-in integrity [:evidence_content_registry :sha256]))
                        "run-finalization-verified" (true? (get-in integrity [:checks :run_finalization_verified]))
                        "pre-assurance-registry-valid" (true? (get-in integrity [:checks :pre_assurance_registry_valid]))
                        "operator-identity-excluded" (false? (get-in integrity [:scope :operator_identity]))
                        "runtime-isolation-excluded" (false? (get-in integrity [:scope :runtime_isolation]))
                        "forensic-schema-version" (= "forensic-claims-status.v1" (:schema_version deferred))
                        "forensic-status" (= "deferred" (:status deferred))
                        "forensic-reason-code" (= "unsigned-forensic-signing-not-configured" (:reason_code deferred))
                        "forensic-integrity-ref" (= "manifest/canonical-integrity.json" (:canonical_integrity_ref deferred))}]
        {:valid? (every? true? (vals sub-checks))
         :checks sub-checks
         :integrity integrity
         :deferred deferred}))))

(defn verify! [run-root]
  (try
    (let [root (io/file run-root)
          completion-file (io/file root paths/completion)
          registry-file (io/file root paths/artifacts-registry)
          validation-file (io/file root "manifest/artifact-registry-validation.json")
          run-finalization-file (io/file root "evidence/finalizations/run/evidence-finalization.json")
          canonical-integrity-file (io/file root "manifest/canonical-integrity.json")
          forensic-claims-status-file (io/file root "manifest/forensic-claims-status.json")
          verdict-policy-file (io/file root "manifest/verdict-policy.json")]
      (when-not (every? #(.isFile %) [completion-file registry-file validation-file run-finalization-file canonical-integrity-file forensic-claims-status-file verdict-policy-file])
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
                                    {:result result
                                     :declared-head (get-in scenario-finalization [:evidence :chain :head :hash])}))
                                scenario-finalizations)
            scenario-hashes (set (map sha-ref scenario-files))
            declared-scenario-hashes (set (map #(get-in % [:finalization :sha256])
                                               (get-in run-finalization [:evidence :scenario-finalizations])))
            event-hashes (evidence-hashes (evidence-files root))
            declared-event-hashes (set (get-in run-finalization [:evidence :declared-evidence-hashes]))
            registry-paths (set (map :path (:artifacts registry)))
            canonical-integrity-verification (verify-canonical-integrity root completion)
            verdict-policy-verification (verdict-policy/verify! root (json/read-str (slurp verdict-policy-file)) "scenario" (:run_id completion))
            partial-fill-verification (verify-partial-fill-artifacts root)
            fraud-group-slash-verification (verify-fraud-group-slash-artifacts root)
            pro-rata-mechanism-node-verification (verify-pro-rata-mechanism-nodes root event-records)
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
                                              (contains? registry-paths "manifest/diagnostic-summary.json"))
                    "canonical-integrity" (:valid? canonical-integrity-verification)
                    "verdict-policy" (:valid? verdict-policy-verification)
                    "assurance-artifacts-registered" (every? registry-paths
                                                             #{"manifest/canonical-integrity.json"
                                                               "manifest/forensic-claims-status.json"
                                                               "manifest/verdict-policy.json"})
                    "partial-fill-artifacts" (:valid? partial-fill-verification)
                    "fraud-group-slash-artifacts" (:valid? fraud-group-slash-verification)
                    "pro-rata-mechanism-nodes" (:valid? pro-rata-mechanism-node-verification)}]
        {"schema_version" "scenario-verification.v1"
         "status" (if (every? true? (vals checks)) "passed" "failed")
         "checks" checks
         "canonical-integrity-checks" (:checks canonical-integrity-verification)
         "run_id" (:run_id completion)}))
    (catch Exception error
      {"schema_version" "scenario-verification.v1"
       "status" "failed"
       "checks" {"terminal-artifacts-readable" false}
       "error" (.getMessage error)})))
