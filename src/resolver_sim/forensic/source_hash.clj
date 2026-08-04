(ns resolver-sim.forensic.source-hash
  "Content-based source tree hashing with git and jj support.
   Detects the VCS automatically and computes commit, tree hash, and dirty flag.
   Used by bb hash:source and by pre-run commitment generation."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [resolver-sim.config.paths :as paths])
  (:import [java.security MessageDigest]))

(def default-source-roots
  ["src"
   "protocols_src"
   "benchmarks"
   (paths/concepts-dir)
   "scenarios"
   "suites"
   "resources"])
(def source-tree-hash-algorithm "source-tree-hash.v1.path-content-sha256")

(defn- sh! [& cmd]
  (try
    (let [{:keys [exit out]} (apply sh/sh cmd)]
      (when (zero? exit)
        (some-> out str/trim not-empty)))
    (catch Exception _ nil)))

(defn- sha256-hex [^bytes ba]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d ba)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- sha256-file [^java.io.File f]
  (sha256-hex (java.nio.file.Files/readAllBytes (.toPath f))))

(defn source-roots
  "Execution-relevant replay-critical roots.
   Defaults cover code, benchmark manifests, concepts, scenarios, suites,
   and packaged resources."
  []
  (let [raw (System/getenv "PRF_SOURCE_ROOTS")
        roots (some-> raw
                      (str/split #",")
                      (->> (map str/trim)
                           (remove str/blank?)
                           vec))]
    (if (seq roots) roots default-source-roots)))

(defn- normalize-rel-path [^String path]
  (str/replace path "\\" "/"))

(defn- rel-path [repo-root ^java.io.File f]
  (-> (.relativize (.toPath (io/file repo-root))
                   (.toPath f))
      str
      normalize-rel-path))

(defn included-source-roots
  "Configured roots that currently exist under repo-root."
  [repo-root roots]
  (->> roots
       (map str)
       (filter (fn [root]
                 (.exists (io/file repo-root root))))
       vec))

(defn- regular-files-under-root [repo-root root]
  (let [f (io/file repo-root root)]
    (cond
      (.isFile f) [f]
      (.isDirectory f) (->> (file-seq f)
                            (filter #(.isFile ^java.io.File %)))
      :else [])))

(defn source-tree-lines
  "Deterministic path:content-hash lines for the current working tree."
  [repo-root roots]
  (let [included (included-source-roots repo-root roots)]
    (->> included
         (mapcat #(regular-files-under-root repo-root %))
         (sort-by #(rel-path repo-root %))
         (map (fn [^java.io.File f]
                (str (rel-path repo-root f) ":" (sha256-file f))))
         vec)))

(defn source-tree-hash*
  "Content-based tree hash for the current working tree under the given roots."
  [repo-root roots]
  (sha256-hex (.getBytes (str/join "\n" (source-tree-lines repo-root roots)) "UTF-8")))

(defn- jj-root []
  (sh! "jj" "root"))

(defn- git-root []
  (sh! "git" "rev-parse" "--show-toplevel"))

(defn- jj-log [template]
  (sh! "jj" "log" "--ignore-working-copy" "-r" "@" "--no-graph" "-T" template))

(defn- jj-dirty? []
  (let [status (sh! "jj" "status" "--color" "never")]
    (and status
         (not (str/includes? status "The working copy is clean")))))

(defn source-hash
  "Detect VCS and return source provenance map.
   Returns nil if no VCS is detected."
  []
  (let [roots (source-roots)]
    (if-let [root (jj-root)]
      (let [source-hash-value (source-tree-hash* root roots)
            included-roots (included-source-roots root roots)]
        {:vcs/type :jj
         :vcs/root root
         :source/commit (jj-log "commit_id")
         :source/change-id (jj-log "change_id.shortest(12)")
         :source/hash source-hash-value
         :source/hash-algorithm source-tree-hash-algorithm
         :source/hash-roots included-roots
         :source/tree-hash source-hash-value
         :source/tree-hash-algorithm source-tree-hash-algorithm
         :source/included-roots included-roots
         :source/dirty? (boolean (jj-dirty?))})
      (when-let [commit (sh! "git" "rev-parse" "HEAD")]
        (let [root (or (git-root) ".")
              source-hash-value (source-tree-hash* root roots)
              included-roots (included-source-roots root roots)]
          {:vcs/type :git
           :vcs/root root
           :source/commit commit
           :source/hash source-hash-value
           :source/hash-algorithm source-tree-hash-algorithm
           :source/hash-roots included-roots
           :source/tree-hash source-hash-value
           :source/tree-hash-algorithm source-tree-hash-algorithm
           :source/included-roots included-roots
           :source/dirty? (boolean (seq (sh! "git" "status" "--porcelain")))})))))

(defn hash-source
  "CLI entry point: print source provenance as EDN. Returns exit code."
  [& _]
  (let [result (source-hash)]
    (if result
      (do (println (pr-str result)) 0)
      (do (println "WARN: No VCS detected (not a git or jj repository).") 1))))
