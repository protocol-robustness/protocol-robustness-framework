(ns resolver-sim.demos.reorder-chain.cli
  "Headless walkthrough for the 'reorder the evidence' demonstration.

   Prints the plain-language story and verdicts to stdout. No PRF vocabulary
   is required to follow the default output.

   Options:
     --json     print the machine-readable demo model instead of the story
     --check    exit 1 (after printing) if any committed expectation fails
   Exit code 0 when the demo's expectations hold, 1 otherwise."
  (:require [resolver-sim.demos.reorder-chain.assertions :as assertions]
            [resolver-sim.demos.reorder-chain.demo :as demo]
            [resolver-sim.notebook-support.demo-views :as views]))

(defn -main [& args]
  (let [json? (some #{"--json"} args)
        check? (some #{"--check"} args)
        result (demo/run)
        {:keys [pass? failures]} (assertions/check)]
    (if json?
      (prn result)
      (do
        (println (views/story-text result))
        (println (views/technical-proof-text result))
        (when check?
          (if pass?
            (println "\nExpected outcome: VERIFIED")
            (do
              (println "\nExpected outcome: VIOLATED")
              (doseq [f failures]
                (println (pr-str f))))))))
    (when (and check? (not pass?))
      (System/exit 1))))
