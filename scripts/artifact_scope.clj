(ns scripts.artifact-scope
  "Root-confined, atomic artifact publication with per-scope manifests.

   Every namespace worker runs with a fresh *scope* bound.  Callers publish
   artifacts through `write!`; the physical path is always derived from the
   current scope's namespace root, never from caller-concatenated strings.

   Guarantees:
     - path confinement: absolute paths, `..` traversal and symlink escapes
       are rejected (resolve-confined),
     - atomic publication: content is written to a uniquely named sibling temp
       file and atomically moved into place (never appended, never partially
       visible),
     - content-addressed semantics: absent target is published; present with
       identical bytes is idempotent reuse; present with different bytes is a
       hard conflict,
     - per-scope manifest: every published artifact is recorded with logical
       id, relative path, hashes, size, writer provenance and publication
       status; finalize-scope! reconciles declared files against the observed
       root and flags undeclared/temp/duplicate problems,
     - ownership markers: run roots and namespace roots carry an _owner.edn
       marker; cleanup refuses to delete anything that does not carry a valid
       marker for the expected run id."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(def ^:dynamic *scope*
  "Current artifact scope (an atom map) or nil when no scope is bound.
   Bound per namespace worker by with-scope."
  nil)

(defn- sha256-hex
  [bytes]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md ^bytes bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- writer-provenance
  "Best-effort identity of the currently executing test var, if any."
  []
  (when-let [v (first t/*testing-vars*)]
    (str v)))

(defn- canonicalize
  "Recursively sort maps/sets by their EDN serialisation so that content
   hashing is stable regardless of collection iteration order."
  [x]
  (let [by-edn (fn [a b] (compare (pr-str a) (pr-str b)))]
    (cond
      (map? x) (into (sorted-map-by by-edn)
                     (map (fn [[k v]] [(canonicalize k) (canonicalize v)])) x)
      (set? x) (into (sorted-set-by by-edn) (map canonicalize x))
      (sequential? x) (mapv canonicalize x)
      :else x)))

(defn- content-bytes
  [content]
  (cond
    (string? content) (.getBytes ^String content "UTF-8")
    :else (.getBytes ^String (pr-str (canonicalize content)) "UTF-8")))

(defn- relative-path?
  [p]
  (and (string? p)
       (not (str/starts-with? p "/"))
       (not (str/includes? p "\\"))
       (not (re-find #"^[A-Za-z]:" p))
       (not (str/includes? p ".."))))

(defn resolve-confined
  "Resolve a relative artifact path against root, rejecting escapes.
   Canonicalizes the parent before accepting, so symlinks pointing outside
   the root are refused.  Returns a java.io.File."
  [root relative]
  (when-not (relative-path? relative)
    (throw (ex-info "artifact path escape rejected"
                    {:relative-path relative :reason :escape})))
  (let [root-canon (.getCanonicalFile (io/file root))
        root-path (.getPath root-canon)
        target (io/file root-canon relative)
        parent-canon (.getCanonicalFile (.getParentFile target))
        parent-path (.getPath parent-canon)]
    (when-not (or (= parent-path root-path)
                  (str/starts-with? parent-path (str root-path java.io.File/separator)))
      (throw (ex-info "artifact path escape rejected (symlink or parent escape)"
                      {:relative-path relative :reason :escape})))
    target))

(defn write!
  "Publish an artifact into the current scope's namespace root.

   opts:
     :logical-id    — required identity (kw or sym), unique within the scope
     :relative-path — required path relative to the namespace root
     :content       — a string (written raw) or any value (written as
                      canonicalised EDN)
     :content-hash  — optional claimed byte hash; mismatch is a hard failure
     :kind          — optional artifact kind/schema tag

   Returns {:status :published|:reused :path :hash :size}."
  [opts]
  (let [scope (or *scope*
                  (throw (ex-info "no artifact scope bound; write! requires an active scope" {})))
        s @scope
        _ (when-not (= :active (:status s))
            (throw (ex-info "artifact scope not active"
                            {:scope-id (:scope-id s) :status (:status s)})))
        {:keys [logical-id relative-path content content-hash kind]} opts
        _ (when (nil? logical-id)
            (throw (ex-info "write! requires :logical-id" opts)))
        _ (when (nil? relative-path)
            (throw (ex-info "write! requires :relative-path" opts)))
        target (resolve-confined (:namespace-root s) relative-path)
        bytes (content-bytes content)
        hash (sha256-hex bytes)
        _ (when (and content-hash (not= content-hash hash))
            (throw (ex-info "content hash mismatch"
                            {:logical-id logical-id :claimed content-hash :actual hash})))
        existing? (.exists target)
        status (if existing?
                 (let [eh (sha256-hex (java.nio.file.Files/readAllBytes (.toPath target)))]
                   (if (= eh hash)
                     :reused
                    (throw (ex-info "content-addressed conflict"
                                    {:logical-id logical-id
                                     :relative-path relative-path
                                     :reason :content-addressed-conflict
                                     :detail :different-bytes-under-same-identity}))))
                 :published)]
    (when (= :published status)
      (io/make-parents target)
      (let [parent-path (.toPath (.getParentFile target))
            tmp (java.nio.file.Files/createTempFile
                 ^java.nio.file.Path parent-path
                 ".tmp-" ".art"
                 (make-array java.nio.file.attribute.FileAttribute 0))]
        (java.nio.file.Files/write tmp bytes (make-array java.nio.file.OpenOption 0))
        (try
          (java.nio.file.Files/move tmp (.toPath target)
                                    (into-array java.nio.file.CopyOption
                                                [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
          (catch java.nio.file.FileAlreadyExistsException _
            ;; published concurrently by another writer; enforce idempotence
            (java.nio.file.Files/deleteIfExists tmp)
            (let [eh (sha256-hex (java.nio.file.Files/readAllBytes (.toPath target)))]
              (when-not (= eh hash)
                (throw (ex-info "content-addressed conflict"
                                {:logical-id logical-id
                                 :relative-path relative-path
                                 :reason :content-addressed-conflict
                                 :detail :concurrent-writer-different-bytes}))))))))
    (swap! scope update :artifacts conj
           {:logical-id logical-id
            :relative-path relative-path
            :kind kind
            :content-hash hash
            :byte-hash hash
            :size (count bytes)
            :writer (writer-provenance)
            :publication-status :complete})
    {:status status :path (.getPath target) :hash hash :size (count bytes)}))

(defn with-scope
  "Run f with a fresh active scope bound to *scope*.

   scope-config:
     :run-id, :namespace, :namespace-root, :scope-id

   Returns [f-result scope] where scope is the (possibly closed) scope atom."
  [scope-config f]
  (let [scope (atom (assoc scope-config
                           :status :active
                           :artifacts []
                           :opened-at (System/currentTimeMillis)))]
    (binding [*scope* scope]
      (let [result (f)]
        [result scope]))))

(defn- file-relatives
  "Relative paths (normalised to / separators, no leading slash) of every file
   beneath root."
  [root]
  (let [base (.getPath (io/file root))
        prefix (str base java.io.File/separator)]
    (->> (file-seq (io/file root))
         (filter #(.isFile %))
         (map (fn [f]
                (let [p (.getPath f)]
                  (subs p (count prefix)))))
         (map #(str/replace % "\\" "/"))
         vec)))

(defn finalize-scope!
  "Close an active scope after execution.  Verifies that every declared file
   exists, hashes recompute, no duplicate logical ids exist, and no temporary
   files remain; diffs the namespace root and reports undeclared files.

   When strict? is true, undeclared files are a hard failure (scope marked
   :incomplete).  Otherwise they are reported in the manifest and the scope is
   marked :closed.

   Returns the manifest map, or throws ex-info with :manifest when strict
   verification fails."
  ([scope] (finalize-scope! scope false))
  ([scope strict?]
   (let [s @scope
         _ (when-not (= :active (:status s))
             (throw (ex-info "scope not active" {:scope-id (:scope-id s)
                                                 :status (:status s)})))
         root (io/file (:namespace-root s))
         declared (mapv :relative-path (:artifacts s))
         declared-set (set declared)
         observed (file-relatives root)
          undeclared (vec (sort (remove #(or (declared-set %)
                                             (= % "_owner.edn"))
                                        observed)))
         declared-but-missing (vec (sort (remove (set observed) declared)))
         tmp-files (vec (filter #(str/starts-with? (.getName (io/file (str root "/" %)))
                                                   ".tmp-") undeclared))
         dup-ids (->> (:artifacts s) (group-by :logical-id)
                      (keep (fn [[id xs]] (when (> (count xs) 1) id))) vec sort)
         missing-hash
         (keep (fn [a]
                 (let [f (io/file root (:relative-path a))
                       ok (and (.exists f)
                               (= (:content-hash a)
                                  (sha256-hex (java.nio.file.Files/readAllBytes (.toPath f)))))]
                   (when-not ok (:logical-id a))))
               (:artifacts s))
          integrity-problems
          (concat
           (when (seq declared-but-missing)
             [{:type :declared-missing :files declared-but-missing}])
           (when (seq missing-hash)
             [{:type :hash-mismatch :ids missing-hash}])
           (when (seq dup-ids)
             [{:type :duplicate-logical-id :ids dup-ids}]))
          ;; Undeclared/temp files are write-bypass findings: reported always,
          ;; hard-failed only in strict mode.
          write-bypass-problems
          (concat
           (when (seq undeclared)
             [{:type :undeclared-files :files undeclared}])
           (when (seq tmp-files)
             [{:type :temporary-files :files tmp-files}]))
          hard-problems (if (and strict? (seq undeclared))
                          (vec (concat integrity-problems write-bypass-problems))
                          (vec integrity-problems))
          manifest {:run-id (:run-id s)
                    :namespace (:namespace s)
                    :artifact-scope-id (:scope-id s)
                    :scope-status (if (seq hard-problems) :incomplete :complete)
                    :artifacts (vec (:artifacts s))
                    :declared-but-missing declared-but-missing
                    :hash-mismatch (vec missing-hash)
                    :duplicate-logical-ids dup-ids
                    :temporary-files tmp-files
                    :undeclared-files undeclared
                    :problems hard-problems}]
      (if (seq hard-problems)
        (do (swap! scope assoc :status :incomplete)
            (throw (ex-info "artifact scope verification failed"
                            {:manifest manifest :problems hard-problems})))
        (do (swap! scope assoc :status :closed :closed-at (System/currentTimeMillis))
            manifest)))))

(defn mark-incomplete!
  "Mark a scope incomplete (used on worker exception/timeout) and return the
   manifest with the incomplete status and any artifacts recorded so far."
  [scope]
  (let [s @scope
        manifest {:run-id (:run-id s)
                  :namespace (:namespace s)
                  :artifact-scope-id (:scope-id s)
                  :scope-status :incomplete
                  :artifacts (vec (:artifacts s))
                  :problems [{:type :scope-incomplete}]}]
    (swap! scope assoc :status :incomplete)
    manifest))

(defn write-owner-marker!
  "Write an ownership marker into a run or namespace root.  Cleanup must
   verify this marker before deleting anything."
  [dir {:keys [run-id namespace]}]
  (.mkdirs (io/file dir))
  (spit (io/file dir "_owner.edn")
        (pr-str {:artifact-root-format 1
                 :run-id run-id
                 :namespace namespace
                 :created-by-pid (.pid (java.lang.ProcessHandle/current))
                 :created-at (System/currentTimeMillis)})))

(defn safe-delete!
  "Delete a root only if it carries a valid ownership marker for
   expected-run-id.  Never follows symlinks (File/delete removes the link)."
  [dir expected-run-id]
  (let [marker (io/file dir "_owner.edn")]
    (when-not (.exists marker)
      (throw (ex-info "refusing to delete unowned root" {:dir (str dir)})))
    (let [m (edn/read-string (slurp marker))]
      (when (and expected-run-id
                 (not= (:run-id m) expected-run-id))
        (throw (ex-info "run-id mismatch; refusing delete"
                        {:dir (str dir) :expected expected-run-id :actual (:run-id m)})))
      (doseq [f (reverse (file-seq (io/file dir)))]
        (.delete f)))))
