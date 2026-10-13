(ns resolver-sim.io.audit-outputs
  "File I/O for multi-epoch stability audit outputs (shell layer)."
  (:require [clojure.java.io :as io]
            [resolver-sim.io.results :as results]
            [resolver-sim.sim.audit :as audit]))

(defn write-audit-outputs
  "Write all canonical audit output files to output-dir.

   Writes:
     epoch-results.edn
     trajectory.csv
     audit-result.edn
     manifest.edn
     PHASE_J_STABILITY_AUDIT.md

   Returns output-dir."
  [output-dir result audit-result manifest]
  (.mkdirs (io/file output-dir))
  (results/write-edn (str output-dir "/epoch-results.edn") (:epoch-results result))
  (results/write-edn (str output-dir "/audit-result.edn") audit-result)
  (results/write-edn (str output-dir "/manifest.edn") manifest)
  (results/write-csv (str output-dir "/trajectory.csv")
                     (audit/trajectory-csv-rows
                      (:full-trajectories result)
                      (:resolver-histories result)
                      (:n-epochs result)))
  (spit (str output-dir "/PHASE_J_STABILITY_AUDIT.md")
        (audit/audit-markdown audit-result manifest))
  output-dir)

(defn run-phase-j-audit!
  "Run the canonical multi-epoch stability audit and write all output files.

   Shell wrapper around sim.audit/run-phase-j-audit."
  [params params-file output-dir & {:keys [seed n-epochs n-trials audit-opts] :as opts}]
  (let [epoch-dir (str output-dir "/epochs")
        _         (.mkdirs (io/file epoch-dir))
        epoch-callback
        (fn [n summary]
          (results/write-edn
           (str epoch-dir "/epoch-" (format "%04d" n) ".edn")
           summary))
        {:keys [result audit manifest]}
        (audit/run-phase-j-audit params params-file
                                 (merge opts
                                        {:seed seed
                                         :n-epochs n-epochs
                                         :n-trials n-trials
                                         :audit-opts audit-opts
                                         :epoch-callback epoch-callback}))]
    (write-audit-outputs output-dir result audit manifest)
    (println (format "\nAudit result: %s" (name (:result audit))))
    (println (format "Output written to: %s" output-dir))
    {:result result
     :audit audit
     :manifest manifest
     :output-dir output-dir}))
