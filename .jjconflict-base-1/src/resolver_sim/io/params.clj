(ns resolver-sim.io.params
  "Load and validate parameter files (EDN format)."
  (:require [resolver-sim.stochastic.types :as types]
            [resolver-sim.governance.rules :as rules]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-edn
  "Load EDN param file."
  [path]
  (let [file (io/file path)]
    (if (.exists file)
      (edn/read-string (slurp file))
      (throw (ex-info (format "Param file not found: %s" path) {:path path})))))

(defn merge-defaults
  "Merge scenario params with defaults.
   
   Layering order (rightmost wins):
     stochastic.types/default-params
       ← governance.rules/default-rules
       ← EDN scenario"
  [scenario]
  (let [escrow-size (:escrow-size scenario 10000)]
    (when-not (number? escrow-size)
      (throw (ex-info (format "Invalid escrow-size: %s. Must be a number." escrow-size)
                      {:escrow-size escrow-size})))
    (merge types/default-params (rules/default-rules escrow-size) scenario)))

(defn validate-and-merge
  "Load, validate (including effective oracle-fixture), and merge with defaults."
  [path]
  (-> path load-edn merge-defaults types/validate-scenario))

(defn validate-scenario
  "Validate an in-memory scenario map (same checks as validate-and-merge)."
  [scenario]
  (-> scenario merge-defaults types/validate-scenario))
