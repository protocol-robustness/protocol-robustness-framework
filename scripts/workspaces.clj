(ns scripts.workspaces
  "List and validate workspace directories."
  (:gen-class))

(defn- ws-dir []
  (java.io.File. "workspaces"))

(defn- workspace-entries []
  (when-let [d (ws-dir)]
    (when (.exists d)
      (sort (filter (fn [^java.io.File f] (.isDirectory f)) (.listFiles d))))))

(defn list-workspaces
  "Print available workspaces with descriptions."
  []
  (if-let [entries (workspace-entries)]
    (do
      (println "Available workspaces:")
      (println "---------------------")
      (doseq [f entries]
        (let [readme (java.io.File. f "README.md")
              deps   (java.io.File. f "deps.edn")
              desc   (when (.exists readme)
                       (try (-> (slurp readme)
                                clojure.string/split-lines
                                (->> (remove empty?) first))
                            (catch Exception _ "")))
              has-deps (when (.exists deps) " deps.edn ✓")]
          (println (str "  " (.getName f))
                   (str "  " (or has-deps ""))
                   (str "  " (or desc "")))))
      (println)
      (println "External archives:")
      (println "  ~/prf-runs/<run-id>/     Forensic run output")
      (println "  ~/prf-private/<id>/      Private discovery workspace"))
    (println "No workspaces directory found.")))

(defn doctor
  "Validate workspace structure: README, deps.edn, path resolution."
  []
  (if-let [entries (workspace-entries)]
    (doseq [ws entries]
      (let [name (.getName ws)
            readme (java.io.File. ws "README.md")
            deps-file (java.io.File. ws "deps.edn")]
        (println (str "▶ " name))
        (let [readme-ok (and (.exists readme) (> (.length readme) 0))
              msg (if readme-ok "README.md ✓" "README.md MISSING or empty")]
          (println (str "  " msg)))
        (if (.exists deps-file)
          (try
            (let [deps (slurp deps-file)]
              (println "  deps.edn ✓")
              (let [path-pattern (re-pattern "\"\\.\\./\\.\\./[^\"]+\"")]
                (doseq [line (clojure.string/split-lines deps)]
                  (when-let [m (re-find path-pattern line)]
                    (let [path (subs m 1 (dec (count m)))
                          abs (java.io.File. (.getParent deps-file) path)]
                      (if (.exists abs)
                        (println (str "    path " path " ✓"))
                        (println (str "    path " path " NOT FOUND"))))))))
            (catch Exception e
              (println (str "  deps.edn ERROR: " (.getMessage e)))))
          (println "  no deps.edn"))
        (println)))
    (println "No workspaces directory found."))
  (println "Doctor complete."))

(defn -main
  [& args]
  (case (first args)
    "list" (list-workspaces)
    "doctor" (doctor)
    (println "Usage: clojure -M -m scripts.workspaces <list|doctor>")))
