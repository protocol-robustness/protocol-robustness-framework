(ns resolver-sim.benchmark.sharing
  (:require [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.benchmark.coverage :as coverage]
            [resolver-sim.benchmark.repo :as repo]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.dag :as dag]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn generate-reproduce-command [evidence-path]
  (str "bb benchmark:reproduce " evidence-path))

(def ^:private object-tag-reader
  "LEGACY RUNTIME-VALUE COMPATIBILITY ONLY — not a permanent serialization design.

   Reads #object[...] tagged literals emitted by pr-str for non-portable
   Clojure objects (e.g. yield-module fns, java.time.Instant). Clojure function
   objects are not portable data: a reader can turn the textual form into an
   opaque placeholder vector, but it cannot reconstruct the original function
   or establish its identity. This reader exists so legacy bundles written
   before the writer-boundary migration remain readable for reproduction and
   export.

   Durable fix is at the WRITER boundary: evidence should serialize a stable
   yield-module identifier/version/config, not the runtime fn value. Until
   then, opaque object representations must not be relied upon in any field
   that influences admission or assurance. Note the committed :evidence/hash
   (:bundle-root) already normalizes runtime fns to a deterministic {:type :fn}
   marker via project-world-to-structure-view, so it is unaffected by this tag.

   Returns an unmistakable legacy sentinel map (never the raw vector), so it
   cannot accidentally satisfy domain code that expects sequential data and so
   admission/validation logic can categorically detect and reject it:
   {:legacy/runtime-object true
    :legacy/class <class-name>
    :legacy/printed-representation <pr-str output>}"
  (fn [[class-sym _hex printed :as v]]
    (if (vector? v)
      {:legacy/runtime-object true
       :legacy/class (when class-sym (str class-sym))
       :legacy/printed-representation printed}
      {:legacy/runtime-object true
       :legacy/printed-representation (str v)})))

(defn legacy-object?
  "True if x is a legacy sentinel emitted by object-tag-reader, i.e. the
   residue of a non-portable #object[...] runtime value read from a legacy
   evidence bundle. Such values must be categorically excluded from new
   evidence admission."
  [x]
  (and (map? x) (= true (:legacy/runtime-object x))))

