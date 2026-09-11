(ns resolver-sim.core
  "CLI entry point. Wires core.cli (option parsing) and core.phases (runners).
   Contains only -main; all logic lives in the sub-namespaces."
  (:require [clojure.string            :as str]
            [resolver-sim.logging      :as log]
            [resolver-sim.core.cli    :as cli]
            [resolver-sim.core.phases :as phases]
            [resolver-sim.io.params   :as params]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.server.grpc :as grpc])
  (:gen-class))

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (cli/validate-args args)]
    (if exit-message
      (do (log/info! "cli/exit" {:ok? ok? :message exit-message})
          (println exit-message)
          (System/exit (if ok? 0 1)))

      (cond
        (:diff-traces options)
        (let [baseline (:baseline options)
              candidate (:candidate options)]
          (if (and baseline candidate)
            (System/exit
             ((requiring-resolve 'resolver-sim.io.diff-runner/run-diff-traces!)
              baseline candidate))
            (do (println "Requires --baseline and --candidate with --diff-traces.")
                (System/exit 1))))

        (:invariants options)
        (let [protocol-id (:protocol options preg/default-protocol-id)
              suite-kw    (when-let [s (:suite options)]
                            (keyword s))
              fixture-kw  (when-let [s (:fixture-suite options)]
                            (keyword s))
              scenario    (:scenario options)
              output      (:output-file options)]
          (cond
            (not ((set (preg/known-protocol-ids)) protocol-id))
            (do (println (str "Unknown protocol: " protocol-id
                              ". Available: " (str/join ", " (preg/known-protocol-ids))))
                (log/error! "unknown protocol" {:protocol-id protocol-id})
                (System/exit 1))

            (and suite-kw fixture-kw)
            (do (println "Use only one of --suite or --fixture-suite.")
                (System/exit 1))

            :else
            (let [result ((requiring-resolve 'resolver-sim.io.scenario-runner/run-and-report)
                          {:protocol       protocol-id
                           :suite          suite-kw
                           :fixture-suite  fixture-kw
                           :scenario       scenario
                           :output-file    output
                           :scenario-filter (:scenario-filter options)}
                          {})]
              (System/exit (:exit-code result))))

          (:serve options)
          (try
            (let [port (:port options)]
              (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable grpc/stop!))
              (grpc/start! port)
              (log/info! "grpc/server-started" {:port port})
              (println "[grpc] Press Ctrl+C to stop.")
              (grpc/await-termination)
              (System/exit 0))
            (catch Throwable e
              (log/error! "grpc/server-error" {:error (.getMessage e)})
              (println "Error in server:" (.getMessage e))
              (.printStackTrace e)
              (System/exit 1)))

          (try
            (log/info! "simulation/params-loading" {:path (:params options)})
            (println "Loading params from:" (:params options))
            (let [p         (params/validate-and-merge (:params options))
                  output    (:output options)
                  phase-key (some #(when (get options %) %) (keys phases/phase-runners))
                  [label run-fn] (get phases/phase-runners phase-key)]
              (cond
                (:ring-spec p) (phases/run-ring-simulation p output)
                phase-key      (do (when label (println label))
                                   (run-fn p output))
                :else          (phases/run-simulation p output))
              (System/exit 0))
            (catch Exception e
              (log/error! "simulation/run-error" {:error (.getMessage e)})
              (println "Error:" (.getMessage e))
              (.printStackTrace e)
              (System/exit 1))))))))
