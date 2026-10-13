(ns resolver-sim.commands.declared-dependencies
  "Report the declared dependency surface of a completed run package:
   package-index artifacts, declared evidence hashes, scenario
   finalizations, execution-DAG edges, and benchmark bindings.

   Usage: java -jar prf.jar declared-dependencies --run-root DIR"
  (:require [clojure.data.json :as json]
            [resolver-sim.compare.packages :as packages]))

(defn- print-collection
  [label xs]
  (println (format "  %s" label))
  (if (seq xs)
    (doseq [x xs]
      (println (format "    %s" (pr-str x))))
    (println "    (none declared)")))

(defn- print-report
  [report]
  (if-not (:valid? report)
    (println (str "declared-dependencies: package not readable: " (pr-str (:reason report))))
    (do
      (println "Declared dependencies")
      (println (format "  run type:       %s" (name (:run-type report))))
      (println (format "  package index:  %s" (:package/index-hash report)))
      (print-collection "package artifacts:" (:package/artifacts report))
      (print-collection "artifact paths:" (:package/artifact-paths report))
      (print-collection "declared evidence hashes:" (:evidence/declared-evidence-hashes report))
      (print-collection "scenario finalization hashes:" (:evidence/scenario-finalization-hashes report))
      (print-collection "scenario ids:" (:evidence/scenario-ids report))
      (println (format "  dag root:       %s" (:dag/root-hash report)))
      (print-collection "dag edges:" (:dag/edges report))
      (print-collection "dag node source hashes:" (:dag/node-source-hashes report))
      (println (format "  benchmark suite: %s" (pr-str (:benchmark/scenario-suite report))))
      (print-collection "benchmark claims:" (:benchmark/claims report))
      (print-collection "benchmark concepts:" (:benchmark/concepts report))
      (print-collection "benchmark property types:" (:benchmark/property-types report)))))

(defn run
  "declared-dependencies --run-root DIR"
  [{:keys [run-root json?]}]
  (if-not run-root
    (do (println "Usage: prf.jar declared-dependencies --run-root DIR")
        {:exit-code 2 :message "Missing --run-root"})
    (let [report (packages/declared-dependencies run-root)]
      (if json?
        (println (json/write-str report :indent true))
        (print-report report))
      {:exit-code (if (:valid? report) 0 1)
       :message (if (:valid? report) "dependencies reported" "package not readable")})))
