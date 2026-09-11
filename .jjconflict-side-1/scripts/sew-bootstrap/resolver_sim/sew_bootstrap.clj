(ns resolver-sim.sew-bootstrap
  "Minimal AOT bootstrapper for the Sew protocol runner JAR.
   Only depends on clojure.core — loaded at compile time.
   At runtime, requires resolver-sim.minimal-runner and dispatches."
  (:gen-class))

(defn -main
  "Load minimal-runner namespace and delegate."
  [& args]
  (require 'resolver-sim.minimal-runner)
  (apply (resolve 'resolver-sim.minimal-runner/-main) args))
