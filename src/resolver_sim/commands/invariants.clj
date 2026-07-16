(ns resolver-sim.commands.invariants
  "Run the full protocol invariant suite.
   Invoked by `prf.jar run-invariants`."

  (:require [resolver-sim.io.scenario-runner :as sr]))

(defn run
  "Run the full invariant registry suite for the given protocol.
   Options:
     :protocol  — protocol id (default sew-v1)
     :json?     — when true, output as JSON"
  [{:keys [protocol json?]}]
  (let [protocol-id (or protocol "sew-v1")
        runner-opts (cond-> {:protocol protocol-id}
                      json? (assoc :report-format :json))]
    (println "WARNING: run-invariants is a legacy/internal suite runner.")
    (println "It does not yet produce a canonical --run-root bundle; use run-scenario --run-root for canonical scenario evidence.")
    (println "Running invariant suite...")
    (println (str "  protocol: " protocol-id))
    (flush)
    (sr/run-registry-suite-and-report runner-opts)))
