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
  (let [evidence (edn/read-string (slurp evidence-path))
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
  (let [evidence (edn/read-string (slurp evidence-path))
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
  (let [evidence (edn/read-string (slurp evidence-path))

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
  (let [attestation (edn/read-string (slurp attestation-path))
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
