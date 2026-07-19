(ns resolver-sim.io.trace-store
  "Adversarial trace persistence and regression loop.

   Persists notable replay traces to disk as:
     results/traces/<id>/meta.json     — score, categories, metrics
     results/traces/<id>/scenario.json — the original scenario (for re-run)
     results/traces/<id>/fixture.json  — Forge-compatible replay fixture

   Selection criteria (what gets persisted):
     - :trace-score > 0  (anything notable)
     - invariant violations detected
     - liveness failure detected

   Additionally tracks the top-N corpus (configurable, default 100).
   When the corpus exceeds N, the lowest-scoring trace is evicted.

   Regression loop:
     (promote-to-regression! store-dir out-dir) copies every persisted
     fixture into test/foundry/traces/regression/ so Forge picks them up.

   Public API:
     store-trace!             — persist one scored+categorised trace
     load-store               — read all metadata from store-dir
     select-top-n             — top-N by score from the store
     select-by-category       — filter store by category keyword
     promote-to-regression!   — copy fixtures → Forge regression dir
     export-regression-manifest — write manifest JSON for Forge test

   CLI (clojure -M:trace-regress):
     Promotes all stored traces and writes manifest.
     Optional args: <store-dir> <regression-dir>"
  (:require [clojure.data.json                 :as json]
            [clojure.java.io                   :as io]
            [clojure.string                    :as str]
            [resolver-sim.io.trace-score       :as ts]
            [resolver-sim.protocols.sew.io.trace-export :as te])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def default-store-dir  "results/traces")
(def default-corpus-max 100)

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- kw-val->str [v]
  (if (keyword? v)
    (if-let [ns (namespace v)]
      (str ns "/" (name v))
      (name v))
    v))

(defn- trace-id
  "Deterministic id from scenario + timestamp."
  [scenario scored-result]
  (let [seed (get scenario :seed "0")
        ts   (System/currentTimeMillis)]
    (str "trace_" seed "_" ts)))

(defn- ensure-dir! [^String path]
  (.mkdirs (io/file path)))

(defn- write-json! [^String path data]
  (ensure-dir! (.getParent (io/file path)))
  (with-open [w (io/writer path)]
    (json/write data w :indent true)))

(defn- read-json [^String path]
  (with-open [r (io/reader path)]
    (json/read r :key-fn keyword)))

(defn- meta-path   [store-dir id] (str store-dir "/" id "/meta.json"))
(defn- scen-path   [store-dir id] (str store-dir "/" id "/scenario.json"))
(defn- fix-path    [store-dir id] (str store-dir "/" id "/fixture.json"))

;; ---------------------------------------------------------------------------
;; Public: load-store (must be defined before evict-if-needed!)
;; ---------------------------------------------------------------------------

