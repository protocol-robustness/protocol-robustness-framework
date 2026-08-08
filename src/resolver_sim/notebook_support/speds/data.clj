(ns resolver-sim.notebook-support.speds.data
  "SPEDS Phase 2: Artifact Ingestion & Finding Extraction.
   Logic for mapping simulation artifacts to visual narratives."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.logging :as log]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.speds.config :as config]))

(defn sha256-hex
  [v]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (str v) "UTF-8"))]
    (format "%064x" (java.math.BigInteger. 1 bytes))))

;; ---
;; 1. Unified Artifact Ingestion

(defn load-summary
  "Canonical loader for test-summary artifact."
  []
  (common/read-json (:test-summary config/artifact-paths)))

(defn load-coverage
  "Canonical loader for coverage artifact."
  []
  (common/read-json (:coverage config/artifact-paths)))

(defn load-equivalence
  "Canonical loader for equivalence summary artifact."
  []
  (common/read-json (:equivalence config/artifact-paths)))

(defn load-test-run
  "Canonical loader for test-run manifest artifact (v1+)."
  []
  (try
    (common/read-json (:test-run config/artifact-paths))
    (catch Exception e
      (log/warn! "Failed to load test-run artifact" {:path (:test-run config/artifact-paths) :error (.getMessage e)})
      nil)))

