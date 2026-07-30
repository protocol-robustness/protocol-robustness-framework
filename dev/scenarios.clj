(ns dev.scenarios
  "REPL dev helpers for running single in-process registry scenarios.
   Routes through resolver-sim.io.scenario-runner (public path).
   For file-backed scenarios use io.scenario-runner/run-scenario-file directly."
  (:require [clojure.string :as str]
            [resolver-sim.io.scenario-runner :as sr]))

(defn- scenario-number
  "Extract the leading S-number from a scenario id/name (e.g. :S103 -> 103)."
  [s]
  (some->> (re-find #"(?i)^S(\d+)" (str s))
           second
           Long/parseLong))

(defn- normalize-name
  "Trim and collapse internal whitespace so registry names match regardless of spacing."
  [s]
  (-> (str s) str/trim (str/replace #"\s+" " ")))

(defn- find-scenario
  "Look up a scenario by keyword id (e.g. :S103) or string name from the registry.
   Returns the registry entry `[display-name data]`, or nil when not found."
  [scenario-id]
  (let [scenarios (requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/all-scenarios)
        id-str    (if (keyword? scenario-id) (name scenario-id) (str scenario-id))
        id-num    (scenario-number id-str)
        id-norm   (normalize-name id-str)]
    (first (filter (fn [[name _]]
                     (when (string? name)
                       (let [n-num  (scenario-number name)
                             n-norm (normalize-name name)]
                         (or (and id-num n-num (= n-num id-num))
                             (= n-norm id-norm)))))
                   @scenarios))))

(defn- sew-replay-fn []
  (requiring-resolve 'resolver-sim.protocols.sew/replay-with-sew-protocol))

(defn list-scenarios
  "List all available scenarios in the registry.
   Returns a seq of scenario names like [S01 baseline-happy-path S02 dispute-timeout ...]
   Optional pattern parameter filters results."
  ([]
   (list-scenarios nil))
  ([pattern]
   (try
     (let [scenarios (requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/all-scenarios)
           all-names (->> @scenarios
                          (map first)  ; Get just the names
                          (filter string?))]  ; Filter out non-string keys
       (if pattern
         (filter #(re-find (re-pattern pattern) %) all-names)
         all-names))
     (catch Exception e
       (println "Error listing scenarios:" (.getMessage e))
       []))))

(defn run-scenario
  "Run a single in-process registry scenario by keyword id (e.g. :S18) or name.
   Routes through io.scenario-runner/run-registry-scenario."
  [scenario-id]
  (let [entry   (or (find-scenario scenario-id)
                    (throw (ex-info "Unknown scenario" {:scenario-id scenario-id})))
        result  (sr/run-registry-scenario entry (sew-replay-fn))]
    (tap> {:type :scenario/result
           :scenario-id scenario-id
           :result result})
    result))

(defn run-scenario-summary
  [scenario-id]
  (let [result (run-scenario scenario-id)
        summary (select-keys result [:outcome :halt-reason :metrics])]
    (tap> summary)
    summary))

(defn run-yield-shortfall-demo
  []
  (run-scenario-summary :S107))

(defn run-baseline
  []
  (mapv run-scenario-summary
        [:S01 :S02 :S03 :S04 :S05 :S06 :S07 :S08 :S09]))
