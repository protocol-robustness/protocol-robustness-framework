(ns resolver-sim.notebook-support.manifest.loader
  "Enumerate and load run manifests from results/runs/ and results/test-artifacts/.

   WARNING: This is a backward-compatibility wrapper.
   New code should use resolver-sim.manifest.run directly.
   The implementation has moved there — this ns forwards to it."
  (:require [resolver-sim.manifest.run :as run]))

(def runs-root run/runs-root)
(def latest-dir run/latest-dir)

(defn list-runs [& args] (apply run/list-runs args))
(defn load-run [& args] (apply run/load-run args))
(defn artifact-by-id [& args] (apply run/artifact-by-id args))
(defn load-artifact-by-id [& args] (apply run/load-artifact-by-id args))
(defn load-latest [& args] (apply run/load-latest args))
(defn load-focused [& args] (apply run/load-focused args))
(defn run->status-indicator [& args] (apply run/run->status-indicator args))
(defn latest-status [& args] (apply run/latest-status args))
