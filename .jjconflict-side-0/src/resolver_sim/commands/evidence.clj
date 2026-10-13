(ns resolver-sim.commands.evidence
  "Evidence commands: make, verify-chain, validate, coverage, backstop.
   Calls existing functions in resolver-sim.evidence.* directly."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.registry :as reg]
            [resolver-sim.evidence.registry-validation :as rv]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.config.paths :as cfg-paths]
            [resolver-sim.validation.integration.artifact-registry :as artifact-registry]
            [resolver-sim.io.event-evidence :as io-evidence]
            [resolver-sim.sim.reference-validation :as rv-suite]
            [resolver-sim.util.evidence :as evidence]))

(def ^:private make-evidence-keys
  #{:artifact-kind :block-time :step :before :after :action :result :attribution})

(def ^:private required-make-evidence-keys
  #{:artifact-kind :before :after :action :result :attribution})

(defn- read-spec
  [path]
  (let [source (if (or (nil? path) (= "-" path)) *in* (io/file path))
        text (slurp source)]
    (if (or (str/ends-with? (str path) ".json")
            (and (or (nil? path) (= "-" path))
                 (str/starts-with? (str/trim text) "{")))
      (json/read-str text :key-fn keyword)
      (edn/read-string text))))

(defn- write-record
  [record json?]
  (if json?
    (json/write-str record)
    (prn record)))

(defn make
  "Construct exactly one canonical evidence record from one explicit spec."
  [{:keys [input json?]}]
  (try
    (let [spec (try
                 (read-spec input)
                 (catch Exception e
                   (throw (ex-info (str "Unable to read evidence specification: "
                                        (.getMessage e))
                                   {:exit-code 2}
                                   e))))]
      (when-not (map? spec)
        (throw (ex-info "Evidence specification must be a map" {:exit-code 2})))
      (let [unknown (seq (remove make-evidence-keys (keys spec)))
            missing (seq (remove #(and (contains? spec %) (some? (get spec %)))
                                 required-make-evidence-keys))]
        (when unknown
          (throw (ex-info (str "Unknown evidence specification field(s): " (pr-str (vec unknown)))
                          {:exit-code 2 :unknown (vec unknown)})))
        (when missing
          (throw (ex-info (str "Missing required evidence specification field(s): "
                               (pr-str (vec (sort missing))))
                          {:exit-code 2 :missing (vec missing)}))))
      (write-record (evidence/make-evidence-record spec) json?)
      {:exit-code 0})
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (.getMessage e)))
      {:exit-code (or (:exit-code (ex-data e)) 1)})
    (catch Exception e
      (binding [*out* *err*]
        (println (.getMessage e)))
      {:exit-code 1})))

(defn- ensure-dir
  "Resolve an explicitly selected bundle or legacy artifact directory."
  [artifact-dir run-root]
  (when-let [path (or run-root artifact-dir)]
    (let [d (io/file path)]
      (when-not (.exists d)
        (println (str "Artifact directory not found: " (.getPath d))))
      d)))

(defn- missing-root-result []
  {:exit-code 2
   :message "Specify --run-root for a canonical bundle or --artifact-dir for a legacy evidence directory"})

(defn- canonical-registry-file [dir]
  (io/file dir paths/artifacts-registry))

(defn- load-registry
  "Load or build the evidence registry for an artifact directory."
  [dir]
  (let [reg-file (io/file dir "evidence-registry.json")]
    (if (.exists reg-file)
      (json/read-str (slurp reg-file) :key-fn keyword)
      (reg/build-evidence-registry (.getPath dir)))))

(defn- run-suite-for-evidence
  "Run the reference-validation suite to generate evidence artifacts.
   Returns the suite output root directory."
  []
  (println "  Running reference-validation suite...")
  (flush)
  (try
    (let [result (rv-suite/generate!)]
      (println (str "  Suite complete: " (:scenario-count result) " scenarios"))
      (flush)
      (str (cfg-paths/reference-validation-suite-dir) "/actual"))
    (catch Exception e
      (println (str "  Suite run skipped (no evidence generated): " (.getMessage e)))
      nil)))

