(ns resolver-sim.vcs
  "VCS-agnostic wrapper supporting jj (Jujutsu) and Git.
   Tries jj first, falls back to git, returns nil when neither is available."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [resolver-sim.forensic.source-hash :as source-hash]))

(defn- run
  "Run a command, return trimmed stdout on success, nil otherwise."
  [cmd & args]
  (try
    (let [{:keys [exit out]} (apply sh cmd args)]
      (when (zero? exit)
        (some-> out str/trim not-empty)))
    (catch Exception _ nil)))

(defn commit-sha
  "Full commit SHA from jj or git HEAD."
  []
  (or (run "jj" "log" "--ignore-working-copy" "-r" "@" "--no-graph" "-T" "commit_id")
      (run "git" "rev-parse" "HEAD")))

(defn short-sha
  "Short commit SHA (12 chars) from jj or git HEAD."
  []
  (or (run "jj" "log" "--ignore-working-copy" "-r" "@" "--no-graph" "-T" "commit_id.shortest(12)")
      (run "git" "rev-parse" "--short" "HEAD")))

(defn commit-message
  "First line of current commit message from jj or git."
  []
  (or (some-> (run "jj" "log" "--ignore-working-copy" "-r" "@" "--no-graph" "-T" "description")
              str/split-lines
              first)
      (run "git" "log" "-1" "--format=%s")))

(defn branch
  "Active bookmark (jj) or branch (git) name.
   For jj, returns bookmarks on the current change, then falls back to
   the change ID prefix, then git branch."
  []
  (or (some-> (run "jj" "bookmark" "list" "--ignore-working-copy" "-r" "@" "-T" "name")
              str/split-lines
              (->> (remove str/blank?))
              first)
      (some-> (run "jj" "log" "--ignore-working-copy" "-r" "@" "--no-graph" "-T" "change_id.shortest(8)")
              not-empty)
      (run "git" "rev-parse" "--abbrev-ref" "HEAD")))

(defn root
  "Repository root directory."
  []
  (or (run "jj" "root")
      (run "git" "rev-parse" "--show-toplevel")))

(defn dirty?
  "True when the working copy has uncommitted changes."
  []
  (let [dirty-str (or (run "jj" "status" "--color" "never")
                      (run "git" "status" "--short"))]
    (and dirty-str (not (str/includes? dirty-str "The working copy is clean")))))

(defn remotes
  "List of remote URLs."
  []
  (when-let [r (run "git" "remote" "-v")]
    (->> (str/split-lines r)
         (map #(second (str/split % #"\s+")))
         distinct
         vec)))

(defn tag
  "Exact tag at the current commit (git-only), or nil when not on a tag or git
   is unavailable."
  []
  (run "git" "describe" "--tags" "--exact-match"))

(def ^:private known-lockfiles
  ["deps.edn" "package-lock.json" "Cargo.lock" "requirements.txt"])

(defn git-tracked?
  "True when a path is tracked in the git index (git-only)."
  [path]
  (some? (run "git" "ls-files" "--error-unmatch" path)))

(defn git-object-hash
  "Git blob hash of a tracked path (git-only), or nil."
  [path]
  (run "git" "hash-object" path))

(defn lockfile-hashes
  "Git blob hashes for known lockfile paths that are tracked, or {} when git is
   unavailable. Lockfile content hashes are git-specific; a jj-only workspace
   yields an empty map rather than a wrong value."
  []
  (into {}
        (keep (fn [f]
                (when (git-tracked? f)
                  [f (git-object-hash f)])))
        known-lockfiles))

(defn metadata
  "Full repo metadata map compatible with benchmark/repo format."
  []
  (let [r (root)]
    {:repo
     {:root    r
      :commit  (commit-sha)
      :branch  (branch)
      :dirty?  (dirty?)
      :remotes (remotes)}}))

(defn dirs-hash
  "Deterministic SHA-256 hash of specific file paths relative to repo root.

   Uses git ls-tree when available (fast, VCS-grounded, content-addressed).
   Falls back to walking files from repo root, sorting by relative path,
   and building a Merkle-style hash.

   (dirs-hash \"src/\" \"protocols_src/\")
   (dirs-hash \"deps.edn\" \"bb.edn\")

   Accepts file paths and directory paths. Directories are walked recursively.
   Returns 64-char hex string, or nil if repo root is unknown."
  [& paths]
  (let [paths (vec paths)]
    (or (try
          (when-let [r (root)]
            (let [args (into ["ls-tree" "-r" "HEAD" "--"] paths)
                  {:keys [exit out]} (apply sh "git" (conj args :dir r))]
              (when (zero? exit)
                (-> (sh "sha256sum" :in (str/trim out))
                    :out
                    (str/split #"\s+")
                    first))))
          (catch Exception _ nil))
        (try
          (when-let [r (root)]
            (let [all-files (->> paths
                                 (mapcat (fn [p]
                                           (let [f (java.io.File. (str r "/" p))]
                                             (cond
                                               (.isDirectory f)
                                               (->> (file-seq f)
                                                    (filter (fn [^java.io.File x]
                                                              (and (.isFile x)
                                                                   (not (.isHidden x)))))
                                                    (map #(.getAbsolutePath %)))
                                               (.isFile f)
                                               [(.getAbsolutePath f)]
                                               :else []))))
                                 (sort-by #(subs % (inc (count r))))
                                 doall)
                  pairs (map (fn [path]
                               (let [rel (subs path (inc (count r)))]
                                 (str rel ":"
                                      (-> (sh "sha256sum" path) :out
                                          (str/split #"\s+") first))))
                             all-files)]
              (when (seq pairs)
                (-> (sh "sha256sum" :in (str/join "\n" pairs))
                    :out
                    (str/split #"\s+")
                    first))))
          (catch Exception _ nil)))))

(defn code-hash
  "Hash of execution-relevant source code from the current working tree."
  []
  (when-let [r (root)]
    (source-hash/source-tree-hash* r (source-hash/source-roots))))

(defn deps-hash
  "Hash of dependency configuration: bb.edn, deps.edn."
  []
  (dirs-hash "bb.edn" "deps.edn"))

(defn input-hash
  "Hash of scenario inputs: scenarios/ and data/fixtures/."
  []
  (dirs-hash "scenarios/" "data/fixtures/"))

(defn dirty-diff-hash
  "SHA-256 hash of the git diff against HEAD for execution-relevant paths.
   Returns nil if repo is clean or VCS unavailable.
   Required when allowing dirty override — commits to what changed."
  []
  (when (dirty?)
    (or (try
          (when-let [r (root)]
            (let [{:keys [exit out]} (sh "git" "diff" "HEAD"
                                         "--" "src/" "protocols_src/"
                                         "bb.edn" "deps.edn"
                                         :dir r)]
              (when (and (zero? exit) (seq (str/trim out)))
                (-> (sh "sha256sum" :in out)
                    :out (str/split #"\s+") first))))
          (catch Exception _ nil))
        (try
          (when-let [r (root)]
            (let [{:keys [exit out]} (sh "jj" "diff" "-r" "@" "--git" :dir r)]
              (when (and (zero? exit) (seq (str/trim out)))
                (-> (sh "sha256sum" :in out)
                    :out (str/split #"\s+") first))))
          (catch Exception _ nil)))))

(defn source-provenance
  "Multi-dimensional source provenance for chain cursor and forensic attestations.

   Returns {:git-commit-sha <string or nil>
            :source/hash    <string or nil>
            :source/hash-algorithm <string or nil>
            :source/hash-roots <vector of strings>
            :code-hash      <string or nil>
            :deps-hash      <string or nil>
            :input-hash     <string or nil>
            :dirty?         <boolean>}
   or nil when VCS root is unavailable (unknown source state)."
  []
  (when (root)
    (let [hash-roots (source-hash/included-source-roots (root)
                                                        (source-hash/source-roots))
          source-hash-value (code-hash)]
      {:git-commit-sha        (commit-sha)
       :source/hash           source-hash-value
       :source/hash-algorithm source-hash/source-tree-hash-algorithm
       :source/hash-roots     hash-roots
       :code-hash             source-hash-value
       :deps-hash             (deps-hash)
       :input-hash            (input-hash)
       :dirty?                (boolean (dirty?))})))
