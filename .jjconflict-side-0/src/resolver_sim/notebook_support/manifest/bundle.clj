(ns resolver-sim.notebook-support.manifest.bundle
  "Evidence bundle helpers: write a portable evidence bundle including the
  manifest, artifact registry, provenance, and bundle-root hash. Uses the
  canonical hash utilities from resolver-sim.notebooks.manifest.hash.
  "
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.notebook-support.manifest.hash :as mhash]))

(defn export-bundle!
  "Write a portable evidence bundle to out-dir/:
    manifest.json    canonical manifest (may include :artifact-registry-sha)
    registry.json    artifact registry (test-artifacts.json), if provided
    provenance.json  run provenance record
    manifest-hash.txt
    bundle-hash.txt  root hash committing to manifest, registry and provenance

  Returns {:out-dir ... :bundle-hash ... :files [...]} or {:error ...}.
  "
  [manifest registry out-dir]
  (try
    (let [d    (io/file out-dir)
          _    (.mkdirs d)
          ;; canonical hashes with domain-appropriate intents
          manifest-h (mhash/hash-with-intent :manifest manifest)
          registry-h  (when registry (mhash/hash-with-intent :registry registry))
          prov {:schema_version  "provenance.v1"
                :run_id          (:run_id manifest)
                :canonical_hash  manifest-h
                :git_commit      (get-in manifest [:framework :git_commit])
                :suite           (:suite manifest)
                :artifact_count  (count (get registry :artifacts []))
                :generated_at    (str (java.time.Instant/now))}
          prov-h (mhash/hash-with-intent :provenance prov)
          ;; bundle root commits to canonical manifest-h, registry-h and prov-h
          bundle-root {:manifest_hash   manifest-h
                       :registry_hash   registry-h
                       :provenance_hash prov-h}
          bundle-h (hc/hash-with-intent {:hash/intent :bundle-root} bundle-root)
          files [(io/file out-dir "manifest.json")
                 (io/file out-dir "registry.json")
                 (io/file out-dir "provenance.json")
                 (io/file out-dir "manifest-hash.txt")
                 (io/file out-dir "bundle-hash.txt")]]
      (spit (nth files 0)  (json/write-str manifest {:indent true}))
      (when registry
        (spit (nth files 1) (json/write-str registry {:indent true})))
      (spit (nth files 2) (json/write-str prov     {:indent true}))
      (spit (nth files 3)  (str manifest-h "\n"))
      (spit (nth files 4)  (str bundle-h "\n"))
      {:out-dir out-dir
       :bundle-hash bundle-h
       :files   (->> files (map #(.getPath %)) (remove nil?))})
    (catch Exception e
      {:error (.getMessage e)})))