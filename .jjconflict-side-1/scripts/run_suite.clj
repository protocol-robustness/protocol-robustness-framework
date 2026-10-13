(ns scripts.run-suite
  "Run a single fixture suite and emit its result.
   Usage: clojure -M:test:with-sew -m scripts.run-suite :suites/all-invariants"
  (:require [resolver-sim.sim.fixtures :as f]
            [resolver-sim.io.fixtures :as io-fix]
            [resolver-sim.evidence.config :as evcfg])
  (:gen-class))

(defn -main
  [& args]
  (let [raw (or (first args)
                (throw (ex-info "Usage: -m scripts.run-suite <suite-key>" {})))
        suite-key (if (.startsWith raw ":")
                    (keyword (subs raw 1))
                    (keyword raw))
        result (io-fix/run-suite-from-key suite-key :save nil {})]
    (f/emit-suite-result suite-key result)
    (println (str suite-key " → " (if (:ok? result) "PASS" "FAIL")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str "  FAIL: " (:trace-id r) " [" (:outcome r) "]")))))
    (when-not (:ok? result)
      (System/exit 1))))
