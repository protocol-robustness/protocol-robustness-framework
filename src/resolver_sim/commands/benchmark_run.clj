(ns resolver-sim.commands.benchmark-run
  "Exact-root lifecycle context for canonical benchmark bundles."
  (:require [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle])
  (:import [java.nio.file Path Paths]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

(defn- run-id []
  (str "benchmark-"
       (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
                (.atZone (Instant/now) ZoneOffset/UTC))
       "-" (subs (str (UUID/randomUUID)) 0 12)))

(defn build-run-context [benchmark-id run-root project-root]
  (let [envelope (lifecycle/build-run-envelope
                  {:run-id (run-id) :run-type :benchmark :run-root run-root
                   :project-root project-root :sensitivity-profile :public})
        root (:run/root envelope)]
    (merge envelope
           {:benchmark/id benchmark-id
            :benchmark/root (.resolve root "benchmark")
            :benchmark/definition-file (.resolve root "benchmark/definition.edn")
            :benchmark/evidence-file (.resolve root "benchmark/evidence/evidence.edn")
            :benchmark/conclusion-file (.resolve root "benchmark/conclusion.json")
            :benchmark/summary-file (.resolve root "benchmark/summary.json")
            :benchmark/index-file (.resolve root "benchmark/index.edn")
            :benchmark/plan-file (.resolve root "benchmark/execution-plan.edn")
            :benchmark/executions-dir (.resolve root "benchmark/executions")})))

(defn initialize! [context]
  (doseq [path [(:run/root context) (:manifest/dir context)
                (:benchmark/root context) (:benchmark/executions-dir context)
                (.getParent ^Path (:benchmark/evidence-file context))]]
    (.mkdirs (io/file (str path))))
  (lifecycle/mark-running! (:run/root context) (:run/id context) :benchmark)
  context)
