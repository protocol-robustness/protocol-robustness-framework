(ns resolver-sim.commands.benchmark-run
  "Exact-root lifecycle context for canonical benchmark bundles."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

(defn- root-state [^Path root]
  (cond
    (not (Files/exists root (make-array LinkOption 0))) :absent
    (not (Files/isDirectory root (make-array LinkOption 0))) :not-a-directory
    (or (Files/exists (.resolve root "COMPLETED") (make-array LinkOption 0))
        (Files/exists (.resolve root "completion.json") (make-array LinkOption 0))) :completed
    (or (Files/exists (.resolve root "manifest") (make-array LinkOption 0))
        (Files/exists (.resolve root "benchmark") (make-array LinkOption 0))
        (Files/exists (.resolve root ".run-state") (make-array LinkOption 0))) :incomplete
    :else (with-open [entries (Files/list root)]
            (if (.hasNext (.iterator entries)) :unrelated :empty))))

(defn- run-id []
  (str "benchmark-"
       (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
                (.atZone (Instant/now) ZoneOffset/UTC))
       "-" (subs (str (UUID/randomUUID)) 0 12)))

(defn build-run-context [benchmark-id run-root project-root]
  (let [project (.toAbsolutePath (.normalize (Paths/get (str (or project-root ".")) (make-array String 0))))
        raw (Paths/get (str run-root) (make-array String 0))
        root (.normalize (if (.isAbsolute raw) raw (.resolve project raw)))
        status (root-state root)]
    (when-not (#{:absent :empty} status)
      (throw (ex-info "Benchmark run root must be absent or empty"
                      {:run/root (str root) :run/root-state status})))
    {:run/id (run-id)
     :run/type :benchmark
     :run/root root
     :benchmark/id benchmark-id
     :manifest/dir (.resolve root "manifest")
     :benchmark/root (.resolve root "benchmark")
     :benchmark/evidence-file (.resolve root "benchmark/evidence.edn")
     :benchmark/scenarios-dir (.resolve root "benchmark/scenarios")}))

(defn initialize! [context]
  (doseq [path [(:run/root context) (:manifest/dir context)
                (:benchmark/root context) (:benchmark/scenarios-dir context)]]
    (.mkdirs (io/file (str path))))
  (spit (io/file (str (:run/root context)) ".run-state")
        (pr-str {:run/id (:run/id context) :run/type :benchmark :state :running}))
  context)
