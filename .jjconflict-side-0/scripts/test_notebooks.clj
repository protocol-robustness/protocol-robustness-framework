(ns scripts.test-notebooks
  "Verify every notebook namespace loads.

   Each notebook is loaded in its OWN isolated JVM, with a per-notebook timeout.
   Each notebook loads in a fresh JVM, with a bounded pool of four subprocesses.
   Isolation avoids accumulated namespace state; per-notebook deadlines and an
   incrementally persisted report make slow or interrupted runs diagnosable.

   Exit code is 0 when every notebook loads, 1 otherwise."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.util.concurrent Callable Executors LinkedBlockingQueue TimeUnit]))

(def notebooks-dir (io/file "notebooks"))
(def expected-files-path (io/file "notebooks/load-set-v1.edn"))
(def ^:private validation-report-name
  (str (System/currentTimeMillis) "-" (.pid (java.lang.ProcessHandle/current)) ".json"))
(def ^:private max-parallel-notebooks 4)
(def ^:private active-processes (atom #{}))
(def ^:const max-child-output-chars 1048576)

(def ^:const per-notebook-timeout-seconds
  "Maximum seconds to wait for a single notebook's subprocess.
   Keep this bounded so the repository-wide gate cannot spend tens of minutes
   waiting on a hung notebook load."
  300)

(def ^:const classpath-alias
  "Alias used to launch each notebook subprocess. Must include every path a
   notebook requires (see deps.edn); matches the way the server is run."
  "-M:with-sew")

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

(defn- expected-notebook-files []
  (let [manifest (edn/read-string (slurp expected-files-path))]
    (when-not (= "prf.notebook-load-set.v1" (:schema manifest))
      (throw (ex-info "unsupported notebook load-set schema" {:schema (:schema manifest)})))
    (set (:files manifest))))

(defn notebook-inventory-differences [files]
  (let [expected (expected-notebook-files)
        actual (set (map #(str (.relativize (.toPath (io/file ".")) (.toPath ^java.io.File %)))
                         files))]
    {:missing (vec (sort (set/difference expected actual)))
     :unexpected (vec (sort (set/difference actual expected)))}))

(defn report-file []
  (let [dir (io/file "target/notebook-validation")]
    (.mkdirs dir)
    (io/file dir validation-report-name)))

(defn result-status [result]
  (cond
    (:running? result) "running"
    (nil? result) "pending"
    (:ok? result) "passed"
    (= :timeout (:exit result)) "timed-out"
    :else "failed"))

(defn- source-revision []
  (try
    (let [{:keys [exit out]} (shell/sh "jj" "log" "-r" "@" "--no-graph" "-T" "commit_id")]
      (when (zero? exit) (str/trim out)))
    (catch Throwable _ nil)))

(defn- persist-progress! [entries results started-at inventory]
  (let [rows (mapv (fn [entry result]
                     {:file (:path entry)
                      :namespace (some-> (:namespace entry) str)
                      :status (result-status result)
                      :exit (some-> result :exit str)
                      :output (some-> result :output)})
                   entries results)
        failures (count (filter #(contains? #{"failed" "timed-out"} (:status %)) rows))
        report {:schema "prf.notebook-validation-report.v1"
                :status (if (and (zero? failures)
                                 (empty? (:missing inventory))
                                 (empty? (:unexpected inventory)))
                          "running" "failed")
                :startedAt started-at
                :updatedAt (str (java.time.Instant/now))
                :sourceRevision (source-revision)
                :expectedCount (count (expected-notebook-files))
                :discoveredCount (count entries)
                :perNotebookTimeoutSeconds per-notebook-timeout-seconds
                :missing (:missing inventory)
                :unexpected (:unexpected inventory)
                :passed (count (filter #(= "passed" (:status %)) rows))
                :failed failures
                :namespaces rows}]
    (spit (report-file) (str (json/write-str report) "\n"))
    report))

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
                           (let [available (- max-child-output-chars (.length sb))
                                 addition (str line "\n")]
                             (when (pos? available)
                               (.append sb (subs addition 0 (min available (count addition)))))
                             (when (> (count addition) available)
                               (reset! truncated? true)))
                           (recur))))))]
    (.start t)
    {:string-builder sb :thread t :truncated? truncated?}))

(defn- destroy-process-tree! [^Process proc]
  (let [descendants (.descendants (.toHandle proc))
        children (with-open [stream descendants]
                   (doall (iterator-seq (.iterator stream))))]
    (doseq [^java.lang.ProcessHandle child (reverse children)]
      (.destroyForcibly child)))
  (.destroyForcibly proc)
  (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)
  nil)

