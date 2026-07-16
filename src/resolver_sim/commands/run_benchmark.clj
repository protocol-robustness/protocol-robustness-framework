(ns resolver-sim.commands.run-benchmark
  "Run a benchmark by registered ID or manifest path."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.commands.benchmark-conclusion :as conclusion]
            [resolver-sim.commands.benchmark-run :as benchmark-run]
            [resolver-sim.commands.scenario-safety :as safety]
            [resolver-sim.evidence.chain :as chain]))

(defn- complete! [context exit-code]
  (let [root (io/file (str (:run/root context)))]
    (spit (io/file root "completion.json")
          (json/write-str {:run/id (:run/id context)
                           :command/status "completed"
                           :benchmark/outcome (if (zero? exit-code) "pass" "fail")
                           :exit-code exit-code}))
    (.delete (io/file root ".run-state"))))

(defn- invoke! [benchmark-id {:keys [output key scenario-output-dir]}]
  (let [benchmark-runner (requiring-resolve 'resolver-sim.benchmark.cli/run-and-report)
        write-evidence (requiring-resolve 'resolver-sim.benchmark.runner/write-evidence)
        result (binding [chain/*allow-dirty* true]
                 (benchmark-runner benchmark-id {:output output
                                                 :key key
                                                 :scenario-output-dir scenario-output-dir}))]
    (when-let [evidence (:evidence result)]
      (write-evidence evidence output))
    result))

(defn- run-with-root! [benchmark-id run-root key]
  (let [context (benchmark-run/build-run-context benchmark-id run-root ".")
        lock (safety/acquire-lock! (:run/root context))]
    (try
      (benchmark-run/initialize! context)
      (let [result (invoke! benchmark-id {:output (str (:benchmark/evidence-file context))
                                          :key key
                                          :scenario-output-dir (str (:benchmark/scenarios-dir context))})
            exit-code (or (:exit-code result) 1)]
        ;; A failed benchmark outcome is still a completed benchmark execution
        ;; with retained evidence. Command finalization only fails on exceptions.
        (conclusion/write! context (:evidence result))
        (complete! context exit-code)
        {:exit-code exit-code :run/id (:run/id context) :run/root (str (:run/root context))})
      (finally (safety/release-lock! lock)))))

(defn run
  "Run a benchmark. `--run-root` creates a canonical benchmark-owned bundle;
   `--output` remains the legacy standalone evidence export destination."
  [{:keys [output key run-root] :as opts}]
  (let [benchmark-id (or (first (:cmd/args opts))
                         (:benchmark-id opts)
                         (:benchmark opts))]
    (cond
      (nil? benchmark-id)
      {:exit-code 2 :message "Usage: prf-runner-sew.jar run-benchmark <benchmark-id> --run-root DIR"}

      (and run-root output)
      {:exit-code 2 :message "Use --run-root for the canonical benchmark bundle; --output is a separate legacy export command"}

      run-root
      (run-with-root! benchmark-id run-root key)

      output
      (let [result (invoke! benchmark-id {:output output :key key})]
        {:exit-code (or (:exit-code result) 1)})

      :else
      {:exit-code 2 :message "Specify --run-root for a canonical benchmark bundle or --output for a legacy export"})))
