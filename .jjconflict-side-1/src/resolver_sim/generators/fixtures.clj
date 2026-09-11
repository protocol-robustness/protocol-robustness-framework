(ns resolver-sim.generators.fixtures
  "Promotion of interesting generated scenarios to regression fixtures."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.generators.equilibrium :as geq]
            [resolver-sim.generators.scenario :as gsc]
            [resolver-sim.config.paths :as paths]))

(defn interesting-run?
  [{:keys [outcome convergence]}]
  (or (= :fail outcome)
      (#{:stuck :invariant-failed :attack-success} convergence)))

(defn promote-batch!
  "Generate a batch and write interesting scenarios to regression fixture dir.
   Returns {:written n :files [...]}"
  [{:keys [start-seed n max-steps profile out-dir dry-run?]
    :or {start-seed 1 n 20 max-steps 8 profile :timeout-boundary
         out-dir (paths/traces-regression-dir)
         dry-run? false}}]
  (let [batch (geq/evaluate-generated-batch {:start-seed start-seed :n n :max-steps max-steps :profile profile})
        runs  (filter interesting-run? (:runs batch))
        _     (.mkdirs (io/file out-dir))
        files (mapv (fn [{:keys [seed]}]
                      (let [sc (gsc/build-scenario {:seed seed :max-steps max-steps :profile profile})
                            path (str out-dir "/gen-" (name profile) "-seed-" seed ".trace.json")]
                        (when-not dry-run?
                          (spit path (json/write-str sc {:indent true})))
                        path))
                    runs)]
    {:written (count files) :files files :dry-run? dry-run?}))
