(ns resolver-sim.commands.scenario-manifest
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.nio.file Files StandardCopyOption]))

(defn- atomic-json! [file value]
  (let [target (io/file file) temp (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str value))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn- read-json [file]
  (when (.isFile (io/file file))
    (json/read-str (slurp file))))

(defn write! [context execution]
  (let [dir (io/file (str (:manifest/dir context)))
        status (if (zero? (:exit-code execution)) "pass" "fail")
        enrichment (or (read-json (io/file dir "run-enrichment.json")) {})
        run (merge {"manifest" {"schema_version" "run-manifest.v1"}
                    "run" {"id" (:run/id context) "type" "scenario" "sensitivity_profile" (name (:sensitivity/profile context)) "status" (if (= status "pass") "complete" "failed")
                           "exit_code" (:exit-code execution) "duration_ms" (:duration-ms execution 0)}
                    "scenario" {"id" (:scenario/ref context) "path" (:scenario/ref context)}
                    "outcome" {"status" status "total" 1 "passed" (if (= status "pass") 1 0) "failed" (if (= status "pass") 0 1)}} enrichment)
        summary {"manifest" {"schema_version" "summary.v1"}
                 "run" {"id" (:run/id context) "overall_status" status
                        "outcome" {"status" status "exit_code" (:exit-code execution) "duration_ms" (:duration-ms execution 0)}}}
        claimable {"schema_version" "claimable-classification.v2" "run_id" (:run/id context)}]
    (atomic-json! (io/file dir "run.json") run)
    (atomic-json! (io/file dir "summary.json") summary)
    (atomic-json! (io/file dir "claimable-classification.json") claimable)
    {:run run :summary summary :claimable claimable}))
