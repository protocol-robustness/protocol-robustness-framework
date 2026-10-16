(ns resolver-sim.commands.validate
  "Structural validation: lint, fmt check, notebook checks.
   Port of bb validate."
  (:require [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- sh
  [& cmd]
  (let [proc (apply shell/sh cmd)]
    (when-not (str/blank? (:out proc))
      (println (:out proc)))
    (when-not (str/blank? (:err proc))
      (binding [*out* *err*] (println (:err proc))))
    (:exit proc)))

(defn- ok?
  [label f]
  (print (str "▶ " label "... "))
  (flush)
  (let [ok (try (zero? (f)) true (catch Exception _ false))]
    (println (if ok "PASS" "FAIL"))
    ok))

(defn notebook-report-valid?
  "True only when every expected notebook was discovered, loaded, and reported.
   This is independent of the task runner's subprocess exit propagation."
  [report]
  (let [rows (:namespaces report)
        expected (:expectedCount report)
        discovered (:discoveredCount report)]
    (and (= "passed" (:status report))
         (integer? expected)
         (= expected discovered)
         (= discovered (:attempted report))
         (= discovered (count rows))
         (zero? (or (:failed report) 0))
         (zero? (or (:timedOut report) 0))
         (empty? (:missing report))
         (empty? (:unexpected report))
         (every? #(= "passed" (:status %)) rows))))

(defn- latest-notebook-report []
  (let [directory (io/file "target/notebook-validation")
        reports (->> (or (.listFiles directory) [])
                     (filter #(and (.isFile %) (str/ends-with? (.getName %) ".json")))
                     (sort-by #(.lastModified %)))]
    (when-let [report-file (last reports)]
      (json/read-str (slurp report-file) :key-fn keyword))))

(defn- notebook-phase []
  (let [command-exit (sh "timeout" "--kill-after=5s" "2100s" "bb" "test:notebooks")
        report (try (latest-notebook-report) (catch Exception _ nil))]
    (if (and (zero? command-exit) (notebook-report-valid? report))
      0
      (do (println "Notebook validation report is missing or incomplete:" (pr-str (select-keys report
                                                                                               [:status :expectedCount :discoveredCount
                                                                                                :attempted :failed :timedOut :missing :unexpected])))
          1))))

(defn run
  "Run the structural validation pipeline: lint, notebook checks, SPEDS."
  [{:keys [_strict? _json?] :as _opts}]
  (println "Structural validation...")
  (let [lint-ok   (ok? "Lint (src:test)" #(sh "clojure" "-M:lint/core"))
        nb-ok     (ok? "Notebook support checks"
                       #(do (require '[resolver-sim.notebook-support.checks]) 0))
        all-nb-ok (ok? "All notebooks load"
                       notebook-phase)
        speds-ok  (ok? "SPEDS tests" #(sh "bb" "test:speds"))]
    (if (and lint-ok nb-ok all-nb-ok speds-ok)
      (do (println "VALIDATION PASSED")
          {:exit-code 0 :message "Validation passed"})
      (do (println "VALIDATION FAILED")
          {:exit-code 1 :message "Validation failed"}))))

(defn fmt-check
  "Check code formatting with cljfmt."
  [{:keys [_json?] :as _opts}]
  (println "Checking formatting...")
  (flush)
  (let [exit (sh "clojure" "-M:fmt/check")]
    (if (zero? exit)
      (do (println "  Format check passed")
          {:exit-code 0 :message "Format check passed"})
      {:exit-code 1 :message "Format check failed"})))

(defn lint
  "Lint source and test code with clj-kondo."
  [{:keys [_json?] :as _opts}]
  (println "Linting source...")
  (flush)
  (let [exit (sh "clojure" "-M:lint/core")]
    (if (zero? exit)
      (do (println "  Lint passed")
          {:exit-code 0 :message "Lint passed"})
      {:exit-code 1 :message "Lint failed"})))
