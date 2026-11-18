(ns resolver-sim.io.diff-runner
  "Shell: load replay JSON files and run structural trace diff."
  (:require [clojure.data.json :as json]
            [clojure.tools.cli :refer [parse-opts]]
            [resolver-sim.evidence.node :as ev-node]
            [resolver-sim.io.diff :as diff])
  (:gen-class))

(defn trace-from-replay-json
  "Extract the :trace vector from a replay result JSON file."
  [path]
  (let [doc (json/read-str (slurp path) :key-fn keyword)
        trace (:trace doc)]
    (if (sequential? trace)
      (vec trace)
      (throw (ex-info "Replay JSON missing :trace vector"
                      {:path path :top-level-keys (keys doc)})))))

(defn diff-replay-files
  "Compare two replay JSON files. Returns diff-traces result (nil when identical)."
  [baseline-path candidate-path]
  (diff/diff-traces (trace-from-replay-json baseline-path)
                    (trace-from-replay-json candidate-path)))

(defn run-diff-traces!
  "Print a human-readable report and return exit code (0 = match, 1 = diverged)."
  [baseline-path candidate-path]
  (ev-node/with-execution-node
    {:execution-id :execution/diff
     :inputs {:baseline-path baseline-path
              :candidate-path candidate-path}
     :status-fn #(if (zero? %) :pass :fail)
     :outputs-fn (fn [exit-code]
                   {:baseline-path baseline-path
                    :candidate-path candidate-path
                    :exit-code exit-code})
     :failure-details-fn (fn [exit-code]
                           (if (zero? exit-code)
                             []
                             [{:failure-type :trace-divergence
                               :class :unexpected
                               :message "Replay traces diverged"
                               :expected? false}]))}
    (fn []
      (let [result (diff-replay-files baseline-path candidate-path)]
        (diff/print-diff-report result)
        (if result 1 0)))))

(def ^:private cli-options
  [["-b" "--baseline PATH" "Baseline replay JSON"]
   ["-c" "--candidate PATH" "Candidate replay JSON"]
   ["-h" "--help" "Show help"]])

(defn- usage [summary]
  (str "Structural trace diff (world state step-by-step)\n\n"
       "Usage:\n"
       "  clojure -M:diff-traces --baseline replay-a.json --candidate replay-b.json\n"
       "  clojure -M:run -- --diff-traces --baseline replay-a.json --candidate replay-b.json\n\n"
       summary))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do (println (usage summary)) (System/exit 0))

      errors
      (do (doseq [e errors] (println e))
          (System/exit 2))

      (and (:baseline options) (:candidate options))
      (System/exit (run-diff-traces! (:baseline options) (:candidate options)))

      (= 2 (count arguments))
      (System/exit (apply run-diff-traces! arguments))

      :else
      (do (println (usage summary))
          (System/exit 2)))))
