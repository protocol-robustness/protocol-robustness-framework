(ns resolver-sim.commands.notebook-focus
  "Manage the Clerk notebook focus file (results/.notebook-focus)."
  (:require [resolver-sim.hash.reference :as hash-ref]))

(def ^:private focus-file hash-ref/notebook-focus-path)
(def ^:private runs-root hash-ref/results-runs-dir)

(defn- available-run-ids
  "Return sorted list of run directory names under runs-root."
  []
  (let [root (java.io.File. runs-root)]
    (when (.exists root)
      (sort (map (fn [^java.io.File f] (.getName f))
                 (filter (fn [^java.io.File f] (.isDirectory f))
                         (.listFiles root)))))))

(defn- run-exists?
  "True when run-id is a simple identifier with a matching directory under runs-root."
  [run-id]
  (let [dir (java.io.File. runs-root run-id)]
    (and (.exists dir) (.isDirectory dir))))

(defn- valid-run-id?
  "Reject path-like IDs that contain separators or traverse upward."
  [run-id]
  (and (seq run-id)
       (not (re-find #"[/\\]" run-id))
       (not= ".." run-id)
       (not (.isAbsolute (java.io.File. run-id)))))

(defn focus
  "Pin the notebook to a specific run-id."
  [{:keys [run-id] :as opts}]
  (cond
    (not (seq run-id))
    (do (println "Usage: bb notebook:focus <run-id>")
        {:exit-code 1 :message "Missing run-id"})

    (not (valid-run-id? run-id))
    (do (println (str "Invalid run-id: " run-id " — must be a simple directory name"))
        {:exit-code 1 :message "Invalid run-id"})

    (not (run-exists? run-id))
    (do (println (str "Run not found: " run-id))
        {:exit-code 1 :message "Run not found"})

    :else
    (do (clojure.java.io/make-parents focus-file)
        (spit focus-file run-id)
        (println "Notebook focused on run:" run-id)
        (println "Reload the Clerk notebook to see this run.")
        {:exit-code 0 :message (str "Focused on " run-id)})))

(defn latest
  "Reset the notebook to always show the latest run."
  [_]
  (let [f (java.io.File. focus-file)]
    (if (.delete f)
      (println "Notebook reset to latest run.")
      (println "Already showing latest run (no focus file found).")))
  {:exit-code 0})

(defn runs
  "List all available run-ids."
  [_]
  (let [ids (available-run-ids)]
    (if (seq ids)
      (doseq [id ids] (println id))
      (println "No runs found. Run bb run:scenario <scenario> first.")))
  {:exit-code 0})
