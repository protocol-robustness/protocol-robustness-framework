(ns resolver-sim.commands.scenario-orchestration
  (:require [clojure.data.json :as json] [clojure.edn :as edn] [clojure.java.io :as io] [clojure.java.shell :as shell]
            [clojure.walk :as walk]
            [resolver-sim.commands.scenario-registry :as registry]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.io.scenarios :as io-scenarios]
            [resolver-sim.commands.scenario-manifest :as manifest]
            [resolver-sim.commands.scenario-safety :as safety]
            [resolver-sim.commands.scenario-extraction :as extraction]
            [resolver-sim.commands.scenario-diagnostics :as diagnostics]
            [resolver-sim.commands.scenario-inventory :as inventory]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.finalization :as finalization]
            [resolver-sim.evidence.finalization-signing :as finalization-signing]
            [resolver-sim.sensitivity.sentinel :as sentinel]
            [resolver-sim.sensitivity.propagation :as prop]
            [resolver-sim.evidence.attestation-bundle :as ab]
            [resolver-sim.evidence.attestation-completeness-profile :as acp]
            [resolver-sim.run.runner-finalization :as runner-finalization]
            [resolver-sim.run.package-index :as package-index]
            [resolver-sim.run.verdict-policy :as verdict-policy]
            [resolver-sim.forensic.source-hash :as source-hash]
            [resolver-sim.run.distribution-provenance :as distribution]
            [resolver-sim.validation.integration.artifact-registry :as artifact-registry]
            [resolver-sim.logging :as log]))
(def ^:private phases [:check-runtime :execute :write-manifest :extract-artifacts :scan-sensitivity :finalize-registry :validate-registry :finalize-run-evidence :build-attestation-bundle :write-canonical-assurance :write-verdict-policy :write-diagnostic :write-pro-rata-mechanism-index :refresh-inventory :refresh-registry :revalidate-registry :write-package-index])
(defn- p [x] (str x))
(defn- checked [phase command result] (if (zero? (:exit result)) result (throw (ex-info "Required scenario finalization phase failed" {:phase phase :command command :exit-code (:exit result) :out (:out result) :err (:err result)}))))
(defn- layout! [c] (doseq [x [(:run/root c) (:manifest/dir c) (:scenario/root c) (:execution/dir c) (:forensic/dir c) (:summaries/dir c)]] (.mkdirs (io/file (p x)))) (spit (io/file (p (:run/root c)) paths/run-state) (pr-str {:run/id (:run/id c) :state :running})) c)
(defn default-check-runtime! [_] {})
(defn default-execute! [c]
  (let [source (input-source/source (:scenario/ref c))
        hash (input-source/sha256 source)
        destination (io/file (p (:inputs/dir c)) (str (subs hash 0 12) "-" (:input/display-name source)))
        provenance (lifecycle/snapshot-input! (:run/root c) source destination)
        scenario (io-scenarios/load-scenario-file (:input/snapshot provenance))
        scenario-id (:scenario-id scenario)
        execution-id (str "execution:" (:run/id c))
        _ (when-not (and (string? scenario-id) (seq scenario-id))
            (throw (ex-info "Canonical single-scenario execution requires a scenario id"
                            {:code :package/missing-authoritative-identity :field :scenario/id})))
        result ((requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report)
                {:scenario (:input/snapshot provenance) :run-id (:run/id c) :run-root (p (:run/root c))
                 :scenario-id scenario-id :execution-id execution-id
                 :scenario/source-hash (str "sha256:" (:input/sha256 provenance))
                 :scenario/input-snapshot-relative (:input/snapshot-relative provenance)
                 :scenario-slug (:scenario/slug c) :scenario-root (p (:scenario/root c)) :execution-dir (p (:execution/dir c)) :artifact-dir (p (:forensic/dir c)) :summary-dir (p (:summaries/dir c)) :manifest-dir (p (:manifest/dir c)) :output-file (p (:replay/file c))} {:report-format (:report-format c)})]
    (assoc result :input/provenance provenance :scenario/id scenario-id :execution/id execution-id)))
(defn- process! [phase command] (checked phase command (apply shell/sh command)))
(defn default-write-manifest! [c e] (manifest/write! c e))
(defn default-extract-artifacts! [c execution]
  (let [result (extraction/extract! c execution)]
    (manifest/write-classification! (:manifest/dir c) (:classification result))
    result))
