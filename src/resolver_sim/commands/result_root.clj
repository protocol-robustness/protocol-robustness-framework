(ns resolver-sim.commands.result-root
  "Report the roots that identify a run's realized result: the bundle-root
   hash, the stable-result hash, and the per-scenario evidence roots.

   Usage: java -jar prf.jar result-root --run-root DIR"
  (:require [clojure.data.json :as json]
            [resolver-sim.compare.packages :as packages]))

(defn- print-report
  [report]
  (if-not (:valid? report)
    (println (str "result-root: package not readable: " (pr-str (:reason report))))
    (do
      (println "Result roots")
      (println (format "  run id:                %s" (:run-id report)))
      (println (format "  bundle root:           %s" (:bundle-root-hash report)))
      (println (format "  stable result hash:    %s" (:stable-result-hash report)))
      (println (format "  stable policy:         %s" (name (:stable-comparison-policy report))))
      (println (format "  scenarios:             %d" (:scenario-count report)))
      (println "  scenario evidence roots:")
      (if (seq (:scenario-evidence-roots report))
        (doseq [root (:scenario-evidence-roots report)]
          (println (format "    %s" root)))
        (println "    (none recorded)")))))

(defn run
  "result-root --run-root DIR"
  [{:keys [run-root json?]}]
  (if-not run-root
    (do (println "Usage: prf.jar result-root --run-root DIR")
        {:exit-code 2 :message "Missing --run-root"})
    (let [report (packages/result-roots run-root)]
      (if json?
        (println (json/write-str report :indent true))
        (print-report report))
      {:exit-code (if (:valid? report) 0 1)
       :message (if (:valid? report) "result roots reported" "package not readable")})))
