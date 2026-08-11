(ns resolver-sim.io.scenario-runner
  "CLI shell: load scenarios, run collections, print reports, exit codes.

   Does not judge pass/fail — delegates to `scenario.runner` and `sim.fixtures`.
   Table output via `scenario.report/print-report`; legacy fixture detail via
   `sim.reporter` when `:report-format :fixture`."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.evidence.forensic-populate :as fp]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.evidence.timestamping :as ts]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.forensic.provenance :as prov]
            [resolver-sim.io.fixtures :as io-fix]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.io.edn :as ppedn]
            [resolver-sim.logging :as log]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.run.bundle-root :as br]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.scenario.report :as report]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.sensitivity.propagation :as prop]
            [resolver-sim.validation.scenario-id :as sid]
            [resolver-sim.yield.invariant-catalog :as yield-inv-cat]
            [resolver-sim.config.paths :as paths]
            [resolver-sim.hash.reference :as hash-ref]))

;; ---------------------------------------------------------------------------
;; Structured logging
;; ---------------------------------------------------------------------------

(def ^:dynamic *finalize-evidence?*
  "When true, let replay finalize its own evidence cursor into the bound artifact directory.
   Structured runs bind this so chain roots are produced by the cursor owner."
  false)

(def ^:dynamic *log-level*
  "Log level threshold for structured stderr output.
     :info   — all events (default)
     :warn   — warnings and errors only
     :silent — errors only"
  :info)

