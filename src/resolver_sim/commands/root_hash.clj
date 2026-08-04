(ns resolver-sim.commands.root-hash
  "Report the structural root hashes that seal a completed run package:
   the package-index hash, the bundle-root hash, and the completion seal.

   Usage: java -jar prf.jar root-hash --run-root DIR"
  (:require [clojure.data.json :as json]
            [resolver-sim.compare.packages :as packages]))

(defn- print-report
  [report]
  (if-not (:valid? report)
    (println (str "root-hash: package not readable: " (pr-str (:reason report))))
    (do
      (println "Root hashes")
      (println (format "  run id:          %s" (:run-id report)))
      (println (format "  run type:        %s" (name (:run-type report))))
      (println (format "  package index:   %s" (:package-index-hash report)))
      (println (format "    path:          %s" (:package-index-path report)))
      (println (format "  bundle root:     %s" (:bundle-root-hash report)))
      (println (format "  completion seal: sha256=%s bytes=%s"
                       (get-in report [:completion-seal :sha256])
                       (get-in report [:completion-seal :bytes]))))))

(defn run
  "root-hash --run-root DIR"
  [{:keys [run-root json?]}]
  (if-not run-root
    (do (println "Usage: prf.jar root-hash --run-root DIR")
        {:exit-code 2 :message "Missing --run-root"})
    (let [report (packages/package-roots run-root)]
      (if json?
        (println (json/write-str report :indent true))
        (print-report report))
      {:exit-code (if (:valid? report) 0 1)
       :message (if (:valid? report) "roots reported" "package not readable")})))
