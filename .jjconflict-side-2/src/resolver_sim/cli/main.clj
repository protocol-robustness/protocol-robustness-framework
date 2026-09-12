(ns resolver-sim.cli.main
  "Entry point for the PRF CLI.
   Usage: java -jar prf.jar <command> [options]"
  (:gen-class)
  (:require [resolver-sim.cli.dispatch :as dispatch]))

(defn -main
  [& args]
  (System/exit (dispatch/run args)))
