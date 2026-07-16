(ns resolver-sim.commands.scenario-registry
  "Immutable final inventory for structured scenario bundles."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.nio.file Files Path Paths]
           [java.security MessageDigest]
           [java.math BigInteger]))

(def ^:private excluded #{"manifest/artifacts.json"
                          "manifest/artifact-registry-validation.json"
                          "completion.json" ".run-state"})

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop [read (.read stream buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur (.read stream buffer))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- registered-file [^Path root raw-path]
  (when-not (and (string? raw-path) (seq raw-path))
    (throw (ex-info "Artifact path must be a non-empty string" {:path raw-path})))
  (let [path (.normalize (.resolve root raw-path))]
    (when-not (.startsWith path root)
      (throw (ex-info "Artifact path escapes run root" {:path raw-path})))
    (let [relative (str (.relativize root path))]
      (when (excluded relative)
        (throw (ex-info "Excluded artifact must not be registered" {:path relative})))
      (when-not (Files/isRegularFile path (make-array java.nio.file.LinkOption 0))
        (throw (ex-info "Registered artifact is missing" {:path relative})))
      [path relative])))

(defn finalize! [run-root]
  (let [root (.toAbsolutePath (.normalize (Paths/get (str run-root) (make-array String 0))))
        registry-file (.toFile (.resolve root "manifest/artifacts.json"))
        registry (json/read-str (slurp registry-file) :key-fn keyword)
        seen (atom #{})
        entries (mapv (fn [entry]
                        (let [id (:id entry)
                              _ (when (or (not (string? id)) (@seen id))
                                  (throw (ex-info "Invalid or duplicate artifact ID" {:id id})))
                              _ (swap! seen conj id)
                              [path relative] (registered-file root (:path entry))]
                          (-> entry
                              (assoc :path relative :sha256 (sha256 (.toFile path)) :bytes (.length (.toFile path)))
                              (dissoc :mtime_utc))))
                      (:artifacts registry))
        hashes (into {} (map (juxt :id :sha256) entries))
        entries (mapv (fn [entry]
                        (update entry :dependencies
                                (fn [deps] (mapv (fn [dep]
                                                   (if-let [hash (get hashes (:id dep))]
                                                     (assoc dep :sha256 hash) dep))
                                                 (or deps []))))) entries)
        final (assoc registry :root_dir "." :artifacts (vec (sort-by :id entries))
                     :generator {:name "finalize-scenario-registry" :version "clojure.v1"})
        temp (io/file (str (.getPath registry-file) ".tmp"))]
    (spit temp (json/write-str final))
    (Files/move (.toPath temp) (.toPath registry-file)
                (into-array java.nio.file.StandardCopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                                               java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
    final))
