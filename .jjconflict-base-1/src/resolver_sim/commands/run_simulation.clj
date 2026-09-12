(ns resolver-sim.commands.run-simulation
  "Consolidated command for running simulations.
   Replaces the original flag-based CLI (resolver-sim.core/-main).
   Usage: clojure -M:cli run-simulation [options]"
  (:require [clojure.tools.cli :as cli]
            [resolver-sim.core.cli :as core-cli]
            [resolver-sim.core.phases :as phases]
            [resolver-sim.io.params :as params]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.logging :as log]))

(defn run
  "Run a simulation, server, invariants, or diff-traces depending on options.
   Parses :cmd/raw-args with the original core.cli option definitions."
  [{:keys [cmd/raw-args] :as opts}]
  (let [{:keys [options errors summary]} (cli/parse-opts raw-args core-cli/cli-options)]
    (cond
      errors
      (do (doseq [e errors] (println e))
          {:exit-code 2})

      (:help options)
      (do (println (core-cli/usage summary))
          {:exit-code 0})

      (:diff-traces options)
      (let [baseline (:baseline options)
            candidate (:candidate options)]
        (if (and baseline candidate)
          {:exit-code ((requiring-resolve 'resolver-sim.io.diff-runner/run-diff-traces!)
                       baseline candidate)}
          (do (println "Requires --baseline and --candidate with --diff-traces.")
              {:exit-code 1})))

      (:invariants options)
      (let [protocol-id (:protocol options preg/default-protocol-id)
            suite-kw (when-let [s (:suite options)] (keyword s))
            fixture-kw (when-let [s (:fixture-suite options)] (keyword s))
            scenario (:scenario options)
            output (:output-file options)]
        (cond
          (not ((set (preg/known-protocol-ids)) protocol-id))
          (do (println (str "Unknown protocol: " protocol-id
                            ". Available: " (clojure.string/join ", " (preg/known-protocol-ids))))
              {:exit-code 1})

          (and suite-kw fixture-kw)
          (do (println "Use only one of --suite or --fixture-suite.")
              {:exit-code 1})

          :else
          (let [result ((requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report)
                        {:protocol protocol-id
                         :suite suite-kw
                         :fixture-suite fixture-kw
                         :scenario scenario
                         :output-file output
                         :scenario-filter (:scenario-filter options)}
                        {})]
            {:exit-code (:exit-code result)})))

      (:serve options)
      (try
        (let [port (:port options)
              grpc (requiring-resolve 'resolver-sim.server.grpc/start!)]
          (.addShutdownHook (Runtime/getRuntime)
                            (Thread. ^Runnable (requiring-resolve 'resolver-sim.server.grpc/stop!)))
          (grpc port)
          (log/info! "grpc/server-started" {:port port})
          (println "[grpc] Press Ctrl+C to stop.")
          ((requiring-resolve 'resolver-sim.server.grpc/await-termination))
          {:exit-code 0})
        (catch Throwable e
          (log/error! "grpc/server-error" {:error (.getMessage e)})
          (println "Error in server:" (.getMessage e))
          (.printStackTrace e)
          {:exit-code 1}))

      :else
      (try
        (log/info! "simulation/params-loading" {:path (:params options)})
        (println "Loading params from:" (:params options))
        (let [p (params/validate-and-merge (:params options))
              output (:output options)
              phase-key (some #(when (get options %) %) (keys phases/phase-runners))
              [label run-fn] (get phases/phase-runners phase-key)]
          (cond
            (:ring-spec p) (phases/run-ring-simulation p output)
            phase-key (do (when label (println label)) (run-fn p output))
            :else (phases/run-simulation p output))
          {:exit-code 0})
        (catch Exception e
          (log/error! "simulation/run-error" {:error (.getMessage e)})
          (println "Error:" (.getMessage e))
          (.printStackTrace e)
          {:exit-code 1})))))
