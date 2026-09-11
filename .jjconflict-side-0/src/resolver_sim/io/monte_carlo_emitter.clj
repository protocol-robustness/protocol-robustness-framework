(ns resolver-sim.io.monte-carlo-emitter
  "Export Monte Carlo batch summary and failure examples for registry binding."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.evidence.config :as evcfg]))

(defn emit-mc-summary!
  "Emit batch-summary.json and failure-examples.json for a given run."
  [summary failures derivation-chain params-sha256 out-dir]
  (let [summary-file (io/file out-dir (evcfg/artifact-file :mc-summary))
        failures-file (io/file out-dir (evcfg/artifact-file :mc-failures))
        enriched-summary (assoc summary
                                :derivation-chain derivation-chain
                                :params-sha256 params-sha256)]
    (.mkdirs (.getParentFile summary-file))
    (spit summary-file (json/write-str enriched-summary {:indent true}))
    (spit failures-file (json/write-str failures {:indent true}))
    (println "Emitted MC artifacts to:" out-dir)))