(defn load-store
  "Read all trace metadata from store-dir.  Returns a vector of meta maps,
   each augmented with :has-fixture (boolean)."
  ([] (load-store default-store-dir))
  ([store-dir]
   (let [root (io/file store-dir)]
     (if-not (.isDirectory root)
       []
       (->> (.listFiles root)
            (filter #(.isDirectory %))
            (keep (fn [dir]
                    (let [mp (str (.getPath dir) "/meta.json")]
                      (when (.exists (io/file mp))
                        (assoc (read-json mp)
                               :has-fixture (.exists (io/file (str (.getPath dir) "/fixture.json"))))))))
            vec)))))

;; ---------------------------------------------------------------------------
;; Internal: corpus eviction
;; ---------------------------------------------------------------------------

(defn- evict-if-needed!
  "If more than corpus-max traces exist, delete the lowest-scoring one."
  [store-dir corpus-max]
  (let [all (load-store store-dir)
        n   (count all)]
    (when (> n corpus-max)
      (let [lowest (->> all
                        (sort-by #(get % :trace-score 0))
                        first)
            id     (:trace-id lowest)]
        (when id
          (doseq [f [(meta-path store-dir id)
                     (scen-path store-dir id)
                     (fix-path  store-dir id)]]
            (.delete (io/file f)))
          (.delete (io/file (str store-dir "/" id))))))))

;; ---------------------------------------------------------------------------
;; Public: store-trace!
;; ---------------------------------------------------------------------------

(defn store-trace!
  "Persist a notable trace to disk."
  [scored-result scenario & [{:keys [store-dir corpus-max force?]
                              :or   {store-dir   default-store-dir
                                     corpus-max  default-corpus-max
                                     force?      false}}]]
  (let [score      (:trace-score scored-result 0)
        categories (ts/score-category scored-result)
        notable?   (or force?
                       (> score 0)
                       (seq categories))]
    (when notable?
      (let [id       (trace-id scenario scored-result)
            fixture  (try (let [f (te/export-trace-fixture scored-result scenario)]
                            (update f :metadata merge
                                    {"trace_kind"   (if (pos? (:invariant-violations (:metrics scored-result) 0))
                                                      "known_failure"
                                                      "fixed_regression")
                                     "scenario_type" (:scenario-id scenario)
                                     "issue_type"    (kw-val->str (:issue/type scored-result))
                                     "adversary_type" "adversarial-agent"
                                     "reconciliation_status" (if (pos? (:invariant-violations (:metrics scored-result) 0))
                                                               "partial-failure"
                                                               "fully-reconciled")
                                     "cdrs_note" (format "Scored %d with categories %s"
                                                         score (map name categories))}))
                          (catch Exception _ nil))
            meta-map {"cdrs_version" "0.1"
                      "trace-id"   id
                      "trace-score" score
                      "categories"  (mapv name categories)
                      "issue/type"  (kw-val->str (:issue/type scored-result))
                      "metrics"     (:metrics scored-result {})
                      "outcome"     (name (:outcome scored-result :unknown))
                      "score-components" (:score-components scored-result {})}]
        (write-json! (meta-path store-dir id)  meta-map)
        (write-json! (scen-path store-dir id)  scenario)
        (when fixture
          (write-json! (fix-path store-dir id) fixture))
        (evict-if-needed! store-dir corpus-max)
        id))))

;; ---------------------------------------------------------------------------
;; Public: select-top-n / select-by-category
;; ---------------------------------------------------------------------------

(defn select-top-n
  "Return the top n stored traces by :trace-score descending."
  ([n] (select-top-n n default-store-dir))
  ([n store-dir]
   (->> (load-store store-dir)
        (sort-by #(- (:trace-score % 0)))
        (take n)
        vec)))

(defn select-by-category
  "Return stored traces matching a category keyword (:top-profitable,
   :liveness-fail, :cascade, :abnormal-slash)."
  ([category] (select-by-category category default-store-dir))
  ([category store-dir]
   (->> (load-store store-dir)
        (filter #(contains? (set (:categories %)) (name category)))
        vec)))

;; ---------------------------------------------------------------------------
;; Public: promote-to-regression!
;; ---------------------------------------------------------------------------

(defn promote-to-regression!
  "Copy every fixture from store-dir into regression-dir.
   Returns the number of fixtures copied.

   Parameters:
     store-dir      (default \"results/traces\")
     regression-dir (default \"test/foundry/traces/regression\")"
  ([] (promote-to-regression! default-store-dir
                              "test/foundry/traces/regression"))
  ([store-dir regression-dir]
   (ensure-dir! regression-dir)
   (let [traces  (filter :has-fixture (load-store store-dir))]
     (doseq [{:keys [trace-id]} traces]
       (let [src  (fix-path store-dir trace-id)
             dst  (str regression-dir "/" trace-id ".json")]
         (io/copy (io/file src) (io/file dst))))
     (count traces))))

;; ---------------------------------------------------------------------------
;; Public: export-regression-manifest
;; ---------------------------------------------------------------------------

(defn export-regression-manifest
  "Write a manifest JSON listing all fixture file paths in regression-dir.
   Forge reads this to discover which files to load.

   Returns the number of fixtures listed."
  ([] (export-regression-manifest "test/foundry/traces/regression"))
  ([regression-dir]
   (ensure-dir! regression-dir)
   (let [dir   (io/file regression-dir)
         files (->> (.listFiles dir)
                    (filter #(str/ends-with? (.getName %) ".json"))
                    (filter #(not= (.getName %) "manifest.json"))
                    (mapv #(.getName %))
                    sort)]
     (write-json! (str regression-dir "/manifest.json")
                  {:version "1.0" :fixtures files})
     (count files))))

;; ---------------------------------------------------------------------------
;; CLI entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Promote stored adversarial traces to the Forge regression suite.

   Usage:
     clojure -M:trace-regress
     clojure -M:trace-regress <store-dir>
     clojure -M:trace-regress <store-dir> <regression-dir>

   Defaults:
     store-dir      = results/traces
     regression-dir = test/foundry/traces/regression"
  [& args]
  (let [store-dir      (or (first args)  default-store-dir)
        regression-dir (or (second args) "test/foundry/traces/regression")
        n-copied  (promote-to-regression! store-dir regression-dir)
        n-listed  (export-regression-manifest regression-dir)]
    (println (str "Promoted " n-copied " fixture(s) → " regression-dir))
    (println (str "Manifest updated: " n-listed " fixture(s) listed"))
    (System/exit 0)))
