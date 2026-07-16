(ns resolver-sim.commands.scenario
  "Scenario command adapters for `bb run:scenario` and `prf.jar run-scenario`."
  (:require [resolver-sim.commands.scenario-orchestration :as orchestration]
            [resolver-sim.commands.scenario-run :as scenario-run]))

(defn- opts->argv
  [{:keys [scenario run-root output-dir scenario-output-dir save-output
           report-format verbose failures summary audit cmd/args]}]
  (let [scenario-args (concat args (when scenario [scenario]))]
    (vec (concat scenario-args
                 (when run-root ["--run-root" run-root])
                 (when output-dir ["--output-dir" output-dir])
                 (when scenario-output-dir ["--scenario-output-dir" scenario-output-dir])
                 (when save-output ["--save-output" save-output])
                 (when report-format ["--report-format" report-format])
                 (when verbose ["--verbose"])
                 (when failures ["--failures"])
                 (when summary ["--summary"])
                 (when audit ["--audit"])))))

(defn run-argv
  "Run a scenario command from command-specific argv. Returns the full result
   map; parsing errors are returned without creating a run directory."
  [args]
  (let [parsed (scenario-run/parse-request args)]
    (if-not (:ok? parsed)
      {:command/status :rejected
       :scenario/outcome :unknown
       :exit-code 2
       :errors (:errors parsed)
       :usage (:summary parsed)}
      (let [context (scenario-run/build-run-context (:request parsed) {:project-root "."})
            result (orchestration/run-scenario! context)]
        (assoc result :warnings (:warnings parsed))))))

(defn- print-result! [result]
  (doseq [warning (:warnings result)]
    (binding [*out* *err*] (println "Warning:" warning)))
  (doseq [error (:errors result)]
    (binding [*out* *err*] (println "Error:" error)))
  (when-let [error (:error result)]
    (binding [*out* *err*] (println "Error:" error)))
  (when-let [root (:run/root result)]
    (println "Scenario run root:" root))
  (println "Command status:" (name (:command/status result)))
  (println "Scenario outcome:" (name (:scenario/outcome result))))

(defn run
  "JAR command handler. The dispatcher supplies parsed options and residual
   positional scenario references; this adapter reconstructs command-specific
   argv so both surfaces use the same parser."
  [opts]
  (let [result (run-argv (opts->argv opts))]
    (print-result! result)
    result))

(defn -main [& args]
  (let [result (run-argv args)]
    (print-result! result)
    (System/exit (:exit-code result))))