(defn default-scan-sensitivity! [c e]
  (let [result (if (= :public (:sensitivity/profile c))
                 (safety/scan-public-bundle! (:run/root c))
                 (safety/scan-internal-bundle! (:run/root c)))
        scenarios (get-in e [:run-result :results] [])
        ;; Raw execution results may not yet carry enriched sensitivity metadata.
        ;; Use the least restrictive explicit structural default rather than
        ;; passing a nil level to the persisted report encoder.
        run-sensitivity (or (prop/merge-sensitivity
                             (mapv prop/effective-scenario-sensitivity scenarios))
                            {:level :sensitivity/public})]
    (safety/write-sensitivity-report! (:manifest/dir c) result run-sensitivity scenarios
                                      {:run-id (:run/id c)
                                       :profile (:sensitivity/profile c)
                                       :sentinel-version sentinel/sentinel-version
                                       :scenario-ids (mapv :scenario-id scenarios)})
    (inventory/build! c)
    result))
(defn default-finalize-registry! [c _] (registry/finalize! (:run/root c)))
(defn default-validate-registry! [c _]
  (let [registry-file (io/file (str (p (:manifest/dir c)) "/artifacts.json"))
        registry-ref paths/artifacts-registry
        result (artifact-registry/validate-artifact-registry-from-file (.getPath registry-file))
        ;; The persisted validation result explicitly commits to the exact
        ;; registry bytes it evaluated. The package index separately commits to
        ;; both artifacts, allowing package validation to reconcile the pair.
        ;; `clojure.data.json` serializes namespaced keyword keys without their
        ;; namespace. These are schema-defined wire keys, so preserve them
        ;; literally; parsing with `:key-fn keyword` restores :registry/*.
        result (assoc result
                      "registry/ref" registry-ref
                      "registry/sha256" (str "sha256:" (lifecycle/sha256-file registry-file))
                      "registry/bytes" (.length registry-file))
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
        _content-registry (finalization/write-content-registry! content-registry-path evidence-files)
        bundle-root (:bundle-root execution)
        runner-selection (get-in bundle-root [:run/request :runner-selection])
        runner-artifact (runner-finalization/build
                         {:run-id (:run/id c)
                          :scenario-id (:scenario/id execution)
                          :execution-id (:execution/id execution)
                          :runner-selection runner-selection
                          :source-provenance (select-keys (get-in bundle-root [:run/environment]) [:source/hash])
                          :execution-result {:execution/termination (if (= "aborted" run-status) :aborted :completed)
                                             :semantic/outcome (if (zero? (:exit-code execution)) :pass :fail)
                                             :cli/exit-code (:exit-code execution)
                                             :bundle/root-hash (:bundle/hash bundle-root)}})
        runner-written (runner-finalization/write!
                        (io/file (p (:execution/dir c)) "runner-finalization.json")
                        runner-artifact)]
    (let [written (finalization/write-run-finalization!
                   {:finalization-path output-path
                    :reconciliation-report-path reconciliation-report-path
                    :scenario-finalization-files scenario-finalizations
                    :require-execution-identities? true
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
                    :bindings {:runner-finalization
                               {:artifact-id "execution/runner-finalization.json"
                                :hash (str "sha256:" (:runner-finalization/hash runner-artifact))
                                :runner-id (:runner-id runner-selection)
                                :runtime-kind :runner-local}}
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

(defn- normalize-for-persistence
  "Walk a value and convert runtime-specific types to canonical persisted
   representations.  Hard-fails on types that cannot be safely persisted.
   Permits: nil, Boolean, string, keyword, integer, vector, map, set.
   Rejects: Double, Float, decimal, Ratio, temporal, fn, Var, and any
   unrecognized type."
  [value]
  (letfn [(walk [x]
            (cond
              (or (nil? x)
                  (instance? Boolean x)
                  (string? x)
                  (keyword? x)
                  (integer? x))
              x
              (instance? Double x)
              (throw (ex-info "Cannot persist Double — convert to canonical representation"
                              {:type (type x) :value x}))
              (instance? Float x)
              (throw (ex-info "Cannot persist Float — convert to canonical representation"
                              {:type (type x) :value x}))
              (decimal? x)
              (throw (ex-info "Cannot persist decimal — convert to rational or integer"
                              {:type (type x) :value x}))
              (instance? clojure.lang.Ratio x)
              (throw (ex-info "Cannot persist Ratio — convert to {:rational/numerator N :rational/denominator D}"
                              {:type (type x) :value x}))
              (instance? java.time.temporal.TemporalAccessor x)
              (throw (ex-info "Cannot persist temporal — convert to epoch millis or ISO string"
                              {:type (type x) :value x}))
              (fn? x)
              (throw (ex-info "Cannot persist function reference"
                              {:type (type x)}))
              (instance? clojure.lang.Var x)
              (throw (ex-info "Cannot persist Var reference"
                              {:type (type x) :value (str x)}))
              ;; Sequence types (LazySeq, PersistentList, Cons, ...) are not
              ;; canonical-bytes encodable. Realize them as vectors so claim
              ;; results / attestations can be hashed and persisted.
              (instance? clojure.lang.ISeq x) (vec x)
              (instance? clojure.lang.IPersistentCollection x) x
              :else
              (throw (ex-info (str "Cannot persist unsupported type: " (type x))
                              {:type (type x) :value (str x)}))))]
    (walk/postwalk (fn [x]
                     (if (instance? clojure.lang.IPersistentCollection x)
                       x
                       (walk x)))
                   value)))

(defn default-build-attestation-bundle!
  "Build an attestation verification bundle with sensitivity provenance.
   Runs after finalize-run-evidence so all attestations are persisted.
   Evidence membership is determined by the committed finalization output,
   not by scanning the filesystem."
  [c execution]
  (let [run-root (io/file (p (:run/root c)))
        bundle-root (:bundle-root execution)
        run-result (:run-result execution)
        profile-mode :review
        fail-closed? true
        ;; Evidence entries from committed run output (not filesystem scan)
        attestations (let [raw (:attestations run-result [])]
                       (doseq [a raw]
                         (when (nil? (:attestation/id a))
                           (if fail-closed?
                             (throw (ex-info "Attestation without :attestation/id in run-result"
                                             {:event :attestation-bundle-missing-id
                                              :attestation (dissoc a :attestation/signature)
                                              :profile-mode profile-mode}))
                             (log/warn! "Attestation without :attestation/id in run-result"
                                        {:event :attestation-bundle-missing-id
                                         :attestation (dissoc a :attestation/signature)}))))
                       (vec (keep (fn [a] (when (:attestation/id a) a)) raw)))
        claim-results (vec (:results run-result []))
        evidence-nodes (let [raw (:evidence-nodes run-result [])]
                         (doseq [n raw]
                           (when (nil? (:node-hash n))
                             (if fail-closed?
                               (throw (ex-info "Evidence node without :node-hash in run-result"
                                               {:event :attestation-bundle-missing-node-hash
                                                :node n
                                                :profile-mode profile-mode}))
                               (log/warn! "Evidence node without :node-hash in run-result"
                                          {:event :attestation-bundle-missing-node-hash
                                           :node n}))))
                         (vec (keep (fn [n] (when (:node-hash n) n)) raw)))
        registries {:attestors (get-in bundle-root [:registry/snapshot :attestors] {})
                    :claim-definitions (get-in bundle-root [:registry/snapshot :claim-definitions] {})
                    :hash-intents (get-in bundle-root [:registry/snapshot :hash-intents] {})}
        ;; Sensitivity report
        sensitivity-report-file (io/file (p (:manifest/dir c)) "sensitivity-report.json")
        _ (when-not (.isFile sensitivity-report-file)
            (throw (ex-info "Sensitivity report is required for attestation bundle build"
                            {:path (str sensitivity-report-file)
                             :reason :sensitivity-report-missing
                             :hint "Run sensitivity scanning before attestation bundling"})))
        sensitivity-report
        (try (json/read-str (slurp sensitivity-report-file) :key-fn keyword)
             (catch Exception e
               (throw (ex-info "Failed to read sensitivity report"
                               {:path (str sensitivity-report-file)
                                :reason :sensitivity-report-read-error
                                :error (.getMessage e)}))))
        sensitivity-provenance (when sensitivity-report
                                 (let [prov (:provenance sensitivity-report)
                                       rh (:report-hash sensitivity-report)]
                                   {:report-hash rh
                                    :source "sensitivity-report.v2"
                                    :provenance prov}))
        ;; Normalize for persistence (hard-fails on unsupported types);
        ;; lazy sequences are realized to vectors so claim results /
        ;; attestations remain canonical-bytes encodable.
        objects-map {:attestations (normalize-for-persistence attestations)
                     :claim-results (normalize-for-persistence claim-results)
                     :evidence-nodes (normalize-for-persistence evidence-nodes)}
        _ (when (and (seq (:attestations run-result)) (empty? attestations))
            (log/warn! "Attestations in run-result but none with :attestation/id"
                       {:event :attestation-bundle-no-attestation-ids}))
        bundle-dir (str (io/file (p (:run/root c)) "evidence" "attestation-bundle"))
        result (ab/build-attestation-bundle
                {:attestations attestations
                 :claim-results claim-results
                 :evidence-nodes evidence-nodes
                 :registries registries
                 :sensitivity-report sensitivity-report
                 :sensitivity-provenance sensitivity-provenance
                 :completeness-profile acp/review-profile
                 :options {:bundle-dir bundle-dir}})]
    (ab/write-attestation-bundle! result objects-map bundle-dir)
    result))
(defn default-write-canonical-assurance!
  "Write unsigned canonical-integrity assurance after evidence finalization.

   The final outer artifact registry inventories this artifact in a later phase;
   it is deliberately not hashed here to avoid a self-referential registry cycle."
  [c _]
  (let [root (io/file (p (:run/root c)))
        finalization-file (io/file root "evidence" "finalizations" "run" "evidence-finalization.json")
        content-registry-file (io/file root "evidence" "content-registry.json")
        runner-finalization-file (io/file root "scenarios" (:scenario/slug c) "execution" "runner-finalization.json")
        validation-file (io/file root "manifest" "artifact-registry-validation.json")
        finalization (json/read-str (slurp finalization-file) :key-fn keyword)
        validation (json/read-str (slurp validation-file) :key-fn keyword)
        integrity {:schema_version "canonical-integrity.v1"
                   :assurance_kind "unsigned-canonical-integrity"
                   :run_id (:run/id c)
                   :status (if (and (= "verified" (get-in finalization [:verification :status]))
                                    (= "passed" (:status validation)))
                             "passed"
                             "failed")
                   :scope {:content_integrity true
                           :evidence_reconciliation true
                           :operator_identity false
                           :runtime_isolation false}
                   :run_finalization {:ref "evidence/finalizations/run/evidence-finalization.json"
                                      :sha256 (str "sha256:" (lifecycle/sha256-file finalization-file))}
                   :evidence_content_registry {:ref "evidence/content-registry.json"
                                               :sha256 (str "sha256:" (lifecycle/sha256-file content-registry-file))}
                   :runner_finalization {:ref (str "scenarios/" (:scenario/slug c) "/execution/runner-finalization.json")
                                         :sha256 (when (.isFile runner-finalization-file)
                                                   (str "sha256:" (lifecycle/sha256-file runner-finalization-file)))}
                   :outer_registry {:ref paths/artifacts-registry
                                    :verification "verified-by-verify-scenario-after-inventory"}
                   :checks {:run_finalization_verified (= "verified" (get-in finalization [:verification :status]))
                            :runner_finalization_present (.isFile runner-finalization-file)
                            :pre_assurance_registry_valid (= "passed" (:status validation))}
                   :limitations ["Unsigned assurance does not establish operator identity or signature trust."
                                 "Runtime isolation is outside this assurance scope."]}
        deferred {:schema_version "forensic-claims-status.v1"
                  :run_id (:run/id c)
                  :status "deferred"
                  :reason_code "unsigned-forensic-signing-not-configured"
                  :claims ["registry-hash-verifies" "cursor-verifies" "forensic-grade"]
                  :next_requirement "forensic-assurance.v1 requires signing-key injection and trusted-key policy."
                  :canonical_integrity_ref "manifest/canonical-integrity.json"}]
    (lifecycle/atomic-json! (io/file root "manifest" "canonical-integrity.json") integrity)
    (lifecycle/atomic-json! (io/file root "manifest" "forensic-claims-status.json") deferred)
    {:canonical-integrity integrity :forensic-claims-status deferred}))
(defn default-write-verdict-policy!
  [c execution]
  (let [root (io/file (p (:run/root c)))
        input (get-in execution [:input/provenance :input/snapshot-relative])
        input-file (io/file root input)
        bundle-root (:bundle-root execution)
        registry-snapshot (:registry/snapshot bundle-root)
        artifact (verdict-policy/build
                  {:run-id (:run/id c)
                   :run-type "scenario"
                   :policy-id "canonical-scenario-verdict.v1"
                   :version-id "verdict-policy.v1"
                   :semantic-outcome (if (zero? (:exit-code execution)) "pass" "fail")
                   :inputs [{"logical_id" "scenario-input-snapshot"
                             "path" input
                             "sha256" (verdict-policy/sha-ref input-file)}]
                   :registries {"evidence_policy_hash" (str (or (:evidence-policy-hash registry-snapshot) "unavailable"))
                                "claim_definition_registry_hash" (str (or (:claim-definition-registry-hash registry-snapshot) "unavailable"))
                                "evaluator_registry" "scenario-invariant-evaluator.v1"}
                   :semantic-environment {"protocol_id" (str (or (:protocol execution) "unknown"))
                                          "runner_id" (str (or (get-in bundle-root [:run/request :runner-selection :runner-id]) "runner/local"))
                                          "execution_id" (:execution/id execution)
                                          "deterministic_time_source" "simulation"}
                   :evaluator-implementation (let [source (source-hash/source-hash)]
                                               {"source_tree_hash" (str (or (:source/hash source) "unavailable"))
                                                "source_tree_hash_algorithm" (str (or (:source/hash-algorithm source) source-hash/source-tree-hash-algorithm))
                                                "source_roots" (vec (or (:source/included-roots source) (:source/hash-roots source) []))
                                                "evaluator_id" "scenario-invariant-evaluator.v1"})
                   :distribution-provenance (distribution/distribution-identity)})]
    (verdict-policy/write! (io/file root "manifest/verdict-policy.json") artifact)))

(defn default-write-package-index!
  [c execution]
  (let [root (io/file (p (:run/root c)))
        ref (fn [relative]
              (let [file (io/file root relative)]
                {:ref relative
                 :sha256 (when (.isFile file) (str "sha256:" (lifecycle/sha256-file file)))
                 :bytes (when (.isFile file) (.length file))}))]
    (let [dag-relative (str "scenarios/" (:scenario/slug c) "/execution/execution-dag.json")
          dag-file (io/file root dag-relative)]
      (when-not (.isFile dag-file)
        (throw (ex-info "Canonical package requires a persisted execution DAG"
                        {:code :package/required-artifact-unavailable
                         :artifact-id :execution-dag
                         :path dag-relative})))
      (package-index/write!
       (io/file root "manifest" "run-package-index.json")
       {:run-id (:run/id c)
        :scenario-id (:scenario/id execution)
        :execution-id (:execution/id execution)
        :run-type :single-scenario
        :bundle-root-hash (get-in execution [:bundle-root :bundle/hash])
        :input-snapshot (ref (get-in execution [:input/provenance :input/snapshot-relative]))
        :scenario-finalization (ref (str "scenarios/" (:scenario/slug c) "/forensic/finalizations/scenarios/" (:scenario/slug c) "/evidence-finalization.json"))
        :runner-finalization (ref (str "scenarios/" (:scenario/slug c) "/execution/runner-finalization.json"))
        :run-finalization (ref "evidence/finalizations/run/evidence-finalization.json")
        :canonical-assurance (ref "manifest/canonical-integrity.json")
        :verdict-policy (ref "manifest/verdict-policy.json")
        :artifact-registry (ref paths/artifacts-registry)
        :registry-validation (ref "manifest/artifact-registry-validation.json")
        :execution-dag (ref (str "scenarios/" (:scenario/slug c) "/execution/execution-dag.json"))
        :pro-rata-mechanism-nodes (when (.isFile (io/file root "manifest/pro-rata-mechanism-nodes.json"))
                                    (ref "manifest/pro-rata-mechanism-nodes.json"))}))))
(defn default-write-diagnostic! [c execution] (diagnostics/write! c execution))
(defn default-write-pro-rata-mechanism-index! [c _]
  (let [root (io/file (p (:run/root c)))
        nodes (->> (file-seq (io/file root "scenarios"))
                   (filter #(.isFile %))
                   (filter #(and (= "evidence-nodes" (.getName (.getParentFile %))
                                    (.endsWith (.getName %) ".edn"))))
                   (keep (fn [file]
                           (try
                             (let [node (edn/read-string (slurp file))]
                               (when (= "pro-rata-mechanism-node.v1"
                                        (get-in node [:extensions :mechanism/node-schema-version]))
                                 {:path (str (.relativize (.toPath root) (.toPath file)))
                                  :node_hash (:node-hash node)
                                  :evidence_hash (get-in node [:extensions :pro-rata/evidence-hash])
                                  :allocation_result_hash (get-in node [:extensions :pro-rata/allocation-result-hash])
                                  :allocation_artifact_hash (get-in node [:extensions :pro-rata/artifact-hash])}))
                             (catch Exception _ nil))))
                   (sort-by :path)
                   vec)
        target (io/file root "manifest/pro-rata-mechanism-nodes.json")]
    (when (seq nodes)
      (spit target (json/write-str {:schema_version "pro-rata-mechanism-nodes.v1"
                                    :mechanism_id "pro-rata-allocation"
                                    :mechanism_version 1
                                    :nodes nodes})))
    {:node-count (count nodes)}))
(defn default-refresh-inventory! [c _] (inventory/build! c))
(defn default-refresh-registry! [c _] (registry/finalize! (:run/root c)))
(defn default-revalidate-registry! [c e] (default-validate-registry! c e))
(defn default-complete! [c e]
  (let [gate (package-index/validate-precompletion-package (str (:run/root c)))
        _ (when-not (:valid? gate)
            (throw (ex-info "Canonical package completion gate failed"
                            {:code :package/completion-gate-failed
                             :reasons (:reasons gate)})))
        root (:run/root c)
        registry (io/file (str root) paths/artifacts-registry)
        validation (io/file (str root) "manifest/artifact-registry-validation.json")
        runner-finalization (io/file (str (:execution/dir c)) "runner-finalization.json")
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
      :runner_finalization_ref (str "scenarios/" (:scenario/slug c) "/execution/runner-finalization.json")
      :runner_finalization_sha256 (when (.isFile runner-finalization)
                                    (str "sha256:" (lifecycle/sha256-file runner-finalization)))
      :run_package_index_ref paths/run-package-index
      :run_package_index_sha256 (let [package-index (io/file (str root) paths/run-package-index)]
                                  (when (.isFile package-index)
                                    (str "sha256:" (lifecycle/sha256-file package-index))))
      :run_package_index_bytes (let [package-index (io/file (str root) paths/run-package-index)]
                                 (when (.isFile package-index) (.length package-index)))
      :artifact_registry_ref paths/artifacts-registry
      :artifact_registry_sha256 (when (.isFile registry) (str "sha256:" (lifecycle/sha256-file registry)))
      :registry_validation_ref "manifest/artifact-registry-validation.json"
      :registry_validation_sha256 (when (.isFile validation) (str "sha256:" (lifecycle/sha256-file validation)))})
    {}))
(def ^:private defaults {:check-runtime default-check-runtime! :execute default-execute! :write-manifest default-write-manifest! :extract-artifacts default-extract-artifacts! :scan-sensitivity default-scan-sensitivity! :finalize-registry default-finalize-registry! :validate-registry default-validate-registry! :finalize-run-evidence default-finalize-run-evidence! :build-attestation-bundle default-build-attestation-bundle! :write-canonical-assurance default-write-canonical-assurance! :write-verdict-policy default-write-verdict-policy! :write-package-index default-write-package-index! :write-diagnostic default-write-diagnostic! :write-pro-rata-mechanism-index default-write-pro-rata-mechanism-index! :refresh-inventory default-refresh-inventory! :refresh-registry default-refresh-registry! :revalidate-registry default-revalidate-registry! :complete default-complete!})
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
             ;; Preserve structured lifecycle reasons (not exception text) for
             ;; callers that need to distinguish a missing required DAG from an
             ;; execution or finalization failure.
             {:command/status :failed :scenario/outcome :unknown :exit-code 1
              :run/id (:run/id context) :run/root (p (:run/root context))
              :phases @records :error (.getMessage error) :error/data (ex-data error)})))
       (finally (safety/release-lock! lock))))))
