(ns resolver-sim.commands.validate
  "Structural validation: lint, fmt check, notebook checks.
   Port of bb validate."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
    :argv ["clojure" "-M:with-sew" "-m" "scripts.test-notebooks"]
    :timeout-ms 900000}
   {:id :speds
    :argv ["bb" "test:speds"]
    :timeout-ms 900000}])

(defn- terminate-process-tree!
  [^Process process]
  (let [descendants (.descendants (.toHandle process))
        children (with-open [stream descendants]
                   (doall (iterator-seq (.iterator stream))))]
    (doseq [^java.lang.ProcessHandle child (reverse children)]
      (.destroyForcibly child)))
  (.destroyForcibly process)
  (.waitFor process 5 TimeUnit/SECONDS)
  nil)

(defn- run-process
  "Run argv with separate retained output files and a hard timeout.

   The exit code is the process result. On Unix, values >=128 are marked as
   signal-like because shells commonly encode signal termination as 128+N; the
   numeric status is retained without claiming that encoding proves a signal."
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
            (merge base {:exit-code nil
                         :terminal :timed-out
                         :exit-cause :timeout}))))
      (catch Exception e
        (merge base {:exit-code nil
                     :terminal :failed
                     :exit-cause :process-start-or-execution-error
                     :error (.getMessage e)}))
      (finally
        (when-let [^Process process @process-holder]
          (when (.isAlive process)
            (terminate-process-tree! process)))))))

(defn- unique-run-directory
  [output-root]
  (let [root (.toAbsolutePath (.normalize (.toPath (io/file output-root))))
        stamp (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss.SSS'Z'")
                       (.atZone (Instant/now) java.time.ZoneOffset/UTC))
        run-dir (.resolve root (str stamp "-" (UUID/randomUUID)))]
    (Files/createDirectories root (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createDirectory run-dir (make-array java.nio.file.attribute.FileAttribute 0))
    run-dir))

(defn- write-report!
  [^Path run-dir report]
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

(defn- stream-log!
  [path destination]
  (let [file (.toFile ^Path path)]
    (when (and (.exists file) (pos? (.length file)))
      (with-open [reader (io/reader file)]
        (binding [*out* destination]
          (doseq [line (line-seq reader)]
            (println line)))))))

(defn- run-phases
  [phases output-root execute-process]
  (let [run-dir (unique-run-directory output-root)
        report-path (.resolve run-dir "validation-report.edn")
        started-at (str (Instant/now))
        base-report {:schema :prf/validation-run.v1
                     :status :running
                     :started-at started-at
                     :phases []}]
    (write-report! run-dir base-report)
    (let [phase-results
          (reduce
           (fn [results {:keys [id argv timeout-ms]}]
             (let [name (name id)
                   stdout-name (str name ".stdout.log")
                   stderr-name (str name ".stderr.log")
                   stdout-path (.resolve run-dir stdout-name)
                   stderr-path (.resolve run-dir stderr-name)
                   phase-start (System/nanoTime)
                   running (merge base-report
                                  {:phases (conj results
                                                 {:id id :argv argv :timeout-ms timeout-ms
                                                  :terminal :running
                                                  :stdout stdout-name
                                                  :stderr stderr-name})})]
               (write-report! run-dir running)
               (spit (.toFile stdout-path) "")
               (spit (.toFile stderr-path) "")
               (print (str "▶ " name "... "))
               (flush)
               (let [execution (try
                                 (execute-process argv timeout-ms stdout-path stderr-path)
                                 (catch Exception e
                                   {:argv argv :timeout-ms timeout-ms
                                    :terminal :failed
                                    :exit-cause :runner-exception
                                    :exit-code nil
                                    :error (.getMessage e)}))
                     result (merge execution
                                   {:id id :argv (vec argv) :timeout-ms timeout-ms
                                    :duration-ms (quot (- (System/nanoTime) phase-start) 1000000)
                                    :stdout stdout-name :stderr stderr-name})
                     next-results (conj results result)]
                 (println (if (= :passed (:terminal result)) "PASS"
                              (if (= :timed-out (:terminal result)) "TIMEOUT" "FAIL")))
                 (stream-log! stdout-path *out*)
                 (stream-log! stderr-path *err*)
                 (write-report! run-dir
                                (merge base-report
                                       {:phases next-results
                                        :status :running}))
                 next-results)))
           [] phases)
          passed? (and (= (count phases) (count phase-results))
                       (every? #(= :passed (:terminal %)) phase-results))
          final-report {:schema :prf/validation-run.v1
                        :status (if passed? :passed :failed)
                        :started-at started-at
                        :finished-at (str (Instant/now))
                        :phases phase-results}]
      (write-report! run-dir final-report)
      (println (str "Validation report: " (.toString report-path)))
      (if passed?
        (do (println "VALIDATION PASSED")
            {:exit-code 0 :message "Validation passed"
             :report-path (.toString report-path)
             :report final-report})
        (do (println "VALIDATION FAILED")
            {:exit-code 1 :message "Validation failed"
             :report-path (.toString report-path)
             :report final-report})))))

(defn run
  "Run the structural validation pipeline: lint, notebook checks, SPEDS."
  [{:keys [_strict? _json? validate/output-dir] :as _opts}]
  (println "Structural validation...")
  (run-phases validation-phases
              (or output-dir (System/getenv "PRF_VALIDATION_OUTPUT_DIR")
                  "target/validation")
              run-process))

(defn fmt-check
  "Check code formatting with cljfmt."
  [{:keys [json?] :as opts}]
  (println "Checking formatting...")
  (flush)
  (let [exit (sh "clojure" "-M:fmt/check")]
    (if (zero? exit)
      (do (println "  Format check passed")
          {:exit-code 0 :message "Format check passed"})
      {:exit-code 1 :message "Format check failed"})))

(defn lint
  "Lint source and test code with clj-kondo."
  [{:keys [json?] :as opts}]
  (println "Linting source...")
  (flush)
  (let [exit (sh "clojure" "-M:lint/core")]
    (if (zero? exit)
      (do (println "  Lint passed")
          {:exit-code 0 :message "Lint passed"})
      {:exit-code 1 :message "Lint failed"})))
