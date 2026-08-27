(ns resolver-sim.commands.research
  "Thin public EDN adapters for the canonical researcher workflow."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.research-workflow :as workflow]))

(defn- read-edn! [path]
  (when-not path (throw (ex-info "--input is required" {:exit-code 2})))
  (edn/read-string (slurp (if (= "-" path) *in* (io/file path)))))

(defn- write-result! [path value]
  (if path
    (with-open [writer (io/writer path)] (pprint/pprint value writer))
    (pprint/pprint value)))

(defn- invoke [opts f success? output-required?]
  (try
    (let [result (f (read-edn! (:input opts)))]
      (when (and output-required? (nil? (:output opts)))
        (throw (ex-info "--output is required" {:exit-code 2})))
      (write-result! (:output opts) result)
      {:exit-code (if (success? result) 0 1) :result result})
    (catch clojure.lang.ExceptionInfo e
      (println (.getMessage e))
      {:exit-code (or (:exit-code (ex-data e)) 1)})
    (catch Exception e
      (println (.getMessage e)) {:exit-code 1})))

(defn create [opts]
  (invoke opts
          (fn [spec]
            (let [artifact (command/build-command (assoc spec :schema-version command/schema-version-v2))]
              (when-not (:valid? (command/validate-command artifact))
                (throw (ex-info "Invalid research command" {})))
              artifact))
          (constantly true) true))

(defn assign [opts]
  (invoke opts assignment/build-assignment (constantly true) true))

(defn run [opts]
  (invoke opts workflow/record-execution (constantly true) true))

(defn submit [opts]
  (invoke opts workflow/submit #(= :accepted (:submission/status %)) false))

(defn verify [opts]
  (invoke opts workflow/verify :verified? false))

(defn reproduce [opts]
  (invoke opts (fn [{:keys [mode original reproduced]}]
                 (workflow/reproduce mode original reproduced))
          #(contains? #{:reproduced :comparable} (:reproduction/status %)) false))

(defn diff [opts]
  (invoke opts (fn [{:keys [left right]}] (workflow/differential left right))
          :comparison/equivalent? false))

(defn inspect [opts]
  (invoke opts (fn [context]
                 {:research-command/root (get-in context [:research-command :command/hash])
                  :research-assignment/root (get-in context [:research-assignment :research-assignment/hash])
                  :research-execution/root (get-in context [:execution :research-execution/root])
                  :verification (workflow/validate-execution context)})
          #(get-in % [:verification :valid?]) false))
