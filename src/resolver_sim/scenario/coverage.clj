(ns resolver-sim.scenario.coverage
  "Coverage report for CDRS v1.1 scenario metadata.

   Scans trace files from disk, extracts :schema-version, :purpose, :threat-tags
   and :id, then produces a structured coverage map. The manifest is derived from
   scenario files — not maintained by hand.

   Two entry points:
     (scan-traces dir)        — load metadata from all .trace.json files in dir
     (coverage-report dir)    — scan + aggregate in one call

   The aggregation functions (group-by-purpose, group-by-threat-tag, etc.) are
   pure and accept a seq of metadata maps, so they can be tested independently."
  (:require [clojure.java.io  :as io]
            [clojure.data.json :as json]
            [clojure.string    :as str]
            [resolver-sim.definitions.registry :as defs]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.scenario.schema-profile :as schema-profile]
            [resolver-sim.logging :as log]
            [resolver-sim.config.paths :as paths]))

(declare coverage-report)

(defn -main
  "CLI entrypoint.

   Usage:
     clojure -M:coverage-report
     clojure -M:coverage-report -- data/fixtures/traces
      clojure -M:coverage-report -- data/fixtures/traces results/test-artifacts/coverage.json"
  [& args]
  (let [[dir out] (->> args (remove #(= % "--")) vec)
        dir       (or dir (paths/traces-dir))
        report    (coverage-report dir)
        payload   (json/write-str report {:indent true})
        out       (or out (evcfg/artifact-path :coverage))]
    (.mkdirs (io/file (evcfg/artifact-dir)))
    (spit out payload)
    (println (str "Wrote coverage report: " out))
    (println (str "Scanned dir: " dir))
    (println (str "Unhit transitions: " (count (:unhit-transitions report))))))

(def ^:private canonical-transitions
  "Canonical transition catalog from semantic registry."
  (defs/canonical-transition-ids))

;; ---------------------------------------------------------------------------
;; Directory scan
;; ---------------------------------------------------------------------------

(defn- safe-keyword
  "Convert a value to a keyword if it is a non-empty string, else return nil."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (seq v)) (keyword v)
    :else nil))

