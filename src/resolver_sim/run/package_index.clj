(ns resolver-sim.run.package-index
  "Immutable package index and package-level validation.

   A package index is the outer runnable boundary for a finalized run.  It is
   intentionally distinct from the immutable inner bundle root. Profiles have
   distinct closure requirements: :single-scenario retains its scenario/DAG
   semantics, while :benchmark validates benchmark artifact bindings only."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.commands.scenario-value-at-risk :as value-at-risk]
            [resolver-sim.evidence.finalization :as finalization]
            [resolver-sim.evidence.node :as evidence-node]
            [resolver-sim.forensic.execution-dag :as execution-dag]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.run.runner-finalization :as runner-finalization]
            [resolver-sim.validation.integration.artifact-registry :as artifact-registry])
  (:import [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.math BigInteger]))

(def schema-version "run-package-index.v1")
(def ^:private supported-run-types #{:single-scenario :benchmark})
(def ^:private single-scenario-artifacts
  #{:input-snapshot :scenario-finalization :runner-finalization :run-finalization
    :canonical-assurance :verdict-policy :artifact-registry :registry-validation :execution-dag})
(def ^:private benchmark-artifacts
  #{:runner-finalization :benchmark-definition :execution-plan :benchmark-index
    :benchmark-evidence :content-registry :benchmark-conclusion :benchmark-conservation
    :benchmark-finalization :benchmark-assurance :canonical-integrity :verdict-policy :forensic-status})

(defn- required-artifacts [run-type]
  (case run-type
    :single-scenario single-scenario-artifacts
    :benchmark benchmark-artifacts
    #{}))

(defn- sha-ref [file] (hash-ref/sha256-ref (lifecycle/sha256-file file)))
(defn- reason [code & {:as data}] (assoc data :code code))
(defn- json-key [k] (if (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)) (str k)))
(defn- contained-file [root ref]
  (let [root-path (.toAbsolutePath (.normalize (.toPath (io/file root))))
        file (.toAbsolutePath (.normalize (.toPath (io/file root ref))))]
    (when (.startsWith file root-path) (.toFile file))))

(defn- package-index-payload [index]
  (dissoc index :run-package/hash))

(defn package-index->wire
  "Schema-local JSON representation for run-package-index.v1."
  [index]
  (assoc index :run/type (name (:run/type index))))

(defn wire->package-index
  "Decode only the documented run-package-index.v1 enum values."
  [wire]
  (let [run-type (:run/type wire)
        decoded (case run-type
                  "single-scenario" :single-scenario
                  "benchmark" :benchmark
                  nil)]
    (if decoded
      (assoc wire :run/type decoded)
      (throw (ex-info "Unsupported package index run type"
                      {:code :package/unsupported-run-type :run-type run-type})))))

(defn build
  [{:keys [run-id scenario-id execution-id run-type bundle-root-hash artifacts input-snapshot runner-finalization run-finalization
           canonical-assurance verdict-policy execution-dag scenario-finalization artifact-registry registry-validation]}]
  (let [artifacts (or artifacts {:input-snapshot input-snapshot
                                 :scenario-finalization scenario-finalization
                                 :runner-finalization runner-finalization
                                 :run-finalization run-finalization
                                 :canonical-assurance canonical-assurance
                                 :verdict-policy verdict-policy
                                 :artifact-registry artifact-registry
                                 :registry-validation registry-validation
                                 :execution-dag execution-dag})
        base {:run-package/schema-version schema-version
              ;; The benchmark producer predates the explicit profile argument;
              ;; its benchmark-finalization role is an unambiguous local signal.
              :run/type (or run-type
                            (if (contains? artifacts :benchmark-finalization)
                              :benchmark
                              :single-scenario))
              :run/id run-id
              :scenario/id scenario-id
              :execution/id execution-id
              :bundle/root-hash bundle-root-hash
              :artifacts artifacts}
        hash (hc/hash-with-intent {:hash/intent :run-package-index} (package-index-payload base))]
    (assoc base :run-package/hash hash)))

(defn write! [path input]
  (let [index (build input)
        target (io/file path)
        temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str (package-index->wire index) :key-fn json-key :indent true))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    {:path target :index index}))

(defn- bytes-sha-ref [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (str "sha256:" (format "%064x" (BigInteger. 1 (.digest digest bytes))))))

