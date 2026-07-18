(ns resolver-sim.run.package-index
  "Immutable package index and package-level validation.

   A package index is the outer runnable boundary for a finalized run.  It is
   intentionally distinct from the immutable inner bundle root.  The currently
   supported profile is :single-scenario; other run types return an explicit
   unsupported result rather than inheriting single-scenario requirements."
  (:require [clojure.data.json :as json]
              [clojure.java.io :as io]
              [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
                        [resolver-sim.evidence.finalization :as finalization]
                                                [resolver-sim.forensic.execution-dag :as execution-dag]
                                                [resolver-sim.hash.canonical :as hc]
            [resolver-sim.run.runner-finalization :as runner-finalization]
                        [resolver-sim.validation.integration.artifact-registry :as artifact-registry])
  (:import [java.nio.file Files StandardCopyOption]
             [java.security MessageDigest]
             [java.math BigInteger]))

(def schema-version "run-package-index.v1")
(def ^:private supported-run-types #{:single-scenario})
(def ^:private single-scenario-artifacts
  #{:input-snapshot :scenario-finalization :runner-finalization :run-finalization
        :canonical-assurance :artifact-registry :registry-validation :execution-dag})

(defn- sha-ref [file] (str "sha256:" (lifecycle/sha256-file file)))
(defn- reason [code & {:as data}] (assoc data :code code))
(defn- json-key [k] (if (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)) (str k)))
(defn- contained-file [root ref]
  (let [root-path (.toAbsolutePath (.normalize (.toPath (io/file root))))
        file (.toAbsolutePath (.normalize (.toPath (io/file root ref))))]
    (when (.startsWith file root-path) (.toFile file))))

(defn build
  [{:keys [run-id scenario-id execution-id run-type bundle-root-hash artifacts input-snapshot runner-finalization run-finalization
           canonical-assurance execution-dag scenario-finalization artifact-registry registry-validation]}]
  (let [artifacts (or artifacts {:input-snapshot input-snapshot
                                 :scenario-finalization scenario-finalization
                                 :runner-finalization runner-finalization
                                 :run-finalization run-finalization
                                 :canonical-assurance canonical-assurance
                                 :artifact-registry artifact-registry
                                 :registry-validation registry-validation
                                 :execution-dag execution-dag})
        base {:run-package/schema-version schema-version
              :run/type (or run-type :single-scenario)
              :run/id run-id
              :scenario/id scenario-id
              :execution/id execution-id
              :bundle/root-hash bundle-root-hash
              :artifacts artifacts}
        hash (hc/hash-with-intent {:hash/intent :run-package-index} base)]
    (assoc base :run-package/hash hash)))

(defn write! [path input]
  (let [index (build input)
        target (io/file path)
        temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str index :key-fn json-key :indent true))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    {:path target :index index}))

(def ^:private sha256-ref-pattern #"^sha256:[0-9a-f]{64}$")
(defn- bytes-sha-ref [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (str "sha256:" (format "%064x" (BigInteger. 1 (.digest digest bytes))))))

    (defn resolve-completion-context
  "Read the terminal completion seal first. A package index becomes trusted only
   after its exact persisted bytes match completion's path/hash/length binding."
  [run-root]
  (let [completion-file (io/file run-root "completion.json")]
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
                            (when-not (= "run-completion.v1" (get completion "schema_version"))
                                                          [(reason :package/completion-invalid :field :schema_version)])
                                                        (when-not (and (string? (get completion "run_id"))
                                                                       (seq (get completion "run_id")))
                                                          [(reason :package/completion-invalid :field :run_id)])
                                                        (when-not (= "completed" (get completion "lifecycle_status"))
                                                          [(reason :package/completion-invalid :field :lifecycle_status)])
                                                        (when (and (get completion "run_type")
                                                                   (not= "scenario" (get completion "run_type")))
                                                          [(reason :package/completion-invalid :field :run_type)])
                            (when-not (string? path) [(reason :package/completion-invalid :field :run_package_index_ref)])
                            (when (and path (nil? index-file)) [(reason :package/package-index-path-invalid :path path)])
                            (when (and index-file (not (.isFile index-file))) [(reason :package/package-index-missing :path path)])
                            (when-not (and (string? expected-hash) (re-matches sha256-ref-pattern expected-hash))
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
                             (try {:index (json/read-str (String. ^bytes index-bytes "UTF-8") :key-fn keyword)
                                   :path index-file
                                   :sha256 actual-hash
                                   :bytes actual-bytes}
                                  (catch Exception _ {:reason (reason :package/package-index-invalid-json :path path)})))
              all-reasons (vec (concat reasons (when-let [r (:reason index-result)] [r])))]
          {:run-root run-root
                     :completion completion
                     :completion-report {:valid? (empty? all-reasons) :reasons all-reasons}
           :package-index index-result
           :reasons all-reasons})
        (catch Exception _
          {:run-root run-root
                     :completion-report {:valid? false :reasons [(reason :package/completion-invalid)]}
           :reasons [(reason :package/completion-invalid)]})))))

(defn read-index [run-root]
  (let [file (io/file run-root "manifest/run-package-index.json")]
    (if (.isFile file)
      {:index (json/read-str (slurp file) :key-fn keyword) :path file}
      {:reason (reason :package/missing-index :path "manifest/run-package-index.json")})))

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
                              (filter #{"manifest/run-package-index.json" "completion.json"} paths))
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
        registry-ref (get-in artifacts [:artifact-registry :ref])
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
                          (when-not (= :passed (:status recalculated))
                            [(reason :registry-validation/recalculated-not-passed :causes (:errors recalculated))])
                          (when-not (:valid? closure) (:reasons closure))))]
        {:valid? (empty? reasons)
         :registry-path registry-ref
         :persisted-status (:status report)
         :recalculated-status (:status recalculated)
         :closure-report closure
         :reasons reasons}))))

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

