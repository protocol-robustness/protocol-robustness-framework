(ns resolver-sim.scripts.evidence
  "CLI wrapper: evidence bundle, signing, and registry helpers.

  Usage:
    clojure -M -m resolver-sim.scripts.evidence bundle <out-dir>
    clojure -M -m resolver-sim.scripts.evidence sign <private-key-path>
    clojure -M -m resolver-sim.scripts.evidence registry [--strict] [run-dir]

  The commands operate on the focused run (results/.notebook-focus) or the
  latest results/test-artifacts directory.

  The registry command does NOT require the focused manifest.

  Options:
    --strict    Enable strict validation mode (promotes warnings to failures)"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.evidence.registry-validation :as rv]
            [resolver-sim.notebook-support.manifest.loader :as loader]
            [resolver-sim.notebook-support.manifest.bundle :as bundle]
            [resolver-sim.notebook-support.manifest.publication :as pub]))

(defn- write-file! [dir name content]
  (let [f (io/file dir name)]
    (.mkdirs (io/file dir))
    (spit f (json/write-str content {:indent true}))
    (.getPath f)))

(defn -main [& args]
  (let [args-vec (vec args)
        strict? (some #{"--strict"} args-vec)
        cmd (first (remove #{"--strict"} args))
        param (second (remove #{"--strict"} args))
        run  (loader/load-focused)
        manifest (:manifest run)
        registry (:registry run)
        out-dir (or param (evcfg/evidence-bundle-dir))]
    (case cmd
      "registry"
      (let [dir (or param (str (evcfg/artifact-dir)))]
        (println "Building evidence registry for:" dir)
        (let [res (rv/build-evidence-registry! :dir dir :strict strict?)]
          (println "  Registry:" (:registry-path res))
          (println "  Validation:" (:validation-path res))
          (println "  Entries:" (:entry-count res))
          (println "  Status:" (:validation-status res))))

      "bundle"
      (do (when-not manifest
            (println "ERROR: no manifest found. Run bb run:scenario first.")
            (System/exit 1))
          (let [res (bundle/export-bundle! manifest registry out-dir)]
            (if (:error res)
              (do (println "ERROR:" (:error res)) (System/exit 1))
              (do (println "Wrote bundle to:" (:out-dir res)) (println "Bundle hash:" (:bundle-hash res))))))

      "sign"
      (let [key-path param]
        (when (or (nil? registry) (empty? (:artifacts registry)))
          (println "ERROR: missing artifact registry; run artifacts:index or evidence:build first.")
          (System/exit 1))
        (let [result (pub/sign-manifest manifest key-path :registry registry)]
          (if (:error result)
            (do (println "ERROR:" (:error result)) (System/exit 1))
            (let [signed-manifest (:manifest result)
                  latest-dir (evcfg/artifact-dir)
                  run-dir (:dir run)
                  envelope {:registry_sha256 (:artifact-registry-sha signed-manifest)
                            :run_id (:run_id signed-manifest)
                            :timestamp (str (java.time.Instant/now))
                            :chain-final (boolean (:artifact-registry-sha signed-manifest))}
                  envelope-json (json/write-str envelope {:indent true})
                  signature-json (json/write-str {:signature (:signature result)
                                                  :hash (:hash result)
                                                  :signer key-path} {:indent true})]
              (spit (io/file latest-dir (evcfg/artifact-file :test-run)) (json/write-str signed-manifest {:indent true}))
              (spit (io/file latest-dir (evcfg/artifact-file :envelope)) envelope-json)
              (spit (io/file latest-dir (evcfg/artifact-file :signature)) signature-json)
              (when run-dir
                (spit (io/file run-dir (evcfg/artifact-file :test-run)) (json/write-str signed-manifest {:indent true}))
                (spit (io/file run-dir (evcfg/artifact-file :envelope)) envelope-json)
                (spit (io/file run-dir (evcfg/artifact-file :signature)) signature-json))
              (println "Signed manifest hash:" (:hash result))
              (println "Signature produced. Evidence chain is now FINAL.")))))

      (do (println "Unknown command. Use 'bundle <out-dir>', 'sign <key-path>', or 'registry [run-dir]'.")
          (System/exit 1)))))
