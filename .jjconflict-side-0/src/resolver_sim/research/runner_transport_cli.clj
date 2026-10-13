(ns resolver-sim.research.runner-transport-cli
  "Small process entry point for a research runner transport endpoint.

   Configuration is EDN and contains only a canonical
   `:runner-participation` plus runtime transport fields accepted by
   `runner-transport/start-runner!`.  It intentionally does not execute a
   workload: this process owns delivery/control observation for the first
   cross-host experiment."
  (:require [clojure.edn :as edn]
            [resolver-sim.research.runner-transport :as transport]))

(defn- read-config [path]
  (let [config (edn/read-string (slurp path))]
    (when-not (map? config)
      (throw (ex-info "Runner transport configuration must be an EDN map" {})))
    config))

(defn -main [& [config-path]]
  (when-not config-path
    (throw (ex-info "Usage: clojure -M -m resolver-sim.research.runner-transport-cli CONFIG.edn" {})))
  (let [runner (transport/start-runner! (read-config config-path))]
    (try
      ;; This is a runtime readiness line, not a canonical artifact.
      (println (pr-str {:transport/status :started
                        :transport/base-url (:transport/base-url runner)
                        :runner/reference
                        (:runner/reference (:runner-participation runner))}))
      (flush)
      @(promise)
      (finally
        (transport/stop-runner! runner)))))