(defn load-all-traces
  "Loads all scenario traces from the configured directory."
  []
  (try
    (let [dir (io/file (:traces-dir config/artifact-paths))]
      (if (and (.exists dir) (.isDirectory dir))
        (->> (.listFiles dir)
             (filter #(str/ends-with? (.getName %) ".trace.json"))
             (keep (fn [f]
                     (try
                       (let [d (json/read-str (slurp f) {:key-fn keyword})]
                         (assoc d :_filename (.getName f)))
                       (catch Exception e
                         (log/warn! "Failed to parse trace file" {:file (.getName f) :error (.getMessage e)})
                         nil))))
             (sort-by :scenario-id))
        []))
    (catch Exception e
      (log/warn! "Failed to load traces directory" {:path (:traces-dir config/artifact-paths) :error (.getMessage e)})
      [])))

(defn load-all-golden-reports
  "Loads all golden replay reports from the configured directory."
  []
  (try
    (let [dir (io/file (:golden-dir config/artifact-paths))]
      (if (and (.exists dir) (.isDirectory dir))
        (->> (.listFiles dir)
             (filter #(str/ends-with? (.getName %) ".report.edn"))
             (keep (fn [f]
                     (try
                       (let [d (common/read-edn (.getAbsolutePath f))]
                         [(str (:trace-id d)) d])
                       (catch Exception e
                         (log/warn! "Failed to parse golden report" {:file (.getName f) :error (.getMessage e)})
                         nil))))
             (into {}))
        {}))
    (catch Exception e
      (log/warn! "Failed to load golden reports directory" {:path (:golden-dir config/artifact-paths) :error (.getMessage e)})
      {})))

(defn scenario-golden-key
  "Normalize a coverage/trace scenario id to a golden report trace-id key.
   Uses lowercasing and strips the scenarios/ prefix.
   Underscores are preserved (removed from earlier versions to avoid
   collision between e.g. S01_foo and S01-foo)."
  [scenario-id]
  (-> (str scenario-id)
      str/lower-case
      (str/replace #"^scenarios/" "")))

(defn find-golden-report
  "Look up a golden replay report for a scenario id.
   Tries exact key first, then falls back to underscore→hyphen conversion
   for backward compatibility with legacy golden reports."
  [golden-reports scenario-id]
  (when (and golden-reports scenario-id)
    (let [key (scenario-golden-key scenario-id)]
      (or (get golden-reports key)
          (get golden-reports (str/replace key #"_" "-"))))))

(defn canonical-summary
  "Normalizes summary variants into stable keys used by notebooks/stories."
  [summary]
  (let [status-lc (str/lower-case (str (or (:overall_status summary) "unknown")))]
    {:run-id (or (:run_id summary) (:run-id config/protocol-defaults))
     :overall-status (if (seq status-lc) status-lc "unknown")
     :failure-count (or (:failure_count summary)
                        (:failing_scenarios summary)
                        (:failed_scenarios summary)
                        (:failed summary)
                        0)
     :replay-match-pct (or (:replay_match_pct summary)
                           (:deterministic_replay_pct summary))
     :git-sha (or (:git_sha summary) (:git-sha config/protocol-defaults))}))

(defn load-run-artifacts
  "Loads the full set of artifacts for a validation run."
  []
  (let [summary  (load-summary)
        test-run (load-test-run)
        coverage (load-coverage)
        equivalence (load-equivalence)
        manifest (try (common/read-json (:manifest config/artifact-paths))
                      (catch Exception e
                        (log/warn! "Failed to load manifest" {:path (:manifest config/artifact-paths) :error (.getMessage e)})
                        nil))]
    {:summary  summary
     :test-run test-run
     :summary-canonical (canonical-summary summary)
     :coverage coverage
     :equivalence equivalence
     :manifest manifest
     :all-traces (load-all-traces)
     :golden-reports (load-all-golden-reports)}))

;; ---
;; 2. Finding Extraction Heuristics

(defn find-scenario-by-id
  "Locates scenario metadata in the coverage artifact."
  [coverage id]
  (let [matches (filter #(= (:id %) id) (:scenarios coverage))]
    (when (> (count matches) 1)
      (log/warn! "Duplicate scenario IDs in coverage" {:scenario-id id :count (count matches)}))
    (first matches)))

(defn find-intercept-event
  "Scans a trace for the first event that triggers an invariant-violation
   rejection or guard intercept."
  [trace]
  (let [events (:events trace)]
    (first (filter (fn [e]
                     (or (str/includes? (str/upper-case (or (:action e) "")) "REJECT")
                         (some? (:guard e))
                         (seq (:violations e))))
                   events))))

(defn get-temporal-boundary
  "Extracts the T+1ms boundary events from a trace."
  [trace deadline-ms]
  (let [events (:events trace)]
    {:t-minus (last (filter #(<= (:time %) deadline-ms) events))
     :t-plus  (first (filter #(> (:time %) deadline-ms) events))}))

;; ---
;; 3. Data-to-Visual Projection (The "Narrator")

(defn project-actor-type
  "Maps a simulation agent ID to a SPEDS actor type."
  [agent-id]
  (let [id (str/lower-case (str agent-id))]
    (cond
      (str/includes? id "malice") :adversarial
      (str/includes? id "attacker") :adversarial
      (str/includes? id "kleros") :backstop
      :else :honest)))

(defn project-event-to-flow
  "Maps a simulation event to a SPEDS flow type."
  [event]
  (let [action (str/lower-case (or (:action event) ""))]
    (cond
      (str/includes? action "yield") :yield
      (str/includes? action "dispute") :adversarial
      :else :principal)))

;; ---
;; 4. Narrative Assembly Helpers

(defn generate-proof-context
  "Builds the metadata required for the V-FRAME footer and badges."
  [artifacts scenario-id]
  (let [{:keys [summary manifest coverage]} artifacts
        scenario (find-scenario-by-id coverage scenario-id)
        git-sha  (or (:git_sha summary) (:git-sha config/protocol-defaults))
        git7     (subs git-sha 0 (min 7 (count git-sha)))]
    {:trace-id    (or (:ipfs-cid manifest) "LOCALLY_SIGNED")
     :git-sha     git7
     :run-id      (or (:run_id summary) (:run-id config/protocol-defaults))
     :hash        (subs (sha256-hex (str scenario-id)) 0 8)
     :title       (or (:title scenario)
                      (-> scenario-id
                          (str/replace #"^scenarios/" "")
                          (str/replace #"-" " ")
                          (str/upper-case)))}))

(defn- pct
  [num den]
  (if (and (number? num) (number? den) (pos? den))
    (* 100.0 (/ num den))
    nil))

(defn- fmt-pct
  [x]
  (if (number? x)
    (format "%.1f%%" (double x))
    "N/A"))

(defn narrative-metrics
  "Computes narrative-safe metrics from artifacts with graceful fallbacks.
   Returns metrics suitable for production visual copy, avoiding hardcoded claims."
  [{:keys [summary coverage] :as artifacts}]
  (let [scenarios     (or (:scenarios coverage) [])
        total         (count scenarios)
        failures      (or (:failing_scenarios summary)
                          (:failed_scenarios summary)
                          (:failed summary)
                          0)
        passes        (max 0 (- total failures))
        replay-match  (or (:replay_match_pct summary)
                          (:deterministic_replay_pct summary)
                          (pct passes total))
        top-threats   (take 2 (sort-by val > (or (:threat-tag-freq coverage) {})))]
    {:scenario-count total
     :replay-match-pct replay-match
     :replay-match-label (fmt-pct replay-match)
     :top-threats (mapv (comp name key) top-threats)
     :determinism-text (if (number? replay-match)
                         (str "Deterministic replay alignment currently at " (fmt-pct replay-match) ".")
                         "Deterministic replay alignment unavailable from current artifacts.")
     :coverage-text (if (seq top-threats)
                      (str "Highest observed threat tags: " (str/join ", " (map (comp name key) top-threats)) ".")
                      "Threat-tag frequency unavailable in current coverage artifact.")}))

;; ---
;; 5. Unified Evidence Surface (Sync Logic)

(defn- try-require [ns-sym]
  (try (require ns-sym) true (catch Exception _ false)))

(defn load-xtdb-summary
  "Attempts to fetch summary data from XTDB if available."
  []
  (when (try-require 'resolver-sim.notebook-support.db)
    (let [ds-fn (resolve 'resolver-sim.notebook-support.db/ds-result)]
      (when-let [ds (and ds-fn (:ds (ds-fn)))]
        ;; Placeholder for actual XTDB query logic
        nil))))

(defn unified-evidence-provider
  "Abstraction that merges file-based artifacts with live database state."
  []
  (let [file-artifacts (load-run-artifacts)
        db-summary     (load-xtdb-summary)]
    (if db-summary
      (update file-artifacts :summary-canonical merge db-summary)
      file-artifacts)))
