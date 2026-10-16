(ns resolver-sim.commands.validate
  "Structural validation: lint, fmt check, notebook checks.
   Port of bb validate."
  (:require [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file AtomicMoveNotSupportedException CopyOption Files Path StandardCopyOption]
           [java.time Instant]
           [java.time.format DateTimeFormatter]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(defn- sh
  [& cmd]
  (let [proc (apply shell/sh cmd)]
    (when-not (str/blank? (:out proc))
      (println (:out proc)))
    (when-not (str/blank? (:err proc))
      (binding [*out* *err*] (println (:err proc))))
    (:exit proc)))

(def ^:private validation-phases
  [{:id :lint
    :argv ["clojure" "-M:lint/core"]
    :timeout-ms 900000}
   {:id :notebook-support
    :argv ["clojure" "-M:with-sew" "-e"
           "(require 'resolver-sim.notebook-support.checks)"]
    :timeout-ms 120000}
   {:id :all-notebooks
    :argv ["bb" "test:notebooks"]
    :timeout-ms 2100000}
   {:id :speds
    :argv ["bb" "test:speds"]
    :timeout-ms 900000}])

(defn- terminate-process-tree! [^Process process]
  (let [descendants (.descendants (.toHandle process))
        children (with-open [stream descendants]
                   (doall (iterator-seq (.iterator stream))))]
    (doseq [^java.lang.ProcessHandle child (reverse children)]
      (.destroyForcibly child)))
  (.destroyForcibly process)
  (.waitFor process 5 TimeUnit/SECONDS)
  nil)

(defn- run-process
  "Run argv with separately retained logs and a hard timeout."
  [argv timeout-ms stdout-file stderr-file]
  (let [process-holder (atom nil)
        base {:argv (vec argv) :timeout-ms timeout-ms}]
    (try
      (spit (.toFile ^Path stdout-file) "")
      (spit (.toFile ^Path stderr-file) "")
      (let [builder (doto (ProcessBuilder. ^java.util.List (vec argv))
                      (.redirectOutput (.toFile ^Path stdout-file))
                      (.redirectError (.toFile ^Path stderr-file)))
            process (.start builder)]
        (reset! process-holder process)
        (if (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
          (let [exit-code (.exitValue process)]
            (merge base
                   {:exit-code exit-code
                    :terminal (if (zero? exit-code) :passed :failed)
                    :exit-cause (cond
                                  (zero? exit-code) :normal-exit
                                  (>= exit-code 128) :nonzero-signal-like-status
                                  :else :nonzero-exit)}))
          (do
            (terminate-process-tree! process)
            (merge base {:exit-code nil :terminal :timed-out :exit-cause :timeout}))))
      (catch Exception error
        (merge base {:exit-code nil :terminal :failed
                     :exit-cause :process-start-or-execution-error
                     :error (.getMessage error)}))
      (finally
        (when-let [^Process process @process-holder]
          (when (.isAlive process)
            (terminate-process-tree! process)))))))

(defn- unique-run-directory [output-root]
  (let [root (.toAbsolutePath (.normalize (.toPath (io/file output-root))))
        stamp (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss.SSS'Z'")
                       (.atZone (Instant/now) java.time.ZoneOffset/UTC))
        run-dir (.resolve root (str stamp "-" (UUID/randomUUID)))]
    (Files/createDirectories root (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createDirectory run-dir (make-array java.nio.file.attribute.FileAttribute 0))
    run-dir))

(defn- write-report! [^Path run-dir report]
  (let [target (.resolve run-dir "validation-report.edn")
        temporary (.resolve run-dir "validation-report.edn.tmp")]
    (spit (.toFile temporary) (str (pr-str report) "\n"))
    (try
      (Files/move temporary target
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING]))
      (catch AtomicMoveNotSupportedException _
        (Files/move temporary target
                    (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    target))

(defn- stream-log! [path destination]
  (let [file (.toFile ^Path path)]
    (when (and (.exists file) (pos? (.length file)))
      (with-open [reader (io/reader file)]
        (binding [*out* destination]
          (doseq [line (line-seq reader)] (println line)))))))

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

(defn- latest-notebook-report-file []
  (let [directory (io/file "target/notebook-validation")
        reports (->> (or (.listFiles directory) [])
                     (filter #(and (.isFile %) (str/ends-with? (.getName %) ".json")))
                     (sort-by #(.lastModified %)))]
    (last reports)))

(defn- notebook-reports-before-run []
  (set (map #(.getName ^java.io.File %)
            (or (.listFiles (io/file "target/notebook-validation")) []))))

(defn- run-phases [phases output-root execute-process]
  (let [run-dir (unique-run-directory output-root)
        report-path (.resolve run-dir "validation-report.edn")
        started-at (str (Instant/now))
        base-report {:schema :prf/validation-run.v1 :status :running
                     :started-at started-at :phases []}]
    (write-report! run-dir base-report)
    (let [phase-results
          (reduce
           (fn [results {:keys [id argv timeout-ms]}]
             (let [phase-name (name id)
                   stdout-name (str phase-name ".stdout.log")
                   stderr-name (str phase-name ".stderr.log")
                   stdout-path (.resolve run-dir stdout-name)
                   stderr-path (.resolve run-dir stderr-name)
                   phase-start (System/nanoTime)
                   notebook-reports-before (when (= id :all-notebooks)
                                             (notebook-reports-before-run))
                   running (merge base-report
                                  {:phases (conj results
                                                 {:id id :argv argv :timeout-ms timeout-ms
                                                  :terminal :running :stdout stdout-name
                                                  :stderr stderr-name})})]
               (write-report! run-dir running)
               (print (str "▶ " phase-name "... "))
               (flush)
               (let [execution (try
                                 (execute-process argv timeout-ms stdout-path stderr-path)
                                 (catch Exception error
                                   {:argv argv :timeout-ms timeout-ms :terminal :failed
                                    :exit-cause :runner-exception :exit-code nil
                                    :error (.getMessage error)}))
                     execution (if (= id :all-notebooks)
                                 (let [report-file (latest-notebook-report-file)
                                       fresh-report? (and report-file
                                                          (not (contains? notebook-reports-before
                                                                          (.getName ^java.io.File report-file))))
                                       report (when fresh-report?
                                                (try (json/read-str (slurp report-file) :key-fn keyword)
                                                     (catch Exception _ nil)))
                                       valid? (and (= :passed (:terminal execution))
                                                   fresh-report?
                                                   (notebook-report-valid? report))]
                                   (cond-> (assoc execution :notebook-report-status
                                                  (if valid? :passed :invalid-or-missing))
                                     (not valid?)
                                     (assoc :terminal :failed
                                            :exit-cause :notebook-report-invalid
                                            :notebook-report-summary
                                            (select-keys report [:status :expectedCount :discoveredCount
                                                                 :attempted :failed :timedOut :missing :unexpected]))))
                                 execution)
                     result (merge execution
                                   {:id id :argv (vec argv) :timeout-ms timeout-ms
                                    :duration-ms (quot (- (System/nanoTime) phase-start) 1000000)
                                    :stdout stdout-name :stderr stderr-name})
                     next-results (conj results result)]
                 (println (case (:terminal result)
                            :passed "PASS" :timed-out "TIMEOUT" "FAIL"))
                 (stream-log! stdout-path *out*)
                 (stream-log! stderr-path *err*)
                 (write-report! run-dir (assoc base-report :phases next-results))
                 next-results)))
           [] phases)
          passed? (and (= (count phases) (count phase-results))
                       (every? #(= :passed (:terminal %)) phase-results))
          final-report {:schema :prf/validation-run.v1
                        :status (if passed? :passed :failed)
                        :started-at started-at :finished-at (str (Instant/now))
                        :phases phase-results}]
      (write-report! run-dir final-report)
      (println (str "Validation report: " (.toString report-path)))
      (if passed?
        (do (println "VALIDATION PASSED")
            {:exit-code 0 :message "Validation passed"
             :report-path (.toString report-path) :report final-report})
        (do (println "VALIDATION FAILED")
            {:exit-code 1 :message "Validation failed"
             :report-path (.toString report-path) :report final-report})))))

(defn run
  "Run the structural validation pipeline: lint, notebook checks, SPEDS."
  [{:keys [_strict? _json? validate/output-dir] :as _opts}]
  (println "Structural validation...")
  (run-phases validation-phases
              (or output-dir (System/getenv "PRF_VALIDATION_OUTPUT_DIR") "target/validation")
              run-process))

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
