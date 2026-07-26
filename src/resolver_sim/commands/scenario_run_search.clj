(ns resolver-sim.commands.scenario-run-search
  "Ad-hoc scenario search: find scenarios matching text, run each in an
   isolated run root, and report failures.  Port of the bb run:scenario:search
   task — each match shells out to a separate clojure subprocess."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn- scenario-files []
  (sort (filter (fn [path]
                  (or (str/ends-with? path ".json")
                      (str/ends-with? path ".edn")))
                (map (fn [f] (.getPath ^java.io.File f))
                     (filter (fn [^java.io.File f] (.isFile f))
                             (file-seq (io/file "scenarios")))))))

(defn- matches-selector? [path selector-lc]
  (str/includes? (str (str/lower-case path) "\n"
                      (str/lower-case (slurp path)))
                 selector-lc))

(defn- run-scenario [path opts]
  (println (str "Running " path))
  (flush)
  (let [result (apply sh "clojure" "-M:with-sew" "-m" "resolver-sim.commands.scenario"
                      path
                      (map name opts))]
    {:path path
     :exit (:exit result)
     :out (:out result)
     :err (:err result)}))

(defn run
  "Search scenarios by text selector and run each in an isolated subprocess.
   opts may contain :report-format or other flags forwarded to each run."
  [{:keys [selector] :as opts}]
  (let [rejected #{"--run-root" "--output-dir" "--scenario-output-dir" "--save-output"}]
    (when (some rejected (keys opts))
      (println "Scenario search creates one generated run root per match;"
               "run individual scenarios to choose --run-root")
      (System/exit 1)))
  (let [selector-lc (str/lower-case selector)
        matched (filter #(matches-selector? % selector-lc) (scenario-files))
        _ (when (empty? matched)
            (println (str "No scenarios matched selector: " selector))
            (System/exit 1))
        results (mapv #(run-scenario % opts) matched)
        failed (filterv #(not (zero? (:exit %))) results)]
    (doseq [r results]
      (when-not (zero? (:exit r))
        (println (str "  FAILED: " (:path r)))))
    (when (seq failed)
      (println (str (count failed) " scenario(s) failed"))
      (System/exit 1))))
