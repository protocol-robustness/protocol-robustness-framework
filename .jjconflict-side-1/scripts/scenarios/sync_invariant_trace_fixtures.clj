(ns scripts.scenarios.sync-invariant-trace-fixtures
  (:require [resolver-sim.io.scenario-fixture-sync :as sync]))

(defn -main [& args]
  (let [only-ids (when (seq args) (set args))
        {:keys [synced skipped]} (sync/sync-all-with-traces!
                                  :write-public-json? true
                                  :only-scenario-ids only-ids)]
    (println (format "Synced %d trace fixture(s)." (count synced)))
    (when (seq skipped)
      (println (format "Skipped %d (no trace file on disk)." (count skipped))))
    (let [drifts (sync/collect-trace-contract-drifts)]
      (when (seq drifts)
        (println "\nRemaining contract drifts after sync:")
        (doseq [d drifts] (println " " (pr-str d)))
        (System/exit 1)))
    (println "All trace contracts aligned with Clojure scenario source.")))
