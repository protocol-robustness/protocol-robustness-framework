(ns resolver-sim.commands.scenario-compare
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.commands.scenario-run :as scenario-run]
            [resolver-sim.commands.scenario-orchestration :as orchestration]
            [resolver-sim.io.diff :as diff]
            [resolver-sim.io.diff-runner :as diff-runner]))

(defn- run-and-capture
  "Run a single scenario and return {:replay-path str :run/root str :exit-code int}."
  [scenario-ref run-root]
  (io/make-parents (str run-root "/.keep"))
  (let [parsed (scenario-run/parse-request [scenario-ref "--run-root" run-root])]
    (if-not (:ok? parsed)
      (throw (ex-info (str "Cannot parse scenario: " scenario-ref)
                      {:scenario scenario-ref :errors (:errors parsed)}))
      (let [context (scenario-run/build-run-context (:request parsed) {:project-root "."})
            result (orchestration/run-scenario! context)
            replay-file (str (:replay/file context))]
        (println (str "  " scenario-ref " -> " (:run/root result)
                      " (" (name (:command/status result))
                      "/" (name (:scenario/outcome result)) ")"))
        {:replay-path (when (.exists (io/file replay-file)) replay-file)
         :run/root (:run/root result)
         :exit-code (:exit-code result)}))))

(defn compare-scenarios
  [{:keys [out json? cmd/args]}]
  (let [[scenario-a scenario-b] args]
    (cond
      (or (nil? scenario-a) (nil? scenario-b))
      (do (println "Usage: prf.jar scenario compare <scenario-a> <scenario-b> [--out <dir>]")
          {:exit-code 2 :message "Two scenario references required"})

      (= scenario-a scenario-b)
      (do (println "Error: scenario-a and scenario-b must be different")
          {:exit-code 2 :message "Identical scenarios"})

      :else
      (let [base-dir (or out "results/trace-compare")
            run-a-dir (str base-dir "/scenario-a")
            run-b-dir (str base-dir "/scenario-b")]
        (println (str "Comparing:\n  A: " scenario-a "\n  B: " scenario-b "\n"))
        (try
          (let [result-a (run-and-capture scenario-a run-a-dir)
                result-b (run-and-capture scenario-b run-b-dir)
                exit-a (:exit-code result-a)
                exit-b (:exit-code result-b)]
            (println)
            (cond
              (or (not (zero? exit-a)) (not (zero? exit-b)))
              (do (println "Cannot compare - one or both scenarios failed:")
                  (println (str "  " scenario-a ": exit " exit-a))
                  (println (str "  " scenario-b ": exit " exit-b))
                  {:exit-code 1 :message "Scenario execution failed"})

              (or (nil? (:replay-path result-a)) (nil? (:replay-path result-b)))
              (do (println "Cannot compare - replay output not found for one or both scenarios")
                  {:exit-code 1 :message "Replay output missing"})

              :else
              (let [trace-a (diff-runner/trace-from-replay-json (:replay-path result-a))
                    trace-b (diff-runner/trace-from-replay-json (:replay-path result-b))
                    divergence (diff/diff-traces trace-a trace-b)]
                (if json?
                  (println (json/write-str {:divergence? (some? divergence)
                                             :divergence divergence
                                             :run-a run-a-dir
                                             :run-b run-b-dir}
                                            :indent true))
                  (diff/print-diff-report divergence))
                (if divergence
                  {:exit-code 1 :message "Traces diverged"}
                  {:exit-code 0 :message "Traces match"}))))
          (catch Exception e
            (println "Comparison failed:" (.getMessage e))
            {:exit-code 1 :message (.getMessage e)}))))))
