(ns resolver-sim.evidence.finalization
  "Pure Phase A constructors and validators for evidence-finalization.v2.
   Persistence, package inventory, signatures, timestamps, and DAG binding are
   intentionally owned by the coordinated artifact-contract integration."
  (:require [clojure.data.json :as json]
              [clojure.edn :as edn]
              [clojure.java.io :as io]
            [clojure.set :as set]
            [resolver-sim.evidence.chain :as chain]
                        [resolver-sim.evidence.node :as evidence-node]
                        [resolver-sim.hash.canonical :as hc])
  (:import [java.nio.file Files StandardCopyOption AtomicMoveNotSupportedException]
             [java.security MessageDigest]
                        [java.math BigInteger]))

(def schema-version "evidence-finalization.v2")
(def hash-set-schema-version "evidence-hash-set.v1")
(def ^:private sha256-ref-pattern #"^sha256:[0-9a-f]{64}$")

(defn sha256-ref? [value]
  (boolean (and (string? value) (re-matches sha256-ref-pattern value))))

(defn sha256-ref [raw-hash]
  (let [hash (str raw-hash)]
    (cond
      (sha256-ref? hash) hash
      (re-matches #"^[0-9a-f]{64}$" hash) (str "sha256:" hash)
      :else (throw (ex-info "Expected SHA-256 digest" {:value raw-hash})))))

(defn hash-set-projection [hashes]
  {:schema-version hash-set-schema-version
   :hash-algorithm "sha256"
   :count (count hashes)
   :hashes (vec hashes)})

(defn validate-hash-set [hash-set]
  (let [hashes (:hashes hash-set)
        sorted (when (vector? hashes) (vec (sort hashes)))
        structurally-valid? (and (vector? hashes)
                                 (= hashes sorted)
                                 (= (count hashes) (count (distinct hashes)))
                                 (every? sha256-ref? hashes)
                                 (= (:count hash-set) (count hashes)))
        expected-root (when structurally-valid?
                        (sha256-ref (hc/hash-with-intent {:hash/intent :evidence-hash-set}
                                                         (hash-set-projection hashes))))
        errors (cond-> []
                 (not= hash-set-schema-version (:schema-version hash-set))
                 (conj :unsupported-schema-version)
                 (not= "sha256" (:hash-algorithm hash-set))
                 (conj :unsupported-hash-algorithm)
                 (not (vector? hashes))
                 (conj :hashes-not-vector)
                 (and (vector? hashes) (not= hashes sorted))
                 (conj :hashes-not-lexicographically-sorted)
                 (and (vector? hashes) (not= (count hashes) (count (distinct hashes))))
                 (conj :duplicate-hashes)
                 (and (vector? hashes) (not-every? sha256-ref? hashes))
                 (conj :malformed-hash)
                 (not= (:count hash-set) (count hashes))
                 (conj :count-mismatch)
                 (not (sha256-ref? (:root hash-set)))
                 (conj :malformed-root)
                 (and expected-root (not= expected-root (:root hash-set)))
                 (conj :root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-hash-set [hashes]
  (let [hashes (mapv sha256-ref hashes)
        normalized (vec (sort (distinct hashes)))
        projection (hash-set-projection normalized)
        root (sha256-ref (hc/hash-with-intent {:hash/intent :evidence-hash-set} projection))]
    (assoc projection :root root)))

(defn- envelope [kind run subject execution evidence bindings verification policy]
  {:schema-version schema-version
   :finalization-kind kind
   :canonicalization {:scheme "prf-canonical-hash-v1"
                      :intent "evidence-finalization-v2"}
   :run run
   :subject subject
   :execution execution
   :evidence evidence
   :bindings bindings
   :verification verification
   :policy policy})

(defn build-scenario-finalization
  "Build a non-persisted v2 scenario-chain finalization. The caller supplies
   already verified chain facts from persisted records in later phases."
  [{:keys [run subject execution execution-id chain supplemental-channels bindings verification policy]}]
  (let [hash-set (build-hash-set (or (:reachable-hashes chain) []))
        empty? (zero? (:record-count chain))
        status (:status chain)
        valid-empty? (= status "valid-empty")
        errors (cond-> []
                 (and empty? (not valid-empty?)) (conj :empty-chain-not-policy-authorized)
                 (and valid-empty? (or (pos? (:record-count chain)) (:head chain)))
                 (conj :invalid-valid-empty-chain)
                 (and (not empty?) (not (contains? #{"verified" "partial"} status)))
                 (conj :non-empty-chain-has-unsupported-status))]
    (cond-> (envelope "scenario-chain-finalization" run subject execution
                       {:chain (assoc chain :reachable-hash-set hash-set)
                        :supplemental-channels (vec (or supplemental-channels []))}
                       bindings
                       (merge {:result-version "scenario-chain-verification.v2"
                               :status (if (seq errors) "invalid" (:status verification))
                               :reasons (vec errors)} verification)
                       policy)
      execution-id (assoc :execution/id execution-id))))

(defn build-run-finalization
  "Build a non-persisted v2 run-evidence finalization. The caller provides all
   four persisted-material sets; exact reconciliation is evaluated locally."
  [{:keys [run execution scenario-finalizations disk-hashes registry-hashes
           chain-hashes declared-hashes bindings verification policy]}]
  (let [sets [disk-hashes registry-hashes chain-hashes declared-hashes]
        normalized (mapv #(set (map sha256-ref (or % []))) sets)
        exact? (apply = normalized)
        declared-set (build-hash-set (or declared-hashes []))
        scenario-finalizations (vec (sort-by :scenario-id scenario-finalizations))
        scenario-finalization-set (build-hash-set (map #(get-in % [:finalization :sha256]) scenario-finalizations))
        scenario-chain-heads (mapv (fn [{:keys [scenario-id chain-head]}]
                                     {:scenario-id scenario-id
                                      :head-hash (:hash chain-head)})
                                   scenario-finalizations)
        scenario-chain-head-set (build-hash-set
                                 (map #(hc/hash-with-intent {:hash/intent :evidence-finalization-v2} %)
                                      scenario-chain-heads))
        reconciliation {:status (if exact? "exact" "mismatch")
                        :sources {:disk-evidence-hashes (build-hash-set (or disk-hashes []))
                                  :registry-evidence-hashes (build-hash-set (or registry-hashes []))
                                  :chain-reachable-hashes (build-hash-set (or chain-hashes []))
                                  :aggregate-declared-hashes declared-set}
                        :differences {:disk-only (vec (sort (clojure.set/difference (first normalized) (second normalized))))
                                      :registry-only (vec (sort (clojure.set/difference (second normalized) (first normalized))))
                                      :chain-only (vec (sort (clojure.set/difference (nth normalized 2) (first normalized))))
                                      :aggregate-only (vec (sort (clojure.set/difference (nth normalized 3) (first normalized))))}}]
    (envelope "run-evidence-finalization" run {:subject-kind "run-evidence-set"} execution
              {:scenario-finalizations scenario-finalizations
                             :scenario-finalization-set scenario-finalization-set
                             :scenario-chain-heads scenario-chain-heads
                             :scenario-chain-head-set scenario-chain-head-set
                             :declared-evidence-hashes (:hashes declared-set)
                             :declared-evidence-hash-set declared-set}
                            (merge bindings
                                   {:scenario-finalization-set-root (:root scenario-finalization-set)
                                    :scenario-chain-head-set-root (:root scenario-chain-head-set)})
              (merge {:result-version "run-evidence-verification.v2"
                      :status (if exact? "verified" "invalid")
                      :reconciliation reconciliation
                      :reasons (if exact? [] [{:reason-code "evidence-reconciliation-mismatch"}])}
                     verification)
              policy)))

(declare validate-finalization persisted-evidence-hash!)

(defn- json-key [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn- atomic-write! [file content]
  (let [target (io/file file)
        parent (.getParentFile target)
        temp (io/file parent (str "." (.getName target) ".tmp-" (java.util.UUID/randomUUID)))]
    (.mkdirs parent)
    (spit temp content)
    (try
      (Files/move (.toPath temp) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                   StandardCopyOption/REPLACE_EXISTING]))
      (catch AtomicMoveNotSupportedException _
        (Files/move (.toPath temp) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))
      (catch Exception e
        (throw (ex-info "Could not atomically write finalization"
                        {:target (str target)} e))))
    (.getPath target)))

(defn- file-sha256-ref [file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (Files/readAllBytes (.toPath (io/file file)))]
    (str "sha256:" (format "%064x" (BigInteger. 1 (.digest digest bytes))))))

(defn- normalize-persisted-evidence-record [record]
  ;; clojure.data.json serializes keyword namespaces away. The forensic event
  ;; files therefore use their stable JSON field names while chain verification
  ;; continues to use the namespaced in-memory representation.
  (cond-> record
    (:id record) (assoc :scenario/id (:id record))
    (:hash record) (assoc :evidence/hash (:hash record))
    (:chain-hash-scheme record) (assoc :evidence/chain-hash-scheme (:chain-hash-scheme record))
    (contains? record :chain-seq) (assoc :evidence/chain-seq (:chain-seq record))
    (contains? record :chain-prev-hash) (assoc :evidence/chain-prev-hash (:chain-prev-hash record))
    (:chain-self-hash record) (assoc :evidence/chain-self-hash (:chain-self-hash record))))

(defn- read-evidence-artifacts [forensic-dir]
  (let [dir (io/file forensic-dir "event-evidence")]
    (if (.isDirectory dir)
      (mapv (fn [file]
              {:file file
               :record (normalize-persisted-evidence-record
                        (json/read-str (slurp file) :key-fn keyword))
               ;; A link-v1 identity commits to canonical evidence content and
               ;; chain position, not JSON serialization bytes. Keep this
               ;; separate binding so byte-level persistence is testable.
               :artifact-bytes-sha256 (file-sha256-ref file)})
            (sort (filter #(.isFile %) (or (.listFiles dir) []))))
      [])))

(defn- registry-evidence-hashes [registry]
  (let [hashes (concat (or (:evidence-hashes registry) [])
                       (keep :hash/content (:entries registry)))]
    (set (map sha256-ref hashes))))

(defn- load-registry-membership [forensic-dir reachable-hashes]
  (let [path (io/file forensic-dir "evidence-registry.json")]
    (cond
      (empty? reachable-hashes)
      {:status "not-applicable" :reasons []}

      (not (.isFile path))
      {:status "missing"
       :reasons [{:reason-code "evidence-content-registry-missing"}]}

      :else
      (try
        (let [registry (json/read-str (slurp path) :key-fn keyword)
              registered (registry-evidence-hashes registry)
              reachable (set (map sha256-ref reachable-hashes))
              missing (vec (sort (set/difference reachable registered)))]
          {:status (if (empty? missing) "verified" "mismatch")
           :root (when-let [root (:registry-hash registry)] (sha256-ref root))
           :missing-hashes missing
           :reasons (if (empty? missing) []
                        [{:reason-code "evidence-content-registry-membership-mismatch"
                          :missing-hashes missing}])})
        (catch Exception _
          {:status "unreadable"
           :reasons [{:reason-code "evidence-content-registry-unreadable"}]})))))

(defn- safe-artifact-id! [artifact-id]
  (when-not (and (string? artifact-id)
                 (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" artifact-id))
    (throw (ex-info "Scenario artifact ID must be a canonical safe identifier"
                    {:scenario-artifact-id artifact-id})))
  artifact-id)

(defn write-scenario-finalization!
  "Build and atomically persist a v2 scenario finalization under a supplied
   forensic artifact directory. The payload contains only identifiers, public
   execution metadata, and digests; it does not copy evidence bodies or inputs."
  [{:keys [forensic-dir scenario-artifact-id scenario-id scenario-input-hash
           run-id run-input-hash execution-id execution-status execution-outcome policy]
    :or {execution-status "completed" execution-outcome "unknown" policy {}}}]
  (safe-artifact-id! scenario-artifact-id)
  (let [artifacts (read-evidence-artifacts forensic-dir)
        records (mapv :record artifacts)
        scenario-id (or scenario-id (:scenario/id (first records)))
        _ (when-not scenario-id
            (throw (ex-info "Scenario finalization requires an explicit or persisted scenario identity"
                            {:scenario-artifact-id scenario-artifact-id})))
        verification (chain/verify-scenario-chain records :scenario-id scenario-id)
        empty? (zero? (:chain/record-count verification))
        aborted? (= execution-status "aborted")
        membership (load-registry-membership forensic-dir (:chain/reachable-hashes verification))
        chain-status (cond
                       aborted? "partial"
                       (and empty? (true? (:allow-empty-targeted-evidence? policy))) "valid-empty"
                       empty? "invalid"
                       (and (= :verified (:chain/status verification))
                            (= "verified" (:status membership))) "verified"
                       :else "invalid")
        sorted-artifacts (sort-by #(get-in % [:record :evidence/chain-seq]) artifacts)
        genesis-artifact (first sorted-artifacts)
        genesis-record (:record genesis-artifact)
        genesis (when genesis-record
                  (let [hash (:evidence/chain-self-hash genesis-record)]
                    {:hash (sha256-ref hash)
                     :chain-seq (:evidence/chain-seq genesis-record)
                     :artifact-id (str "evidence-record/" hash)
                     :artifact-bytes-sha256 (:artifact-bytes-sha256 genesis-artifact)}))
        head-artifact (last sorted-artifacts)
        head (when-let [hash (:chain/head-hash verification)]
               {:hash (sha256-ref hash)
                :chain-seq (:chain/final-seq verification)
                :artifact-id (str "evidence-record/" hash)
                :artifact-bytes-sha256 (:artifact-bytes-sha256 head-artifact)})
        finalization (build-scenario-finalization
                      {:run {:run-id run-id :run-input-hash (sha256-ref run-input-hash)}
                       :execution-id execution-id
                       :subject {:subject-kind "scenario"
                                 :scenario-id scenario-id
                                 :scenario-artifact-id scenario-artifact-id
                                 :scenario-input {:artifact-id (str "scenario-input/" scenario-artifact-id)
                                                  :sha256 (sha256-ref scenario-input-hash)}}
                       :execution {:status execution-status
                                   :outcome execution-outcome
                                   :terminality (if aborted? "open" "closed")}
                       :chain {:format "link-v1"
                               :status chain-status
                               :record-count (:chain/record-count verification)
                               :genesis genesis
                               :head head
                               :head-verification {:membership (if head (if (contains? #{"verified" "partial"} chain-status) "verified" "invalid") "not-applicable")
                                                   :terminality (if head (if (= chain-status "verified") "unique-terminal" "non-terminal") "not-applicable")
                                                   :successor-count 0}
                               :reachable-hashes (mapv sha256-ref (:chain/reachable-hashes verification))}
                       :bindings {:evidence-content-registry
                                  {:artifact-id "evidence/content-registry"
                                   :root (:root membership)}}
                       :verification {:status (cond
                                                (contains? #{"verified" "valid-empty"} chain-status) "verified"
                                                (= chain-status "partial") "partial"
                                                :else "invalid")
                                      :checks [{:check-id "registry-membership"
                                                :status (if (= "verified" (:status membership)) "passed"
                                                            (if (= "not-applicable" (:status membership)) "not-applicable" "failed"))}]
                                      :reasons (vec (concat (:chain/errors verification)
                                                            (:reasons membership)
                                                            (when aborted?
                                                              [{:reason-code "scenario-execution-aborted"}
                                                               {:reason-code "chain-terminal-state-unproven"}])
                                                            (when (and empty? (not (true? (:allow-empty-targeted-evidence? policy))))
                                                              [{:reason-code "empty-targeted-evidence-not-authorized"}]))) }
                       :policy policy})
        validation (validate-finalization finalization {:require-execution-id? (boolean execution-id)})
        path (io/file forensic-dir "finalizations" "scenarios" scenario-artifact-id "evidence-finalization.json")]
    (when-not (:valid? validation)
      (throw (ex-info "Scenario finalization failed validation" validation)))
    (let [written-path (atomic-write! path (json/write-str finalization {:key-fn json-key :indent true}))
          persisted (json/read-str (slurp written-path) :key-fn keyword)
          persisted-validation (validate-finalization persisted {:require-execution-id? (boolean execution-id)})]
      (when-not (:valid? persisted-validation)
        (throw (ex-info "Persisted scenario finalization failed validation"
                        (assoc persisted-validation :path written-path))))
      {:path written-path
       :finalization persisted
       :validation persisted-validation})))

(defn- read-finalization!
  ([file] (read-finalization! file {}))
  ([file opts]
  (let [payload (json/read-str (slurp file) :key-fn keyword)
        validation (validate-finalization payload opts)]
    (when-not (:valid? validation)
      (throw (ex-info "Scenario finalization failed validation"
                      {:path (str file) :validation validation})))
    payload)))

(defn- persisted-evidence-hash! [file]
  (let [record (normalize-persisted-evidence-record
                (json/read-str (slurp file) :key-fn keyword))]
    (sha256-ref (:evidence/hash record))))

(defn evaluate-run-policy
  "Evaluate V2 run-assurance requirements without conflating absence,
   invalidity, and a profile that does not require the requirement."
  [finalization]
  (let [forensic? (= "forensic-release.v1" (get-in finalization [:policy :profile-id]))
        requirement (fn [required? satisfied?]
                      (cond
                        (not required?) "not-required"
                        satisfied? "satisfied"
                        :else "missing"))
        requirements {:scenario-finalizations
                      (requirement forensic?
                                   (every? #(contains? #{"verified" "valid-empty"} (:chain-status %))
                                           (get-in finalization [:evidence :scenario-finalizations])))
                      :reconciliation
                      (requirement forensic? (= "exact" (get-in finalization [:verification :reconciliation :status])))
                      :evidence-dag
                      (requirement forensic? (= "verified" (get-in finalization [:bindings :evidence-dag :status])))
                      :runner-finalization
                      (requirement forensic?
                                   (and (= :runner-local (get-in finalization [:bindings :runner-finalization :runtime-kind]))
                                        (sha256-ref? (get-in finalization [:bindings :runner-finalization :hash]))))
                      :signature
                      (requirement forensic? false)
                      :timestamp
                      (requirement forensic? false)
                      :package-closure
                      (requirement forensic? false)}
        satisfied? (every? #(contains? #{"satisfied" "not-required"} %) (vals requirements))]
    {:profile-id (get-in finalization [:policy :profile-id] "inspection.v1")
     :requirements requirements
     :satisfied? satisfied?}))

(defn write-content-registry!
  "Persist the non-circular evidence-content registry used by a run finalization.
   Its inventory is restricted to explicitly supplied evidence-content files."
  [registry-path evidence-files]
  (let [hashes (mapv persisted-evidence-hash! evidence-files)
        hash-set (build-hash-set hashes)
        registry {:schema-version "evidence-content-registry.v1"
                  :evidence-hashes (:hashes hash-set)
                  :evidence-count (:count hash-set)
                  :registry-hash (:root hash-set)}]
    (atomic-write! registry-path (json/write-str registry {:indent true}))
    registry))

(defn- evidence-dag-binding [node-files]
  (if (empty? node-files)
    {:status "missing" :reasons [{:reason-code "evidence-dag-binding-missing"}]}
    (try
      (let [nodes (mapv #(edn/read-string (slurp %)) node-files)
            validation (evidence-node/validate-node-dag nodes)
            hashes (mapv (comp sha256-ref :node-hash) nodes)]
        (if (:valid? validation)
          {:status "verified"
           :root (:root (build-hash-set hashes))
           :node-count (count nodes)
           :reasons []}
          {:status "invalid"
           :reasons [{:reason-code "evidence-dag-validation-failed"
                      :errors (:errors validation)}]}))
      (catch Exception _
        {:status "unreadable"
         :reasons [{:reason-code "evidence-dag-unreadable"}]}))))

(defn write-run-finalization!
  "Build and atomically persist a run-evidence-finalization from persisted
   material only. The caller owns placement and lifecycle ordering: it supplies
   the finalization destination, accepted scenario finalization files, evidence
   content files, and the already-finalized content registry. This function does
   not add the finalization to that registry, preventing an inventory cycle."
  [{:keys [finalization-path reconciliation-report-path scenario-finalization-files evidence-files evidence-node-files registry-path
            run execution policy bindings require-execution-identities?]
    :or {policy {} bindings {} require-execution-identities? false}}]
  (when-not (and finalization-path (seq scenario-finalization-files) registry-path)
    (throw (ex-info "Run finalization requires destination, scenario finalizations, and registry"
                    {:finalization-path finalization-path
                     :scenario-finalization-count (count scenario-finalization-files)
                     :registry-path registry-path})))
  (let [scenario-files (sort-by str scenario-finalization-files)
        scenarios (mapv #(read-finalization! % {:require-execution-id? require-execution-identities?}) scenario-files)
        registry (json/read-str (slurp registry-path) :key-fn keyword)
        registry-hashes (registry-evidence-hashes registry)
        disk-hashes (mapv persisted-evidence-hash! evidence-files)
        chain-hashes (mapcat #(get-in % [:evidence :chain :reachable-hashes] []) scenarios)
        scenario-refs (mapv (fn [file finalization]
                              {:scenario-id (get-in finalization [:subject :scenario-id])
                               :execution/id (:execution/id finalization)
                               :finalization {:artifact-id (str "scenario-finalization/"
                                                               (get-in finalization [:subject :scenario-artifact-id]))
                                              :sha256 (file-sha256-ref file)}
                               :execution-status (get-in finalization [:execution :status])
                               :chain-status (get-in finalization [:evidence :chain :status])
                               :chain-head (get-in finalization [:evidence :chain :head])})
                            scenario-files scenarios)
        dag-binding (evidence-dag-binding evidence-node-files)
        scenarios-accepted? (every? #(and (= "verified" (get-in % [:verification :status]))
                                           (contains? #{"verified" "valid-empty"}
                                                      (get-in % [:evidence :chain :status])))
                                    scenarios)
        result (build-run-finalization
                {:run run
                 :execution execution
                 :scenario-finalizations scenario-refs
                 :disk-hashes disk-hashes
                 :registry-hashes registry-hashes
                 :chain-hashes chain-hashes
                 :declared-hashes chain-hashes
                 :bindings (merge bindings
                                  {:evidence-content-registry
                                                                     {:artifact-id "evidence/content-registry"
                                                                      :root (some-> (or (:registry-hash registry) (:root registry)) sha256-ref)}
                                                                     :evidence-dag (select-keys dag-binding [:root :node-count :status])})
                 :policy policy
                 :verification (cond-> (if scenarios-accepted?
                                                           {}
                                                           {:status "invalid"
                                                            :reasons [{:reason-code "scenario-finalization-not-accepted"}]})
                                                   (not= "verified" (:status dag-binding))
                                                   (update :reasons (fnil into []) (:reasons dag-binding)))})
        policy-evaluation (evaluate-run-policy result)
        result (assoc-in result [:verification :policy] policy-evaluation)
        result (if (:satisfied? policy-evaluation)
                 result
                 (-> result
                     (assoc-in [:verification :status] "invalid")
                     (update-in [:verification :reasons] (fnil conj [])
                                {:reason-code "forensic-policy-unsatisfied"
                                 :requirements (:requirements policy-evaluation)})))
        validation (validate-finalization result)
        reconciliation-report {:schema-version "run-evidence-reconciliation.v1"
                               :run-id (:run-id run)
                               :finalization-kind "run-evidence-finalization"
                               :status (get-in result [:verification :reconciliation :status])
                               :sources (get-in result [:verification :reconciliation :sources])
                               :differences (get-in result [:verification :reconciliation :differences])
                               :unaccepted-scenario-finalizations
                               (vec (for [[file scenario] (map vector scenario-files scenarios)
                                          :when (not (and (= "verified" (get-in scenario [:verification :status]))
                                                          (contains? #{"verified" "valid-empty"}
                                                                     (get-in scenario [:evidence :chain :status]))))]
                                      {:path (str file)
                                       :scenario-id (get-in scenario [:subject :scenario-id])
                                       :verification-status (get-in scenario [:verification :status])
                                       :chain-status (get-in scenario [:evidence :chain :status])}))
                               :unreadable-files []}]
    (when-not (:valid? validation)
      (throw (ex-info "Run finalization failed validation" validation)))
    (let [path (atomic-write! finalization-path (json/write-str result {:key-fn json-key :indent true}))
          report-path (when reconciliation-report-path
                        (atomic-write! reconciliation-report-path
                                       (json/write-str reconciliation-report {:indent true})))
          persisted (read-finalization! path)]
      {:path path
       :reconciliation-report-path report-path
       :finalization persisted
       :validation (validate-finalization persisted)})))

(defn validate-finalization
  "Validate a persisted finalization. Legacy callers use the one-argument
   structural mode; canonical package validation passes
   {:require-execution-id? true} for scenario finalizations."
  ([finalization] (validate-finalization finalization {}))
  ([finalization {:keys [require-execution-id? require-execution-identities?]}]
  (let [kind (:finalization-kind finalization)
        scenario? (= kind "scenario-chain-finalization")
        run? (= kind "run-evidence-finalization")
        chain (get-in finalization [:evidence :chain])
        execution (:execution finalization)
        verification (:verification finalization)
        hash-set (or (get-in finalization [:evidence :chain :reachable-hash-set])
                     (get-in finalization [:evidence :declared-evidence-hash-set]))
        reachable (:reachable-hashes chain)
        run-hash-sets [(get-in finalization [:evidence :scenario-finalization-set])
                       (get-in finalization [:evidence :scenario-chain-head-set])]
        run-scenarios (get-in finalization [:evidence :scenario-finalizations])
        declared-heads (get-in finalization [:evidence :scenario-chain-heads])
        expected-finalization-set (when run?
                                   (build-hash-set (map #(get-in % [:finalization :sha256]) run-scenarios)))
        expected-heads (when run?
                         (mapv (fn [{:keys [scenario-id chain-head]}]
                                 {:scenario-id scenario-id :head-hash (:hash chain-head)})
                               run-scenarios))
        expected-head-set (when run?
                            (build-hash-set
                             (map #(hc/hash-with-intent {:hash/intent :evidence-finalization-v2} %)
                                  expected-heads)))
        errors (cond-> []
                 (not= schema-version (:schema-version finalization)) (conj :unsupported-schema-version)
                 (and require-execution-id? scenario? (nil? (:execution/id finalization)))
                 (conj :missing-execution-id)
                 (and require-execution-id? scenario?
                      (some? (:execution/id finalization))
                      (not (and (string? (:execution/id finalization)) (seq (:execution/id finalization)))))
                 (conj :malformed-execution-id)
                 (not (contains? #{"scenario-chain-finalization" "run-evidence-finalization"} kind)) (conj :unsupported-finalization-kind)
                 (not= "prf-canonical-hash-v1" (get-in finalization [:canonicalization :scheme])) (conj :unsupported-canonicalization)
                 (not= "evidence-finalization-v2" (get-in finalization [:canonicalization :intent])) (conj :unsupported-intent)
                 (and hash-set (not (:valid? (validate-hash-set hash-set)))) (conj :invalid-hash-set)
                                  (and run? (some #(not (:valid? (validate-hash-set %))) run-hash-sets))
                                  (conj :invalid-run-scenario-set-commitment)
                 (and (= kind "scenario-chain-finalization") (= "valid-empty" (:status chain))
                                       (or (pos? (:record-count chain)) (:head chain) (:genesis chain)
                                           (seq (:reachable-hashes chain)))) (conj :invalid-valid-empty-chain)
                                  (and (= kind "scenario-chain-finalization") (= "verified" (:status chain))
                                       (pos? (:record-count chain))
                                       (or (nil? (:genesis chain))
                                           (nil? (:head chain))
                                           (not= "verified" (get-in chain [:head-verification :membership]))
                                           (not= "unique-terminal" (get-in chain [:head-verification :terminality]))))
                                  (conj :verified-chain-missing-verified-terminal-head)
                                  (and (= kind "scenario-chain-finalization")
                                       (some? (get-in chain [:head :artifact-bytes-sha256]))
                                       (not (sha256-ref? (get-in chain [:head :artifact-bytes-sha256]))))
                                  (conj :malformed-head-artifact-bytes-sha256)
                                  (and scenario?
                                       (some? (get-in chain [:genesis :artifact-bytes-sha256]))
                                       (not (sha256-ref? (get-in chain [:genesis :artifact-bytes-sha256]))))
                                  (conj :malformed-genesis-artifact-bytes-sha256)
                                  (and scenario? (= "verified" (:status chain))
                                       (not= "verified" (:status verification)))
                                  (conj :verified-chain-has-nonverified-result)
                                  (and scenario? (= "verified" (:status chain))
                                       (not= "closed" (:terminality execution)))
                                  (conj :verified-chain-not-closed)
                                  (and scenario? (= "verified" (:status chain))
                                                        (not= "passed"
                                                              (:status (some #(when (= "registry-membership" (:check-id %)) %)
                                                                             (:checks verification)))))
                                                   (conj :verified-chain-registry-membership-not-passed)
                                  (and scenario? (= "partial" (:status chain))
                                       (or (= "verified" (:status verification))
                                           (not= "open" (:terminality execution))))
                                  (conj :partial-chain-claims-terminal-verification)
                                  (and scenario? (= "valid-empty" (:status chain))
                                       (or (not= "verified" (:status verification))
                                           (not= "closed" (:terminality execution))))
                                  (conj :valid-empty-chain-has-invalid-execution-status)
                                  (and scenario? (not (vector? reachable)))
                                  (conj :reachable-hashes-not-vector)
                                  (and scenario? (vector? reachable)
                                       (or (not= (:record-count chain) (count reachable))
                                           (not= reachable (:hashes hash-set))))
                                  (conj :reachable-hashes-do-not-match-chain-commitment)
                                  (and scenario? (vector? reachable)
                                       (not-every? sha256-ref? reachable))
                                  (conj :malformed-reachable-hash)
                                  (and run? (= "verified" (:status verification))
                                       (not= "exact" (get-in verification [:reconciliation :status])))
                                  (conj :verified-run-reconciliation-not-exact)
                                  (and run? (= "verified" (:status verification))
                                       (not= "closed" (:terminality execution)))
                                  (conj :verified-run-not-closed)
                                  (and run?
                                       (not= (count (get-in finalization [:evidence :scenario-finalizations]))
                                             (count (set (map :scenario-id
                                                              (get-in finalization [:evidence :scenario-finalizations]))))))
                                  (conj :duplicate-run-scenario-finalization)
                                  (and run?
                                                                         (some (fn [entry]
                                                                                 (not (sha256-ref? (get-in entry [:finalization :sha256]))) )
                                                                               run-scenarios))
                                                                    (conj :malformed-scenario-finalization-digest)
                                  (and run? require-execution-identities?
                                       (some #(not (and (string? (:execution/id %)) (seq (:execution/id %)))) run-scenarios))
                                                                    (conj :missing-run-member-execution-id)
                                                                    (and run? (not= run-scenarios (vec (sort-by :scenario-id run-scenarios))))
                                                                    (conj :run-scenario-finalizations-not-canonically-ordered)
                                                                    (and run? (not= declared-heads expected-heads))
                                                                    (conj :scenario-chain-heads-do-not-match-finalizations)
                                                                    (and run? (not= (get-in finalization [:evidence :scenario-finalization-set :root])
                                                                                    (:root expected-finalization-set)))
                                                                    (conj :scenario-finalization-set-root-mismatch)
                                                                    (and run? (not= (get-in finalization [:evidence :scenario-chain-head-set :root])
                                                                                    (:root expected-head-set)))
                                                                    (conj :scenario-chain-head-set-root-mismatch))]
    {:valid? (empty? errors) :errors errors
     :run-id (get-in finalization [:run :run-id])
     :scenario-id (get-in finalization [:subject :scenario-id])
     :execution-id (:execution/id finalization)})))
