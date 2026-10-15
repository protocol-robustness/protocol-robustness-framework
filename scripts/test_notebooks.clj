(ns scripts.test-notebooks
  "Verify every notebook namespace loads.

   Each notebook is loaded in its OWN isolated JVM, with a per-notebook timeout.
   This is deliberate: loading notebooks in one JVM accumulates namespace
   state and memory, which can produce spurious loader errors on heavy
   notebooks. Bounded parallelism retains fresh-JVM isolation without making
   total duration the sum of 48+ cold JVM launches. Each namespace has a hard
   timeout and each run retains a machine-readable per-namespace report.

   Exit code is 0 when every notebook loads, 1 otherwise."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]
           [java.util UUID]
           [java.util.concurrent Callable Executors LinkedBlockingQueue TimeUnit]))

(def notebooks-dir (io/file "notebooks"))

(def ^:const per-notebook-timeout-seconds
  "Maximum seconds to wait for a single notebook's subprocess. This is a
   per-task failure budget, not an aggregate gate timeout. The enclosing
   bb validate phase has its own 900-second hard budget and retains progress
   logs if that budget expires."
  600)

(def ^:const max-parallel-notebooks
  "Keep notebook evaluation isolated while limiting concurrent JVM memory and CPU use."
  4)

(def ^:const max-child-output-chars
  "Bound retained subprocess output while continuing to drain child pipes."
  1048576)

(def ^:const classpath-args
  "Add the notebook root through a temporary alias while preserving all source
   paths declared by the current checkout's deps.edn. Some notebook namespaces
   are rooted directly under `notebooks`, rather than under `notebooks.*`."
  ["-Sdeps"
   "{:aliases {:notebooks {:extra-paths [\"notebooks\"]}}}"
   "-M:with-sew:notebooks"])

(defn read-ns-form [^java.io.File f]
  (with-open [rdr (io/reader f)]
    (some (fn [line]
            (when-let [m (re-find #"\(ns\s+(\S+)" line)]
              (symbol (last m))))
          (line-seq rdr))))

(defn notebook-files []
  (sort
   (filter
    (fn [f]
      (and (.isFile f)
           (re-find #"\.clj$" (.getName f))
           (not (str/includes? (.getPath f) "/archive/"))
           (not (str/includes? (.getName f) "_template"))))
    (file-seq notebooks-dir))))

(defn- read-all
  "Read a stream to a string in a background thread (so a noisy subprocess
   cannot block on a full pipe buffer). Returns a string."
  [^java.io.InputStream is]
  (let [sb (StringBuilder.)
        truncated? (atom false)
        t (Thread. (fn []
                     (with-open [r (io/reader is)]
                       (loop []
                         (when-let [line (.readLine r)]
                           (when (< (.length sb) max-child-output-chars)
                             (let [remaining (- max-child-output-chars (.length sb))
                                   content (subs line 0 (min (count line) remaining))]
                               (.append sb content)
                               (when (< (.length sb) max-child-output-chars)
                                 (.append sb "\n"))))
                           (when (>= (.length sb) max-child-output-chars)
                             (reset! truncated? true))
                           (recur))))))]
    (.start t)
    {:string-builder sb :thread t :truncated? truncated?}))

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

(defn load-notebook-isolated
  "Require a notebook namespace in a fresh JVM, with a timeout.

   Returns {:ok? bool :exit int-or-:timeout :output string}."
  [ns-sym]
  (let [started (System/nanoTime)
        cmd (into ["clojure"]
                  (concat classpath-args ["-e" (str "(require '" ns-sym ")")]))
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectErrorStream true))
        proc (.start pb)
        {:keys [string-builder thread truncated?]} (read-all (.getInputStream proc))
        exited? (.waitFor proc (long per-notebook-timeout-seconds)
                          java.util.concurrent.TimeUnit/SECONDS)]
    (if exited?
      (let [exit (.exitValue proc)]
        (.join thread 5000)
        {:ok? (zero? exit) :exit exit
         :duration-ms (quot (- (System/nanoTime) started) 1000000)
         :output (str string-builder (when @truncated? "\n[output truncated at 1 MiB]\n"))})
      (do (terminate-process-tree! proc)
          (.join thread 5000)
          {:ok? false :exit :timeout
           :duration-ms (quot (- (System/nanoTime) started) 1000000)
           :output (str string-builder (when @truncated? "\n[output truncated at 1 MiB]\n"))}))))