(defn- subordinate-reasons [run-root index completion artifacts]
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
                                                             (when-not (and (string? expected-input-hash) (re-matches sha256-ref-pattern expected-input-hash))
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
                                                                        :expected expected-execution-id :actual (vec (sort execution-ids)) )])
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

(declare validate-completeness)

(defn- validate-integrity-for-index
  "Validate a trusted, completion-bound immutable index and its indexed local
   closure. The caller must pass the same parsed index that was derived from
   the completion-bound bytes; this function never falls back to a default
   manifest path."
  [run-root completion index path]
      (let [run-type (:run/type index)
            base (dissoc index :run-package/hash)
            expected (hc/hash-with-intent {:hash/intent :run-package-index} base)
            artifacts (:artifacts index)
            entries (sort-by (comp name key) artifacts)
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
                                 (and file (.isFile (io/file file)) (not= sha256 (sha-ref file)))
                                 (conj (reason :package/artifact-hash-mismatch :artifact-id id :path ref))
                                 (and (contains? single-scenario-artifacts id) (nil? bytes))
                                 (conj (reason :package/missing-artifact-byte-length :artifact-id id :path ref))
                                 (and bytes (.isFile (io/file file)) (not= bytes (.length (io/file file))))
                                 (conj (reason :package/artifact-length-mismatch :artifact-id id :path ref)))))
                           entries)
                          (subordinate-reasons run-root index completion artifacts)))]
        {:valid? (empty? reasons) :status (if (empty? reasons) :valid :invalid)
         :index index :index-path path :reasons reasons}))

(defn- validate-completeness-at-root
  "Check required terminal artifacts for the package profile."
  [run-root]
  (let [{:keys [index] missing-reason :reason} (read-index run-root)]
      (if missing-reason {:complete? false :status :incomplete :reasons [missing-reason]}
      (let [required (case (:run/type index) :single-scenario single-scenario-artifacts #{})
            missing (sort (seq (clojure.set/difference required (set (keys (:artifacts index))))) )
            completion (io/file run-root "completion.json")
            reasons (vec (concat
                          (when-not (contains? supported-run-types (:run/type index)) [(reason :package/unsupported-run-type :run-type (:run/type index))])
                          (map #(reason :package/missing-required-artifact :artifact-id %) missing)
                          (when-not (.isFile completion) [(reason :package/missing-completion)])
                          (when (and (.isFile completion)
                                     (not= (str "sha256:" (lifecycle/sha256-file (io/file run-root "manifest/run-package-index.json")))
                                           (get (json/read-str (slurp completion)) "run_package_index_sha256")))
                            [(reason :package/completion-index-mismatch)])
                          (when (and (.isFile completion)
                                     (not= (.length (io/file run-root "manifest/run-package-index.json"))
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
        required (when index (case (:run/type index) :single-scenario single-scenario-artifacts #{}))
        missing (when index (sort (set/difference required (set (keys (:artifacts index))))) )
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
          required (case run-type :single-scenario single-scenario-artifacts #{})
          identity-reasons (when (= run-type :single-scenario)
                             (concat (when-not (and (string? (:scenario/id index)) (seq (:scenario/id index)))
                                       [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :scenario/id)])
                                     (when-not (and (string? (:execution/id index)) (seq (:execution/id index)))
                                       [(reason :package/missing-authoritative-identity :artifact-id :package-index :field :execution/id)])))
          missing (sort (seq (set/difference required (set (keys (:artifacts index))))) )
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
        integrity (validate-integrity-from-context ctx)
        artifacts (get-in ctx [:package-index :index :artifacts])
        trusted? (empty? (:reasons ctx))
        canonical-report (when trusted? (validate-canonical-assurance run-root artifacts))
        registry-report (when trusted? (validate-registry-validation run-root artifacts))
        dag-report (when trusted?
                     (some-> (artifact-json run-root artifacts :execution-dag)
                             execution-dag/validate-persisted-dag))]
    (assoc ctx
           :closure-report integrity
           :integrity-report integrity
           :completeness-report complete
           :canonical-assurance-report canonical-report
           :registry-validation-report registry-report
           :execution-dag-report dag-report
           :reconciliation-report {:valid? (:valid? integrity)
                                   :reasons (filter #(contains? #{:package/run-id-mismatch
                                                                  :package/completion-artifact-commitment-mismatch
                                                                  :package/scenario-finalization-reconciliation-failed}
                                                                (:code %))
                                                    (:reasons integrity))})))

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
        reasons (vec (concat (:reasons runnable)
                             (when-not (and assurance (:valid? assurance))
                               [(reason :package/canonical-assurance-not-passed)])))]
    {:release-eligible? (empty? reasons) :reasons reasons
     :note "Unsigned canonical integrity establishes content integrity only; release policy must add signer/operator assurance."}))

(defn complete? [run-root] (:complete? (validate-completeness run-root)))
(defn integrity-valid? [run-root] (:valid? (validate-integrity run-root)))
(defn runnable? [run-root] (:runnable? (validate-runnability run-root)))
(defn semantic-pass? [run-root] (:semantic-pass? (validate-semantic-result run-root)))
(defn release-eligible? [run-root] (:release-eligible? (validate-release-eligibility run-root)))
