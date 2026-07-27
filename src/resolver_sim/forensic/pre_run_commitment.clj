(ns resolver-sim.forensic.pre-run-commitment
  "Pre-run commitment manifest: binds source hash, deps hash, corpus hash,
   runner binary hash, and config hash BEFORE execution begins.
   Callers may provide an explicit execution directory; the legacy writer uses
   results/runs/<run-id>/pre-run-commitment.json."
  (:require [clojure.java.io :as io]
            [resolver-sim.forensic.source-hash :as src-hash]
            [resolver-sim.forensic.deps-hash :as deps-hash]
            [resolver-sim.forensic.corpus-hash :as corpus-hash]
            [clojure.data.json :as json])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(defn sha256-bytes
  "SHA-256 of a UTF-8 string, returned as hex."
  [s]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d (.getBytes s "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn sha256-file
  "SHA-256 of a file's content, or nil if not found."
  [path]
  (let [f (java.io.File. path)]
    (when (.isFile f)
      (let [d (MessageDigest/getInstance "SHA-256")]
        (.update d (java.nio.file.Files/readAllBytes (.toPath f)))
        (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))))

(defn build-commitment
  "Build the pre-run commitment map for a given run context.
   context may include :suite-key, :scenario-paths, :run-id."
  ([context] (build-commitment context nil))
  ([{:keys [suite-key scenario-paths run-id] :as context} runner-jar-path]
   (let [source (src-hash/source-hash)
         deps (deps-hash/deps-hash)
         corpus (when suite-key (try (corpus-hash/corpus-hash suite-key) (catch Exception _ nil)))
         runner-bin (when runner-jar-path
                      {:runner/binary-path runner-jar-path
                       :runner/binary-size (.length (java.io.File. runner-jar-path))
                       :runner/binary-sha256 (sha256-file runner-jar-path)})
         config-hash (sha256-file "config/evidence.json")
         commitment {:pre-run/schema-version "pre-run-commitment.v1"
                     :pre-run/generated-at (str (Instant/now))
                     :pre-run/run-id run-id
                     :pre-run/suite-key suite-key
                     :pre-run/source source
                     :pre-run/deps deps
                     :pre-run/corpus (when corpus (dissoc corpus :corpus/scenarios))
                     :pre-run/runner-binary runner-bin
                     :pre-run/config-hash config-hash
                     :pre-run/commitment-hash nil}
         ;; Self-referential hash: SHA-256 of the serialized map minus the hash field
         preimage (pr-str (dissoc commitment :pre-run/commitment-hash))
         hash (sha256-bytes preimage)]
     (assoc commitment :pre-run/commitment-hash hash))))

(defn write-commitment!
  "Write a pre-run commitment to disk.

   The one-argument arity retains the legacy results/runs location. An explicit
   execution-dir makes the commitment owned by a structured run bundle."
  ([commitment]
   (write-commitment! commitment nil))
  ([commitment execution-dir]
   (let [run-id (or (:pre-run/run-id commitment) "unknown")
         dir (or execution-dir (str (io/file "results" "runs" run-id)))
         f (io/file dir "pre-run-commitment.json")]
     (.mkdirs (io/file dir))
     (spit f (json/write-str commitment {:indent true}))
     {:path (.getPath f) :hash (:pre-run/commitment-hash commitment)})))