(defn- normalize-sid [v]
  (-> (str (or v ""))
      str/lower-case
      (str/replace #"^:" "")
      (str/replace #"\.json$" "")
      (str/replace #"\.trace\.json$" "")
      (str/replace #"\.edn$" "")))

(defn- scenario-map->meta [m source-file]
  (let [sid (or (:id m) (:scenario-id m))
        purpose (safe-keyword (:purpose m))
        threat-tags (->> (or (:threat-tags m) []) (map safe-keyword) (filter some?) vec)
        transitions (->> (or (:transitions m)
                             (map :action (or (:events m) [])))
                         (map safe-keyword)
                         (filter some?)
                         vec)]
    {:id sid
     :title (or (:title m) "")
     :purpose purpose
     :threat-tags threat-tags
     :transitions transitions
     :source-file source-file}))

(defn- load-scenario-metadata-index
  "Index canonical scenario metadata by normalized scenario id."
  []
  (let [scenario-dir sc/*scenario-dir*
        scenario-ext sc/*scenario-ext*
        scenario-files (->> (file-seq (io/file scenario-dir))
                            (filter #(.isFile %))
                            (filter #(str/ends-with? (.getName %) scenario-ext)))]
    (reduce (fn [idx f]
              (try
                (let [raw (sc/load-scenario-file (.getPath f))
                      entries (cond
                                (map? raw) [raw]
                                (vector? raw) (filter map? raw)
                                :else [])]
                  (reduce (fn [m e]
                            (let [{:keys [id] :as meta} (scenario-map->meta e (.getName f))
                                  k (normalize-sid id)]
                              (if (seq k)
                                (assoc m k meta)
                                m)))
                          idx
                          entries))
                (catch Exception e
                  (log/warn! :scenario-index-read-failed
                             {:path (str f) :error (.getMessage e)})
                  idx)))
            {}
            scenario-files)))

(defn- read-trace-metadata
  "Read only the metadata header fields from a .trace.json file.
   Returns nil if the file cannot be parsed."
  [file scenario-index]
  (try
    (with-open [r (io/reader file)]
      (let [raw (json/read r :key-fn keyword)
            purpose     (safe-keyword (:purpose raw))
            threat-tags (mapv safe-keyword (get raw :threat-tags []))
            events      (get raw :events [])
            transitions (->> events
                             (map :action)
                             (keep safe-keyword)
                             vec)
            guards      (->> events
                             (keep (fn [e]
                                     (when (true? (get e :adversarial?))
                                       {:guard :adversarial-attempt
                                        :transition (safe-keyword (:action e))})))
                             vec)
            trace-id (or (:id raw) (str/replace (.getName file) #"\.trace\.json$" ""))
            fallback-meta (get scenario-index (normalize-sid trace-id))]
        {:file           (.getName file)
         :path           (.getPath file)
         :id             trace-id
         :title          (or (:title raw) (:title fallback-meta) "")
         :schema-version (or (str (:schema-version raw)) "unknown")
         :purpose        (or purpose (:purpose fallback-meta))
         :threat-tags    (let [tags (filterv some? threat-tags)]
                           (if (seq tags) tags (or (:threat-tags fallback-meta) [])))
         :transitions    (if (seq transitions) transitions (or (:transitions fallback-meta) []))
         :guards         guards}))
    (catch Exception e
      (log/warn! :trace-metadata-read-failed
                 {:file (.getName file) :error (.getMessage e)})
      nil)))

(defn- scenario-outcome-label [{:keys [id]}]
  (let [s (if (keyword? id) (name id) (str id))]
    (cond
      (str/includes? s "-fail") :fail
      (str/includes? s "-inconclusive") :inconclusive
      (str/includes? s "-not-applicable") :not-applicable
      :else :pass)))

(defn scan-traces
  "Scan a directory for .trace.json files and return a vector of metadata maps.
   Files that cannot be parsed are silently skipped.

   Each map contains:
     :file           — filename (not full path)
     :path           — full path
     :id             — :id from scenario, or filename-derived keyword
     :title          — :title if present, else empty string
     :schema-version — \"1.0\", \"1.1\", etc.
     :purpose        — keyword or nil
     :threat-tags    — vector of keywords (may be empty)"
  [dir]
  (let [d (io/file dir)
        scenario-index (load-scenario-metadata-index)]
    (if (.isDirectory d)
      (->> (file-seq d)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".trace.json"))
           (sort-by #(.getPath %))
           (keep #(read-trace-metadata % scenario-index))
           vec)
      [])))

;; ---------------------------------------------------------------------------
;; Pure aggregation
;; ---------------------------------------------------------------------------

(defn- group-ids-by [key-fn scenarios]
  (->> scenarios
       (group-by key-fn)
       (reduce-kv (fn [m k vs] (assoc m k (mapv :id vs))) {})))

(defn- threat-tag-frequency [scenarios]
  (->> scenarios
       (mapcat :threat-tags)
       frequencies
       (sort-by (comp - val))
       (into {})))

(defn aggregate
  "Build a coverage report map from a seq of metadata maps (as returned by scan-traces).

   Returns:
     :total             — total scenario count
      :schema-versions   — {version-str count}
      :enriched-version  — schema-profile/enriched-version marker
     :by-purpose        — {purpose [id ...]}  (:unclassified for nil purpose)
     :by-threat-tag     — {tag [id ...]}
     :threat-tag-freq   — {tag count} sorted by frequency desc
     :unclassified-count — count of scenarios with no :purpose
      :scenarios         — full metadata seq

     Transition coverage additions:
      :transition-hit-freq              — {transition count}
      :transition-outcome-freq          — {transition {:pass n :fail n ...}}
      :transition-by-purpose-hit-freq   — {purpose {transition count}}
      :transition-by-threat-tag-hit-freq— {tag {transition count}}
      :guard-hit-freq                   — {guard count}
      :guard-by-purpose-hit-freq        — {purpose {guard count}}
      :guard-by-threat-tag-hit-freq     — {tag {guard count}}
      :unhit-transitions                — [transition ...]
      :canonical-transitions            — all tracked transitions"
  [scenarios]
  (let [classified   (filter :purpose scenarios)
        unclassified (remove :purpose scenarios)
        by-purpose   (-> (group-ids-by :purpose classified)
                         (assoc :unclassified (mapv :id unclassified)))
        by-version   (->> (group-by :schema-version scenarios)
                          (reduce-kv (fn [m k vs] (assoc m k (count vs))) {}))
        transition-hit-freq
        (->> scenarios (mapcat :transitions) frequencies (into (sorted-map)))
        transition-outcome-freq
        (reduce (fn [m s]
                  (let [o (scenario-outcome-label s)]
                    (reduce (fn [m2 t]
                              (update-in m2 [t o] (fnil inc 0)))
                            m
                            (:transitions s))))
                {}
                scenarios)
        transition-by-purpose-hit-freq
        (->> scenarios
             (group-by #(or (:purpose %) :unclassified))
             (reduce-kv (fn [m p ss]
                          (assoc m p (->> ss (mapcat :transitions) frequencies (into (sorted-map)))))
                        {}))
        transition-by-threat-tag-hit-freq
        (reduce (fn [m s]
                  (reduce (fn [m2 ttag]
                            (reduce (fn [m3 tr]
                                      (update-in m3 [ttag tr] (fnil inc 0)))
                                    m2
                                    (:transitions s)))
                          m
                          (if (seq (:threat-tags s))
                            (:threat-tags s)
                            [:untagged])))
                {}
                scenarios)
        guard-hit-freq
        (->> scenarios
             (mapcat :guards)
             (map :guard)
             frequencies
             (into (sorted-map)))
        guard-by-purpose-hit-freq
        (->> scenarios
             (group-by #(or (:purpose %) :unclassified))
             (reduce-kv (fn [m p ss]
                          (assoc m p (->> ss (mapcat :guards) (map :guard) frequencies (into (sorted-map)))))
                        {}))
        guard-by-threat-tag-hit-freq
        (reduce (fn [m s]
                  (let [guards (map :guard (:guards s))]
                    (reduce (fn [m2 ttag]
                              (reduce (fn [m3 g]
                                        (update-in m3 [ttag g] (fnil inc 0)))
                                      m2
                                      guards))
                            m
                            (if (seq (:threat-tags s))
                              (:threat-tags s)
                              [:untagged]))))
                {}
                scenarios)
        seen-transitions (set (keys transition-hit-freq))
        unhit-transitions (->> canonical-transitions
                               (remove seen-transitions)
                               sort
                               vec)]
    {:total              (count scenarios)
     :schema-versions    by-version
     :enriched-version   (schema-profile/enriched-version)
     :by-purpose         by-purpose
     :by-threat-tag      (group-ids-by identity
                                       (for [s scenarios t (:threat-tags s)]
                                         (assoc s :id (:id s) :_tag t)))
     :threat-tag-freq    (threat-tag-frequency scenarios)
     :unclassified-count (count unclassified)
     :transition-hit-freq transition-hit-freq
     :transition-outcome-freq transition-outcome-freq
     :transition-by-purpose-hit-freq transition-by-purpose-hit-freq
     :transition-by-threat-tag-hit-freq transition-by-threat-tag-hit-freq
     :guard-hit-freq guard-hit-freq
     :guard-by-purpose-hit-freq guard-by-purpose-hit-freq
     :guard-by-threat-tag-hit-freq guard-by-threat-tag-hit-freq
     :canonical-transitions canonical-transitions
     :unhit-transitions unhit-transitions
     :scenarios          scenarios}))

;; ---------------------------------------------------------------------------
;; Combined entry point
;; ---------------------------------------------------------------------------

(defn coverage-report
  "Scan dir for .trace.json files and return an aggregated coverage map.
   Uses the default traces directory when called with no arguments."
  ([]
   (coverage-report (paths/traces-dir)))
  ([dir]
   (-> dir scan-traces aggregate (assoc :scanned-dir dir))))