(defn run-notebook-loads
  "Load `namespaces` with separate JVMs and a bounded worker pool.

   `load-fn` is injectable to test timeout/failure accounting without running
   notebooks. Results preserve source order even though progress reports
   completed work immediately."
  [namespaces load-fn worker-count]
  (let [total (count namespaces)
        executor (Executors/newFixedThreadPool (int worker-count))
        events (LinkedBlockingQueue.)
        indexed (map-indexed vector namespaces)]
    ;; Submit all entries to the bounded executor. Each callable is isolated in
    ;; its own JVM by `load-fn`; the worker pool only bounds simultaneous JVMs.
    (doseq [[index ns-sym] indexed]
      (.submit executor
               ^Callable
               (reify Callable
                 (call [_]
                   (.put events {:event :started :index index :namespace (str ns-sym)})
                   (let [result (try
                                  (assoc (load-fn ns-sym) :index index :namespace (str ns-sym))
                                  (catch Exception error
                                    {:index index :namespace (str ns-sym) :ok? false :exit :error
                                     :duration-ms 0
                                     :output (str "runner error: " (.getMessage error))}))]
                     (.put events {:event :completed :result result})
                     result)))))
    (try
      (loop [finished 0, results (vec (repeat total nil))]
        (if (= finished total)
          results
          (let [{:keys [event index namespace result]} (.take events)]
            (if (= event :started)
              (do
                (println (format "  [start %d/%d] %s (timeout %ds)"
                                 (inc index) total namespace per-notebook-timeout-seconds))
                (flush)
                (recur finished results))
              (let [status (cond
                             (:ok? result) "PASS"
                             (= :timeout (:exit result)) "TIMEOUT"
                             :else "FAIL")
                    next-results (assoc results (:index result) result)]
                (println (format "  [%d/%d] %s %s (%d ms)"
                                 (inc finished) total status (:namespace result)
                                 (:duration-ms result)))
                (flush)
                (when-not (:ok? result)
                  (println "    exit:" (pr-str (:exit result)))
                  (doseq [line (take-last 6 (str/split-lines (:output result)))]
                    (println "    " line))
                  (flush))
                (recur (inc finished) next-results))))))
      (finally
        (.shutdownNow executor)
        (.awaitTermination executor 5 TimeUnit/SECONDS)))))

(defn- report-directory []
  (let [root (io/file "target/notebook-validation")]
    (.mkdirs root)
    (let [stamp (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss.SSS'Z'")
                         (.atZone (Instant/now) java.time.ZoneOffset/UTC))]
      (io/file root (str stamp "-" (UUID/randomUUID))))))

(defn- source-revision []
  (let [result (try (shell/sh "jj" "log" "-r" "@" "--no-graph" "-T" "commit_id")
                    (catch Exception _ nil))]
    (when (and result (zero? (:exit result)))
      (str/trim (:out result)))))

(defn write-validation-report! [directory entries results started-at]
  (.mkdirs (io/file directory "logs"))
  (let [rows (mapv (fn [[entry result]]
                     (let [namespace (:namespace result)
                           log-name (str (str/replace namespace #"[^A-Za-z0-9._-]" "_") ".log")]
                       (spit (io/file directory "logs" log-name) (:output result))
                       {:path (.getPath ^java.io.File (:file entry))
                        :namespace namespace
                        :status (cond (:ok? result) "passed"
                                      (= :timeout (:exit result)) "timed-out"
                                      :else "failed")
                        :command (when-not (str/starts-with? namespace "<missing-ns>")
                                   (into ["clojure"]
                                         (concat classpath-args
                                                 ["-e" (str "(require '" namespace ")")])))
                        :exit (if (number? (:exit result)) (:exit result)
                                  (some-> (:exit result) name))
                        :durationMs (:duration-ms result)
                        :log (str "logs/" log-name)}))
                   (map vector entries results))
        failures (filterv #(= "failed" (:status %)) rows)
        timeouts (filterv #(= "timed-out" (:status %)) rows)
        report {:schema "prf.notebook-validation-report.v1"
                :status (if (and (empty? failures) (empty? timeouts)) "passed" "failed")
                :startedAt started-at
                :finishedAt (str (Instant/now))
                :sourceRevision (source-revision)
                :runtime {:clojure (clojure-version)
                          :java (System/getProperty "java.version")
                          :classpathArgs classpath-args
                          :maxParallelNotebooks max-parallel-notebooks
                          :perNotebookTimeoutSeconds per-notebook-timeout-seconds}
                :attempted (count rows)
                :loaded (count (filter #(= "passed" (:status %)) rows))
                :failed (count failures)
                :timedOut (count timeouts)
                :namespaces rows}]
    (spit (io/file directory "report.json")
          (str (json/write-str report) "\n"))
    report))

(defn -main [& _args]
  (let [files (notebook-files)
        entries (mapv (fn [file] {:file file :namespace (read-ns-form file)}) files)
        valid-entries (filterv :namespace entries)
        start (str (Instant/now))
        results (run-notebook-loads (mapv :namespace valid-entries)
                                    load-notebook-isolated max-parallel-notebooks)
        no-ns (mapv (fn [{:keys [file]}]
                      {:namespace (str "<missing-ns>:" (.getPath ^java.io.File file))
                       :ok? false :exit :missing-ns
                       :duration-ms 0 :output (str "No ns form found in " (.getPath ^java.io.File file))})
                    (remove :namespace entries))
        all-results (into results no-ns)
        all-entries (into valid-entries (map (fn [entry] (assoc entry :namespace
                                                               (str "<missing-ns>:"
                                                                    (.getPath ^java.io.File (:file entry)))))
                                            (remove :namespace entries)))
        directory (report-directory)
        report (write-validation-report! directory all-entries all-results start)
        total (:attempted report)
        passed (:loaded report)
        failed (:failed report)]
    (println)
    (println "─── Notebook Load Summary ───")
    (println (str "  Passed: " passed "/" total))
    (println (str "  Failed/timeouts: " failed))
    (println (str "  Report: " (.getPath (io/file directory "report.json"))))
    (println)
    (flush)
    (System/exit (if (pos? failed) 1 0))))
