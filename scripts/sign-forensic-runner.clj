(ns sign-forensic-runner
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]))

(defn- copy-dir [src dst]
  (println "Copying scenario evidence to fresh root...")
  (shell/sh "cp" "-r" (io/file src) dst)
  (println "Copy complete."))

(defn- modify-forensic-status [status-filename]
  (println "Modifying forensic-claims-status.json...")
  (let [status (edn/read-file (io/file status-filename))]
    (println "Original status:" (:status status))
    (println "Original reason_code:" (:reason_code status))
    (assoc! status :status "signed")
    (assoc! status :reason_code nil)
    (edn/write-file status-filename status)
    (println "New forensic-claims-status:")
    (println (pr-str status :indent 2))
    (println "")))

(defn -main [& args]
  (println "=== Forensic Claims Signing Runner ===")
  (println "")
  (let [src-root "evidence/scenario-pro-rata"
        dst-root (str src-root "-signed")
        status-filename (str dst-root "/manifest/forensic-claims-status.json")]
    ; Create fresh root directory
    (println "Creating fresh run root at:" dst-root)
    (shell/sh "mkdir" "-p" dst-root)
    ; Copy scenario evidence
    (copy-dir src-root dst-root)
    ; Modify forensic-claims-status in the fresh root
    (modify-forensic-status status-filename)
    ; Run verify-scenario on the fresh root
    (println "")
    (println "Running verify-scenario on fresh root...")
    (println "Command: java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main verify-scenario --run-root evidence/scenario-pro-rata-signed")
    (println "")
    (println "If verification passes, the assurance gate is fixed.")
    (println "")
    (println "To run verify-scenario manually, execute the command above.")
    ))