(defn resolve-completion-context
  "Read the terminal completion seal first. A package index becomes trusted only
   after its exact persisted bytes match completion's path/hash/length binding."
  [run-root]
  (let [completion-file (io/file run-root paths/completion)]
    (cond
      (not (.isFile completion-file))
      {:run-root run-root
       :completion-report {:valid? false :reasons [(reason :package/completion-missing)]}
       :reasons [(reason :package/completion-missing)]}

      :else
      (try
        (let [completion (json/read-str (slurp completion-file))
              path (get completion "run_package_index_ref")
              index-file (when (string? path) (contained-file run-root path))
              expected-hash (get completion "run_package_index_sha256")
              expected-bytes (get completion "run_package_index_bytes")
              ;; Read once: the parsed declarations must be the exact bytes whose
              ;; hash and length completion seals.
              index-bytes (when (and index-file (.isFile index-file)) (Files/readAllBytes (.toPath index-file)))
              actual-hash (when index-bytes (bytes-sha-ref index-bytes))
              actual-bytes (when index-bytes (alength index-bytes))
              reasons (vec (concat
                            (when-not (contains? #{"run-completion.v1" "benchmark-completion.v1"}
                                                 (get completion "schema_version"))
                              [(reason :package/completion-invalid :field :schema_version)])
                            (when-not (and (string? (get completion "run_id"))
                                           (seq (get completion "run_id")))
                              [(reason :package/completion-invalid :field :run_id)])
                            (when-not (= "completed" (get completion "lifecycle_status"))
                              [(reason :package/completion-invalid :field :lifecycle_status)])
                            (when (and (get completion "run_type")
                                       (not (contains? #{"scenario" "benchmark"} (get completion "run_type"))))
                              [(reason :package/completion-invalid :field :run_type)])
                            (when-not (string? path) [(reason :package/completion-invalid :field :run_package_index_ref)])
                            (when (and path (nil? index-file)) [(reason :package/package-index-path-invalid :path path)])
                            (when (and index-file (not (.isFile index-file))) [(reason :package/package-index-missing :path path)])
                            (when-not (hash-ref/valid-sha256-ref? expected-hash)
                              [(reason :package/completion-invalid :field :run_package_index_sha256)])
                            (when-not (and (integer? expected-bytes) (not (neg? expected-bytes)))
                              [(reason :package/completion-invalid :field :run_package_index_bytes)])
                            (when (and index-bytes (not= expected-hash actual-hash))
                              [(reason :package/package-index-hash-mismatch :expected expected-hash :actual actual-hash)])
                            (when (and index-bytes (not= expected-bytes actual-bytes))
                              [(reason :package/package-index-byte-length-mismatch :expected expected-bytes :actual actual-bytes)])))
              ;; A mismatched index is not a trusted declaration. Do not parse or
              ;; derive any package verdict from it. Parsing is from `index-bytes`,
              ;; never a later filesystem read.
              index-result (when (and (empty? reasons) index-bytes)
                             (try {:index (wire->package-index
                                           (json/read-str (String. ^bytes index-bytes "UTF-8") :key-fn keyword))
                                   :path index-file
                                   :sha256 actual-hash
                                   :bytes actual-bytes}
                                   (catch Exception ex {:reason (reason :package/package-index-invalid-json
                                                                          :path path
                                                                          :exception (.getMessage ex)
                                                                          :exception-class (str (class ex)))})))
              profile-reasons (when-let [index (:index index-result)]
                                (case (:run/type index)
                                  :single-scenario
                                  (when (or (not= "run-completion.v1" (get completion "schema_version"))
                                            (and (get completion "run_type")
                                                 (not= "scenario" (get completion "run_type"))))
                                    [(reason :package/completion-invalid :field :run_type)])
                                  :benchmark
                                  (when (or (not= "benchmark-completion.v1" (get completion "schema_version"))
                                            (not= "benchmark" (get completion "run_type")))
                                    [(reason :package/completion-invalid :field :run_type)])
                                  [(reason :package/completion-invalid
                                           :field :run/type
                                           :message (str "Unrecognized run type: " (:run/type index)))]))
              all-reasons (let [base (vec (concat reasons (when-let [r (:reason index-result)] [r]) profile-reasons))]
                            ;; Defensive: if all validation passed but we have no index,
                            ;; add a fallback reason rather than returning nil with empty reasons.
                            (if (and (empty? base) (nil? index-result))
                              [(reason :package/package-index-unavailable :path path)]
                              base))]
          {:run-root run-root
           :completion completion
           :completion-report {:valid? (empty? all-reasons) :reasons all-reasons}
           ;; Profile incompatibility is terminal too: callers must never receive
           ;; an index whose completion seal does not describe that profile.
           :package-index (when (empty? all-reasons) index-result)
           :reasons all-reasons})
        (catch Exception ex
          {:run-root run-root
           :completion-report {:valid? false :reasons [(reason :package/completion-invalid
                                                              :exception (.getMessage ex)
                                                              :exception-class (str (class ex)))]}
           :reasons [(reason :package/completion-invalid
                             :exception (.getMessage ex)
                             :exception-class (str (class ex)))]})))))

(defn read-index [run-root]
  (let [file (io/file run-root paths/run-package-index)]
    (if (.isFile file)
      (try
        {:index (wire->package-index (json/read-str (slurp file) :key-fn keyword))
         :path file}
        (catch Exception _
          {:reason (reason :package/package-index-invalid-json :path paths/run-package-index)}))
      {:reason (reason :package/missing-index :path paths/run-package-index)})))

(defn- artifact-json [run-root artifacts id]
  (try
    (when-let [ref (get-in artifacts [id :ref])]
      (when-let [file (contained-file run-root ref)]
        (when (.isFile (io/file file))
          (json/read-str (slurp file) :key-fn keyword))))
    (catch Exception _ {:package/unreadable-artifact id})))

(defn- validate-canonical-assurance [run-root artifacts]
  (let [assurance (artifact-json run-root artifacts :canonical-assurance)
        run-final-ref (get-in artifacts [:run-finalization :sha256])
        content-registry-ref (get-in assurance [:evidence_content_registry :ref])
        content-registry-file (and content-registry-ref (contained-file run-root content-registry-ref))
        content-registry-hash (when (and content-registry-file (.isFile content-registry-file))
                                (sha-ref content-registry-file))]
    (cond
      (:package/unreadable-artifact assurance)
      {:valid? false :reasons [(reason :package/unreadable-artifact :artifact-id :canonical-assurance)]}
      (nil? assurance)
      {:valid? false :reasons [(reason :package/missing-artifact :artifact-id :canonical-assurance)]}
      :else
      (let [checks (:checks assurance)
            reasons (vec (concat
                          (when-not (= "canonical-integrity.v1" (:schema_version assurance))
                            [(reason :canonical-assurance/unsupported-schema)])
                          (when-not (= "unsigned-canonical-integrity" (:assurance_kind assurance))
                            [(reason :canonical-assurance/unsupported-kind)])
                          (when-not (= "passed" (:status assurance))
                            [(reason :canonical-assurance/not-passed)])
                          (when-not (= run-final-ref (get-in assurance [:run_finalization :sha256]))
                            [(reason :canonical-assurance/run-finalization-mismatch)])
                          (when-not (= (get-in artifacts [:runner-finalization :sha256])
                                       (get-in assurance [:runner_finalization :sha256]))
                            [(reason :canonical-assurance/runner-finalization-mismatch)])
                          (when-not (true? (:run_finalization_verified checks))
                            [(reason :canonical-assurance/run-finalization-not-verified)])
                          (when-not (true? (:runner_finalization_present checks))
                            [(reason :canonical-assurance/runner-finalization-not-verified)])
                          (when-not (and content-registry-file (.isFile content-registry-file))
                            [(reason :canonical-assurance/content-registry-missing)])
                          (when (and content-registry-hash
                                     (not= content-registry-hash (get-in assurance [:evidence_content_registry :sha256])))
                            [(reason :canonical-assurance/content-registry-hash-mismatch
                                     :expected (get-in assurance [:evidence_content_registry :sha256])
                                     :actual content-registry-hash)])
                          (when-not (true? (:pre_assurance_registry_valid checks))
                            [(reason :canonical-assurance/pre-assurance-registry-not-verified)])))]
        {:valid? (empty? reasons)
         :assurance-kind (:assurance_kind assurance)
         :run-id (:run_id assurance)
         :run-finalization-hash (get-in assurance [:run_finalization :sha256])
         :scope (:scope assurance)
         :reasons reasons}))))

(defn- validate-registry-closure
  "Verify every persisted artifact entry claimed by manifest/artifacts.json.
   Registry hashes are unprefixed SHA-256 values, unlike package references."
  [run-root registry]
  (let [entries (:artifacts registry)
        ids (map :id entries)
        paths (map :path entries)
        reasons (vec (concat
                      (when-not (vector? entries) [(reason :registry-validation/artifacts-not-vector)])
                      (when-not (= (count ids) (count (set ids))) [(reason :registry-validation/duplicate-artifact-id)])
                      (when-not (= (count paths) (count (set paths))) [(reason :registry-validation/duplicate-artifact-path)])
                      ;; These terminal outer artifacts are deliberately outside
                      ;; the pre-package registry to avoid a circular commitment.
                      (mapcat (fn [path]
                                [(reason :registry-validation/terminal-package-artifact-indexed :path path)])
                              (filter #{paths/run-package-index paths/completion} paths))
                      (mapcat (fn [entry]
                                (let [path (:path entry)
                                      file (and (string? path) (contained-file run-root path))
                                      expected-hash (:sha256 entry)
                                      expected-bytes (:bytes entry)]
                                  (cond-> []
                                    (nil? file) (conj (reason :registry-validation/path-outside-root :path path))
                                    (and file (not (.isFile file))) (conj (reason :registry-validation/missing-artifact :path path))
                                    (and file (.isFile file) (not= expected-hash (lifecycle/sha256-file file)))
                                    (conj (reason :registry-validation/artifact-hash-mismatch :path path
                                                  :expected expected-hash :actual (lifecycle/sha256-file file)))
                                    (and file (.isFile file) (not= expected-bytes (.length file)))
                                    (conj (reason :registry-validation/artifact-byte-length-mismatch :path path
                                                  :expected expected-bytes :actual (.length file))))))
                              (if (vector? entries) entries []))))]
    {:valid? (empty? reasons) :reasons reasons :artifact-count (count entries)}))

(defn- validate-registry-validation [run-root artifacts]
  (let [report (artifact-json run-root artifacts :registry-validation)
        registry-entry (get artifacts :artifact-registry)
        registry-ref (:ref registry-entry)
        registry-file (when registry-ref (contained-file run-root registry-ref))]
    (cond
      (:package/unreadable-artifact report)
      {:valid? false :reasons [(reason :package/unreadable-artifact :artifact-id :registry-validation)]}
      (not (and registry-file (.isFile registry-file)))
      {:valid? false :reasons [(reason :registry-validation/registry-missing)]}
      :else
      (let [recalculated (artifact-registry/validate-artifact-registry-from-file (.getPath registry-file))
            registry (try (json/read-str (slurp registry-file) :key-fn keyword)
                          (catch Exception _ nil))
            closure (if registry
                      (validate-registry-closure run-root registry)
                      {:valid? false :reasons [(reason :registry-validation/registry-unreadable)]})
            reasons (vec (concat
                          (when-not (= "passed" (:status report)) [(reason :registry-validation/persisted-not-passed)])
                          ;; The persisted validation result is not merely an
                          ;; accepted-looking report: it must identify the exact
                          ;; indexed registry bytes it evaluated.
                          (when-not (= registry-ref (:registry/ref report))
                            [(reason :registry-validation/registry-path-mismatch
                                     :expected registry-ref :actual (:registry/ref report))])
                          (when-not (= (:sha256 registry-entry) (:registry/sha256 report))
                            [(reason :registry-validation/registry-hash-mismatch
                                     :expected (:sha256 registry-entry) :actual (:registry/sha256 report))])
                          (when-not (= (:bytes registry-entry) (:registry/bytes report))
                            [(reason :registry-validation/registry-byte-length-mismatch
                                     :expected (:bytes registry-entry) :actual (:registry/bytes report))])
                          (when-not (= :passed (:status recalculated))
                            [(reason :registry-validation/recalculated-not-passed :causes (:errors recalculated))])
                          (when-not (:valid? closure) (:reasons closure))))]
        {:valid? (empty? reasons)
         :registry-path registry-ref
         :registry-sha256 (:sha256 registry-entry)
         :registry-bytes (:bytes registry-entry)
         :persisted-status (:status report)
         :recalculated-status (:status recalculated)
         :closure-report closure
         :reasons reasons}))))

(defn- validate-pro-rata-mechanism-nodes
  [run-root artifacts]
  (if-not (contains? artifacts :pro-rata-mechanism-nodes)
    {:valid? true :reasons []}
    (let [manifest (artifact-json run-root artifacts :pro-rata-mechanism-nodes)
          registry (artifact-json run-root artifacts :artifact-registry)
          entries (into {} (map (juxt :path identity) (:artifacts registry)))
          nodes (:nodes manifest)
          reasons (vec (concat
                        (when-not (= "pro-rata-mechanism-nodes.v1" (:schema_version manifest))
                          [(reason :pro-rata-mechanism-nodes/unsupported-schema)])
                        (when-not (= "pro-rata-allocation" (:mechanism_id manifest))
                          [(reason :pro-rata-mechanism-nodes/invalid-mechanism-id)])
                        (when-not (= 1 (:mechanism_version manifest))
                          [(reason :pro-rata-mechanism-nodes/invalid-mechanism-version)])
                        (when-not (vector? nodes)
                          [(reason :pro-rata-mechanism-nodes/nodes-not-vector)])
                        (mapcat (fn [{:keys [path node_hash evidence_hash allocation_result_hash allocation_artifact_hash]}]
                                  (let [file (and (string? path) (contained-file run-root path))
                                        registry-entry (get entries path)
                                        node (try (when (and file (.isFile file))
                                                    (edn/read-string (slurp file)))
                                                  (catch Exception _ nil))]
                                    (cond-> []
                                      (nil? registry-entry) (conj (reason :pro-rata-mechanism-nodes/not-registered :path path))
                                      (and registry-entry (not= "evidence.node" (:kind registry-entry))) (conj (reason :pro-rata-mechanism-nodes/not-core-node :path path))
                                      (nil? node) (conj (reason :pro-rata-mechanism-nodes/unreadable-node :path path))
                                      (and node (not= node_hash (:node-hash node))) (conj (reason :pro-rata-mechanism-nodes/node-hash-mismatch :path path))
                                      (and node (not= :mechanism/pro-rata-allocation (get-in node [:extensions :mechanism/id]))) (conj (reason :pro-rata-mechanism-nodes/invalid-node-mechanism :path path))
                                      (and node (not= evidence_hash (get-in node [:extensions :pro-rata/evidence-hash]))) (conj (reason :pro-rata-mechanism-nodes/evidence-hash-mismatch :path path))
                                      (and node (not= allocation_result_hash (get-in node [:extensions :pro-rata/allocation-result-hash]))) (conj (reason :pro-rata-mechanism-nodes/allocation-result-hash-mismatch :path path))
                                      (and node (not= allocation_artifact_hash (get-in node [:extensions :pro-rata/artifact-hash]))) (conj (reason :pro-rata-mechanism-nodes/allocation-artifact-hash-mismatch :path path)))))
                                (if (vector? nodes) nodes []))))]
      {:valid? (empty? reasons) :reasons reasons})))

(defn- completion-commitment-reasons
  [completion artifacts]
  (let [bindings [["runner_finalization_sha256" :runner-finalization]
                  ["artifact_registry_sha256" :artifact-registry]
                  ["registry_validation_sha256" :registry-validation]]]
    (vec (keep (fn [[field artifact-id]]
                 (let [actual (get completion field)
                       expected (get-in artifacts [artifact-id :sha256])]
                   (when (and actual (not= actual expected))
                     (reason :package/completion-artifact-commitment-mismatch
                             :field field :artifact-id artifact-id
                             :expected expected :actual actual))))
               bindings))))

(defn- legacy-subordinate-reasons [run-root index completion artifacts]
  (let [runner (artifact-json run-root artifacts :runner-finalization)
        scenario-final (artifact-json run-root artifacts :scenario-finalization)
        scenario-validation (when scenario-final (finalization/validate-finalization scenario-final {:require-execution-id? true}))
        run-final (artifact-json run-root artifacts :run-finalization)
        run-final-validation (when run-final
                               (finalization/validate-finalization run-final {:require-execution-identities? true}))
        runner-validation (when runner (runner-finalization/valid? runner))
        dag (artifact-json run-root artifacts :execution-dag)
        dag-validation (when dag (execution-dag/validate-persisted-dag dag {:require-identities? true}))
        assurance-validation (validate-canonical-assurance run-root artifacts)
        registry-validation (validate-registry-validation run-root artifacts)
        indexed-scenario-hash (get-in artifacts [:scenario-finalization :sha256])
        committed-scenario-hashes (set (map #(get-in % [:finalization :sha256])
                                            (get-in run-final [:evidence :scenario-finalizations])))
        registry (artifact-json run-root artifacts :artifact-registry)
        expected-run-id (:run/id index)
        expected-scenario-id (:scenario/id index)
        expected-execution-id (:execution/id index)
        expected-input-hash (get-in artifacts [:input-snapshot :sha256])
        input-reasons (vec (concat
                            (when-not (hash-ref/valid-sha256-ref? expected-input-hash)
                              [(reason :package/missing-authoritative-input-commitment :artifact-id :input-snapshot)])
                            (when (and scenario-final expected-input-hash
                                       (not= expected-input-hash (get-in scenario-final [:subject :scenario-input :sha256])))
                              [(reason :package/scenario-input-hash-mismatch :artifact-id :scenario-finalization
                                       :expected expected-input-hash :actual (get-in scenario-final [:subject :scenario-input :sha256]))])
                            (when (and run-final expected-input-hash
                                       (not= expected-input-hash (get-in run-final [:run :run-input-hash])))
                              [(reason :package/run-input-hash-mismatch :artifact-id :run-finalization
                                       :expected expected-input-hash :actual (get-in run-final [:run :run-input-hash]))])
                            (when (and dag-validation expected-input-hash
                                       (not-every? #(= expected-input-hash (get-in % [:node/input-hashes :scenario/source-hash]))
                                                   (:nodes dag-validation)))
                              [(reason :package/dag-input-hash-mismatch :artifact-id :execution-dag
                                       :expected expected-input-hash)])))
        run-ids {:completion (or (get completion "run_id") (:run_id completion) (:run/id completion))
                 :execution-dag (:run-id dag-validation)
                 :runner (:run/id runner)
                 :run-final (get-in run-final [:run :run-id])
                 :assurance (:run-id assurance-validation)
                 :registry (or (:run_id registry) (:run/id registry))}
        run-id-mismatches (vec (for [[source actual] run-ids
                                     :when (and actual (not= actual expected-run-id))]
                                 {:source source :expected expected-run-id :actual actual}))
        identity-reasons (vec (concat
                               (when-not (and (string? expected-scenario-id) (seq expected-scenario-id))
                                 [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :scenario/id)])
                               (when-not (and (string? expected-execution-id) (seq expected-execution-id))
                                 [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :execution/id)])
                               (for [[artifact-id actual] [[:execution-dag (:scenario-id dag-validation)]
                                                           [:runner-finalization (:scenario/id runner)]]
                                     :when (and actual (not= actual expected-scenario-id))]
                                 (reason :package/scenario-id-mismatch :artifact-id artifact-id :expected expected-scenario-id :actual actual))
                               (when (and runner (not (and (string? (:scenario/id runner)) (seq (:scenario/id runner)))))
                                 [(reason :package/missing-authoritative-identity :artifact-id :runner-finalization :field :scenario/id)])
                               (for [[artifact-id actual] [[:execution-dag (:execution-id dag-validation)]
                                                           [:runner-finalization (:execution/id runner)]
                                                           [:scenario-finalization (:execution-id scenario-validation)]]
                                     :when (and actual (not= actual expected-execution-id))]
                                 (reason :package/execution-id-mismatch :artifact-id artifact-id :expected expected-execution-id :actual actual))
                               (when (and runner (not (and (string? (:execution/id runner)) (seq (:execution/id runner)))))
                                 [(reason :package/missing-authoritative-identity :artifact-id :runner-finalization :field :execution/id)])
                               (let [members (get-in run-final [:evidence :scenario-finalizations])
                                     ids (set (map :scenario-id members))
                                     execution-ids (set (map :execution/id members))
                                     member (first members)]
                                 (concat
                                  (when (and run-final (not= #{expected-scenario-id} ids))
                                    [(reason :package/scenario-id-mismatch :artifact-id :run-finalization
                                             :expected expected-scenario-id :actual (vec (sort ids)))])
                                  (when (and run-final (not= #{expected-execution-id} execution-ids))
                                    [(reason :package/execution-id-mismatch :artifact-id :run-finalization
                                             :expected expected-execution-id :actual (vec (sort execution-ids)))])
                                  (when (and scenario-final member
                                             (not= (:execution-id scenario-validation) (:execution/id member)))
                                    [(reason :package/execution-id-mismatch :artifact-id :scenario-finalization
                                             :expected (:execution/id member) :actual (:execution-id scenario-validation))])
                                  (when (and scenario-final member
                                             (not= (:scenario-id scenario-validation) (:scenario-id member)))
                                    [(reason :package/scenario-id-mismatch :artifact-id :scenario-finalization
                                             :expected (:scenario-id member) :actual (:scenario-id scenario-validation))])))))
        scenario-reconciliation (cond
                                  (nil? run-final) {:valid? false :reasons [(reason :package/run-finalization-unreadable)]}
                                  (not (contains? committed-scenario-hashes indexed-scenario-hash))
                                  {:valid? false :reasons [(reason :package/scenario-finalization-not-committed
                                                                   :indexed-hash indexed-scenario-hash
                                                                   :committed-hashes (vec (sort committed-scenario-hashes)))]}
                                  :else {:valid? true :reasons []})]
    (vec (concat
          (for [artifact [runner scenario-final run-final dag] :when (:package/unreadable-artifact artifact)]
            (reason :package/unreadable-artifact :artifact-id (:package/unreadable-artifact artifact)))
          (when (and runner (not (:valid? runner-validation)))
            [(reason :package/invalid-runner-finalization :artifact-id :runner-finalization
                     :causes (:errors runner-validation))])
          (when (and scenario-final (not (:valid? scenario-validation)))
            [(reason :package/invalid-scenario-finalization :artifact-id :scenario-finalization
                     :causes (:errors scenario-validation))])
          (when (and run-final (not (:valid? run-final-validation)))
            [(reason :package/invalid-run-finalization :artifact-id :run-finalization
                     :causes (:errors run-final-validation))])
          (when (and dag (not (:valid? dag-validation)))
            [(reason :package/invalid-execution-dag :artifact-id :execution-dag
                     :causes (:reasons dag-validation))])
          (when-not (:valid? assurance-validation)
            [(reason :package/invalid-canonical-assurance :artifact-id :canonical-assurance
                     :causes (:reasons assurance-validation))])
          (when-not (:valid? registry-validation)
            [(reason :package/invalid-registry-validation :artifact-id :registry-validation
                     :causes (:reasons registry-validation))])
          (when-not (:valid? scenario-reconciliation)
            [(reason :package/scenario-finalization-reconciliation-failed
                     :artifact-id :scenario-finalization
                     :causes (:reasons scenario-reconciliation))])
          (when (seq run-id-mismatches)
            [(reason :package/run-id-mismatch :causes run-id-mismatches)])
          identity-reasons
          input-reasons
          (completion-commitment-reasons completion artifacts)))))

(defn- artifact-validation-report
  "Read an indexed JSON artifact once and retain only the validated report and
   authoritative fields needed by package reconciliation. Raw wire maps do not
   escape this adapter layer."
  [run-root artifacts artifact-id validator]
  (let [artifact (artifact-json run-root artifacts artifact-id)]
    (cond
      (:package/unreadable-artifact artifact)
      {:artifact-id artifact-id
       :valid? false
       :reasons [(reason :package/unreadable-artifact :artifact-id artifact-id)]}

      (nil? artifact)
      {:artifact-id artifact-id
       :valid? false
       :reasons [(reason :package/missing-artifact :artifact-id artifact-id)]}

      :else
      (try
        (let [validation (validator artifact)]
          (assoc validation :artifact-id artifact-id :artifact-present? true))
        (catch Exception error
          {:artifact-id artifact-id
           :valid? false
           :reasons [(reason :package/invalid-artifact
                             :artifact-id artifact-id
                             :message (.getMessage error)
                             :data (ex-data error))]})))))

(defn- registry-artifact-entry [registry pred]
  (first (filter pred (:artifacts registry))))

(defn- indexed-edn-artifact [run-root artifacts artifact-id]
  (try
    (when-let [ref (get-in artifacts [artifact-id :ref])]
      (when-let [file (contained-file run-root ref)]
        (when (.isFile file)
          (edn/read-string (slurp file)))))
    (catch Exception _ nil)))

(defn- validate-value-at-risk
  "Recompute the standalone value-at-risk observation from registry-bound
   source artifacts. The registry path, rather than observation metadata,
   authoritatively identifies the replay source."
  [run-root artifacts]
  (let [registry (artifact-json run-root artifacts :artifact-registry)
        value-entry (when (map? registry)
                      (registry-artifact-entry registry #(= "manifest/value-at-risk.json" (:path %))))
        summary-entry (when (map? registry)
                        (registry-artifact-entry registry #(= "manifest/summary.json" (:path %))))
        replay-entry (when (map? registry)
                       (registry-artifact-entry registry #(str/ends-with? (or (:path %) "") "execution/replay-output.json")))
        ;; Declaration-free scenarios retain a stable not-declared artifact and
        ;; need no historical replay source to validate that opt-out.
        missing (vec (keep (fn [[artifact entry]] (when-not entry artifact))
                           [[:value-at-risk value-entry] [:summary summary-entry]
                            [:replay-output replay-entry]]))
        observation (when value-entry
                      (try (json/read-str (slurp (contained-file run-root (:path value-entry))) :key-fn keyword)
                           (catch Exception _ nil)))
        summary (when summary-entry
                  (try (json/read-str (slurp (contained-file run-root (:path summary-entry))) :key-fn keyword)
                       (catch Exception _ nil)))
        replay-root (when replay-entry
                      (try (json/read-str (slurp (contained-file run-root (:path replay-entry))) :key-fn keyword)
                           (catch Exception _ nil)))
        replay (if (and (map? replay-root) (contains? replay-root :run/scenario-results))
                 (first (:run/scenario-results replay-root))
                 replay-root)
        snapshot (indexed-edn-artifact run-root artifacts :input-snapshot)
        source-ref (:path replay-entry)
        observed-source-ref (get-in observation [:calculation :source_ref])
        ;; The input snapshot and replay path are package-bound. The original
        ;; input-source provenance contains non-reconstructable origin metadata,
        ;; so retain the persisted observation provenance only for that field.
        provenance (:derived_from observation)
        validator (when (and observation snapshot replay source-ref)
                    (try (value-at-risk/validate-persisted observation snapshot replay provenance source-ref)
                         (catch Exception error
                           {:exception (.getMessage error)})))
        not-declared? (= "not-declared" (:status observation))
        missing (if not-declared? (vec (remove #{:replay-output} missing)) missing)
        reasons (vec (concat
                      (when (seq missing)
                        [(reason :value-at-risk/source-not-registered :artifacts missing)])
                      (when (and (not not-declared?) source-ref (not= source-ref observed-source-ref))
                        [(reason :value-at-risk/source-ref-mismatch
                                 :expected source-ref :actual observed-source-ref)])
                      (when (and observation summary
                                 (not= observation (:value_at_risk summary)))
                        [(reason :value-at-risk/summary-mismatch)])
                      (when (and (not not-declared?) (empty? missing)
                                 (or (nil? observation) (nil? summary) (nil? replay) (nil? snapshot)))
                        [(reason :value-at-risk/validator-failed
                                 :causes ["required-artifact-unreadable"])])
                      (when (and (not not-declared?) validator (not= "pass" (:status validator)))
                        [(reason :value-at-risk/validator-failed
                                 :causes (or (:reason_codes validator)
                                             [(:exception validator) "validator-failed"]))])))]
    {:valid? (empty? reasons)
     :value-at-risk-path (:path value-entry)
     :summary-path (:path summary-entry)
     :replay-path source-ref
     :validator-report validator
     :reasons reasons}))

(defn- validated-subordinate-reports
  "Collect the semantic reports used by package validation. Each parser is
   invoked once here; later reconciliation consumes these reports rather than
   re-reading or re-parsing persisted artifacts."
  [run-root artifacts]
  (let [scenario (artifact-validation-report
                  run-root artifacts :scenario-finalization
                  #(let [r (finalization/validate-finalization % {:require-execution-id? true})]
                     (assoc r :scenario-input-hash (get-in % [:subject :scenario-input :sha256]))))
        run-final (artifact-validation-report
                   run-root artifacts :run-finalization
                   #(let [r (finalization/validate-finalization % {:require-execution-identities? true})]
                      (assoc r
                             :run-input-hash (get-in % [:run :run-input-hash])
                             :scenario-members
                             (mapv (fn [member]
                                     (select-keys member [:scenario-id :execution/id :finalization]))
                                   (get-in % [:evidence :scenario-finalizations])))))
        runner (artifact-validation-report run-root artifacts :runner-finalization runner-finalization/valid?)
        dag (artifact-validation-report run-root artifacts :execution-dag
                                        #(execution-dag/validate-persisted-dag % {:require-identities? true}))
        assurance (validate-canonical-assurance run-root artifacts)
        registry (validate-registry-validation run-root artifacts)
        value-at-risk (validate-value-at-risk run-root artifacts)]
    {:scenario-finalization-report scenario
     :run-finalization-report run-final
     :runner-finalization-report runner
     :execution-dag-report dag
     :canonical-assurance-report assurance
     :registry-validation-report registry
     :value-at-risk-report value-at-risk}))

(defn- report-causes [report]
  (or (:reasons report) (:errors report) []))

(defn- reconciliation-report
  "Reconcile package identities and commitments from validated subordinate
   reports. This intentionally does not accept raw artifact maps."
  [index completion artifacts reports]
  (let [expected-run-id (:run/id index)
        expected-scenario-id (:scenario/id index)
        expected-execution-id (:execution/id index)
        expected-input-hash (get-in artifacts [:input-snapshot :sha256])
        scenario (:scenario-finalization-report reports)
        run-final (:run-finalization-report reports)
        runner (:runner-finalization-report reports)
        dag (:execution-dag-report reports)
        assurance (:canonical-assurance-report reports)
        members (:scenario-members run-final)
        member (first members)
        run-identities {:completion (get completion "run_id")
                        :execution-dag (:run-id dag)
                        :runner-finalization (:run-id runner)
                        :run-finalization (:run-id run-final)
                        :canonical-assurance (:run-id assurance)}
        run-mismatches (vec (for [[artifact-id actual] run-identities
                                  :when (and actual (not= expected-run-id actual))]
                              {:artifact-id artifact-id :expected expected-run-id :actual actual}))
        reasons (vec (concat
                      (when-not (and (string? expected-run-id) (seq expected-run-id))
                        [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :run/id)])
                      (when-not (and (string? expected-scenario-id) (seq expected-scenario-id))
                        [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :scenario/id)])
                      (when-not (and (string? expected-execution-id) (seq expected-execution-id))
                        [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :execution/id)])
                      (when (seq run-mismatches)
                        [(reason :package/run-id-mismatch :causes run-mismatches)])
                      (for [[artifact-id actual] [[:execution-dag (:scenario-id dag)]
                                                  [:runner-finalization (:scenario-id runner)]
                                                  [:scenario-finalization (:scenario-id scenario)]]
                            :when (and actual (not= expected-scenario-id actual))]
                        (reason :package/scenario-id-mismatch :artifact-id artifact-id
                                :expected expected-scenario-id :actual actual))
                      (for [[artifact-id actual] [[:execution-dag (:execution-id dag)]
                                                  [:runner-finalization (:execution-id runner)]
                                                  [:scenario-finalization (:execution-id scenario)]]
                            :when (and actual (not= expected-execution-id actual))]
                        (reason :package/execution-id-mismatch :artifact-id artifact-id
                                :expected expected-execution-id :actual actual))
                      (when (and (:artifact-present? run-final)
                                 (not= #{expected-scenario-id} (set (map :scenario-id members))))
                        [(reason :package/scenario-id-mismatch :artifact-id :run-finalization
                                 :expected expected-scenario-id :actual (mapv :scenario-id members))])
                      (when (and (:artifact-present? run-final)
                                 (not= #{expected-execution-id} (set (map :execution/id members))))
                        [(reason :package/execution-id-mismatch :artifact-id :run-finalization
                                 :expected expected-execution-id :actual (mapv :execution/id members))])
                      (when (and member (:artifact-present? scenario)
                                 (not= (:scenario-id member) (:scenario-id scenario)))
                        [(reason :package/scenario-id-mismatch :artifact-id :scenario-finalization
                                 :expected (:scenario-id member) :actual (:scenario-id scenario))])
                      (when (and member (:artifact-present? scenario)
                                 (not= (:execution/id member) (:execution-id scenario)))
                        [(reason :package/execution-id-mismatch :artifact-id :scenario-finalization
                                 :expected (:execution/id member) :actual (:execution-id scenario))])
                      (when-not (hash-ref/valid-sha256-ref? expected-input-hash)
                        [(reason :package/missing-authoritative-input-commitment :artifact-id :input-snapshot)])
                      (when (and expected-input-hash (:artifact-present? scenario)
                                 (not= expected-input-hash (:scenario-input-hash scenario)))
                        [(reason :package/scenario-input-hash-mismatch :artifact-id :scenario-finalization
                                 :expected expected-input-hash :actual (:scenario-input-hash scenario))])
                      (when (and expected-input-hash (:artifact-present? run-final)
                                 (not= expected-input-hash (:run-input-hash run-final)))
                        [(reason :package/run-input-hash-mismatch :artifact-id :run-finalization
                                 :expected expected-input-hash :actual (:run-input-hash run-final))])
                      (when (and expected-input-hash (:artifact-present? dag)
                                 (not-every? #(= expected-input-hash (get-in % [:node/input-hashes :scenario/source-hash]))
                                             (:nodes dag)))
                        [(reason :package/dag-input-hash-mismatch :artifact-id :execution-dag
                                 :expected expected-input-hash)])
                      (when (and member
                                 (not= (get-in artifacts [:scenario-finalization :sha256])
                                       (get-in member [:finalization :sha256])))
                        [(reason :package/scenario-finalization-not-committed
                                 :indexed-hash (get-in artifacts [:scenario-finalization :sha256])
                                 :committed-hash (get-in member [:finalization :sha256]))])
                      (completion-commitment-reasons completion artifacts)))]
    {:valid? (empty? reasons) :reasons reasons
     :run-id expected-run-id :scenario-id expected-scenario-id :execution-id expected-execution-id}))

(defn- subordinate-validation-reasons [reports reconciliation]
  (vec (concat
        (for [[artifact-id report-key package-code]
              [[:runner-finalization :runner-finalization-report :package/invalid-runner-finalization]
               [:scenario-finalization :scenario-finalization-report :package/invalid-scenario-finalization]
               [:run-finalization :run-finalization-report :package/invalid-run-finalization]
               [:execution-dag :execution-dag-report :package/invalid-execution-dag]
               [:canonical-assurance :canonical-assurance-report :package/invalid-canonical-assurance]
               [:registry-validation :registry-validation-report :package/invalid-registry-validation]
               [:value-at-risk :value-at-risk-report :package/invalid-value-at-risk]]
              :let [report (get reports report-key)]
              :when (and report (not (:valid? report)))]
          (reason package-code :artifact-id artifact-id :causes (vec (report-causes report))))
        (mapcat (fn [report]
                  (filter #(= :package/unreadable-artifact (:code %))
                          (report-causes report)))
                (vals reports))
        (when-not (:valid? reconciliation)
          (concat
           [(reason :package/reconciliation-failed :causes (:reasons reconciliation))]
           ;; Preserve specific top-level reconciliation codes for callers while
           ;; retaining the grouped report for structured diagnostics.
           (:reasons reconciliation))))))

(declare validate-completeness)

(defn validate-benchmark-package-closure
  "Validate the structural closure of a benchmark package. This public validator
   intentionally validates indexed artifact refs/hashes and completion bindings,
   not scenario identities, finalization membership, or an execution DAG."
  [run-root completion index]
  (let [artifacts (:artifacts index)
        entries (sort-by (comp name key) artifacts)
        missing (sort (set/difference benchmark-artifacts (set (keys artifacts))))
        duplicate-paths (->> entries (map (comp :ref val)) (remove nil?) frequencies
                             (keep (fn [[path n]] (when (> n 1) path))) vec)
        artifact-reasons
        (mapcat (fn [[id {:keys [ref sha256]}]]
                  (let [file (and (string? ref) (contained-file run-root ref))]
                    (cond-> []
                      (nil? file) (conj (reason :package/path-outside-root :artifact-id id :path ref))
                      (and file (not (.isFile file))) (conj (reason :package/missing-artifact :artifact-id id :path ref))
                      (not (hash-ref/valid-sha256-ref? sha256))
                      (conj (reason :package/invalid-artifact-sha256 :artifact-id id :path ref :actual sha256))
                      (and file (.isFile file) (hash-ref/valid-sha256-ref? sha256)
                           (not= sha256 (sha-ref file)))
                      (conj (reason :package/artifact-hash-mismatch :artifact-id id :path ref)))))
                entries)
        binding-reasons
        (vec (concat
              (when-not (= "benchmark-completion.v1" (get completion "schema_version"))
                [(reason :package/completion-invalid :field :schema_version)])
              (when-not (= "benchmark" (get completion "run_type"))
                [(reason :package/completion-invalid :field :run_type)])
              (when-not (= (:run/id index) (get completion "run_id"))
                [(reason :package/run-id-mismatch :expected (:run/id index) :actual (get completion "run_id"))])
              (for [[field artifact-field] [["finalization_ref" :ref] ["finalization_sha256" :sha256]]
                    :let [expected (get-in artifacts [:benchmark-finalization artifact-field])
                          actual (get completion field)]
                    :when (not= expected actual)]
                (reason :package/completion-artifact-commitment-mismatch
                        :field field :artifact-id :benchmark-finalization
                        :expected expected :actual actual))))
        reasons (vec (concat
                      (when-not (= schema-version (:run-package/schema-version index))
                        [(reason :package/unsupported-schema)])
                      (when-not (= :benchmark (:run/type index))
                        [(reason :package/unsupported-run-type :run-type (:run/type index))])
                      (when-not (= (hc/hash-with-intent {:hash/intent :run-package-index}
                                                        (package-index-payload index))
                                   (:run-package/hash index))
                        [(reason :package/index-hash-mismatch)])
                      (map #(reason :package/missing-required-artifact :artifact-id %) missing)
                      (when (seq duplicate-paths) [(reason :package/duplicate-artifact-path :paths duplicate-paths)])
                      artifact-reasons binding-reasons))]
    {:valid? (empty? reasons) :status (if (empty? reasons) :valid :invalid)
     :index index :reasons reasons}))

(defn- validate-integrity-for-index
  "Validate a trusted, completion-bound immutable index and its indexed local
   closure. The caller must pass the same parsed index that was derived from
   the completion-bound bytes; this function never falls back to a default
   manifest path."
  [run-root completion index path]
  (if (= :benchmark (:run/type index))
    (assoc (validate-benchmark-package-closure run-root completion index) :index-path path)
    (let [run-type (:run/type index)
          base (package-index-payload index)
          expected (hc/hash-with-intent {:hash/intent :run-package-index} base)
          artifacts (:artifacts index)
          entries (sort-by (comp name key) artifacts)
          reports (validated-subordinate-reports run-root artifacts)
          reconciliation (reconciliation-report index completion artifacts reports)
          pro-rata-mechanism-nodes (validate-pro-rata-mechanism-nodes run-root artifacts)
          duplicate-paths (->> entries (map (comp :ref val)) (remove nil?) frequencies (keep (fn [[p n]] (when (> n 1) p))) vec)
          reasons (vec (concat
                        (when-not (= schema-version (:run-package/schema-version index)) [(reason :package/unsupported-schema)])
                        (when-not (contains? supported-run-types run-type) [(reason :package/unsupported-run-type :run-type run-type)])
                        (when-not (= expected (:run-package/hash index)) [(reason :package/index-hash-mismatch)])
                        (when (seq duplicate-paths) [(reason :package/duplicate-artifact-path :paths duplicate-paths)])
                        (mapcat
                         (fn [[id {:keys [ref sha256 bytes]}]]
                           (let [file (and (string? ref) (contained-file run-root ref))]
                             (cond-> []
                               (nil? file)
                               (conj (reason :package/path-outside-root :artifact-id id :path ref))
                               (and file (not (.isFile (io/file file))))
                               (conj (reason :package/missing-artifact :artifact-id id :path ref))
                               (not (hash-ref/valid-sha256-ref? sha256))
                               (conj (reason :package/invalid-artifact-sha256 :artifact-id id :path ref :actual sha256))
(and file (.isFile (io/file file)) (hash-ref/valid-sha256-ref? sha256) (not= sha256 (sha-ref file)))
                                (conj (reason :package/artifact-hash-mismatch :artifact-id id :path ref))
                               (and (contains? single-scenario-artifacts id) (nil? bytes))
                               (conj (reason :package/missing-artifact-byte-length :artifact-id id :path ref))
                               (and (some? bytes) (not (and (integer? bytes) (not (neg? bytes)))))
                               (conj (reason :package/invalid-artifact-byte-length :artifact-id id :path ref :actual bytes))
                               (and file (integer? bytes) (not (neg? bytes)) (.isFile (io/file file))
                                    (not= bytes (.length (io/file file))))
                               (conj (reason :package/artifact-length-mismatch :artifact-id id :path ref)))))
                         entries)
                        (when-not (:valid? pro-rata-mechanism-nodes)
                          [(reason :package/invalid-pro-rata-mechanism-nodes
                                   :causes (:reasons pro-rata-mechanism-nodes))])
                        (subordinate-validation-reasons reports reconciliation)))]
      {:valid? (empty? reasons) :status (if (empty? reasons) :valid :invalid)
       :index index :index-path path :reasons reasons
       :runner-finalization-report (:runner-finalization-report reports)
       :scenario-finalization-report (:scenario-finalization-report reports)
       :run-finalization-report (:run-finalization-report reports)
       :execution-dag-report (:execution-dag-report reports)
       :canonical-assurance-report (:canonical-assurance-report reports)
       :registry-validation-report (:registry-validation-report reports)
       :value-at-risk-report (:value-at-risk-report reports)
       :reconciliation-report reconciliation
       :checks (merge
                (into {} (for [[artifact-id report-key check-key]
                               [[:runner-finalization :runner-finalization-report :runner-finalization-valid]
                                [:scenario-finalization :scenario-finalization-report :scenario-finalization-valid]
                                [:run-finalization :run-finalization-report :run-finalization-valid]
                                [:execution-dag :execution-dag-report :execution-dag-valid]
                                [:canonical-assurance :canonical-assurance-report :canonical-assurance-valid]
                                [:registry-validation :registry-validation-report :registry-validation-valid]
                                [:value-at-risk :value-at-risk-report :value-at-risk-valid]]]
                           (let [report (get reports report-key)]
                             [check-key (if (and report (:valid? report)) :pass :fail)])))
                {:reconciliation-valid (if (:valid? reconciliation) :pass :fail)})})))

(defn- validate-completeness-at-root
  "Check required terminal artifacts for the package profile."
  [run-root]
  (let [{:keys [index] missing-reason :reason} (read-index run-root)]
    (if missing-reason {:complete? false :status :incomplete :reasons [missing-reason]}
        (let [required (required-artifacts (:run/type index))
              missing (sort (seq (clojure.set/difference required (set (keys (:artifacts index))))))
              completion (io/file run-root paths/completion)
              reasons (vec (concat
                            (when-not (contains? supported-run-types (:run/type index)) [(reason :package/unsupported-run-type :run-type (:run/type index))])
                            (map #(reason :package/missing-required-artifact :artifact-id %) missing)
                            (when-not (.isFile completion) [(reason :package/missing-completion)])
                            (when (and (.isFile completion)
                                       (not= (str "sha256:" (lifecycle/sha256-file (io/file run-root paths/run-package-index)))
                                             (get (json/read-str (slurp completion)) "run_package_index_sha256")))
                              [(reason :package/completion-index-mismatch)])
                            (when (and (.isFile completion)
                                       (not= (.length (io/file run-root paths/run-package-index))
                                             (get (json/read-str (slurp completion)) "run_package_index_bytes")))
                              [(reason :package/completion-index-length-mismatch)])))]
          {:complete? (empty? reasons) :status (if (empty? reasons) :complete :incomplete) :reasons reasons}))))

(defn validate-precompletion-package
  "Validate the frozen package-index closure immediately before the terminal
   completion seal is written. This deliberately does not call the sealed-package
   completeness validator: completion.json cannot exist at this lifecycle point.
   Semantic outcome and release policy are excluded."
  [run-root]
  (let [{:keys [index path] missing-reason :reason} (read-index run-root)
        required (when index (required-artifacts (:run/type index)))
        missing (when index (sort (set/difference required (set (keys (:artifacts index))))))
        complete {:complete? (and index (empty? missing))
                  :status (if (and index (empty? missing)) :complete :incomplete)
                  :reasons (vec (concat (when missing-reason [missing-reason])
                                        (map #(reason :package/missing-required-artifact :artifact-id %) missing)))}
        integrity (if index
                    (validate-integrity-for-index run-root nil index path)
                    {:valid? false :status :incomplete :reasons (:reasons complete)})
        reasons (vec (concat (:reasons complete) (:reasons integrity)))]
    {:valid? (empty? reasons) :reasons reasons
     :complete complete :integrity integrity}))

(defn validate-completeness-from-context [ctx]
  (if (seq (:reasons ctx))
    {:complete? false :status :incomplete :reasons (:reasons ctx)}
    (let [index (get-in ctx [:package-index :index])
          run-type (:run/type index)
          required (required-artifacts run-type)
          identity-reasons (when (= run-type :single-scenario)
                             (concat (when-not (and (string? (:scenario/id index)) (seq (:scenario/id index)))
                                       [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :scenario/id)])
                                     (when-not (and (string? (:execution/id index)) (seq (:execution/id index)))
                                       [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :execution/id)])))
          missing (sort (seq (set/difference required (set (keys (:artifacts index))))))
          reasons (vec (concat
                        (when-not (contains? supported-run-types run-type)
                          [(reason :package/unsupported-run-type :run-type run-type)])
                        identity-reasons
                        (map #(reason :package/missing-required-artifact :artifact-id %) missing)))]
      {:complete? (empty? reasons)
       :status (if (empty? reasons) :complete :incomplete)
       :reasons reasons})))

(defn validate-integrity-from-context [ctx]
  (if (seq (:reasons ctx))
    {:valid? false :status :invalid :reasons (:reasons ctx)}
        ;; The trusted index was parsed from the exact bytes completion commits to.
        ;; Artifact files are still read for closure validation, but index declarations
        ;; are never resolved again through an unsealed default path.
    (let [{:keys [index path]} (:package-index ctx)]
      (validate-integrity-for-index (:run-root ctx) (:completion ctx) index path))))

(defn validate-completeness [run-root]
  (validate-completeness-from-context (resolve-completion-context run-root)))

(defn validate-integrity [run-root]
  (validate-integrity-from-context (resolve-completion-context run-root)))

(defn resolve-validation-context
  "Resolve the completion-sealed package once and retain independent reports.
   Downstream predicates derive their verdicts from this context rather than
   reopening a mutable default package-index path."
  [run-root]
  (let [ctx (resolve-completion-context run-root)
        complete (validate-completeness-from-context ctx)
        integrity (validate-integrity-from-context ctx)]
    ;; `validate-integrity-from-context` resolves the completion-bound index and
    ;; collects each subordinate report once. Preserve those reports here rather
    ;; than independently reopening finalization, DAG, assurance, or registry
    ;; files for each derived package predicate.
    (assoc ctx
           :closure-report integrity
           :integrity-report integrity
           :completeness-report complete
           :checks (:checks integrity)
           :runner-finalization-report (:runner-finalization-report integrity)
           :scenario-finalization-report (:scenario-finalization-report integrity)
           :run-finalization-report (:run-finalization-report integrity)
           :execution-dag-report (:execution-dag-report integrity)
           :canonical-assurance-report (:canonical-assurance-report integrity)
           :registry-validation-report (:registry-validation-report integrity)
           :value-at-risk-report (:value-at-risk-report integrity)
           :reconciliation-report (:reconciliation-report integrity))))

(defn validate-runnability-from-context
  "Derive runnability from one resolved, completion-sealed filesystem view.
   Semantic outcome is deliberately not an input to this structural verdict."
  [ctx]
  (let [complete (:completeness-report ctx)
        integrity (:integrity-report ctx)
        reasons (vec (concat (:reasons complete) (:reasons integrity)))]
    {:runnable? (empty? reasons) :status (if (empty? reasons) :runnable :not-runnable)
     :reasons reasons :complete complete :integrity integrity}))

(defn validate-runnability [run-root]
  (validate-runnability-from-context (resolve-validation-context run-root)))

(defn validate-semantic-result [run-root]
  (let [ctx (resolve-validation-context run-root)
        data (:completion ctx)
        pass? (= "pass" (get data "semantic_status"))
        reasons (vec (concat (:reasons ctx)
                             (when (and (empty? (:reasons ctx)) (not pass?))
                               [(reason :package/semantic-not-pass
                                        :actual (get data "semantic_status"))])))]
    {:semantic-pass? (and (empty? reasons) pass?) :reasons reasons}))

(defn validate-release-eligibility [run-root]
  (let [ctx (resolve-validation-context run-root)
        runnable (let [complete (:completeness-report ctx) integrity (:integrity-report ctx)]
                   {:runnable? (and (:complete? complete) (:valid? integrity))
                    :reasons (vec (concat (:reasons complete) (:reasons integrity)))})
        assurance (:canonical-assurance-report ctx)
        signed-assurance? (and assurance
                               (:valid? assurance)
                               (not= "unsigned-canonical-integrity" (:assurance-kind assurance)))
        reasons (vec (concat (:reasons runnable)
                             (when-not (and assurance (:valid? assurance))
                               [(reason :package/canonical-assurance-not-passed
                                        :causes (vec (report-causes assurance))
                                        :assurance-kind (:assurance-kind assurance)
                                        :scope (:scope assurance))])
                             ;; Canonical content integrity is intentionally not
                             ;; an operator/signature release authorization.
                             (when-not signed-assurance?
                               [(reason :package/release-signature-assurance-required)])))]
    {:release-eligible? (empty? reasons) :reasons reasons
     :note "Unsigned canonical integrity establishes content integrity only; release eligibility requires signer/operator assurance."}))

(defn complete? [run-root] (:complete? (validate-completeness run-root)))
(defn integrity-valid? [run-root] (:valid? (validate-integrity run-root)))
(defn runnable? [run-root] (:runnable? (validate-runnability run-root)))
(defn semantic-pass? [run-root] (:semantic-pass? (validate-semantic-result run-root)))
(defn release-eligible? [run-root] (:release-eligible? (validate-release-eligibility run-root)))
