(ns resolver-sim.demos.not-admitted.cli
  "Headless walkthrough for the 'tamper with the amount' demonstration.

   Prints the plain-language story and verdicts to stdout, so a presenter can
   rehearse verbatim or a watcher can follow a terminal/recording. No PRF
   vocabulary is required to follow the default output.

   Options:
     --json     print the machine-readable demo model instead of the story
     --check    exit 1 (after printing) if any committed expectation fails
   Exit code 0 when the demo's expectations hold, 1 otherwise."
  (:require [resolver-sim.demos.not-admitted.assertions :as assertions]
            [resolver-sim.demos.not-admitted.demo :as demo]
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
