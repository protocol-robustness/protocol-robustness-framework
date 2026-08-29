(ns resolver-sim.benchmark.authority-state-snapshot-store
  "P1b node-local durable current-pointer store for verified authority snapshots.

  Immutable snapshot and dependency-manifest bodies live in the supplied
  content-addressed object store. This namespace owns only the durable current
  pointer and writer lease; it never promotes a snapshot whose closure has not
  been independently read back and verified."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.authority-state-snapshot :as snapshot]
            [resolver-sim.io.content-addressed-store :as cas])
  (:import [java.nio.channels FileChannel FileLock]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util UUID]))

(def ^:const pointer-schema "authority-state-current-pointer.v1")

(def pointer-fields
  #{:artifact/schema
    :authority-store/format
    :authority-store/id
    :chain-instance-genesis/root
    :authority-state-snapshot/root})

(defn- reject! [reason message data]
  (throw (ex-info message (assoc data :reason reason))))

(defn- pointer-valid? [pointer]
  (and (map? pointer)
       (= pointer-fields (set (keys pointer)))
       (= pointer-schema (:artifact/schema pointer))
       (= snapshot/store-format (:authority-store/format pointer))))

(defn- canonical-read! [file reason]
  (let [bytes (Files/readAllBytes (.toPath file))
        text (String. bytes StandardCharsets/UTF_8)
        value (try (edn/read-string text)
                   (catch Exception _
                     (reject! reason "Durable authority pointer is malformed" {:path (str file)})))]
    (when-not (= text (cas/canonical-edn value))
      (reject! reason "Durable authority pointer bytes are not canonical EDN" {:path (str file)}))
    value))

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

(defn- durable-replace! [target value]
  (let [parent (.getParentFile target)
        _ (.mkdirs parent)
        temporary (io/file parent (str "." (.getName target) ".tmp-" (UUID/randomUUID)))]
    (try
      (Files/write (.toPath temporary)
                   (.getBytes (cas/canonical-edn value) StandardCharsets/UTF_8)
                   (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW
                                                   StandardOpenOption/WRITE]))
      (force-file! temporary)
      (Files/move (.toPath temporary) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      (when-not (force-directory! parent)
        (reject! :directory-durability-unavailable
                 "Authority current pointer directory cannot be synchronised"
                 {:path (str parent)}))
      true
      (finally
        (Files/deleteIfExists (.toPath temporary))))))

(defn open-store!
  "Open one node-local authority snapshot directory and acquire its exclusive
  writer lease. The caller supplies the expected immutable store and chain
  identities; opening another chain or store under the same path fails closed."
  [root store-id genesis-root]
  (let [directory (io/file root)
        _ (.mkdirs directory)
        lock-file (io/file directory "writer.lock")
        _ (when-not (.exists lock-file) (spit lock-file ""))
        channel (FileChannel/open (.toPath lock-file)
                                  (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                                  StandardOpenOption/WRITE]))
        lock (try (.tryLock channel)
                  (catch java.nio.channels.OverlappingFileLockException _ nil))]
    (when-not lock
      (.close channel)
      (reject! :authority-store-writer-locked
               "Authority snapshot store already has a writer" {:root (str root)}))
    {:root (str root)
     :store-id store-id
     :genesis-root genesis-root
     :objects (cas/create-store (io/file directory "objects"))
     :pointer-file (io/file directory "current.edn")
     :writer-channel channel
     :writer-lock lock}))

(defn close-store! [store]
  (when-let [^FileLock lock (:writer-lock store)] (.release lock))
  (when-let [^FileChannel channel (:writer-channel store)] (.close channel))
  nil)

(defn- read-snapshot! [store root]
  (let [body (cas/resolve-artifact (:objects store) root)]
    (when-not body
      (reject! :current-snapshot-unavailable "Current authority snapshot is unavailable" {:root root}))
    (when-not (= root (:authority-state-snapshot/root body))
      (reject! :current-snapshot-address-mismatch "Current snapshot does not match pointer" {:root root}))
    body))

(defn read-current!
  "Read the exact durable current snapshot and verify its complete closure. A
  corrupt pointer, missing snapshot, or invalid closure fails closed; this
  function never searches for or falls back to an older snapshot."
  [store verify-object verify-semantic-joins]
  (let [file (:pointer-file store)]
    (when-not (.exists file)
      (reject! :authority-store-uninitialised "Authority store has no current snapshot" {}))
    (let [pointer (canonical-read! file :invalid-current-pointer)]
      (when-not (pointer-valid? pointer)
        (reject! :invalid-current-pointer "Authority current pointer has invalid shape" {}))
      (when-not (and (= (:store-id store) (:authority-store/id pointer))
                     (= (:genesis-root store) (:chain-instance-genesis/root pointer)))
        (reject! :authority-store-identity-mismatch
                 "Authority current pointer belongs to a different store or chain" {}))
      (let [body (read-snapshot! store (:authority-state-snapshot/root pointer))]
        (when-not (and (= (:store-id store) (:authority-store/id body))
                       (= (:genesis-root store) (:chain-instance-genesis/root body)))
          (reject! :authority-store-identity-mismatch
                   "Authority current snapshot belongs to a different store or chain" {}))
        (snapshot/verify-snapshot-closure!
         body #(cas/resolve-artifact (:objects store) %) verify-object verify-semantic-joins)))))

(defn publish-current!
  "Publish a fully materialised P1 snapshot as the only current snapshot.
  Snapshot and manifest must already be immutable CAS objects. Their entire
  closure is read back and verified before the synced atomic pointer replacement
  is attempted. A durability failure throws and does not acknowledge success."
  [store snapshot-body verify-object verify-semantic-joins]
  (when-not (and (= (:store-id store) (:authority-store/id snapshot-body))
                 (= (:genesis-root store) (:chain-instance-genesis/root snapshot-body)))
    (reject! :authority-store-identity-mismatch
             "Candidate snapshot belongs to a different store or chain" {}))
  (let [root (:authority-state-snapshot/root snapshot-body)
        persisted (cas/put-if-absent!
                   (:objects store)
                   {:hash-reference root
                    :artifact snapshot-body
                    :verify #(-> % snapshot/validate-snapshot :valid?)})]
    (when-not (:crash-durable? persisted)
      (reject! :snapshot-object-not-durable
               "Candidate snapshot object was not durably prepared" {:root root}))
    (snapshot/verify-snapshot-closure!
     (:artifact persisted) #(cas/resolve-artifact (:objects store) %) verify-object verify-semantic-joins)
    (durable-replace!
     (:pointer-file store)
     {:artifact/schema pointer-schema
      :authority-store/format snapshot/store-format
      :authority-store/id (:store-id store)
      :chain-instance-genesis/root (:genesis-root store)
      :authority-state-snapshot/root root})
    {:published? true :authority-state-snapshot/root root}))
