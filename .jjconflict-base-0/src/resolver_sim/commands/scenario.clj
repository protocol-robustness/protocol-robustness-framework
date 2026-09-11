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

(defn- run-single
  "Run a single scenario from a parse request."
  [request]
  (let [context (scenario-run/build-run-context request {:project-root "."})]
    (orchestration/run-scenario! context)))

(defn run-argv
  "Run one scenario command from command-specific argv. Returns the full result
   map; parsing errors are returned without creating a run directory. A
   canonical run root owns exactly one scenario; registered suite execution has
   separate aggregate lifecycle semantics and is intentionally not multiplexed
   through this command."
  [args]
  (let [parsed (scenario-run/parse-request args)]
    (if-not (:ok? parsed)
      {:command/status :rejected
       :scenario/outcome :unknown
       :exit-code 2
       :errors (:errors parsed)
       :usage (:summary parsed)}
      (let [request (:request parsed)
            refs (:scenario/refs request)]
        (if (= 1 (count refs))
          (assoc (run-single request) :warnings (:warnings parsed))
          {:command/status :rejected
           :scenario/outcome :unknown
           :exit-code 2
           :errors ["run-scenario accepts exactly one scenario; use the future run-suite command for registered collections"]
           :usage (:summary parsed)})))))

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