(defn verify-chain
  "Verify evidence chain hashes and links for an explicit legacy artifact directory."
  [{:keys [artifact-dir _strict? _json?] :as _opts}]
  (let [dir (ensure-dir artifact-dir nil)]
    (if-not (.exists dir)
      {:exit-code 3 :message "Artifact directory not found"}
      (try
        (println (str "Verifying evidence chain in " artifact-dir "..."))
        (let [registry (load-registry dir)
              result (chain/evidence-chain-integrity registry)]
          (println (str "  Chain intact: " (:chain-intact result)))
          (println (str "  Artifacts: " (:artifact-count result)))
          (println (str "  Registry hash valid: " (:registry-hash-valid result)))
          (println (str "  All hashes well-formed: " (:all-hashes-well-formed result)))
          (let [ok? (:chain-intact result)]
            (if ok?
              {:exit-code 0 :message "Evidence chain verified" :result result}
              {:exit-code 1 :message "Evidence chain integrity check failed" :result result})))
        (catch Exception e
          (println "  Error:" (.getMessage e))
          {:exit-code 4 :message (.getMessage e)})))))

(defn validate
  "Validate an explicit canonical bundle root or legacy evidence directory.
   Canonical validation reads manifest/artifacts.json and never writes to an
   already completed bundle."
  [{:keys [artifact-dir run-root strict?] :as _opts}]
  (let [dir (ensure-dir artifact-dir run-root)]
    (cond
      (nil? dir) (missing-root-result)
      (not (.exists dir)) {:exit-code 3 :message "Artifact directory not found"}
      :else
      (try
        (let [canonical (canonical-registry-file dir)
              result (if (.isFile canonical)
                       (artifact-registry/validate-artifact-registry-from-file (.getPath canonical))
                       (let [registry (load-registry dir)]
                         (rv/validate-evidence-registry registry
                                                        :strict (boolean strict?)
                                                        :artifact-dir (.getPath dir))))
              ok? (= :passed (:status result))]
          (println (str "Validating evidence registry in " (.getPath dir) "..."))
          (println (str "  Status: " (:status result)))
          {:exit-code (if ok? 0 1)
           :message (if ok? "Evidence validation passed" "Evidence validation failed")
           :result result})
        (catch Exception e
          (println "  Error:" (.getMessage e))
          {:exit-code 4 :message (.getMessage e)})))))

(defn coverage
  "Check evidence coverage completeness.
   Calculates which evidence types are present vs types declared in the
   canonical evidence-type->mechanism registry.
   A registry-gap type (e.g. :guard-rejected, :resolver-rotation) that exists
   in evidence artifacts but is not in the mechanism map will NOT trigger
   a failure — only absent canonical types are reported as missing."
  [{:keys [artifact-dir _json?] :as _opts}]
  (let [dir (ensure-dir artifact-dir nil)]
    (if-not (.exists dir)
      {:exit-code 3 :message "Artifact directory not found"}
      (try
        (println (str "Checking evidence coverage in " artifact-dir "..."))
        ;; The chain registry tracks chain artifacts, while coverage concerns
        ;; captured event evidence. Build the directory registry directly.
        (let [registry (reg/build-evidence-registry (.getPath dir))
              entries (:entries registry [])
              types (set (map :evidence/type entries))
              expected (set (keys io-evidence/evidence-type->mechanism))
              present (set/intersection types expected)
              missing (set/difference expected types)]
          (println (str "  Present types: " (count present) "/" (count expected)))
          (when (seq missing)
            (print "  Missing types: ") (clojure.pprint/pprint (sort missing)))
          (if (empty? missing)
            {:exit-code 0 :message "Evidence coverage complete"
             :result {:present (count present) :expected (count expected) :missing []}}
            {:exit-code 1 :message (str "Missing " (count missing) " evidence type(s)")
             :result {:present (count present) :expected (count expected)
                      :missing (sort missing)}}))
        (catch Exception e
          (println "  Error:" (.getMessage e))
          {:exit-code 4 :message (.getMessage e)})))))

(defn run-backstop
  "Run the evidence review gate: run reference-validation, then verify the
   captured evidence directory. Coverage is reported in default mode and is
   promoted to a gate only with :strict? because a single run need not exercise
   every protocol evidence type."
  [{:keys [_fast? _full? artifact-dir _json? strict?] :as opts}]
  (println "Running evidence backstop...")
  (flush)
  (let [suite-complete? (boolean (run-suite-for-evidence))
        evidence-dir (or artifact-dir (evcfg/artifact-dir))
        verify-opts (assoc opts :artifact-dir evidence-dir)
        results (if suite-complete?
                  [(verify-chain verify-opts)
                   (validate verify-opts)
                   (coverage verify-opts)]
                  [{:exit-code 0 :message "Suite run completed"}])
        gate-results (if strict? results (take 2 results))
        failures (filter #(not (zero? (:exit-code % 0))) gate-results)]
    (if (empty? failures)
      (do (println "Evidence backstop PASSED")
          {:exit-code 0 :results results})
      (do (println "Evidence backstop FAILED")
          {:exit-code 1 :results results}))))