(defn read-evidence-file
  "Reads an evidence bundle, tolerating the #object tagged literals that
  pr-str emits for non-portable Clojure objects (e.g. yield-module fns,
  java.time.Instant). These values are not round-trippable; reproduce/export
  only read the surrounding canonical map, so each tagged literal is kept as
  an inert legacy sentinel map (see legacy-object?)."
  [path]
  (edn/read-string {:readers {'object object-tag-reader}}
                   (slurp path)))

(defn share-summary
  ([evidence] (share-summary evidence "evidence/latest.edn"))
  ([evidence evidence-path]
   (let [bm-id (get-in evidence [:benchmark :benchmark/id])
         protocol-commit (get-in evidence [:repo :repo :commit])
         scenarios-pass? (= (get-in evidence [:metrics :passed])
                            (get-in evidence [:metrics :total]))
         active? (= :active (get-in evidence [:benchmark :benchmark/status]))
         claims-pass? (coverage/required-claims-passed?
                       (:benchmark evidence)
                       (:claim-results evidence))
         outcome (cond
                   (not scenarios-pass?) "SCENARIOS FAILED"
                   (and active? claims-pass?) "ACTIVE BENCHMARK PASS"
                   active? "SCENARIOS PASS; REQUIRED CLAIMS INCOMPLETE"
                   :else "EXPERIMENTAL: SCENARIOS PASS")
         evidence-hash (:evidence/hash evidence)
         signed? (contains? evidence :evidence/signature)]
     (str "Benchmark:\n" bm-id "\n\n"
          "Protocol Commit:\n" protocol-commit "\n\n"
          "Result:\n" outcome "\n\n"
          "Evidence Hash:\n" evidence-hash "\n\n"
          "Signed:\n" (if signed? "yes" "no") "\n\n"
          "Reproduce:\n" (generate-reproduce-command evidence-path)))))

(defn reproduce [evidence-path]
  (let [evidence (read-evidence-file evidence-path)
        target-commit (get-in evidence [:repo :repo :commit])
        current-meta (repo/metadata)
        current-commit (get-in current-meta [:repo :commit])
        manifest-path (get-in evidence [:benchmark :manifest])]
    (if (not= target-commit current-commit)
      (do
        (println "Warning: Repository commit mismatch.")
        (println "Target commit: " target-commit)
        (println "Current commit: " current-commit))
      (println "Repository state matches."))

    (println "Rerunning benchmark...")
    ;; We need to make sure we use the same manifest. 
    ;; Manifest might have changed, so ideally we'd use the one in evidence.
    ;; But for now let's use manifest-path if available.
    (let [new-evidence (runner/run-benchmark (or manifest-path hash-ref/escrow-dispute-pack-path))
          new-hash (:evidence/hash new-evidence)
          old-hash (:evidence/hash evidence)]
      (println "Recomputed Hash: " new-hash)
      (println "Original Hash:   " old-hash)
      (if (hc/intent-hash= new-hash old-hash)
        (do (println "✓ Hash match! Results are reproducible.") true)
        (do (println "✗ Hash mismatch! Results differ from original run.") false)))))

(def ^:private required-export-entries
  #{"./evidence.edn" "./manifest.edn" "./repo.edn" "./results.edn" "./metrics.edn" "./export-manifest.json"})

(defn- sha256-file [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (java.io.FileInputStream. file)]
      (let [buffer (byte-array 8192)]
        (loop [n (.read in buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur (.read in buffer))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- write-export-manifest! [tmp-dir evidence-hash]
  (let [files (->> (file-seq tmp-dir)
                   (filter #(.isFile %))
                   (sort-by #(.getPath %)))
        base (.toPath tmp-dir)
        artifacts (mapv (fn [file]
                          {:path (str (.relativize base (.toPath file)))
                           :sha256 (sha256-file file)}) files)
        manifest {:manifest/version "benchmark-export.v1"
                  :evidence/hash evidence-hash
                  :artifacts artifacts}
        path (io/file tmp-dir "export-manifest.json")]
    (spit path (json/write-str manifest {:key-fn name :indent true}))
    {:path path :manifest manifest :sha256 (sha256-file path)}))

(defn export [evidence-path export-tar-path]
  (let [evidence (read-evidence-file evidence-path)
        tmp-dir (.toFile (java.nio.file.Files/createTempDirectory "benchmark-export-"
                                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        export-file (.getAbsoluteFile (io/file export-tar-path))]
    (try
      (when-let [parent (.getParentFile export-file)]
        (.mkdirs parent))
      (spit (io/file tmp-dir "evidence.edn") (pr-str evidence))
      (spit (io/file tmp-dir "manifest.edn") (pr-str (:benchmark evidence)))
      (spit (io/file tmp-dir "repo.edn") (pr-str (:repo evidence)))
      (spit (io/file tmp-dir "results.edn") (pr-str (:results evidence)))
      (spit (io/file tmp-dir "metrics.edn") (pr-str (:metrics evidence)))
      (let [graph-dir (io/file (dag/artifact-directory evidence-path))]
        (when-not (.isDirectory graph-dir)
          (dag/export! evidence-path))
        (let [{:keys [exit err]} (shell/sh "cp" "-R" (.getPath graph-dir) (.getPath tmp-dir))]
          (when-not (zero? exit)
            (throw (ex-info "Could not stage evidence DAG artifacts" {:error err})))))
      (let [{manifest-path :path manifest-sha256 :sha256}
            (write-export-manifest! tmp-dir (:evidence/hash evidence))
            {create-exit :exit create-err :err}
            (shell/sh "tar" "--sort=name" "--mtime=2026-01-01" "--owner=0" "--group=0"
                      "--numeric-owner" "-czf" (.getPath export-file) "-C" (.getPath tmp-dir) ".")
            {verify-exit :exit verify-out :out verify-err :err}
            (if (zero? create-exit)
              (shell/sh "tar" "-tzf" (.getPath export-file))
              {:exit 1 :out "" :err create-err})
            entries (set (str/split-lines verify-out))]
        (if (and (zero? create-exit)
                 (zero? verify-exit)
                 (every? entries required-export-entries))
          (do
            (spit (str export-tar-path ".manifest.json")
                  (json/write-str {:manifest/version "benchmark-delivery.v1"
                                   :evidence/hash (:evidence/hash evidence)
                                   :archive/path export-tar-path
                                   :archive/sha256 (sha256-file export-file)
                                   :export-manifest/sha256 manifest-sha256}
                                  {:key-fn name :indent true}))
            (println "Portable bundle exported to:" export-tar-path)
            true)
          (do (println "Export failed:" (str/trim (or verify-err create-err))) false)))
      (catch java.io.IOException e
        (println "Export failed: could not run `tar`." (.getMessage e))
        false)
      (finally
        (shell/sh "rm" "-rf" (.getPath tmp-dir))))))

(defn publish-ipfs [export-tar-path]
  (try
    (let [{:keys [exit out err]} (shell/sh "ipfs" "add" "-Q" export-tar-path)]
      (if (zero? exit)
        (let [cid (str/trim out)
              delivery-path (str export-tar-path ".manifest.json")
              delivery (when (.isFile (io/file delivery-path))
                         (json/read-str (slurp delivery-path) :key-fn keyword))
              manifest {:ipfs-cid cid
                        :timestamp (System/currentTimeMillis)
                        :bundle-path export-tar-path
                        :evidence-hash (:evidence/hash delivery)
                        :archive-sha256 (:archive/sha256 delivery)
                        :export-manifest-sha256 (:export-manifest/sha256 delivery)}]
          (spit "evidence-manifest.json" (json/write-str manifest {:key-fn name :indent true}))
          (println "Published to IPFS")
          (println "\nCID:\n" cid)
          (println "\nGateway:\n" (str "https://ipfs.io/ipfs/" cid))
          cid)
        (do
          (println "IPFS publication failed:" (str/trim err))
          nil)))
    (catch java.io.IOException e
      (println "IPFS publication unavailable: could not run `ipfs`."
               "Install the IPFS CLI and ensure it is on PATH.")
      nil)))

(defn attest [evidence-path private-key-path password]
  (let [evidence (read-evidence-file evidence-path)

        bm-id (get-in evidence [:benchmark :benchmark/id])
        bm-commit (get-in evidence [:benchmark :commit])
        protocol-commit (get-in evidence [:repo :repo :commit])
        evidence-hash (:evidence/hash evidence)
        signature (signing/sign-hash evidence-hash private-key-path password)
        attestation {:benchmark/id bm-id
                     :benchmark/commit bm-commit
                     :protocol/commit protocol-commit
                     :evidence/hash evidence-hash
                     :attestor {:public-key-path (str private-key-path ".pub")}
                     :signature signature}]
    (println "Attestation generated.")
    attestation))

(defn verify-attestation [attestation-path]
  (let [attestation (read-evidence-file attestation-path)
        evidence-hash (:evidence/hash attestation)
        signature (:signature attestation)
        pub-key-path (get-in attestation [:attestor :public-key-path])]
    (if (and evidence-hash signature pub-key-path)
      (let [sig-ok? (signing/verify-signature evidence-hash signature pub-key-path)]
        (println "Attestation Verification:")
        (println "Benchmark ID:    " (:benchmark/id attestation))
        (println "Evidence Hash:   " evidence-hash)
        (println "Signature valid: " (if sig-ok? "✓" "✗"))
        sig-ok?)
      (do
        (println "Invalid attestation format.")
        false))))
