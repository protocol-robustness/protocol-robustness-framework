(ns resolver-sim.cli-bootstrap
  "Minimal AOT bootstrapper for the PRF CLI JAR.
   Only depends on clojure.core — loaded at compile time.
   At runtime, requires resolver-sim.cli.main and dispatches."
  (:gen-class))

(defn -main
  "Load CLI main namespace and delegate."
  [& args]
  (require 'resolver-sim.cli.main)
  (apply (resolve 'resolver-sim.cli.main/-main) args))
