(ns resolver-sim.commands.scenario-orchestration
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.java.shell :as shell]
            [resolver-sim.commands.scenario-registry :as registry]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.io.input-source :as input-source]
                        [resolver-sim.commands.scenario-manifest :as manifest]
                                    [resolver-sim.commands.scenario-safety :as safety]
                                                [resolver-sim.commands.scenario-extraction :as extraction]
                                                                                                            [resolver-sim.commands.scenario-diagnostics :as diagnostics]
                                                                                                            [resolver-sim.commands.scenario-inventory :as inventory]
                                                                                                                                    [resolver-sim.evidence.chain :as chain]
                                                                                                                                    [resolver-sim.evidence.finalization :as finalization]
                                                                                                                                                                                                                                                                        [resolver-sim.evidence.finalization-signing :as finalization-signing]
                                                                                                                                                                                                                                                                        [resolver-sim.validation.integration.artifact-registry :as artifact-registry]))
                                                            (def ^:private phases [:check-runtime :execute :write-manifest :extract-artifacts :scan-sensitivity :finalize-registry :validate-registry :finalize-run-evidence :write-diagnostic :refresh-inventory :refresh-registry :revalidate-registry])
(defn- p [x] (str x))
(defn- checked [phase command result] (if (zero? (:exit result)) result (throw (ex-info "Required scenario finalization phase failed" {:phase phase :command command :exit-code (:exit result) :out (:out result) :err (:err result)}))))
(defn- layout! [c] (doseq [x [(:run/root c) (:manifest/dir c) (:scenario/root c) (:execution/dir c) (:forensic/dir c) (:summaries/dir c)]] (.mkdirs (io/file (p x)))) (spit (io/file (p (:run/root c)) ".run-state") (pr-str {:run/id (:run/id c) :state :running})) c)
(defn default-check-runtime! [_] {})
(defn default-execute! [c]
  (let [source (input-source/source (:scenario/ref c))
        hash (input-source/sha256 source)
        destination (io/file (p (:inputs/dir c)) (str (subs hash 0 12) "-" (:input/display-name source)))
        provenance (lifecycle/snapshot-input! (:run/root c) source destination)
        result ((requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report)
                {:scenario (:input/snapshot provenance) :run-id (:run/id c) :run-root (p (:run/root c)) :scenario-slug (:scenario/slug c) :scenario-root (p (:scenario/root c)) :execution-dir (p (:execution/dir c)) :artifact-dir (p (:forensic/dir c)) :summary-dir (p (:summaries/dir c)) :manifest-dir (p (:manifest/dir c)) :output-file (p (:replay/file c))} {:report-format (:report-format c)})]
    (assoc result :input/provenance provenance)))
(defn- process! [phase command] (checked phase command (apply shell/sh command)))
(defn default-write-manifest! [c e] (manifest/write! c e))
(defn default-extract-artifacts! [c _]
  (let [result (extraction/extract! c)]
    (manifest/write-classification! (:manifest/dir c) (:classification result))
    result))
(defn default-scan-sensitivity! [c _]
  (let [result (if (= :public (:sensitivity/profile c))
                 (safety/scan-public-bundle! (:run/root c))
                 (safety/scan-internal-bundle! (:run/root c)))]
    (safety/write-sensitivity-report! (:manifest/dir c) result)
    (inventory/build! c)
    result))
(defn default-finalize-registry! [c _] (registry/finalize! (:run/root c)))
(defn default-validate-registry! [c _]
  (let [registry-file (io/file (str (p (:manifest/dir c)) "/artifacts.json"))
        result (artifact-registry/validate-artifact-registry-from-file (.getPath registry-file))
        report (io/file (.getParentFile registry-file) "artifact-registry-validation.json")]
    (spit report (json/write-str result))
    (when (= :failed (:status result))
      (throw (ex-info "Artifact registry validation failed" {:registry (.getPath registry-file)})))
    result))
