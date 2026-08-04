(ns resolver-sim.commands.run-lifecycle
  "Shared root ownership and terminal lifecycle primitives for run bundles."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.paths :as paths]
            [resolver-sim.io.edn :as ppedn])
  (:import [java.math BigInteger]
           [java.nio.file FileAlreadyExistsException Files LinkOption Path Paths StandardCopyOption]
           [java.security MessageDigest]))

(defn root-state [^Path root]
  (cond
    (not (Files/exists root (make-array LinkOption 0))) :absent
    (not (Files/isDirectory root (make-array LinkOption 0))) :not-a-directory
    (Files/exists (.resolve root paths/completion) (make-array LinkOption 0)) :completed
    (or (Files/exists (.resolve root "manifest") (make-array LinkOption 0))
        (Files/exists (.resolve root paths/run-state) (make-array LinkOption 0))) :incomplete
    :else (with-open [entries (Files/list root)]
            (if (.hasNext (.iterator entries)) :unrelated :empty))))

(defn require-fresh-root! [^Path root run-kind]
  (let [state (root-state root)]
    (when-not (#{:absent :empty} state)
      (throw (ex-info (if (= run-kind :scenario)
                        "Run root must be absent or empty"
                        (str (clojure.string/capitalize (name run-kind)) " run root must be absent or empty"))
                      {:run/root (str root) :run/root-state state :run/type run-kind})))
    state))

(defn sha256-file [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (io/input-stream (io/file (str file)))]
      (let [buffer (byte-array 8192)]
        (loop [read (.read stream buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur (.read stream buffer))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn atomic-json! [file value]
  (let [target (io/file (str file))
        temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str value :indent true))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    value))

(defn build-run-envelope
  "Resolve and validate the common top-level context for a canonical run."
  [{:keys [run-id run-type run-root project-root sensitivity-profile]}]
  (let [project (.toAbsolutePath (.normalize (Paths/get (str (or project-root ".")) (make-array String 0))))
        raw (Paths/get (str run-root) (make-array String 0))
        root (.normalize (if (.isAbsolute raw) raw (.resolve project raw)))
        state (require-fresh-root! root run-type)]
    {:project/root project
     :run/id run-id
     :run/type run-type
     :run/root root
     :run/root-state state
     :sensitivity/profile (or sensitivity-profile :public)
     :manifest/dir (.resolve root "manifest")
     :lifecycle/state-file (.resolve root paths/run-state)
     :lifecycle/completion-file (.resolve root paths/completion)
     :lifecycle/lock-file (.resolve root paths/run-lock)}))

(defn snapshot-input!
  "Snapshot an InputSource below `run-root`, returning immutable provenance
   with a root-relative snapshot path."
  [run-root source destination]
  (let [root (.toAbsolutePath (.normalize (.toPath (io/file (str run-root)))))
        target (.toAbsolutePath (.normalize (.toPath (io/file (str destination)))))]
    (when-not (.startsWith target root)
      (throw (ex-info "Input snapshot destination escapes run root"
                      {:run/root (str root) :destination (str target)})))
    (let [provenance (input-source/snapshot! source target)]
      (assoc provenance :input/snapshot-relative (str (.relativize root target))))))

(defn acquire-run-lock!
  "Acquire the exclusive root lock before any lifecycle or artifact write."
  [run-root run-id run-type]
  (let [root (io/file (str run-root))
        lock (io/file root paths/run-lock)]
    (.mkdirs root)
    (try
      (Files/createFile (.toPath lock) (make-array java.nio.file.attribute.FileAttribute 0))
       (spit lock (ppedn/ppr-str {:run/id run-id :run/type run-type}))
      lock
      (catch FileAlreadyExistsException _
        (throw (ex-info "Run root is already in use"
                        {:run/root (.getPath root) :lock (.getPath lock)
                         :run/id run-id :run/type run-type}))))))

(defn release-run-lock! [lock]
  (when (and lock (.exists (io/file lock)))
    (.delete (io/file lock))))

(defn mark-running! [run-root run-id run-type]
  (let [root (io/file (str run-root))
        target (io/file root paths/run-state)]
    (.mkdirs root)
     (spit target (ppedn/ppr-str {:run/id run-id :run/type run-type :lifecycle/status :running}))
    target))

(defn complete! [run-root completion]
  (let [root (io/file (str run-root))]
    (atomic-json! (io/file root paths/completion) completion)
    (.delete (io/file root paths/run-state))
    completion))