(defn load-notebook-isolated
  "Require a notebook namespace in a fresh JVM, with a timeout.

   Returns {:ok? bool :exit int-or-:timeout :output string}."
  [ns-sym]
  (let [cmd ["clojure" classpath-alias "-e" (str "(require '" ns-sym ")")]
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectErrorStream true))
        proc (.start pb)
        _tracked (swap! active-processes conj proc)
        {:keys [string-builder thread truncated?]} (read-all (.getInputStream proc))
        exited? (.waitFor proc (long per-notebook-timeout-seconds)
                          java.util.concurrent.TimeUnit/SECONDS)
        output (fn [] (str string-builder
                           (when @truncated? "\n[output truncated at 1 MiB]\n")))]
    (try
      (if exited?
        (let [exit (.exitValue proc)]
          (.join thread 5000)
          {:ok? (zero? exit) :exit exit :output (output)})
        (do (destroy-process-tree! proc)
            (.join thread 5000)
            {:ok? false :exit :timeout :output (output)}))
      (finally (swap! active-processes disj proc)))))

(defn load-notebook [ns-sym]
  (let [{:keys [ok? exit output] :as result} (load-notebook-isolated ns-sym)]
    (if ok?
      (do (println "  ✓" ns-sym) result)
      (do (println "  ✗" ns-sym)
          (println "    exit:" (pr-str exit))
          (let [last-lines (->> output str/split-lines
                                (take-last 6)
                                (map #(str "      " %)))]
            (doseq [l last-lines] (println l)))
          result))))

(defn- run-notebook-loads! [entries results started-at inventory]
  (let [executor (Executors/newFixedThreadPool (int max-parallel-notebooks))
        events (LinkedBlockingQueue.)]
    (doseq [[index entry] (map-indexed vector entries)]
      (.submit executor
               ^Callable
               (reify Callable
                 (call [_]
                   (.put events {:event :started :index index})
                   (let [result (if-let [ns-sym (:namespace entry)]
                                  (try (load-notebook-isolated ns-sym)
                                       (catch Throwable error
                                         {:ok? false :exit :runner-error
                                          :output (str "runner error: " (.getMessage error))}))
                                  {:ok? false :exit :missing-ns :output "No ns form found"})]
                     (.put events {:event :completed :index index :result result})
                     result)))))
    (try
      (loop [completed 0]
        (when (< completed (count entries))
          (let [{:keys [event index result]} (.take events)]
            (if (= event :started)
              (do (swap! results assoc index {:running? true})
                  (persist-progress! entries @results started-at inventory)
                  (recur completed))
              (let [entry (nth entries index)
                    ns-sym (:namespace entry)
                    status (result-status result)]
                (println (format "  [%d/%d] %s %s" (inc completed) (count entries)
                                 (str/upper-case status) ns-sym))
                (when-not (:ok? result)
                  (println "    exit:" (pr-str (:exit result)))
                  (doseq [line (take-last 6 (str/split-lines (:output result)))]
                    (println "    " line)))
                (swap! results assoc index result)
                (persist-progress! entries @results started-at inventory)
                (recur (inc completed)))))))
      (finally
        (.shutdownNow executor)
        (.awaitTermination executor 5 TimeUnit/SECONDS)))))

(defn -main [& _args]
  (let [files (notebook-files)
        inventory (notebook-inventory-differences files)
        entries (mapv (fn [f] {:path (str (.relativize (.toPath (io/file ".")) (.toPath f)))
                               :file f :namespace (read-ns-form f)}) files)
        start (str (java.time.Instant/now))
        results (atom (vec (repeat (count entries) nil)))
        finished? (atom false)
        shutdown-hook (Thread.
                       (fn []
                         (when-not @finished?
                           (doseq [^Process process @active-processes]
                             (destroy-process-tree! process))
                           (let [report (persist-progress! entries @results start inventory)
                                 unfinished (->> (:namespaces report)
                                                 (filter #(contains? #{"pending" "running"} (:status %)))
                                                 (mapv #(select-keys % [:file :namespace :status])))]
                             (spit (report-file)
                                   (str (json/write-str (assoc report :status "interrupted"
                                                                :unfinishedNamespaces unfinished
                                                                :interruptedAt (str (java.time.Instant/now))))
                                        "\n"))))))
        hook-registered (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
        progress-init (persist-progress! entries @results start inventory)
        inventory-warning (when (or (seq (:missing inventory)) (seq (:unexpected inventory)))
                            (println "Notebook inventory mismatch:" (pr-str inventory)))
        load-results (run-notebook-loads! entries results start inventory)
        rows @results
        failed (+ (count (filter #(not (:ok? %)) rows))
                  (count (:missing inventory))
                  (count (:unexpected inventory)))
        total (+ (count entries) (count (:missing inventory)) (count (:unexpected inventory)))
        passed (- total failed)
        report (persist-progress! entries rows start inventory)
        final-report (spit (report-file)
                           (str (json/write-str (assoc report :status (if (zero? failed) "passed" "failed")
                                                        :finishedAt (str (java.time.Instant/now))
                                                        :failed failed :passed passed :attempted total)) "\n"))]
    (println)
    (println "─── Notebook Load Summary ───")
    (println (str "  Passed: " passed "/" total))
    (when (pos? failed)
      (println (str "  Failed/timeouts or inventory errors: " failed)))
    (println (str "  Report: " (.getPath (report-file))))
    (println)
    (reset! finished? true)
    (System/exit (if (pos? failed) 1 0))))
