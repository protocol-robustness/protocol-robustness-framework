(ns resolver-sim.scenario.summary
  "Pure summary aggregation for scenario collection runs.")

(defn build-summary
  "Build a collection summary from entry results.

   `entries` is a vector of maps from `scenario.runner/build-entry-result`
   (must include :pass?).

   Optional keys in `opts`:
     :suite-id — keyword label for the collection
     :elapsed-ms — wall time (caller supplies)
     :golden-verify-mode — fixture golden verify mode (optional metadata)"
  [entries & [opts]]
  (let [opts (or opts {})
        passed (count (filter :pass? entries))
        total  (count entries)]
    (cond-> {:passed    passed
             :total     total
             :elapsed-ms (:elapsed-ms opts 0)
             :ok?       (= passed total)
             :results   (vec entries)}
      (:suite-id opts) (assoc :suite-id (:suite-id opts))
      (:golden-verify-mode opts) (assoc :golden-verify-mode (:golden-verify-mode opts)))))
