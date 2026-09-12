(require '[clojure.test :as t]
         '[resolver-sim.evidence.chain :as chain]
         '[resolver-sim.util.evidence :as ev])

(defn make-ev [n]
  (ev/make-evidence-record
   {:artifact-kind :transition
    :step 1 :block-time 1000
    :before {:counter (dec n)}
    :after {:counter n}
    :action {:type :increment :n n}
    :result {:ok true}
    :attribution {:ctx/run-id "parallel-test"
                  :ctx/scenario-id (str "scenario-" n)
                  :ctx/step 1
                  :ctx/event-id (str "evt-" n)}}))

(defn run-parallel-scenarios [n]
  (chain/reset-scenario-evidence!)
  (let [futs (mapv (fn [i]
                     (future
                       (chain/with-fresh-registry
                         (chain/with-fresh-chain-cursor
                           (let [ev (make-ev i)]
                             (chain/register-evidence! ev)
                             (chain/register-scenario-snapshot!))))))
                   (range n))
        _ (mapv deref futs)
        snapshots (chain/scenario-evidence-snapshots)]
    [n (count snapshots) snapshots]))

(let [results (doall (map run-parallel-scenarios [10 50 100]))]
  (doseq [[expected actual snapshots] results]
    (println (str "Parallel chain (" expected " scenarios): " actual " snapshots "
                  (if (= expected actual) "✓" "✗"))))
  (let [all-pass (every? (fn [[e a _]] (= e a)) results)]
    (if all-pass
      (println "\nAll parallel evidence chain tests PASSED.")
      (do (println "\nFAILED: snapshot count mismatch")
          (System/exit 1)))))
