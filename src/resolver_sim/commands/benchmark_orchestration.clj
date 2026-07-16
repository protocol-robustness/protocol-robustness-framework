(ns resolver-sim.commands.benchmark-orchestration
  "Injectable ordered finalization for canonical benchmark bundles."
  (:refer-clojure :exclude [run!]))

(def phases [:execute :write-manifest :snapshot-definition :write-conclusion
             :write-summary :scan-sensitivity :build-inventory
             :finalize-registry :validate-registry :complete])

(defn run!
  "Run benchmark phases in order. `:execute` receives context; subsequent
   phases receive context and execution result. Any exception terminates the
   sequence immediately, leaving lifecycle cleanup to the command owner."
  [context phase-fns]
  (let [records (atom [])
        call (fn [phase execution]
               (try
                 (let [result (if (= phase :execute)
                                ((phase-fns phase) context)
                                ((phase-fns phase) context execution))]
                   (swap! records conj {:phase phase :status :completed})
                   result)
                 (catch Throwable error
                   (swap! records conj {:phase phase :status :failed :error (.getMessage error)})
                   (throw error))))
        execution (call :execute nil)]
    (doseq [phase (rest phases)]
      (call phase execution))
    {:execution execution :phases @records}))
