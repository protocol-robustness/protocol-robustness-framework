(ns resolver-sim.io.telemetry-emitter
  "Export telemetry records from scenario results for registry binding."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.protocols.protocol :as proto]))

(defn emit-telemetry!
  "Project :telemetry-record from protocol and write to JSON."
  [protocol result out-path]
  (when (satisfies? proto/AnalysisModule protocol)
    (let [telemetry (proto/io-projection protocol result :telemetry-record)]
      (when telemetry
        (let [f (io/file out-path)]
          (.mkdirs (.getParentFile f))
          (spit f (json/write-str telemetry {:indent true}))
          (println "Emitted telemetry to:" out-path))))))
