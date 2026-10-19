(ns scripts.validate-notebook-registry
  "Validate data/notebooks.edn: all paths resolve, no duplicate IDs, no archived paths,
   and every source-level reference to a notebook file resolves."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.notebook-support.common :as common])
  (:import [java.nio.file Files Paths LinkOption]))

(def ^:private notebook-index-resource (io/resource "notebooks/index.clj"))
(def ^:private package-root
  (when notebook-index-resource
    (.getParent (.getParent (Paths/get (.toURI notebook-index-resource))))))
(def notebooks-dir
  (when package-root (.toFile (.resolve package-root "notebooks"))))
(def data-file "data/notebooks.edn")
(def scan-dirs
  "Source directories to scan for notebook file references."
  ["src" "protocols_src" "scripts" "docs"])
(def archived-ids
  ;; IDs that were once active and are now retired.
  ;; Entries with :notebook/status :archived may exist in the registry
  ;; and must carry :notebook/replaced-by pointing to an active ID.
  #{:atlas-artifact :collusion-artifact :dispute-artifact :economic-artifact
    :golden-artifact :theory-falsification-artifact :workbench-production
    :not-appealed})

(defn registry []
  (common/read-edn data-file))

(defn- archived? [entry]
  (= :archived (:status entry)))

(defn- active-entries [entries]
  (remove archived? entries))

(defn- active-ids [entries]
  (set (map :id (active-entries entries))))

(defn -main [& args]
  (let [registry-data (registry)
        package-registry (when package-root
                           (edn/read-string
                            (slurp (.toFile (.resolve package-root "data/notebooks.edn")))))
        entries (:notebooks registry-data)
        archived (filter archived? (:archived-notebooks registry-data))
        active (active-entries entries)
        ids (map :id entries)
        all-ids (concat ids (map :id archived))
        active-ids-set (active-ids entries)
        active-paths (map :path active)
        errors (atom [])]

    (when-not package-root
      (swap! errors conj "Extracted notebook package is unavailable; select the :notebooks alias."))
    (when (and package-registry (not= registry-data package-registry))
      (swap! errors conj "data/notebooks.edn differs from the pinned prf-notebooks registry."))

    ;; 1. Unique IDs (across all entries including archived)
    (let [dupes (keys (filter #(> (val %) 1) (frequencies all-ids)))]
      (when (seq dupes)
        (swap! errors conj (str "Duplicate notebook IDs: " dupes))))

    ;; 2. Active entry paths must resolve; archived paths are expected missing
    (doseq [p active-paths]
      (when-not (and package-root
                    (Files/isRegularFile (.resolve package-root p) (make-array LinkOption 0)))
        (swap! errors conj (str "Active notebook path does not exist: " p))))

    ;; 3. Archived metadata must point to an active replacement ID
    (doseq [entry archived]
      (let [id (:id entry)
            replaced-by (:replaced-by entry)]
        (when-not replaced-by
          (swap! errors conj (str "Archived entry " id " is missing :replaced-by")))
        (when (and replaced-by (not (contains? active-ids-set replaced-by)))
          (swap! errors conj (str "Archived entry " id " has :replaced-by " replaced-by
                                  " which is not an active notebook ID")))))

    ;; 4. No active entry uses an archived ID
    (doseq [e active]
      (when (contains? archived-ids (:id e))
        (swap! errors conj (str "Active notebook uses archived ID: " (:id e)))))

    ;; 5. All active .clj files have registry entries (except _template and index)
    (let [disk-files (->> (.listFiles notebooks-dir)
                          (filter #(.isFile %))
                          (map #(.getName %))
                          (filter #(str/ends-with? % ".clj"))
                          (remove #(#{"_template.clj" "index.clj"} %))
                          (map #(str "notebooks/" %))
                          set)
          registered (set active-paths)
          missing (clojure.set/difference disk-files registered)]
      (when (seq missing)
        (swap! errors conj (str "Active notebooks not in registry: " (vec (sort missing))))))

    ;; 6. All source-level references to notebook files resolve
    ;; The leading boundary prevents matching a longer path such as
    ;; "src/notebooks/serve.clj" (a real source file, not a repo-root notebook).
    (let [notebook-ref-pat #"(?<![\w/-])notebooks/[\w_-]+\.clj"
          refs (atom {})]
      (doseq [dir scan-dirs
              :when (.exists (io/file dir))
              f (file-seq (io/file dir))
              :when (.isFile f)
              :let [ext (last (str/split (.getName f) #"\."))]
              :when (#{"clj" "py" "md" "edn"} ext)]
        (let [content (slurp f)
              rel-path (.getPath f)]
          (doseq [m (re-seq notebook-ref-pat content)]
            (when-not (and package-root
                          (Files/isRegularFile (.resolve package-root m) (make-array LinkOption 0)))
              (swap! refs assoc m (conj (get @refs m []) rel-path))))))
      (doseq [[notebook-file sources] (sort @refs)]
        (swap! errors conj (str "Notebook file not found: " notebook-file
                                " (referenced from " (str/join ", " sources) ")"))))

    ;; 7. :presentation editorial layer (drives the exported site)
    (let [theme-keys (set (keys (:themes (common/read-edn data-file))))
          all-ids    (set (map :id entries))]
      (doseq [e entries
              :let [id (:id e)
                    pres (:presentation e)]]
        (when pres
          (let [theme (:theme pres)
                kind (:kind pres)
                level (:audience-level pres)
                deeper (:deeper-id pres)
                featured? (:featured? pres)
                rank (:start-here-rank pres)]
            (when (and theme (not (contains? theme-keys theme)))
              (swap! errors conj (str "Notebook " id " has unknown :presentation.theme " theme)))
            (when (and kind (not (#{:demo :analysis :report :tool} kind)))
              (swap! errors conj (str "Notebook " id " has unknown :presentation.kind " kind)))
            (when (and level (not (#{:intro :intermediate :deep-dive} level)))
              (swap! errors conj (str "Notebook " id " has unknown :presentation.audience-level " level)))
            (when (and deeper (not (contains? all-ids deeper)))
              (swap! errors conj (str "Notebook " id " has :presentation.deeper-id " deeper
                                      " which is not a registry notebook ID"))))
          (when (and (:featured? pres) (not (and (:start-here-rank pres) (pos? (:start-here-rank pres)))))
            (swap! errors conj (str "Featured notebook " id " is missing a positive :start-here-rank"))))))

    ;; 8. Featured notebooks must have unique start-here ranks
    (let [ranks (->> entries
                     (keep #(when (:featured? (or (:presentation %) {}))
                              (:start-here-rank (:presentation %))))
                     (frequencies)
                     (filter #(> (val %) 1))
                     (map key))]
      (when (seq ranks)
        (swap! errors conj (str "Duplicate :start-here-rank values: " (vec (sort ranks))))))

    ;; Report
    (println (str "Notebook registry validation: " (count all-ids) " entries"
                  " (" (count active) " active, " (count archived) " archived)"))
    (if (seq @errors)
      (do (doseq [e @errors] (println "  FAIL:" e))
          (System/exit 1))
      (do (println "  All checks passed.")
          (println (str "  " (count all-ids) " unique IDs"))
          (println (str "  " (count active-paths) " active paths resolve"))
          (println (str "  " (count archived) " archived entries with replacement metadata"))
          (println (str "  All active notebooks registered."))
          (println (str "  All source-level references resolve."))
          (println (str "  All :presentation themes/kinds/levels/deeper-ids/ranks valid."))))))
