(ns resolver-sim.evidence.forensic-adapter
  "Extension point for derived forensic evidence artifacts.

   Adapters read existing evidence artifacts and write derived diagnostic indexes
   without mutating source evidence or changing capture semantics."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.io.event-evidence :as event-evidence]))

(defprotocol ForensicEvidenceAdapter
  (adapter-id [this])
  (adapter-version [this])
  (adapter-kind [this])
  (build-adapter-artifact [this opts]))

(defn- input-artifact-summary
  [artifact-root artifact]
  (let [path (or (:path artifact)
                 (:relative-path artifact)
                 (:artifact/path artifact))
        f (when path
            (let [file (io/file path)]
              (if (.isAbsolute file)
                file
                (io/file artifact-root path))))]
    (cond-> {:path path
             :hash (or (:hash artifact)
                       (:evidence/hash artifact)
                       (:evidence-hash artifact)
                       (:sha256 artifact))}
      (and f (.exists f)) (assoc :sha256 (chain/compute-file-sha256 (.getPath f))))))

(defn write-forensic-adapter-output!
  "Write a derived forensic adapter artifact and register it as diagnostic.

   Required keys:
   - :adapter-id
   - :adapter-version
   - :adapter-kind
   - :schema-version
   - :artifact-root
   - :output-path relative to artifact-root, or absolute
   - :input-artifacts collection of maps/paths containing paths and hashes
   - :output derived payload

   Returns {:path ... :output-hash ... :artifact-entry ...}."
  [{:keys [adapter-id adapter-version adapter-kind schema-version artifact-root
           output-path input-artifacts output]
    :or {schema-version "forensic-adapter-output.v1"
         artifact-root (evcfg/artifact-dir)}}]
  (let [root (str artifact-root)
        out-file (let [f (io/file output-path)]
                   (if (.isAbsolute f) f (io/file root output-path)))
        inputs (mapv (fn [a]
                       (if (map? a)
                         (input-artifact-summary root a)
                         (input-artifact-summary root {:path (str a)})))
                     input-artifacts)
        output-hash (hc/hash-with-intent {:hash/intent :evidence-record} output)
        artifact {:schema-version schema-version
                  :generated-at (str (java.time.Instant/now))
                  :adapter/id adapter-id
                  :adapter/version adapter-version
                  :adapter/kind adapter-kind
                  :artifact-root root
                  :input-artifacts inputs
                  :output-hash output-hash
                  :output output}]
    (.mkdirs (.getParentFile out-file))
    (spit out-file (json/write-str artifact {:key-fn event-evidence/qualified-key :indent true}))
    (let [entry {:id (str "forensic-adapter-" (name adapter-id))
                 :kind "derived-diagnostic-artifact"
                 :path (.getPath out-file)
                 :schema-version schema-version
                 :producer "forensic-adapter.v1"
                 :importance "DIAGNOSTIC"
                 :adapter-id (str adapter-id)
                 :adapter-version adapter-version
                 :adapter-kind (str adapter-kind)
                 :output-hash output-hash
                 :sha256 (chain/compute-file-sha256 (.getPath out-file))
                 :bytes (.length out-file)
                 :mtime-utc (str (java.time.Instant/ofEpochMilli (.lastModified out-file)))}]
      (chain/register-additional-artifact! entry)
      {:path (.getPath out-file)
       :output-hash output-hash
       :artifact-entry entry})))

(defn run-adapter!
  "Run a protocol-based forensic adapter and write its returned artifact when the
   adapter implementation delegates to write-forensic-adapter-output!."
  [adapter opts]
  (build-adapter-artifact adapter opts))
