(ns scripts.test-notebooks
  "Verify every notebook namespace loads.

   Each notebook is loaded in its OWN isolated JVM, with a per-notebook timeout.
   This is deliberate: loading ~37 heavy notebooks sequentially in one JVM
   accumulates namespace state and memory, which intermittently produces
   spurious loader errors (e.g. 'Syntax error reading source' at valid code) on
   the heaviest notebooks and lets a single slow notebook hang the whole gate.
   Isolation removes both problems.

   Exit code is 0 when every notebook loads, 1 otherwise."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def notebooks-dir (io/file "notebooks"))

(def ^:const per-notebook-timeout-seconds
  "Maximum seconds to wait for a single notebook's subprocess.
   Covers cold-JVM startup (~40s) plus expensive top-level computation.
   notebooks/workbench_v2.clj alone needs ~400s (heavy top-level scenario
   work), so this is set comfortably above that. With per-notebook isolation a
   slow or hung notebook only delays its own result, never the whole gate."
  600)

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

(defn- read-all
  "Read a stream to a string in a background thread (so a noisy subprocess
   cannot block on a full pipe buffer). Returns a string."
  [^java.io.InputStream is]
  (let [sb (StringBuilder.)
        t (Thread. (fn []
                     (with-open [r (io/reader is)]
                       (loop []
                         (when-let [line (.readLine r)]
                           (.append sb line)
                           (.append sb "\n")
                           (recur))))))]
    (.start t)
    {:string-builder sb :thread t}))

(defn load-notebook-isolated
  "Require a notebook namespace in a fresh JVM, with a timeout.

   Returns {:ok? bool :exit int-or-:timeout :output string}."
  [ns-sym]
  (let [cmd ["clojure" classpath-alias "-e" (str "(require '" ns-sym ")")]
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectErrorStream true))
        proc (.start pb)
        {:keys [string-builder thread]} (read-all (.getInputStream proc))
        exited? (.waitFor proc (long per-notebook-timeout-seconds)
                          java.util.concurrent.TimeUnit/SECONDS)]
    (if exited?
      (let [exit (.exitValue proc)]
        (.join thread 5000)
        {:ok? (zero? exit) :exit exit :output (str string-builder)})
      (do (.destroy proc)
          (.join thread 5000)
          {:ok? false :exit :timeout :output (str string-builder)}))))

(defn load-notebook [ns-sym]
  (let [{:keys [ok? exit output]} (load-notebook-isolated ns-sym)]
    (if ok?
      (do (println "  ✓" ns-sym) true)
      (do (println "  ✗" ns-sym)
          (println "    exit:" (pr-str exit))
          (let [last-lines (->> output str/split-lines
                                (take-last 6)
                                (map #(str "      " %)))]
            (doseq [l last-lines] (println l)))
          false))))

(defn -main [& _args]
  (let [files (notebook-files)
        total (count files)
        results (mapv (fn [f]
                        (if-let [ns-sym (read-ns-form f)]
                          (load-notebook ns-sym)
                          (do (println "  ?" (.getPath f) "(no ns form found)")
                              false)))
                      files)
        failed (count (remove true? results))
        passed (- total failed)]
    (println)
    (println "─── Notebook Load Summary ───")
    (println (str "  Passed: " passed "/" total))
    (when (pos? failed)
      (println (str "  Failed: " failed)))
    (println)
    (System/exit (if (pos? failed) 1 0))))