(defn- log-event
  "Write a structured JSON-line event to stderr, subject to *log-level*.
   event — keyword name (e.g. :pre-commitment, :dag-write)
   data  — additional key-value pairs merged into the event map"
  [level event & {:as data}]
  (let [level-kw (if (keyword? level) level (keyword level))]
    (when (case *log-level*
            :info   (contains? #{:info :warn :error} level-kw)
            :warn   (contains? #{:warn :error} level-kw)
            :silent (= level-kw :error)
            true)
      (.println *err*
                (json/write-str
                 (merge {:event (name event)
                         :ts    (str (java.time.Instant/now))
                         :level (name level-kw)
                         :thread (.getName (Thread/currentThread))}
                        data))))))

(def ^:private supported-scenario-profiles
  #{:minimal :research :forensic :demo :debug-heavy})

(def ^:private known-scenario-metadata-keys
  #{:scenario/assumptions
    :scenario/model-scope
    :scenario/expected-outcome
    :scenario/claim-intents
    :scenario/evidence-profile
    :scenario/output-profile
    :scenario/output-overrides
    :scenario/sensitivity})

(defn- normalize-profile
  [profile]
  (cond
    (nil? profile) nil
    (keyword? profile) profile
    (string? profile) (keyword profile)
    :else profile))

(defn- normalize-claim-intents
  [claim-intents]
  (cond
    (nil? claim-intents) nil
    (vector? claim-intents) claim-intents
    (sequential? claim-intents) (vec claim-intents)
    :else [claim-intents]))

(defn- scenario-metadata-extra
  [scenario]
  (into {}
        (filter (fn [[k _]]
                  (and (keyword? k)
                       (= "scenario" (namespace k))
                       (not (contains? known-scenario-metadata-keys k)))))
        scenario))

(defn- warn-scenario-metadata-issues!
  [scenario metadata-extra]
  (let [scenario-id (:scenario-id scenario)]
    (when-not scenario-id
      (log/warn! :scenario-id-missing
                 {:path (:scenario-path scenario)
                  :message "Scenario ids should be explicit; path fallback is compatibility-only"}))
    (when (seq metadata-extra)
      (log/warn! :scenario-metadata-extra
                 {:scenario-id scenario-id
                  :keys (vec (sort (keys metadata-extra)))
                  :message "Preserving unknown :scenario/* metadata for registry validation"}))
    (when (and (nil? (:scenario/assumptions scenario))
               (nil? (:assumptions scenario))
               (seq (get-in scenario [:theory :assumptions])))
      (log/warn! :scenario-metadata-fallback
                 {:scenario-id scenario-id
                  :field :scenario/assumptions
                  :source :theory/assumptions
                  :message "Using :theory/:assumptions as compatibility fallback"}))
    (when (and (nil? (:scenario/claim-intents scenario))
               (nil? (:claim-intents scenario))
               (some? (get-in scenario [:theory :claim-id])))
      (log/warn! :scenario-metadata-fallback
                 {:scenario-id scenario-id
                  :field :scenario/claim-intents
                  :source :theory/claim-id
                  :message "Using :theory/:claim-id as compatibility fallback"}))))

(defn- validate-scenario-metadata!
  [{:scenario/keys [assumptions claim-intents evidence-profile output-profile output-overrides]
    :as metadata}
   scenario-id]
  (when (and (some? assumptions) (not (vector? assumptions)))
    (throw (ex-info "Scenario assumptions must be a vector when present"
                    {:scenario-id scenario-id
                     :field :scenario/assumptions
                     :actual assumptions})))
  (when (and (some? claim-intents) (not (vector? claim-intents)))
    (throw (ex-info "Scenario claim intents must normalize to a vector"
                    {:scenario-id scenario-id
                     :field :scenario/claim-intents
                     :actual claim-intents})))
  (when (and evidence-profile
             (not (contains? supported-scenario-profiles evidence-profile)))
    (throw (ex-info "Unknown scenario evidence profile"
                    {:scenario-id scenario-id
                     :field :scenario/evidence-profile
                     :actual evidence-profile
                     :supported supported-scenario-profiles})))
  (when (and output-profile
             (not (contains? supported-scenario-profiles output-profile)))
    (throw (ex-info "Unknown scenario output profile"
                    {:scenario-id scenario-id
                     :field :scenario/output-profile
                     :actual output-profile
                     :supported supported-scenario-profiles})))
  (when (and (some? output-overrides) (not (map? output-overrides)))
    (throw (ex-info "Scenario output overrides must be a map when present"
                    {:scenario-id scenario-id
                     :field :scenario/output-overrides
                     :actual output-overrides})))
  (when-let [sensitivity (:scenario/sensitivity metadata)]
    (when-let [errors (prop/validate-sensitivity sensitivity)]
      (throw (ex-info "Invalid scenario sensitivity declaration"
                      {:scenario-id scenario-id
                       :scenario/sensitivity sensitivity
                       :validation-errors errors}))))
  metadata)

(defn- scenario-metadata
  [scenario]
  (let [metadata-extra (scenario-metadata-extra scenario)
        metadata {:scenario/assumptions      (or (:scenario/assumptions scenario)
                                                 (:assumptions scenario)
                                                 (get-in scenario [:theory :assumptions]))
                  :scenario/model-scope      (or (:scenario/model-scope scenario)
                                                 (:model-scope scenario))
                  :scenario/expected-outcome (or (:scenario/expected-outcome scenario)
                                                 (:expected-outcome scenario))
                  :scenario/claim-intents    (normalize-claim-intents
                                              (or (:scenario/claim-intents scenario)
                                                  (:claim-intents scenario)
                                                  (some-> scenario :theory :claim-id vector)))
                  :scenario/evidence-profile (normalize-profile
                                              (or (:scenario/evidence-profile scenario)
                                                  (:evidence-profile scenario)))
                  :scenario/output-profile   (normalize-profile
                                              (or (:scenario/output-profile scenario)
                                                  (:output-profile scenario)))
                  :scenario/output-overrides (or (:scenario/output-overrides scenario)
                                                 (:output-overrides scenario))
                  :scenario/sensitivity      (:scenario/sensitivity scenario)}
        metadata* (cond-> metadata
                    (seq metadata-extra)
                    (assoc :scenario/metadata-extra metadata-extra))]
    (warn-scenario-metadata-issues! scenario metadata-extra)
    (when-let [scenario-id (:scenario-id scenario)]
      (sid/validate-scenario-id! scenario-id))
    (validate-scenario-metadata! metadata* (:scenario-id scenario))))

(defn- replay-fn-for-protocol
  [protocol-id]
  (let [protocol-id (or protocol-id preg/default-protocol-id)
        protocol    (preg/get-protocol protocol-id)]
    (when-not protocol
      (throw (ex-info "Unknown scenario protocol"
                      {:protocol protocol-id
                       :known-protocols (vec (preg/known-protocol-ids))})))
    (fn [scenario]
      (let [replay-opts (cond-> {:allow-dirty? true
                                 :skip-finalize (not *finalize-evidence?*)}
                          (= "yield-v1" protocol-id)
                          (assoc :flags {:yield-dt-validation? true
                                         :metrics-profile :yield-provider}))]
        (if (= "sew-v1" protocol-id)
          ((requiring-resolve 'resolver-sim.protocols.sew/replay-with-sew-protocol) scenario replay-opts)
          (replay/replay-with-protocol protocol scenario replay-opts))))))

(defn- scenario-file-details
  [scenario-path default-protocol-id]
  (let [scenario (-> scenario-path
                     io-sc/load-scenario-file
                     normalize/normalize-scenario
                     io-fix/resolve-protocol-params-ref)
        protocol-id (or (:protocol scenario) default-protocol-id)]
    {:scenario scenario
     :protocol protocol-id}))

(defn- scenario-entry-for-path
  [path default-protocol-id]
  (let [{:keys [scenario protocol]} (scenario-file-details path default-protocol-id)
        scenario* (if (= "yield-v1" protocol)
                    (yield-inv-cat/enrich-expectations scenario)
                    scenario)
        scenario-with-path (assoc scenario* :scenario-path path)
        explicit-scenario-id (:scenario-id scenario-with-path)
        scenario-id (or explicit-scenario-id path)
        dispatcher-id (keyword "protocol" protocol)
        ;; Hash the source bytes, preserving classpath/resource semantics rather
        ;; than treating logical references as filesystem paths.
        source-hash (input-source/sha256 (input-source/source path))]
    (when-not explicit-scenario-id
      (log/warn! :scenario-id-compatibility-fallback
                 {:path path
                  :scenario-id scenario-id
                  :message "Scenario id fell back to the file path; add an explicit :scenario-id before registry validation"}))
    {:name              (str scenario-id "  [" path "]")
     :scenario          scenario-with-path
     :source            :file
     :protocol          protocol
     :scenario-id       scenario-id
     :dispatcher-id     dispatcher-id
     :scenario-path     path
     :scenario/source-hash source-hash
     :scenario-metadata (scenario-metadata scenario-with-path)}))

(def ^:private default-runner-selection
  "Default pinned runner selection for canonical suite runs."
  {:mode :pinned
   :runner-id :runner/local-bb
   :description "Default pinned local Babashka runner"})

(defn- resolve-scenario-reference
  "Resolve a bare scenario ID to its canonical EDN fixture.

   Explicit filesystem and resource paths are returned unchanged. A bare ID is
   first tried as a filename prefix match (fast, no file loading), then matched
   against :scenario-id (slow, loads every EDN file).  Prefixes let you type
   just \"S84\" instead of \"S84_yield-protocol-fee-mid-accrual\"."
  [scenario]
  (if (or (nil? scenario)
          (str/starts-with? scenario "resource:")
          (str/starts-with? scenario "file:")
          (str/ends-with? scenario ".edn")
          (str/ends-with? scenario ".json")
          (.exists (io/file scenario)))
    scenario
    (let [fixture-dir (io/file "scenarios" "edn")]
      (or
       (let [prefix-lc (str/lower-case scenario)
             matches (->> (file-seq fixture-dir)
                          (filter #(and (.isFile %)
                                        (str/ends-with? (.getName %) ".edn")))
                          (filter #(str/starts-with? (str/lower-case (.getName %))
                                                     prefix-lc))
                          (map #(.getPath %))
                          (vec))]
         (case (count matches)
           0 nil
           1 (first matches)
           (throw (ex-info (str "Ambiguous prefix \"" scenario "\" matches "
                                (count matches) " scenarios")
                           {:prefix scenario :matches matches}))))
       (some (fn [file]
               (when (and (.isFile file)
                          (str/ends-with? (.getName file) ".edn"))
                 (try
                   (let [path (.getPath file)
                         fixture (io-sc/load-scenario-file path)]
                     (when (= scenario (:scenario-id fixture)) path))
                   (catch Exception e
                     (log-event :warn :scenario-id-resolve-error
                                :file (.getPath file)
                                :error (.getMessage e))
                     nil))))
             (file-seq fixture-dir))
       scenario))))

(defn resolve-path-run-request
  "Prepare a normalized request envelope for one or more file-backed scenarios.

   This is a boundary helper only: it performs file loading, normalization,
   protocol inference, and per-protocol expectation enrichment without running
   replay. Returns a map under `:scenario-run/request` suitable for reuse by
   direct scenario runs, named suites, registry validation, and future
   comparison-oriented entrypoints."
  [paths opts]
  (let [default-protocol-id (or (:protocol opts) preg/default-protocol-id)
        runner-selection (or (:runner-selection opts) default-runner-selection)
        entries (mapv #(scenario-entry-for-path % default-protocol-id) paths)
        ids (map :scenario-id entries)]
    (when-not (= (count ids) (count (distinct ids)))
      (throw (ex-info "Duplicate scenario IDs in run request"
                      {:duplicate-ids (->> ids frequencies (keep (fn [[id n]] (when (> n 1) id))) vec)})))
    {:scenario-run/request
     {:runner/backend :local-current
      :runner-selection runner-selection
      :suite/key (:suite-id opts)
      :protocol/default-id default-protocol-id
      :evidence/profile (or (:evidence-profile opts)
                            (when (= 1 (count entries))
                              (get-in (first entries) [:scenario-metadata :scenario/evidence-profile])))
      :output/profile (or (:output-profile opts)
                          (when (= 1 (count entries))
                            (get-in (first entries) [:scenario-metadata :scenario/output-profile])))
      :entries entries
      :entry-count (count entries)}}))

(defn- execute-path-run-request
  [{:scenario-run/keys [request]} opts]
  (let [entries (:entries request)]
    (when-not (:parallel? opts)
      (doseq [{:keys [name protocol]} entries]
        (log-event :info :scenario-start :scenario-name name :protocol protocol)))
    (runner/run-collection
     {:entries   entries
      :replay-fn (fn [scenario]
                   ((replay-fn-for-protocol (or (:protocol scenario)
                                                (:protocol/default-id request)
                                                preg/default-protocol-id))
                    scenario))}
     (merge {:normalize? false :suite-id (:suite/key request)} opts))))

(defn- hashable-scenario-value
  "Convert scenario-only non-integer numbers to explicit, lossless values for
   provenance hashing. The canonical hash codec intentionally accepts only
   integers; scenario EDN may validly contain decimal configuration such as a
   yield availability ratio. Type tags prevent distinct source values from
   silently aliasing during hashing."
  [value]
  (cond
    (instance? java.math.BigDecimal value)
    {:canonical/type "big-decimal" :canonical/value (.toPlainString ^java.math.BigDecimal value)}

    (instance? clojure.lang.Ratio value)
    {:canonical/type "ratio" :canonical/numerator (numerator value) :canonical/denominator (denominator value)}

    (or (instance? Double value) (instance? Float value))
    {:canonical/type "floating-point" :canonical/value (str value)}

    (map? value) (into {} (map (fn [[key item]] [key (hashable-scenario-value item)]) value))
    (vector? value) (mapv hashable-scenario-value value)
    (set? value) (->> value (map hashable-scenario-value) (sort-by pr-str) vec)
    (seq? value) (mapv hashable-scenario-value value)
    :else value))

(defn- enrich-summary-results
  [summary request]
  (let [entry-by-id (into {}
                          (map (juxt :scenario-id identity))
                          (:entries request))]
    (update summary :results
            (fn [results]
              (mapv (fn [result]
                      (if-let [entry (get entry-by-id (:scenario-id result))]
                        (assoc result
                               :scenario-hash (hc/hash-with-intent {:hash/intent :scenario}
                                                                   (hashable-scenario-value (:scenario entry)))
                               :scenario-path (:scenario-path entry)
                               :dispatcher-id (:dispatcher-id entry)
                               :scenario-metadata (:scenario-metadata entry)
                               :scenario-input-hash (:scenario/source-hash entry)
                               :runner {:backend (:runner/backend request)
                                        :protocol-id (:protocol entry)
                                        :dispatcher-id (:dispatcher-id entry)
                                        :runner-selection (:runner-selection request)}
                               :execution/raw (:replay-result result))
                        result))
                    results)))))

(defn- run-effective-sensitivity
  "Compute the maximum sensitivity across all scenario results in a run.
   Uses merge-sensitivity over each result's scenario metadata."
  [results]
  (prop/merge-sensitivity (mapv prop/effective-scenario-sensitivity results)))
(defn normalize-run-result
  [request summary]
  (let [summary* (enrich-summary-results summary request)
        results (:results summary*)
        totals {:passed (count (filter :pass? results))
                :failed (count (remove :pass? results))
                :total (count results)}
        run-sensitivity (run-effective-sensitivity results)]
    {:scenario-run/request request
     :scenario-run/result
     (cond-> {:status (if (:ok? summary*) :pass :fail)
              :suite/key (:suite-id summary*)
              :evidence/profile (:evidence/profile request)
              :output/profile (:output/profile request)
              :runner-selection (:runner-selection request)
              :totals totals
              :results results
              :summary summary*
              :diagnostics {:elapsed-ms (:elapsed-ms summary* 0)
                            :suite-id (:suite-id summary*)}}
       run-sensitivity (assoc :sensitivity/run-level run-sensitivity))}))

(defn- sew-replay-fn []
  (replay-fn-for-protocol "sew-v1"))

(defn- default-report-opts [opts]
  (merge {:outline-printer (fn [name result]
                             (let [{:keys [header lines footer separator]}
                                   ((requiring-resolve 'resolver-sim.protocols.sew.narrative/scenario-outline)
                                    name result)]
                               (println header)
                               (doseq [line lines] (println line))
                               (println footer)
                               (println separator)))}
         opts))

(declare run-paths)

(defn- parse-scenario-selector
  "Parse and validate the non-executable scenario selector accepted by the CLI.
   Selectors are EDN maps with optional :scenario/id-prefix, :scenario/tags,
   and :scenario/protocol fields. Invalid selectors always fail the run."
  [selector]
  (let [selector (if (string? selector) (edn/read-string selector) selector)
        allowed #{:scenario/id-prefix :scenario/tags :scenario/protocol}]
    (when-not (map? selector)
      (throw (ex-info "Scenario selector must be an EDN map" {:selector selector})))
    (when-not (every? allowed (keys selector))
      (throw (ex-info "Scenario selector contains unsupported keys"
                      {:selector selector :allowed allowed})))
    (when-let [prefix (:scenario/id-prefix selector)]
      (when-not (string? prefix)
        (throw (ex-info ":scenario/id-prefix must be a string" {:selector selector}))))
    (when-let [tags (:scenario/tags selector)]
      (when-not (and (set? tags) (every? keyword? tags))
        (throw (ex-info ":scenario/tags must be a set of keywords" {:selector selector}))))
    (when-let [protocol (:scenario/protocol selector)]
      (when-not (or (string? protocol) (keyword? protocol))
        (throw (ex-info ":scenario/protocol must be a string or keyword" {:selector selector}))))
    selector))

(defn- scenario-selected? [selector scenario]
  (let [scenario-id (str (or (:scenario/id scenario) (:id scenario) ""))
        scenario-tags (set (or (:scenario/tags scenario) (:tags scenario) []))
        protocol (or (:scenario/protocol scenario) (:protocol scenario))]
    (and (or (nil? (:scenario/id-prefix selector))
             (str/starts-with? scenario-id (:scenario/id-prefix selector)))
         (or (nil? (:scenario/tags selector))
             (every? scenario-tags (:scenario/tags selector)))
         (or (nil? (:scenario/protocol selector))
             (= (str (:scenario/protocol selector)) (str protocol))))))

(defn- sew-registry-scenarios []
  @(requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/all-scenarios))

(defn- sew-scenario-type [scenario-id]
  (get @(requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/scenario-type-registry)
       scenario-id {}))

(def ^:private registry-suite-runners
  "Protocol ID → (fn [opts] → summary-map).
   sew-v1:   in-process invariant registry (protocols_src/.../invariant_scenarios.clj)
   yield-v1: file-backed suite (scenarios/edn/Y*.edn via :yield-provider-scenarios)"
  {"sew-v1" (fn [{:keys [suite-id scenario-filter] :as opts}]
              (let [entries (if scenario-filter
                              (let [selector (parse-scenario-selector scenario-filter)]
                                (filterv (fn [entry]
                                           (let [s (if (vector? entry) (second entry) entry)]
                                             (and (map? s) (scenario-selected? selector s))))
                                         (sew-registry-scenarios)))
                              (sew-registry-scenarios))]
                (runner/run-collection
                 {:entries entries
                  :replay-fn (sew-replay-fn)
                  :type-meta-fn sew-scenario-type}
                 (merge {:suite-id (or suite-id :sew-invariants)} opts))))
   "yield-v1" (fn [opts]
                (run-paths (suites/suite-paths :yield-provider-scenarios)
                           (assoc opts :protocol "yield-v1")))})

(defn run-registry-suite
  "Run the protocol registry suite. Returns summary map.
   Protocol is read from :protocol in opts (default sew-v1).
   sew-v1:   in-process invariant registry (S01–S107+)
   yield-v1: file-backed yield provider suite (Y01–Y05)"
  ([] (run-registry-suite {}))
  ([opts]
   (let [protocol-id (or (:protocol opts) preg/default-protocol-id)
         runner (get registry-suite-runners protocol-id)]
     (if runner
       (runner opts)
       (throw (ex-info "No registry suite runner for protocol"
                       {:protocol protocol-id
                        :known (keys registry-suite-runners)}))))))

(defn run-paths
  "Run file-backed scenarios from `paths`. Each path becomes one entry.
   Files are normalized before replay. Returns summary map.

   opts may include `:protocol` (registry id, default sew-v1)."
  [paths opts]
  (let [{request :scenario-run/request :as envelope}
        (resolve-path-run-request paths opts)
        _ (doseq [{:keys [scenario-id protocol]} (:entries request)]
            (log/info! :scenario-run-start {:scenario-id scenario-id :protocol protocol}))
        summary (execute-path-run-request envelope opts)
        normalized (normalize-run-result request summary)]
    (assoc (:summary (:scenario-run/result normalized))
           :scenario-run/request request)))

(defn run-scenario-file
  "Run a single scenario file. Returns summary map with one entry."
  [scenario-path opts]
  (run-paths [scenario-path] opts))

(defn run-registry-scenario
  "Run a single scenario from the in-process invariant registry.

   Takes a registry entry `[display-name scenario-or-pair]` (as found in e.g.
   inv-sc/all-scenarios) and runs it through the same replay pipeline used by
   run-registry-suite.  Returns the entry result map from
   scenario.runner/build-entry-result.

   This is the public entry point for dev REPL helpers that need to run one
   in-process scenario without going through file-backed dispatch."
  [[name data] replay-fn]
  (let [summary (runner/run-collection
                 {:entries [[name data]]
                  :replay-fn replay-fn}
                 {:normalize? false})]
    (first (:results summary))))

(defn- preserve-ns-key [k]
  "JSON key function that preserves Clojure keyword namespaces.
   :bundle/hash → \"bundle/hash\", not \"hash\"."
  (if (keyword? k)
    (if-let [ns (namespace k)]
      (str ns "/" (name k))
      (name k))
    (str k)))

(defn- json-safe-value
  "Convert a value to a JSON-serializable form.
   json/write-str calls value-fn for every leaf value in the tree."
  [_k v]
  (cond
    (instance? clojure.lang.IRecord v) (into {} v)
    (instance? clojure.lang.Keyword v) (str v)
    (instance? clojure.lang.Symbol v) (str v)
    (instance? java.util.UUID v) (str v)
    (set? v) (vec v)
    (instance? clojure.lang.IDeref v) (throw (ex-info "Ref values are not permitted in JSON artifacts" {:type (class v)}))
    (fn? v) (throw (ex-info "Function values are not permitted in JSON artifacts" {:type (class v)}))
    (instance? java.util.Date v) (str v)
    (instance? clojure.lang.Ratio v) (double v)
    :else (if (or (nil? v) (instance? Boolean v) (instance? java.lang.Number v) (string? v) (vector? v) (map? v) (list? v))
            v
            (do (log/warn! :json-safe-value-fallback {:type (str (type v)) :value (pr-str v)})
                (str v)))))

(defn- atomic-write-text!
  "Replace a run-owned projection only after its complete bytes are staged in a
   unique sibling file. This prevents readers from observing a torn JSON file;
   ownership/CAS is still required for shared canonical paths."
  [path content]
  (let [target (io/file path)
        parent (.getParentFile target)
        temp (io/file parent (str "." (.getName target) ".tmp-"
                                  (java.util.UUID/randomUUID)))]
    (.mkdirs parent)
    (spit temp content)
    (try
      (java.nio.file.Files/move
       (.toPath temp) (.toPath target)
       (into-array java.nio.file.StandardCopyOption
                   [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                    java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (java.nio.file.Files/deleteIfExists (.toPath temp))))))

(defn write-result-json
  "Write a complete JSON result projection through an atomic replace. Callers
   must provide a run-owned path; this function does not grant shared-path
   publication authority."
  [output-path result]
  (when (and output-path (not= output-path "-"))
    (atomic-write-text! output-path
                        (json/write-str result
                                        :key-fn preserve-ns-key
                                        :value-fn json-safe-value
                                        :indent true))))

(defn run-registry-suite-and-report
  "Run protocol registry suite, print report, return exit code.
   Protocol is read from :protocol in opts (default sew-v1)."
  ([] (run-registry-suite-and-report {}))
  ([opts]
   (let [protocol-id (or (:protocol opts) preg/default-protocol-id)
         suite-id (case protocol-id
                    "sew-v1"   :sew-invariants
                    "yield-v1" :yield-provider-scenarios
                    nil)
         title (case protocol-id
                 "sew-v1"   "Sew Invariant Suite — Deterministic Scenarios (Clojure in-process)"
                 "yield-v1" "Yield Provider Suite — File-backed scenarios (Y01–Y05)"
                 (str "Invariant suite for protocol: " protocol-id))
         summary (run-registry-suite opts)
         code    (report/print-report (cond-> summary
                                        suite-id (assoc :suite-id suite-id))
                                      (default-report-opts
                                       (assoc opts :title title)))]
     code)))

(defn run-paths-and-report
  "Run path list, print report, return exit code."
  [paths opts]
  (let [summary (run-paths paths opts)
        suite-id (:suite-id opts)
        title    (when suite-id (format "Scenario suite: %s" (name suite-id)))
        code     (report/print-report summary
                                      (default-report-opts
                                       (cond-> opts
                                         title (assoc :title title))))]
    code))

(defn run-scenario-file-and-report
  "Run one scenario file and print the report. Returns exit code.

   `output-path` is accepted for compatibility but writing is handled by the
   outer `run-and-report` bundle path."
  [scenario-path output-path opts]
  (try
    (let [summary (run-scenario-file scenario-path opts)
          code    (report/print-report summary (default-report-opts opts))]
      (when output-path
        nil)
      code)
    (catch Exception e
      (println (format "Error running scenario %s: %s" scenario-path (.getMessage e)))
      1)))

(defn run-named-suite-and-report
  "Run a registered suite keyword (e.g. :yield-provider-scenarios). Returns exit code."
  [suite-key opts]
  (if-let [paths (suites/suite-paths suite-key)]
    (run-paths-and-report
     (if-let [scenario-filter (:scenario-filter opts)]
       (let [selector (parse-scenario-selector scenario-filter)
             enriched (mapv (fn [path]
                              (let [entry (scenario-entry-for-path path (or (:protocol opts) (suites/suite-protocol-id suite-key)))]
                                (assoc entry :raw-path path)))
                            paths)
             filtered (filterv (fn [{:keys [scenario]}]
                                 (and (map? scenario) (scenario-selected? selector scenario)))
                               enriched)]
         (mapv :raw-path filtered))
       paths)
     (assoc opts
            :suite-id suite-key
            :protocol (or (:protocol opts)
                          (suites/suite-protocol-id suite-key))))
    (do
      (println (str "Unknown suite: " suite-key
                    ". Known: " (str/join ", " (map name (suites/known-suite-keys)))))
      1)))

(defn suite-scenario-details
  "Extract scenario metadata from a scenario path using the same normalization
   and protocol-resolution path used for execution.

   Returns {:scenario/id string :scenario/path string :scenario/protocol keyword}.

   NOTE: Transitional display-only helper.  Loads every file to extract metadata.
   Call sparingly — prefer suite-level protocol-id from suites/suite-definition
   for display purposes."
  [path]
  (let [{request :scenario-run/request}
        (resolve-path-run-request [path] {})
        entry (first (:entries request))]
    {:scenario/id       (:scenario-id entry)
     :scenario/path     (:scenario-path entry)
     :scenario/protocol (keyword (:protocol entry))
     :scenario/source   (:source entry)
     :scenario/dispatcher-id (:dispatcher-id entry)}))

(defn known-suite-summaries
  "Return suite-level metadata for all named scenario suites.
   Per-scenario details use filename-derived IDs and suite-level protocol.
   Avoids loading individual scenario files.

   NOTE: Display-only metadata.  For authoritative per-scenario metadata
   use suite-scenario-details (transitional, loads files)."
  []
  (mapv (fn [suite-key]
          (let [{:keys [title description kind ci-tier protocol-id paths]}
                (suites/suite-definition suite-key)
                scenario-path->id (fn [path]
                                    (-> (java.io.File. path)
                                        (.getName)
                                        (clojure.string/replace #"\.(json|edn)$" "")))]
            {:suite/key           suite-key
             :suite/type          kind
             :suite/protocols     #{protocol-id}
             :suite/scenario-count (count paths)
             :suite/scenarios     (mapv (fn [path]
                                          {:scenario/id (scenario-path->id path)
                                           :scenario/path path
                                           :scenario/protocol (keyword protocol-id)})
                                        paths)
             :suite/title         title
             :suite/description   description
             :suite/ci-tier       ci-tier}))
        (suites/known-suite-keys)))

(defn run-fixture-suite
  "Run a composed EDN fixture suite (e.g. :suites/all-invariants).
   Returns the unified summary map; does not print unless opts request it."
  [suite-key mode opts]
  (io-fix/run-suite-from-key suite-key mode nil (assoc opts :silent? true)))

(defn run-fixture-suite-and-report
  "Run fixture suite with the invariant-style table report. Returns exit code.

   opts:
     :report-format — :table (default) uses scenario.report; :fixture uses sim.reporter
     Other opts forwarded to fixtures/run-suite (e.g. :result-display-level for :fixture)."
  [suite-key mode opts]
  (let [report-format (or (:report-format opts) :table)
        silent?       (= :table report-format)
        summary       (io-fix/run-suite-from-key suite-key mode nil
                                                 (assoc opts :silent? silent?))]
    (if (= :table report-format)
      (report/print-report summary
                           (default-report-opts
                            (assoc opts
                                   :title (format "Fixture suite: %s" (name suite-key)))))
      (if (:ok? summary) 0 1))))

;; ── Protocol resolution ─────────────────────────────────────────────────────────

(defn- resolve-protocol-id
  "Resolve the effective protocol id from dispatch, with fallback to default."
  [dispatch]
  (let [default (or (:protocol dispatch) preg/default-protocol-id)
        inferred (when (:scenario dispatch)
                   (get-in (resolve-path-run-request [(:scenario dispatch)]
                                                     {:protocol default})
                           [:scenario-run/request :entries 0 :protocol]))
        suite-pid (when (:suite dispatch)
                    (suites/suite-protocol-id (:suite dispatch)))]
    (or inferred suite-pid default)))

;; ── Canonicality ────────────────────────────────────────────────────────────────

(defn- determine-canonicality
  "Determine whether a run is canonical and the non-canonical reason code."
  [dispatch opts runner-selection source-provenance]
  (let [selection-mode (:mode runner-selection)
        canonical? (and (nil? (:scenario dispatch))
                        (nil? (:fixture-suite dispatch))
                        (nil? (or (:scenario-filter dispatch) (:scenario-filter opts)))
                        (not (:parallel? opts))
                        (not (:source/dirty? source-provenance))
                        (not= :dev (:mode dispatch))
                        (= :pinned selection-mode)
                        (keyword? (:runner-id runner-selection)))
        non-canonical-reason (cond
                               (:scenario dispatch) {:code :single-scenario-selected
                                                     :details "Single scenario selected; not a full suite run"}
                               (:fixture-suite dispatch) {:code :fixture-suite-selected
                                                          :details "Fixture suite selected; not a registered suite run"}
                               (= :dev (:mode dispatch)) {:code :dev-mode
                                                          :details "Development mode; bundle not suitable for comparison"}
                               (= :capability-match selection-mode) {:code :capability-match-runner
                                                                     :details "Capability-matched runner; non-deterministic selection"}
                               (or (:scenario-filter dispatch) (:scenario-filter opts)) {:code :scenario-filtering
                                                                                         :details "Scenario filtering applied"}
                               (:parallel? opts) {:code :parallel-execution
                                                  :details "Parallel execution does not have a canonical evidence ordering"}
                               (:source/dirty? source-provenance) {:code :dirty-source
                                                                   :details "Source tree is dirty"}
                               (not (keyword? (:runner-id runner-selection))) {:code :unidentified-runner
                                                                               :details "Pinned runner identity is required"}
                               (= :quorum selection-mode) {:code :quorum-not-yet-canonical
                                                           :details "Quorum mode selected; not yet canonical"}
                               :else nil)]
    {:canonical? canonical?
     :non-canonical-reason non-canonical-reason
     :selection-mode selection-mode}))

;; ── Dispatch execution ──────────────────────────────────────────────────────────

(defn- dispatch-keyword
  "Determine the dispatch keyword from the dispatch map."
  [dispatch]
  (cond (:fixture-suite dispatch) :fixture-suite
        (:suite dispatch) :suite
        (:scenario dispatch) :scenario
        :else :registry))

(def ^:private dispatch-selector-keys
  [:fixture-suite :suite :scenario])

(defn- validate-dispatch!
  "Reject dispatch maps that attempt more than one execution mode."
  [dispatch]
  (let [selected (->> dispatch-selector-keys
                      (filter #(some? (get dispatch %)))
                      vec)]
    (when (> (count selected) 1)
      (throw (ex-info "Ambiguous dispatch map"
                      {:dispatch dispatch
                       :selected selected}))))
  dispatch)

(defn- summary->run-result
  [dispatch request summary]
  (if request
    (:scenario-run/result (normalize-run-result request summary))
    (let [results (vec (:results summary))
          passed (count (filter :pass? results))
          total (or (:total summary) (count results))
          failed (or (:failed summary) (- total passed))]
      {:status (if (or (true? (:ok? summary))
                       (= passed total))
                 :pass
                 :fail)
       :suite/key (or (:suite-id summary)
                      (:suite dispatch))
       :evidence/profile (:evidence/profile request)
       :output/profile (:output/profile request)
       :runner-selection (:runner-selection request)
       :totals {:total total
                :passed passed
                :failed failed}
       :results results
       :summary summary
       :diagnostics {:elapsed-ms (:elapsed-ms summary 0)
                     :suite-id (:suite-id summary)}})))

(defn- dispatch-summary
  [dispatch opts protocol-id]
  (case (dispatch-keyword dispatch)
    :fixture-suite (run-fixture-suite (:fixture-suite dispatch) nil opts)
    :suite (if-let [paths (suites/suite-paths (:suite dispatch))]
             (run-paths paths
                        (assoc opts
                               :suite-id (:suite dispatch)
                               :protocol (or (:protocol opts)
                                             (suites/suite-protocol-id (:suite dispatch)))))
             (throw (ex-info "Unknown suite"
                             {:suite (:suite dispatch)
                              :known (vec (suites/known-suite-keys))})))
    :scenario (run-scenario-file (:scenario dispatch)
                                 (assoc opts :protocol protocol-id))
    :registry (run-registry-suite (assoc opts :protocol protocol-id))))

(defn- dispatch-report-exit-code
  [dispatch-key summary opts protocol-id]
  (case dispatch-key
    :fixture-suite (if (= :table (or (:report-format opts) :table))
                     (report/print-report summary
                                          (default-report-opts
                                           (assoc opts
                                                  :title (format "Fixture suite: %s"
                                                                 (name (:suite-id summary))))))
                     (if (:ok? summary) 0 1))
    :suite (let [suite-id (:suite-id summary)
                 title (when suite-id (format "Scenario suite: %s" (name suite-id)))]
             (report/print-report summary
                                  (default-report-opts
                                   (cond-> opts
                                     title (assoc :title title)))))
    :scenario (report/print-report summary (default-report-opts opts))
    :registry (let [suite-id (case protocol-id
                               "sew-v1" :sew-invariants
                               "yield-v1" :yield-provider-scenarios
                               (:suite-id summary))
                    title (case protocol-id
                            "sew-v1" "Sew Invariant Suite — Deterministic Scenarios (Clojure in-process)"
                            "yield-v1" "Yield Provider Suite — File-backed scenarios (Y01–Y05)"
                            (str "Invariant suite for protocol: " protocol-id))]
                (report/print-report (cond-> summary
                                       suite-id (assoc :suite-id suite-id))
                                     (default-report-opts
                                      (assoc opts :title title))))))

(defn- extract-protocol-state
  "Extract protocol state hashing data from summary results.
   Merges :force-authorisations and :force-authorisations/consumed from
   the final world of each scenario result. Returns a map suitable for
   merging into the run-result for build-bundle-root, or nil when empty."
  [summary]
  (let [scenarios
        (into {}
              (keep (fn [result]
                      (when-let [world (get-in result [:replay-result :world])]
                        (let [scenario-id (or (:scenario-id result) (:scenario-path result))
                              state (cond-> {}
                                      (seq (:force-authorisations world))
                                      (assoc :force-authorisations (:force-authorisations world))
                                      (seq (:force-authorisations/consumed world))
                                      (assoc :force-authorisations/consumed (:force-authorisations/consumed world))
                                      (seq (:held-adjustments world))
                                      (assoc :held-adjustments (:held-adjustments world)))]
                          (when (seq state) [scenario-id state]))))
                    (:results summary)))]
    (when (seq scenarios)
      (let [fa (into {} (keep (fn [[scenario-id state]]
                                (when-let [records (:force-authorisations state)]
                                  [scenario-id records]))) scenarios)
            fa-consumed (into {} (keep (fn [[scenario-id state]]
                                         (when-let [records (:force-authorisations/consumed state)]
                                           [scenario-id records]))) scenarios)
            held-adjustments (into {} (keep (fn [[scenario-id state]]
                                              (when-let [adjustments (:held-adjustments state)]
                                                [scenario-id adjustments]))) scenarios)]
        (cond-> {}
          (seq fa) (assoc :protocol/force-authorisations fa)
          (seq fa-consumed) (assoc :protocol/force-authorisations-consumed fa-consumed)
          (seq held-adjustments) (assoc :protocol/held-adjustments held-adjustments))))))

(defn- execute-dispatch!
  "Run the appropriate scenario/suite/fixture and return {:exit-code :dispatch-key
   :run-request :run-result :bundle-root}."
  [dispatch opts protocol-id runner-selection]
  (let [dispatch-key (dispatch-keyword dispatch)
        summary (dispatch-summary dispatch opts protocol-id)
        run-request (merge {:runner/backend :local-current
                            :runner-selection runner-selection
                            :suite/key (or (:suite-id summary)
                                           (:suite dispatch))
                            :protocol/default-id protocol-id
                            :evidence/profile (:evidence-profile opts)
                            :output/profile (:output-profile opts)}
                           (:scenario-run/request summary))
        run-result (summary->run-result dispatch run-request summary)
        proto-state (extract-protocol-state summary)
        run-result (if proto-state (merge run-result proto-state) run-result)
        exit-code (if (= :pass (:status run-result)) 0 1)
        bundle-root (br/build-bundle-root run-request run-result)]
    {:exit-code exit-code
     :dispatch-key dispatch-key
     :summary summary
     :run-request run-request
     :run-result run-result
     :bundle-root bundle-root}))

;; ── Execution node spec builder ─────────────────────────────────────────────────

;; ── Dry-run / error recovery ────────────────────────────────────────────────────

(defn- run-dry
  "Dry-run: count entries without replaying.
   Returns {:exit-code 0 :dry-run true :entry-count n}."
  [dispatch opts protocol-id]
  (let [suite-key (:suite dispatch)]
    {:exit-code   0
     :dry-run     true
     :entry-count
     (case (dispatch-keyword dispatch)
       :fixture-suite
       (let [suite (io-fix/load-fixture (:fixture-suite dispatch))]
         (count (:traces suite [])))
       :suite
       (if-let [paths (suites/suite-paths suite-key)]
         (count paths)
         0)
       :scenario 1
       :registry
       (count (get-in registry-suite-runners [protocol-id :entries] []))
       0)}))

(defn- build-minimal-error-root
  "Build a minimal bundle-root for error recovery when the normal path fails."
  [_ protocol-id _ error]
  {:bundle/schema-version "bundle-root.v1"
   :bundle/id             "error-recovery"
   :run/request           {:runner/backend :local-current
                           :protocol/default-id protocol-id}
   :orchestrator/id       :error-recovery
   :execution/summary     {:status :error
                           :error  (.getMessage error)
                           :exception (str (class error))}
   :run/environment       {:clojure/version (clojure-version)}})

(defn- build-execution-node-spec
  "Build the spec map for with-execution-node+ from resolved parameters."
  [dispatch opts runner-selection canonical? non-canonical-reason protocol-id source-provenance]
  {:execution-id :execution/replay
   :runner :scenario-runner
   :inputs (merge {:dispatch dispatch
                   :runner-selection runner-selection
                   :canonical? canonical?
                   :non-canonical-reason non-canonical-reason
                   :opts {:protocol protocol-id
                          :report-format (:report-format opts)
                          :suite-id (:suite-id opts)}}
                  source-provenance)
   :status-fn (fn [{:keys [exit-code]}]
                (cond (zero? exit-code) :pass
                      (integer? exit-code) :fail
                      :else :error))
   :outputs-fn (fn [{:keys [exit-code dispatch-key bundle-root]}]
                 {:exit-code exit-code
                  :dispatch dispatch
                  :dispatch-key dispatch-key
                  :canonical? canonical?
                  :non-canonical-reason non-canonical-reason
                  :bundle/root bundle-root
                  :bundle/root-hash (some-> bundle-root :bundle/hash)})
   :failure-details-fn (fn [{:keys [exit-code dispatch-key]}]
                         (if (zero? exit-code)
                           []
                           [{:failure-type :replay-failed
                             :class :unexpected
                             :message (str "Replay exited with code " exit-code
                                           " (" (name dispatch-key) ")")
                             :expected? false}]))})

;; ── Post-execution ──────────────────────────────────────────────────────────────

(defn- populate-forensic-claims!
  "Run Phase 3 forensic claims/attestations after the evidence registry exists.

   Scenario replay can execute without the final forensic artifact bundle. In
   that case, defer claim population instead of attempting to read absent
   registry and cursor files."
  []
  (let [artifact-dir (str (evcfg/artifact-dir))
        registry-file (io/file artifact-dir "evidence-registry.json")]
    (if-not (.exists registry-file)
      (log-event :info :forensic-claims-skipped
                 :reason :evidence-registry-not-yet-emitted
                 :artifact-dir artifact-dir)
      (do
        (log-event :info :forensic-claims-start)
        (try
          (let [rid (or (some-> (prov/provenance-map) :bundle/id) "unknown")
                pop-result (fp/populate-claims-and-attestations! rid)]
            (log-event :info :forensic-claims-done
                       :claim-count (:claim-count pop-result)
                       :attestation-count (:attestation-count pop-result)
                       :all-pass? (:all-pass? pop-result)))
          (catch Exception e
            (log-event :warn :forensic-claims-failed :error (.getMessage e))))))))

(defn- write-run-links!
  "Write immutable run-scoped links into the selected forensic run directory.
   A concurrent run never overwrites another run's links; any future `latest`
   pointer must be published separately with explicit CAS/fencing."
  [run-id dispatch protocol-id tsa-url canonical?]
  (try
    (let [dir (evcfg/artifact-dir)
          scenario-path (:scenario dispatch)
          cursor-file (io/file dir "chain-cursor-final.json")
          evidence-root (when (.exists cursor-file)
                          (chain/evidence-root-hash :dir dir))
          links {:type :forensic-run
                 :run/id run-id
                 :scenario/path scenario-path
                 :protocol/id protocol-id
                 :evidence/root evidence-root
                 :tsa/configured? (boolean tsa-url)
                 :tsa/url tsa-url
                 :signature/configured? (boolean (System/getenv "PRF_SIGNING_KEY"))
                 :canonical? canonical?
                 :generated-at (str (java.time.Instant/now))}
          links-dir (io/file dir "run-links")
          f (io/file links-dir (str run-id ".edn"))
          temp (io/file links-dir (str "." run-id ".tmp-" (java.util.UUID/randomUUID)))]
      (.mkdirs links-dir)
      (spit temp (ppedn/ppr-str links))
      (java.nio.file.Files/move (.toPath temp) (.toPath f)
                                (into-array java.nio.file.StandardCopyOption
                                            [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (log-event :info :run-links-written
                 :path (.getPath f)
                 :scenario scenario-path))
    (catch Exception e
      (log-event :warn :run-links-failed
                 :error (.getMessage e)))))

(defn- build-enriched-bundle-root
  "Merge source provenance and execution node hashes into the bundle root."
  [bundle-root execution-node source-provenance]
  (merge bundle-root
         source-provenance
         (when execution-node
           {:execution/node-hash (:node-hash execution-node)
            :execution/content-hash (:content-hash execution-node)
            :execution/record-hash (:record-hash execution-node)
            :dag/root-node-hash (:node-hash execution-node)})))

(defn run-and-report
  "CLI dispatcher: full invariant suite, named suite, or single file.

   `dispatch` is a map with optional keys:
      :suite          — path-list keyword (e.g. :yield-provider-scenarios, :sew-yield-scenarios)
      :fixture-suite  — EDN fixture keyword (e.g. :suites/all-invariants)
      :scenario       — file path
      :output-file    — canonical replay JSON path when running a single scenario
      :artifact-dir   — low-level forensic/evidence artifact directory
                         (:output-dir is a deprecated internal alias)
      :run-root       — complete structured bundle root, when applicable
      :run-id         — operational run ID supplied by the command orchestrator
      :scenario-root, :execution-dir, :summary-dir, :manifest-dir
                       — structured-run destinations owned by their respective writers
      :protocol       — protocol id (default sew-v1)
      :dry-run?       — when true, validate without replaying
   `opts` may include:
      :parallel?      — when true, run scenarios concurrently via pmap

   Returns {:exit-code <int> :bundle-root <map> :execution-node <map>}."
  [dispatch opts]
  (let [dispatch (update dispatch :scenario resolve-scenario-reference)]
    (validate-dispatch! dispatch)
    (let [protocol-id (resolve-protocol-id dispatch)
          source-provenance (prov/source-provenance)]
      (if (:dry-run? dispatch)
        (run-dry dispatch opts protocol-id)
        (let [runner-selection (or (:runner-selection dispatch) default-runner-selection)
              {:keys [canonical? non-canonical-reason]}
              (determine-canonicality dispatch opts runner-selection source-provenance)
              tsa-url (System/getenv "PRF_TSA_URL")]
          (when (and (not canonical?) non-canonical-reason)
            (log/warn! :non-canonical-run
                       {:reason non-canonical-reason
                        :message "Run is non-canonical; bundle will be marked accordingly"}))
          (when tsa-url
            (log-event :info :tsa-url :tsa-url tsa-url))

    ;; Pre-run commitment (best-effort, lazy-loaded forensic namespaces)
          (let [suite-key (:suite dispatch)
                run-id (or (:run-id dispatch) (str "run-" (java.time.Instant/now)))
                structured? (boolean (:run-root dispatch))
              ;; The Python forensic runner supplies PRF_ARTIFACT_DIR for an
              ;; isolated per-run artifact tree. Treat that as a finalizing
              ;; evidence run even when it does not use the structured-run
              ;; directory layout.
                forensic-artifact-dir (System/getenv "PRF_ARTIFACT_DIR")
                finalize-evidence? (boolean (or structured? forensic-artifact-dir))
                artifact-dir (or (:artifact-dir dispatch) (:output-dir dispatch)
                                 forensic-artifact-dir
                                 (str (paths/runs-root) "/" run-id))
                execution-dir (:execution-dir dispatch)
                _ (when (and structured? (nil? execution-dir))
                    (throw (ex-info "Structured runs require :execution-dir"
                                    {:dispatch (select-keys dispatch [:run-root :scenario-root :artifact-dir])})))
                _ (try
                    (let [prc (requiring-resolve 'resolver-sim.forensic.pre-run-commitment/build-commitment)
                          pwrite (requiring-resolve 'resolver-sim.forensic.pre-run-commitment/write-commitment!)
                          ctx {:suite-key suite-key :run-id run-id}
                          commitment (prc ctx)
                          written (if structured?
                                    (pwrite commitment execution-dir)
                                    (pwrite commitment))]
                      (log-event :info :pre-commitment :hash (:hash written))
                ;; Sign if key available
                      (when (or (System/getenv "PRF_SIGNING_KEY")
                                (.exists (java.io.File. "signing-key.pem")))
                        (let [fsign (requiring-resolve 'resolver-sim.forensic.signing/sign-and-write!)]
                          (fsign (:path written) commitment)
                          (log-event :info :pre-commitment-signed))))
                    (catch Exception e
                      (log-event :warn :pre-commitment-failed :error (.getMessage e))))]

        ;; P1b: Top-level try/catch — ensure structured output even on crash
            (try
              (ev-node/with-fresh-registry
                (chain/with-fresh-registry
                  (chain/with-fresh-chain-cursor
                    (binding [ts/*tsa-url* (or tsa-url ts/*tsa-url*)
                              *finalize-evidence?* finalize-evidence?
                              evcfg/*artifact-dir* artifact-dir]
                      (let [exec-spec (-> (build-execution-node-spec
                                           dispatch opts runner-selection
                                           canonical? non-canonical-reason protocol-id
                                           source-provenance)
                                          (assoc :extensions-fn
                                                 (fn [thunk-result]
                                                   (let [summary (:summary thunk-result)
                                                         results (:results summary)
                                                         fixture-refs (->> results
                                                                           (map :fixture-refs)
                                                                           (remove nil?)
                                                                           (apply concat)
                                                                           seq)]
                                                     (when fixture-refs
                                                       {:fixture/refs (vec fixture-refs)})))))
                            result (ev-node/with-execution-node+
                                     exec-spec
                                     (fn []
                                       (execute-dispatch! dispatch opts protocol-id
                                                          runner-selection)))
                            thunk-result (:result result)
                            execution-node (:execution-node result)
                            thunk-error (:error result)
                            _ (if thunk-error
                                (throw thunk-error)
                                (let [report-exit-code
                                      (dispatch-report-exit-code (:dispatch-key thunk-result)
                                                                 (:summary thunk-result)
                                                                 opts
                                                                 protocol-id)
                                      dispatch-exit-code (:exit-code thunk-result)]
                                  (when (and report-exit-code dispatch-exit-code
                                             (not= report-exit-code dispatch-exit-code))
                                    (log/warn! :exit-code-mismatch
                                               {:report-exit-code report-exit-code
                                                :dispatch-exit-code dispatch-exit-code
                                                :dispatch dispatch}))))
                            bundle-root (:bundle-root thunk-result)
                            _ (when (nil? bundle-root)
                                (throw (ex-info "run-and-report: nil bundle-root from execute-dispatch!"
                                                {:dispatch dispatch})))
                          ;; A bundle root is immutable once :bundle/hash is assigned.
                          ;; Keep execution-node references and replay internals in their
                          ;; own artifacts; never publish raw worlds, traces, or metrics here.
                            enriched-root bundle-root
                            raw-results (or (get-in thunk-result [:run-result :results])
                                            (get-in thunk-result [:summary :results]))
                          ;; The canonical scenario-JAR slug is the artifact ID.
                          ;; Persist only the v2 finalization's public metadata and
                          ;; digest commitments beneath the supplied forensic root.
                            _ (when (and structured? (not= :scenario (:dispatch-key thunk-result)))
                                (throw (ex-info "Structured suite finalization is not implemented; refusing unsafe publication"
                                                {:dispatch-key (:dispatch-key thunk-result)})))
                            _ (when structured?
                                (let [write-finalization!
                                      (requiring-resolve 'resolver-sim.evidence.finalization/write-scenario-finalization!)
                                      scenario-result (first raw-results)
                                    ;; Source bytes were snapshotted while resolving the request;
                                    ;; do not reopen a logical scenario reference during finalization.
                                      scenario-input-hash (get-in thunk-result [:run-request :entries 0 :scenario/source-hash])
                                      _ (when-not scenario-input-hash
                                          (throw (ex-info "Structured finalization requires a snapshotted scenario source hash" {})))
                                      written (write-finalization!
                                               {:forensic-dir artifact-dir
                                                :scenario-artifact-id (:scenario-slug dispatch)
                                                :scenario-id (:scenario-id scenario-result)
                                                :scenario-input-hash scenario-input-hash
                                                :run-id run-id
                                                :execution-id (:execution-id dispatch)
                                                :run-input-hash scenario-input-hash
                                              ;; Semantic failure is a completed execution with a fail verdict.
                                                :execution-status "completed"
                                                :execution-outcome (or (some-> (:outcome scenario-result) name)
                                                                       "unknown")
                                                :policy {:allow-empty-targeted-evidence? false}})]
                                  (log-event :info :scenario-finalization-written
                                             :path (:path written))))]
                      ;; Structured scenario bundles publish their authoritative
                      ;; registry and completion record in the outer lifecycle.
                      ;; Do not emit registry/cursor forensic claims here: at this
                      ;; point they are pre-finalization observations and would be
                      ;; misleadingly recorded as semantic failures.
                        (if structured?
                          (log-event :info :forensic-claims-deferred
                                     :reason :awaiting-run-finalization)
                          (populate-forensic-claims!))
                        (write-run-links! run-id dispatch protocol-id tsa-url canonical?)

                      ;; Emit a post-hoc evidence commitment root node that anchors
                      ;; the execution DAG to the evidence chain.
                      ;; This is the externally meaningful evidence anchor for a run.
                      ;; parent-hashes references the execution node; bootstrap-roots
                      ;; references the evidence-chain cursor hash.
                      ;; Runners and selectors will later point at this node.
                        (try
                          (let [evidence-root (chain/evidence-root-hash)
                                exec-hash (:node-hash execution-node)
                                exec-status (get-in execution-node [:result :status])
                                bundle-root-hash (some-> execution-node
                                                         :policy-output :visible :outputs :bundle/root-hash)]
                            (when (and evidence-root exec-hash)
                              (ev-node/emit-execution-node!
                               {:execution-id :evidence/commitment-root
                                :policy-id :evidence-policy/computed
                                :parent-hashes [(hash-ref/sha256-ref  exec-hash)]
                                :bootstrap-roots [(str "evidence-chain:sha256:" evidence-root)]
                                :status :pass
                                :inputs {:execution/node-hash (hash-ref/sha256-ref  exec-hash)
                                         :evidence/chain-cursor-hash (hash-ref/sha256-ref  evidence-root)}
                                :outputs {:bundle/root-hash (when bundle-root-hash
                                                              (hash-ref/sha256-ref  bundle-root-hash))
                                          :execution/status exec-status}})))
                          (catch Exception e
                            (log-event :warn :commitment-root-node-failed
                                       :error (.getMessage e))))

            ;; Execution DAG (best-effort, lazy-loaded)
                        (try
                          (let [dag-build (requiring-resolve 'resolver-sim.forensic.execution-dag/build-dag)
                                dag-write (requiring-resolve 'resolver-sim.forensic.execution-dag/write-dag!)
                                dag-make-node (requiring-resolve 'resolver-sim.forensic.execution-dag/make-plan-node)
                                scenario-id (:scenario-id dispatch)
                                execution-id (:execution-id dispatch)
                              ;; Direct inner runs may omit identities and retain
                              ;; best-effort DAG behavior. Canonical orchestration
                              ;; supplies the explicit identity contract.
                                nodes (if scenario-id
                                        [(dag-make-node {:id (str "node:" scenario-id)
                                                         :type :scenario-run
                                                         :input-hashes {:scenario/source-hash (:scenario/source-hash dispatch)}})]
                                        [])
                                dag (dag-build nodes [] {:run-id run-id
                                                         :scenario-id scenario-id
                                                         :execution-id execution-id})]
                            (if structured?
                              (dag-write dag run-id execution-dir)
                              (dag-write dag run-id))
                            (log-event :info :dag-write :node-count (:dag/node-count dag)))
                          (catch Exception e
                            (log-event :warn :dag-write-failed :error (.getMessage e))))

                      ;; Write run-enrichment.json for manifest enrichment in consolidated path
                        (when-let [manifest-dir (:manifest-dir dispatch)]
                          (try
                            (let [evidence-root (chain/evidence-root-hash)
                                  exec-hash (some-> execution-node :node-hash)
                                  bundle-root-hash (some-> execution-node
                                                           :policy-output :visible :outputs :bundle/root-hash)
                                  dag-path (if structured?
                                             (str (io/file execution-dir "execution-dag.json"))
                                             (str (paths/runs-root) "/" run-id "/execution-dag.json"))
                                  dag-root-hash (try
                                                  (-> (json/read-str (slurp dag-path) :key-fn keyword)
                                                      :dag/root-hash)
                                                  (catch Exception e
                                                    (log-event :warn :dag-root-hash-read-failed
                                                               :path dag-path
                                                               :error (.getMessage e))
                                                    nil))
                                  pre-commit-path (if structured?
                                                    (str (io/file execution-dir "pre-run-commitment.json"))
                                                    (str (paths/runs-root) "/" run-id "/pre-run-commitment.json"))
                                  pre-commit (try
                                               (json/read-str (slurp pre-commit-path) :key-fn keyword)
                                               (catch Exception e
                                                 (log-event :warn :pre-commit-read-failed
                                                            :path pre-commit-path
                                                            :error (.getMessage e))
                                                 nil))
                                  env (get-in enriched-root [:run/environment] {})
                                  source-data (or (:source pre-commit) source-provenance)
                                  enrichment
                                  {"execution"
                                   {"chain-root-ref" (when evidence-root
                                                       (str "evidence-chain:sha256:" evidence-root))
                                    "execution-node-ref" (when exec-hash
                                                           (str "evidence-node:sha256:" exec-hash))
                                    "dag-root-ref" (when dag-root-hash
                                                     (str "dag:sha256:" dag-root-hash))
                                    "dag-path" (when structured?
                                                 (str (paths/scenarios-root) "/" (:scenario-slug dispatch)
                                                      "/execution/execution-dag.json"))
                                    "pre-run-commitment-path" (when structured?
                                                                (str (paths/scenarios-root) "/" (:scenario-slug dispatch)
                                                                     "/execution/pre-run-commitment.json"))}
                                   "implementation"
                                   {"source-ref" (:hash source-data)
                                    "source-commit" (:commit source-data)
                                    "source-dirty?" (boolean (:dirty? source-data))
                                    "deps-ref" (some-> pre-commit :deps :root-hash)
                                    "config-ref" (:config-hash pre-commit)}
                                   "configuration"
                                   {"evidence-config-ref" (:config-hash pre-commit)
                                    "contract-version" "evidence-contract.v1"
                                    "hashing-version" "domain-hash.v1"}
                                   "environment"
                                   {"clojure-version" (:clojure/version env)
                                    "java-version" (:java/version env)
                                    "os-name" (:os/name env)}}
                                  enrichment-path (str manifest-dir "/run-enrichment.json")]
                              ;; The manifest directory is owned by this run. Atomic
                              ;; replacement prevents a manifest reader seeing a
                              ;; partially serialized enrichment projection.
                              (atomic-write-text! enrichment-path
                                                  (json/write-str enrichment {:indent true}))
                              (log-event :info :run-enrichment-written :path enrichment-path))
                            (catch Exception e
                              (log-event :warn :run-enrichment-failed :error (.getMessage e)))))

                        (let [output-path (or (:output-file dispatch)
                                              (when (:output-dir dispatch)
                                                (str (:output-dir dispatch) "/replay-output.json")))]
                          (when output-path
                            (io/make-parents output-path)
                            (write-result-json output-path enriched-root)))
                      ;; The outer orchestrator consumes this transient result to
                      ;; build derived projections. It is not persisted as part of
                      ;; the immutable bundle root or package boundary.
                        {:exit-code (:exit-code thunk-result)
                         :run-result (:run-result thunk-result)
                         :bundle-root enriched-root
                         :execution-node execution-node})))))
          ;; Top-level catch: produce minimal output on complete failure
              (catch Exception t
                (log-event :error :run-failed :error (.getMessage t) :exception (str (class t)))
              ;; Never rewrite a scenario finalization from this recovery path:
              ;; publication errors occur after a completed chain may already exist.
                (let [minimal-root (build-minimal-error-root dispatch protocol-id source-provenance t)
                      err-path (or (:output-file dispatch)
                                   (when (:output-dir dispatch)
                                     (str (:output-dir dispatch) "/replay-output.json")))]
                  (when err-path
                    (io/make-parents err-path)
                    (write-result-json err-path minimal-root))
                  {:exit-code 1
                   :bundle-root minimal-root
                   :execution-node nil})))))))))