(ns resolver-sim.scripts.verify-signature
  "CLI: verify a manifest signature for the focused/latest run.

  Usage: clojure -M -m resolver-sim.scripts.verify-signature <public-key-path>
  "
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.notebook-support.manifest.loader :as loader]
            [resolver-sim.notebook-support.manifest.publication :as pub]))

(defn -main [& args]
  (let [pubkey (first args)
        run (loader/load-focused)
        manifest (:manifest run)
        registry (:registry run)
        latest-dir (evcfg/artifact-dir)
        sigf (io/file latest-dir (evcfg/artifact-file :signature))]
    (when-not pubkey
      (println "Usage: clojure -M -m resolver-sim.scripts.verify-signature <public-key-path>")
      (System/exit 1))
    (when-not manifest
      (println "No manifest found; run scenario first.")
      (System/exit 1))
    (when-not (.exists sigf)
      (println "No signature.json found in results/test-artifacts; sign first.")
      (System/exit 1))
    (let [sig-obj (json/read-str (slurp sigf) :key-fn keyword)
          signature (get sig-obj :signature)
          ;; Reconstruct the registry-bound manifest that sign-manifest signs.
          res (pub/verify-manifest-signature manifest signature pubkey :registry registry)]
      (if (:error res)
        (do (println "ERROR:" (:error res)) (System/exit 2))
        (do (println "Signature valid?" (:valid res))
            (println "Canonical hash:" (:hash res))
            (when-not (:valid res) (System/exit 3)))))))