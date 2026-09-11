(ns resolver-sim.manifest.run
  "Enumerate and load run manifests from results/runs/ and results/test-artifacts/.

   All functions are pure after the initial filesystem read.
   Returns plain maps; no db deps.

   Extracted from resolver-sim.notebooks.manifest.loader to be
   reusable outside notebook context (e.g. demo runner)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.manifest.common :as common]))

(def runs-root (evcfg/runs-root))
(def latest-dir (evcfg/artifact-dir))

;; ── internal helpers ──────────────────────────────────────────────────────────

(defn- run-dir? [f]
  (and (.isDirectory f)
       (-> (io/file f "test-run.json") .exists)))

(defn- dir->slug [dir-name]
  (let [n (count dir-name)]
    (if (> n 16)
      (subs dir-name 0 (- n 16))
      dir-name)))

(defn- manifest->summary [run-dir manifest]
  {:run-id      (or (:run_id manifest) (last (str/split (.getName run-dir) #"/")))
   :slug        (dir->slug (.getName run-dir))
   :status      (or (get-in manifest [:suite :status])
                    (get-in manifest [:targets 0 :status])
                    "unknown")
   :suite-id    (or (get-in manifest [:suite :id]) "unknown")
   :scenario    (or (get-in manifest [:suite :scenario])
                    (get-in manifest [:suite :selector]))
   :duration-ms (get manifest :duration_ms)
   :created-at  (or (:created_at manifest) "")
   :git-commit  (get-in manifest [:framework :git_commit])
   :dir         (.getPath run-dir)})

;; ── public API ────────────────────────────────────────────────────────────────

(defn list-runs
  "Return a vector of run-summary maps, sorted newest-first, for all runs
  found under results/runs/. Returns [] if the directory does not exist."
  []
  (let [root (io/file runs-root)]
    (if-not (.exists root)
      []
      (->> (.listFiles root)
           (filter run-dir?)
           (keep (fn [d]
                   (when-let [m (common/read-json (str (.getPath d) "/test-run.json"))]
                     (manifest->summary d m))))
           (sort-by :created-at #(compare %2 %1))
           vec))))

(defn load-run
  "Load all 4 artifacts for run-id from results/runs/<slug>-<run-id>/,
  searching by matching the run-id suffix.
  Returns {:manifest ... :summary ... :registry ... :classification ...} or nil."
  [run-id]
  (let [root (io/file runs-root)]
    (when (.exists root)
      (some (fn [d]
              (when (and (.isDirectory d)
                         (str/ends-with? (.getName d) run-id))
                (let [base (.getPath d)]
                  {:manifest        (common/read-json (str base "/" (evcfg/artifact-file :test-run)))
                   :summary         (common/read-json (str base "/" (evcfg/artifact-file :test-summary)))
                   :registry        (common/read-json (str base "/test-artifacts.json"))
                   :classification  (common/read-json (str base "/" (evcfg/artifact-file :claimable-classification)))
                   :dir             base})))
            (.listFiles root)))))

(defn artifact-by-id
  "Look up an artifact entry by ID in the loaded registry map."
  [run artifact-id]
  (let [registry (:registry run)]
    (some #(when (= (:id %) artifact-id) %) (:artifacts registry))))

(defn load-artifact-by-id
  "Look up and load an artifact's file content by ID."
  [run artifact-id]
  (when-let [art (artifact-by-id run artifact-id)]
    (common/read-json (str (:dir run) "/" (:path art)))))

(defn load-latest
  "Load the 4 canonical artifacts from results/test-artifacts/.
  Returns nil fields for files that don't exist yet."
  []
  {:manifest        (common/read-json (str latest-dir "/" (evcfg/artifact-file :test-run)))
   :summary         (common/read-json (str latest-dir "/" (evcfg/artifact-file :test-summary)))
   :registry        (common/read-json (str latest-dir "/test-artifacts.json"))
   :classification  (common/read-json (str latest-dir "/" (evcfg/artifact-file :claimable-classification)))
   :dir             latest-dir})

(defn load-focused
  "Load the run specified in results/.notebook-focus (contains a run-id),
  falling back to load-latest if the file is absent or the run-id not found."
  []
  (let [focus-file (io/file hash-ref/notebook-focus-path)]
    (if (.exists focus-file)
      (let [run-id (str/trim (slurp focus-file))]
        (or (load-run run-id)
            (load-latest)))
      (load-latest))))

(defn run->status-indicator
  "Derive a status keyword for a loaded run map.
  Checks: overall_status from summary, artifact hash presence, signature.

  Returns one of :verified :stale :divergent :unsigned :unknown."
  [{:keys [manifest summary registry]}]
  (cond
    (nil? manifest)                               :unknown
    (= "fail" (get summary :overall_status))      :divergent
    (nil? (get-in manifest [:framework :git_commit])) :stale
    (empty? (get registry :artifacts))            :stale
    :else                                          :unsigned))

(defn latest-status
  "Convenience: load-latest + run->status-indicator."
  []
  (-> (load-latest) run->status-indicator))
