(ns resolver-sim.io.resource-path
  "Path resolution utility supporting three URI schemes:

     resource:path — classpath resource via io/resource
     file:path     — absolute or relative filesystem path
     path          — user convenience path; try filesystem first, then classpath

   Internal defaults must use explicit resource: paths, not bare paths.
   External/experimental paths use file: prefixes or bare file paths."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private resource-prefix hash-ref/resource-prefix)
(def ^:private file-prefix "file:")

(defn- resource-path
  [path]
  (some-> (io/resource path) str))

(defn- file-path
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (.getAbsolutePath f))))

(defn resolve-path
  "Resolve a path spec to an absolute filesystem path, or nil if not found.
   URI scheme:
     resource:path — classpath resource
     file:path     — filesystem path (absolute or relative to CWD)
     path          — try filesystem first, then classpath (convenience)"
  [path-spec]
  (cond
    (str/starts-with? path-spec resource-prefix)
    (resource-path (subs path-spec (count resource-prefix)))

    (str/starts-with? path-spec file-prefix)
    (file-path (subs path-spec (count file-prefix)))

    :else
    (or (file-path path-spec)
        (resource-path path-spec))))

(defn resolve-path!
  "Like resolve-path but throws if the path is not found."
  [path-spec]
  (or (resolve-path path-spec)
      (throw (ex-info (str "Path not found: " path-spec) {:path path-spec}))))

(defn open-input-stream
  "Open an input stream for a path spec.
   Supports resource:, file:, and bare path schemes.
   Throws if not found."
  [path-spec]
  (cond
    (str/starts-with? path-spec resource-prefix)
    (let [rpath (subs path-spec (count resource-prefix))]
      (or (some-> (io/resource rpath) io/input-stream)
          (throw (ex-info (str "Resource not found: " rpath) {:path path-spec}))))

    (str/starts-with? path-spec file-prefix)
    (let [fpath (subs path-spec (count file-prefix))]
      (io/input-stream (io/file fpath)))

    :else
    (let [f (io/file path-spec)]
      (if (.exists f)
        (io/input-stream f)
        (or (some-> (io/resource path-spec) io/input-stream)
            (throw (ex-info (str "Path not found: " path-spec) {:path path-spec})))))))

(defn slurp-path
  "Read the full content of a path spec as a string.
   Supports resource:, file:, and bare path schemes."
  [path-spec]
  (with-open [in (open-input-stream path-spec)]
    (slurp in)))

(defn edn-read
  "Read EDN from a path spec.
   Supports resource:, file:, and bare path schemes."
  [path-spec]
  (let [s (slurp-path path-spec)]
    (when s
      (edn/read-string s))))

(def ^:const canonical-registry-path hash-ref/benchmark-registry-path)

(defn path-exists?
  "Check whether a path spec is resolvable.
   Supports resource:, file:, and bare path schemes."
  [path-spec]
  (boolean (resolve-path path-spec)))

(defn relative-to
  "Resolve a relative path against a base resource: or file: path.
   If base is resource:..., the child is resolved as resource:base-dir/child.
   If base is file:..., the child is resolved as file:base-dir/child.
   Bare paths treat base as a filesystem directory."
  [base child]
  (cond
    (str/starts-with? base resource-prefix)
    (let [base-path (subs base (count resource-prefix))
          dir (subs base-path 0 (max 0 (str/last-index-of base-path "/")))]
      (str resource-prefix dir "/" child))

    (str/starts-with? base file-prefix)
    (let [base-path (subs base (count file-prefix))
          dir (subs base-path 0 (max 0 (str/last-index-of base-path "/")))]
      (str file-prefix dir "/" child))

    :else
    (let [f (io/file base)
          dir (.getParent f)]
      (if dir
        (str dir "/" child)
        child))))

(defn pack-registry-path
  "Resolve a pack registry path relative to the canonical registry base.
   The canonical registry lives at resource:benchmarks/registry.edn,
   so pack registries like 'packs/sew/registry.edn' are resolved as
   resource:benchmarks/packs/sew/registry.edn by default."
  ([pack-rel-path]
   (pack-registry-path pack-rel-path nil))
  ([pack-rel-path registry-path]
   (let [base (or registry-path canonical-registry-path)]
     (relative-to base pack-rel-path))))
