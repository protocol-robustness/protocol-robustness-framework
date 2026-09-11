(ns resolver-sim.benchmark.main
  "Minimal bootstrapper: forwards all args to resolver-sim.benchmark.cli/-main.
   AOT-compiled as the JAR Main-Class entry point.
   This namespace must stay dependency-free (only clojure.main)."
  (:gen-class)
  (:require [clojure.main]))

(defn -main
  "Entry point for java -jar prf-benchmark.jar.
   Prepends -m resolver-sim.benchmark.cli and forwards all args."
  [& args]
  (apply clojure.main/main "-m" "resolver-sim.benchmark.cli" args))
