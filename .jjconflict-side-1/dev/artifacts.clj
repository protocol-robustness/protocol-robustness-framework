(ns dev.artifacts
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]))

(def default-artifact-root
  "results/test-artifacts")

(defn read-json
  [path]
  (with-open [r (io/reader path)]
    (json/read r :key-fn keyword)))

(defn latest-run-dir
  "Return the most recently modified run directory, or nil if none exists.
   Optionally filter by prefix (e.g., 'run-' for test runs)."
  ([]
   (latest-run-dir nil))
  ([prefix]
   (let [root (io/file default-artifact-root)]
     (when (.exists root)
       (->> (file-seq root)
            (filter #(and (.isDirectory %)
                         (or (nil? prefix)
                             (.startsWith (.getName %) prefix))))
            (sort-by #(.lastModified %) >)
            first
            str)))))

(defn artifact-path
  ([filename]
   (artifact-path (latest-run-dir) filename))
  ([run-dir filename]
   (str (io/file run-dir filename))))

(defn read-artifact
  [filename]
  (read-json (artifact-path filename)))

(defn explain-registry
  ([] (explain-registry (artifact-path "test-artifacts.json")))
  ([path]
   (let [registry (read-json path)
         artifacts (:artifacts registry)]
     {:path path
      :artifact-count (count artifacts)
      :schema-versions (frequencies (map :schema-version artifacts))
      :importance (frequencies (map :importance artifacts))
      :sample-artifacts (take 5 artifacts)})))

(defn tap-registry
  ([] (tap-registry (artifact-path "test-artifacts.json")))
  ([path]
   (let [summary (explain-registry path)]
     (tap> summary)
     summary)))
