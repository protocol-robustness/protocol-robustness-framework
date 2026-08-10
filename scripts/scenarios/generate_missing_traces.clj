(ns scripts.scenarios.generate-missing-traces
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [resolver-sim.io.scenario-export :as export]
            [resolver-sim.io.scenarios :as io-sc]))

(defn- golden-trace-ids
  []
  (let [dir (io/file "data/fixtures/golden")]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".report.edn"))
           (keep (fn [f]
                   (second (re-find #":trace-id\s+\"([^\"]+)\"" (slurp f)))))))))

(defn- existing-trace-ids
  []
  (let [dir (io/file "data/fixtures/traces")]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".trace.json"))
           (map #(str/replace (.getName %) ".trace.json" ""))
           (set)))))

(defn- normalize-scenario-id
  "Convert S112_aave-partial-liquidity-recovery → s112-aave-partial-liquidity-recovery"
  [sid]
  (if-let [[_ num rest] (re-matches #"^S(\d+[a-z]?)_(.+)$" sid)]
    (str "s" num "-" rest)
    sid))

(defn- find-scenario-file
  [trace-id]
  (let [public-name (export/scenario-id->public-json-filename trace-id)
        std-path (when public-name (str "scenarios/" public-name))]
    (or (and std-path (.exists (io/file std-path)) std-path)
        (let [up (str "scenarios/" (str/upper-case (first (str trace-id)))
                      (subs trace-id 1) ".json")]
          (when (.exists (io/file up)) up))
        ;; EDN scenarios live under scenarios/edn/ (post-JSON-migration).
        ;; Filenames use the S-DR-* public convention; the trace-id is the
        ;; lowercase golden id (e.g. s-dr-010-missing-evidence).  Match the
        ;; EDN file whose scenario-id equals the trace-id case-insensitively.
        (let [edn-dir (io/file "scenarios/edn")]
          (when (.isDirectory edn-dir)
            (some (fn [f]
                    (when (.endsWith (.getName f) ".edn")
                      (try
                        (let [data (io-sc/load-scenario-file (.getPath f))]
                          (when (and (map? data)
                                     (= (str/lower-case (str (:scenario-id data)))
                                        (str/lower-case trace-id)))
                            (.getPath f)))
                        (catch Exception _ nil))))
                  (.listFiles edn-dir))))
        (let [dir (io/file "scenarios")]
          (when (.isDirectory dir)
            (some (fn [f]
                    (when (.endsWith (.getName f) ".json")
                      (try
                        (let [data (json/read-str (slurp f) :key-fn keyword)]
                          (when (and (map? data)
                                     (= (:scenario-id data) trace-id))
                            (.getPath f)))
                        (catch Exception _ nil))))
                  (.listFiles dir)))))))

(defn- normalize-trace-file!
  "Rewrite the trace file so its scenario-id matches the golden trace-id convention."
  [trace-id]
  (let [file (io/file "data/fixtures/traces" (str trace-id ".trace.json"))]
    (when (.exists file)
      (let [data (json/read-str (slurp file) :key-fn keyword)
            sid (:scenario-id data)
            normalized (normalize-scenario-id (str sid))]
        (when (and normalized (not= normalized (str sid)))
          (spit file (json/write-str (assoc data
                                            :scenario-id normalized
                                            :id (str "scenarios/" normalized))
                                     :indent true :key-fn name))
          (println (str "    normalized scenario-id: " sid " → " normalized)))))))

(defn -main [& _]
  (let [golden-ids (set (golden-trace-ids))
        existing (existing-trace-ids)
        orphans (sort (remove existing golden-ids))
        generated (atom 0)
        skipped (atom 0)]
    (println (format "Golden reports: %d  Existing traces: %d  Orphans: %d"
                     (count golden-ids) (count existing) (count orphans)))
    (doseq [trace-id orphans]
      (if-let [scenario-path (find-scenario-file trace-id)]
        (try
          (let [scenario (io-sc/load-scenario-file scenario-path)]
            (if (map? scenario)
              (do
                (export/export-scenario-files! scenario)
                (normalize-trace-file! trace-id)
                (println (str "  ✓ " trace-id " ← " scenario-path))
                (swap! generated inc))
              (do
                (println (str "  - " trace-id " SKIPPED: compound scenario file (" (type scenario) ")"))
                (swap! skipped inc))))
          (catch Exception e
            (println (str "  ✗ " trace-id " ERROR: " (.getMessage e)))
            (swap! skipped inc)))
        (do
          (println (str "  - " trace-id " SKIPPED: no matching scenario file"))
          (swap! skipped inc))))
    (println (format "Generated %d traces. %d skipped." @generated @skipped))
    (let [nb (io/file "notebooks/report.clj")]
      (when (.exists nb)
        (.setLastModified nb (System/currentTimeMillis))
        (println "Touched notebooks/report.clj for Clerk re-evaluation")))))
