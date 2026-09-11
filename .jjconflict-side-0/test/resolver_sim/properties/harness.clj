(ns resolver-sim.properties.harness
  "Shared property-based test infrastructure.

   Usage:
     (require '[resolver-sim.properties.harness :as pbh])
     (tc/quick-check (pbh/trial-count) my-prop)

   Override trial count:
     clojure -M:test -Dprf.property.trials=500 ...
   "
  (:require [clojure.test :refer [is]]))

(def ^:const default-trials
  "Default number of property trials for focused/local runs.
   Each replay trial takes ~7s, so 20 trials ≈ 2.5 minutes."
  20)

(def ^:const review-trials
  "Default number of property trials for review backstop.
   Activate with: -Dprf.property.trials=500
   500 trials ≈ 1 hour for replay-based properties."
  500)

(defn trial-count
  "Readable trial count from JVM property `prf.property.trials`.
   Falls back to `default` (or `default-trials`)."
  ([] (trial-count default-trials))
  ([default]
   (Long/parseLong (System/getProperty "prf.property.trials" (str default)))))

(defn report-failure
  "Format a test.check result for diagnostic output.
   Includes pass?, num-tests, fail count, and shrunk smallest args."
  [result]
  (let [k (select-keys result [:pass? :num-tests :fail :result :shrunk])]
    (pr-str k)))