(defn default-finalize-run-evidence! [c execution]
  (let [run-root (io/file (p (:run/root c)))
        input-path (or (get-in execution [:input/provenance :input/snapshot])
                       (:scenario/ref c))
        forensic-dirs (->> (file-seq (io/file run-root "scenarios"))
                           (filter #(.isDirectory %))
                           (filter #(= "forensic" (.getName %))))
        scenario-finalizations (->> forensic-dirs
                                    (mapcat #(filter (fn [file]
                                                       (and (.isFile file)
                                                            (= "evidence-finalization.json" (.getName file))))
                                                     (file-seq (io/file % "finalizations" "scenarios"))))
                                    vec)
        evidence-files (->> forensic-dirs
                            (mapcat #(or (.listFiles (io/file % "event-evidence")) []))
                            (filter #(.isFile %))
                            (filter #(.endsWith (.getName %) ".json"))
                            vec)
        evidence-node-files (->> forensic-dirs
                                 (mapcat #(or (.listFiles (io/file % "evidence-nodes")) []))
                                 (filter #(.isFile %))
                                 (filter #(.endsWith (.getName %) ".edn"))
                                 vec)
        scenario-executions (mapv #(json/read-str (slurp %) :key-fn keyword)
                                  scenario-finalizations)
        execution-statuses (map #(get-in % [:execution :status]) scenario-executions)
        aborted-count (count (filter #{"aborted"} execution-statuses))
        failed-count (count (filter #{"failed"} execution-statuses))
        completed-count (count (filter #{"completed"} execution-statuses))
        run-status (cond (pos? aborted-count) "aborted"
                         (pos? failed-count) "failed"
                         :else "completed")
        content-registry-path (io/file (p (:run/root c)) "evidence" "content-registry.json")
        output-path (io/file (p (:run/root c)) "evidence" "finalizations" "run"
                             "evidence-finalization.json")
        reconciliation-report-path (io/file (p (:run/root c)) "evidence" "reports"
                                            "run-evidence-reconciliation.json")
        _content-registry (finalization/write-content-registry! content-registry-path evidence-files)]
    (let [written (finalization/write-run-finalization!
     {:finalization-path output-path
           :reconciliation-report-path reconciliation-report-path
           :scenario-finalization-files scenario-finalizations
      :evidence-files evidence-files
      :evidence-node-files evidence-node-files
      :registry-path content-registry-path
      :run {:run-id (:run/id c)
            :run-input-hash (finalization/sha256-ref (chain/compute-file-sha256 input-path))}
      :execution {:status run-status
                        :terminality (if (pos? aborted-count) "open" "closed")
                        :scenario-count (count scenario-finalizations)
                        :completed-scenario-count completed-count
                        :failed-scenario-count failed-count
                        :aborted-scenario-count aborted-count}
      :policy {:profile-id (or (:profile/id (:signing/config c)) "inspection.v1")}})
          signing-config (:signing/config c)
          forensic? (= :forensic-release.v1 (:profile/id signing-config))]
      (cond
        signing-config
        (let [config-validation (finalization-signing/validate-signing-config signing-config)]
          (when-not (:valid? config-validation)
            (throw (ex-info "Run finalization signing configuration is invalid"
                            {:reason :signature/signing-not-configured
                             :validation config-validation})))
          (let [signer (finalization-signing/file-signer! (get-in signing-config [:signing :key-provider]))
                trusted (finalization-signing/load-trusted-registry! (:verification signing-config))
                policy {:signer-role (get-in signing-config [:signing :signer-role])
                        :threshold {:minimum (or (get-in signing-config [:verification :minimum-trusted-signatures])
                                                  (get-in signing-config [:signing :threshold]) 1)}}
                signed (finalization-signing/sign-persisted-finalization!
                        {:finalization-path (:path written)
                         :signatures-dir (io/file (p (:run/root c)) "evidence" "finalizations" "run" "signatures")
                         :signer signer
                         :trusted-registry (:registry trusted)
                         :policy policy})
                _report (finalization-signing/write-verification-report!
                         (io/file (p (:run/root c)) "evidence" "reports"
                                  "run-finalization-signature-verification.json")
                         (:payload-hash signed) (:hash trusted) (:verification signed))
                timestamp-config (:timestamp signing-config)
                timestamp-result
                (when timestamp-config
                  (let [tsa-registry (finalization-signing/load-tsa-registry! timestamp-config)
                        receipt (finalization-signing/request-rfc3161-receipt!
                                 {:signature-path (:path signed)
                                  :timestamps-dir (io/file (p (:run/root c)) "evidence" "finalizations" "run" "timestamps")
                                  :tsa-url (:tsa-url timestamp-config)})
                        evaluation (if (:valid? receipt)
                                     (finalization-signing/evaluate-timestamp-receipt
                                      (assoc (:metadata receipt)
                                             :receipt-path (:receipt-path receipt)
                                             :signature-hash (:payload-hash signed))
                                      (:registry tsa-registry))
                                     {:status :requirement/present-invalid
                                      :reason (:reason receipt)})]
                    (finalization-signing/write-timestamp-verification-report!
                     (io/file (p (:run/root c)) "evidence" "reports"
                              "run-finalization-timestamp-verification.json")
                     (:hash tsa-registry) evaluation)
                    evaluation))]
            (when-not (get-in signed [:verification :valid?])
              (throw (ex-info "Run finalization signature policy is unsatisfied"
                              {:reason :signature/threshold-not-met
                               :verification (:verification signed)})))
            (when (and (get-in signing-config [:timestamp :required?])
                       (not= :requirement/satisfied (:status timestamp-result)))
              (throw (ex-info "Run finalization timestamp policy is unsatisfied"
                              {:reason :timestamp/policy-unsatisfied
                               :verification timestamp-result})))
            signed))

        forensic?
        (throw (ex-info "Forensic release requires protected runtime signing configuration"
                        {:reason :signature/signing-not-configured}))

        :else
        written))))
(defn default-write-diagnostic! [c execution] (diagnostics/write! c execution))
(defn default-refresh-inventory! [c _] (inventory/build! c))
(defn default-refresh-registry! [c _] (registry/finalize! (:run/root c)))
(defn default-revalidate-registry! [c e] (default-validate-registry! c e))
(defn default-complete! [c e]
  (let [root (:run/root c)
        registry (io/file (str root) "manifest/artifacts.json")
        validation (io/file (str root) "manifest/artifact-registry-validation.json")
        outcome (if (zero? (:exit-code e)) "pass" "fail")]
    (lifecycle/complete!
     root
     {:schema_version "run-completion.v1"
      :run_id (:run/id c)
      :run_type "scenario"
      :lifecycle_status "completed"
      :semantic_status outcome
      ;; Compatibility fields retained for existing scenario consumers.
      :status "completed"
      :outcome outcome
      :exit_code (:exit-code e)
      :manifest_ref "manifest/run.json"
      :artifact_registry_ref "manifest/artifacts.json"
      :artifact_registry_sha256 (when (.isFile registry) (str "sha256:" (lifecycle/sha256-file registry)))
      :registry_validation_ref "manifest/artifact-registry-validation.json"
      :registry_validation_sha256 (when (.isFile validation) (str "sha256:" (lifecycle/sha256-file validation)))})
    {}))
(def ^:private defaults {:check-runtime default-check-runtime! :execute default-execute! :write-manifest default-write-manifest! :extract-artifacts default-extract-artifacts! :scan-sensitivity default-scan-sensitivity! :finalize-registry default-finalize-registry! :validate-registry default-validate-registry! :finalize-run-evidence default-finalize-run-evidence! :write-diagnostic default-write-diagnostic! :refresh-inventory default-refresh-inventory! :refresh-registry default-refresh-registry! :revalidate-registry default-revalidate-registry! :complete default-complete!})
(defn run-scenario!
  ([context] (run-scenario! context {}))
  ([context overrides]
   (let [lock (safety/acquire-lock! (:run/root context))]
     (try
       (layout! context)
       (let [phase-fns (merge defaults overrides)
             records (atom [])
             run-phase (fn [phase execution]
                         (try
                           (let [result (if (#{:check-runtime :execute} phase)
                                          ((phase-fns phase) context)
                                          ((phase-fns phase) context execution))]
                             (swap! records conj {:phase phase :status :completed})
                             result)
                           (catch Throwable error
                             (swap! records conj {:phase phase :status :failed :error (.getMessage error)})
                             (throw error))))]
         (try
           (run-phase :check-runtime nil)
           (let [execution (assoc (run-phase :execute nil) :duration-ms 0)]
             (doseq [phase (drop 2 phases)] (run-phase phase execution))
             (run-phase :complete execution)
             {:command/status :completed :scenario/outcome (if (zero? (:exit-code execution)) :pass :fail)
              :exit-code (:exit-code execution) :run/id (:run/id context) :run/root (p (:run/root context)) :phases @records})
           (catch Throwable error
             {:command/status :failed :scenario/outcome :unknown :exit-code 1
              :run/id (:run/id context) :run/root (p (:run/root context)) :phases @records :error (.getMessage error)})))
       (finally (safety/release-lock! lock))))))
