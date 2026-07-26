(ns scripts.validate-notebook-registry
  "Validate data/notebooks.edn: all paths resolve, no duplicate IDs, no archived paths."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.notebook-support.common :as common]))

(def notebooks-dir (io/file "notebooks"))
(def data-file "data/notebooks.edn")
(def archived-ids
  #{:atlas-artifact :collusion-artifact :dispute-artifact :economic-artifact
    :golden-artifact :theory-falsification-artifact :workbench-production
    :not-appealed})

(defn registry []
  (let [parsed (common/read-edn data-file)]
    (:notebooks parsed)))

(defn -main [& args]
  (let [entries (registry)
        ids (map :id entries)
        paths (map :path entries)
        errors (atom [])]

    ;; 1. Unique IDs
    (let [dupes (keys (filter #(> (val %) 1) (frequencies ids)))]
      (when (seq dupes)
        (swap! errors conj (str "Duplicate notebook IDs: " dupes))))

    ;; 2. All paths resolve
    (doseq [p paths]
      (when-not (.exists (io/file p))
        (swap! errors conj (str "Path does not exist: " p))))

    ;; 3. No archived paths in registry
    (doseq [id ids]
      (when (contains? archived-ids id)
        (swap! errors conj (str "Archived notebook still in registry: " id))))

    ;; 4. All active .clj files have registry entries (except _template and index)
    (let [active (->> (.listFiles notebooks-dir)
                      (filter #(.isFile %))
                      (map #(.getName %))
                      (filter #(str/ends-with? % ".clj"))
                      (remove #(#{"_template.clj" "index.clj"} %))
                      (map #(str "notebooks/" %))
                      set)
          registered (set paths)
          missing (clojure.set/difference active registered)]
      (when (seq missing)
        (swap! errors conj (str "Active notebooks not in registry: " (vec (sort missing))))))

    ;; Report
    (println (str "Notebook registry validation: " (count entries) " entries"))
    (if (seq @errors)
      (do (doseq [e @errors] (println "  FAIL:" e))
          (System/exit 1))
      (do (println "  All checks passed.")
          (println (str "  " (count ids) " unique IDs"))
          (println (str "  " (count paths) " paths resolve"))
          (println (str "  No archived entries"))
          (println (str "  All active notebooks registered."))))))
