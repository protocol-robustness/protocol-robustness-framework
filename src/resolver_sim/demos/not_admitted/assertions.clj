(ns resolver-sim.demos.not-admitted.assertions
  "Deterministic expectations for the demonstration.

   The demo commits to exactly two verdicts (:demo/expect). This namespace
   verifies them against the real verifier output, so the demonstration cannot
   silently drift away from what the verifier actually says."
  (:require [resolver-sim.demos.not-admitted.demo :as demo]))

(defn check
  "Run the demo and verify every committed expectation holds.

   Returns {:demo/id ... :pass? bool :failures [<details>]}."
  []
  (let [result (demo/run)
        baseline-admitted? (get-in result [:demo/baseline :admitted?])
        after-admitted? (get-in result [:demo/outcome :admitted?])
        failures (cond-> []
                   (not baseline-admitted?)
                   (conj {:where :baseline
                          :expected :admitted
                          :actual :not-admitted})

                   after-admitted?
                   (conj {:where :after-action
                          :expected :not-admitted
                          :actual :admitted}))]
    {:demo/id (:demo/id result)
     :pass? (empty? failures)
     :failures failures
     :result result}))
