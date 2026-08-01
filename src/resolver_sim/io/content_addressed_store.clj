(ns resolver-sim.io.content-addressed-store
  "Immutable, unlinked content-addressed object storage.

   Bytes are strict canonical EDN, not an incidental `pr-str` rendering. The
   store intentionally has no evidence-chain integration: stored objects are
   durable preparation inputs until a separate terminal record makes them
   reachable."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files StandardOpenOption]
           [java.util UUID]))

(defn- byte-compare [^bytes left ^bytes right]
  (let [left-length (alength left)
        right-length (alength right)]
    (loop [index 0]
      (cond
        (= index (min left-length right-length)) (compare left-length right-length)
        :else (let [a (bit-and (int (aget left index)) 0xff)
                    b (bit-and (int (aget right index)) 0xff)]
                (if (= a b) (recur (inc index)) (compare a b)))))))

(defn- canonical-compare [left right]
  (byte-compare (hc/canonical-bytes left) (hc/canonical-bytes right)))

(defn- canonical-data
  "Accept only exact EDN data that has a deterministic, reversible EDN policy.
   Integers and ratios retain their exact EDN representation. Temporal values,
   floating point values, records, functions, lazy sequences, and arbitrary
   runtime objects are rejected rather than receiving a lossy fallback."
  [value]
  (cond
    (or (nil? value) (boolean? value) (string? value) (keyword? value) (integer? value)
        (instance? clojure.lang.Ratio value)) value
    (vector? value) (mapv canonical-data value)
    (map? value) (into (sorted-map-by canonical-compare)
                       (map (fn [[key item]] [(canonical-data key) (canonical-data item)]))
                       value)
    (set? value) (into (sorted-set-by canonical-compare) (map canonical-data value))
    :else (throw (ex-info "Content-addressed storage rejects non-canonical EDN data"
                          {:reason :non-canonical-storage-value
                           :type (some-> value class str)}))))

(defn canonical-edn
  "Canonical, reversible EDN bytes represented as a UTF-8 string."
  [artifact]
  (str (pr-str (canonical-data artifact)) "\n"))

(defn artifact-path
  "Return the deterministic EDN path for a qualified SHA-256 reference."
  [store hash-reference]
  (when-not (hash-ref/valid-sha256-ref? hash-reference)
    (throw (ex-info "Content-addressed store requires a qualified SHA-256 reference"
                    {:reason :invalid-hash-reference :hash hash-reference})))
  (let [digest (subs hash-reference (count "sha256:"))]
    (io/file (:root store) "sha256" (str digest ".edn"))))

(defn create-store
  "Create a filesystem-backed unlinked store. Every write reports whether the
   host accepted directory fsync; callers must not claim crash durability when
   `:crash-durable?` is false."
  [root]
  {:root (str root)})

(defn- force-file! [file]
  (with-open [channel (FileChannel/open (.toPath file)
                                        (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
    (.force channel true)))

(defn- force-directory! [directory]
  (try
    (with-open [channel (FileChannel/open (.toPath directory)
                                          (into-array StandardOpenOption [StandardOpenOption/READ]))]
      (.force channel true))
    true
    (catch UnsupportedOperationException _ false)
    (catch java.io.IOException _ false)))

(defn- atomic-create! [target content]
  (let [target-path (.toPath target)
        parent (.getParentFile target)
        temp (io/file parent (str "." (.getName target) ".tmp-" (UUID/randomUUID)))]
    (.mkdirs parent)
    (spit temp content)
    (force-file! temp)
    (try
      ;; Same-filesystem hard-link creation is atomic create-if-absent and never
      ;; replaces a pre-existing content-addressed key.
      (Files/createLink target-path (.toPath temp))
      {:status :created :directory-fsynced? (force-directory! parent)}
      (catch java.nio.file.FileAlreadyExistsException _
        {:status :exists :directory-fsynced? true})
      (finally
        (Files/deleteIfExists (.toPath temp))))))

(defn resolve-artifact
  "Resolve a canonical EDN object by qualified hash reference, or nil when
   absent. Read-side canonical byte validation rejects noncanonical files before
   artifact-specific self-hash verification occurs."
  [store hash-reference]
  (let [path (artifact-path store hash-reference)]
    (when (.exists path)
      (let [bytes (slurp path)
            artifact (edn/read-string bytes)]
        (when-not (= bytes (canonical-edn artifact))
          (throw (ex-info "Stored artifact bytes are not canonical EDN"
                          {:reason :noncanonical-stored-bytes
                           :hash hash-reference :path (str path)})))
        artifact))))

(defn put-if-absent!
  "Durably write a self-verifying immutable artifact without a canonical index.
   Identical writes are idempotent; a same-key/different-content write rejects.
   `:crash-durable?` reports whether the target directory was fsynced after the
   atomic link operation on this host."
  [store {:keys [hash-reference artifact verify]}]
  (when-not (hash-ref/valid-sha256-ref? hash-reference)
    (throw (ex-info "Invalid content-addressed storage key"
                    {:reason :invalid-hash-reference :hash hash-reference})))
  (when-not (map? artifact)
    (throw (ex-info "Content-addressed store accepts maps only"
                    {:reason :invalid-artifact :artifact artifact})))
  (when-not (and (fn? verify) (boolean (verify artifact)))
    (throw (ex-info "Artifact failed self-verification before persistence"
                    {:reason :artifact-verification-failed :hash hash-reference})))
  (let [path (artifact-path store hash-reference)
        encoded (canonical-edn artifact)
        result (atomic-create! path encoded)
        persisted (resolve-artifact store hash-reference)]
    (when-not (= artifact persisted)
      (throw (ex-info "Content-addressed storage collision"
                      {:reason :hash-content-collision :hash hash-reference :path (str path)})))
    {:status (if (= :created (:status result)) :stored :already-present)
     :hash hash-reference
     :artifact persisted
     :crash-durable? (:directory-fsynced? result)}))

(defn verify-stored-artifact
  "Resolve, canonical-byte-check, and authenticate an object using its
   artifact-specific verifier. Unresolved is distinct from invalid."
  [store hash-reference verify]
  (let [artifact (resolve-artifact store hash-reference)
        valid? (boolean (and artifact (fn? verify) (verify artifact)))]
    {:present? (some? artifact) :valid? valid? :hash hash-reference :artifact artifact}))
