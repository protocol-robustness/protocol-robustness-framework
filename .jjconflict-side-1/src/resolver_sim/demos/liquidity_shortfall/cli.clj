(ns resolver-sim.demos.liquidity-shortfall.cli
  "Headless walkthrough for the 'liquidity shortfall' demonstration.

   Options:
     --json     print the machine-readable demo model instead of the story
     --check    exit 1 (after printing) if any committed expectation fails
   Exit code 0 when the demo's expectations hold, 1 otherwise."
  (:require [clojure.string :as str]
            [resolver-sim.demos.liquidity-shortfall.assertions :as assertions]
            [resolver-sim.demos.liquidity-shortfall.demo :as demo]))

(defn- story-text [m]
  (let [pool (:demo/pool m)
        requests (:demo/requests m)
        conservation (:demo/conservation m)]
    (str/join "\n"
              [(str "Question: " (:demo/question m))
               ""
               "Pool"
               (str "  Available liquidity: " (:available pool) " " (:unit pool))
               (str "  Requests: " (:requested pool) " " (:unit pool))
               ""
               "Allocation (pro-rata)"
               (str/join "\n"
                         (map (fn [r]
                                (str "  " (name (:request/id r))
                                     ": requested " (:requested r)
                                     " -> allocated " (:allocated r)
                                     " (shortfall " (:shortfall r) ")"))
                              requests))
               ""
               "Conservation"
               (str "  requested " (:requested conservation)
                    " = allocated " (:allocated conservation)
                    " + shortfall " (:shortfall conservation)
                    "  " (if (:holds? conservation) "✓ HOLDS" "✕ VIOLATED"))
               ""
               "Why"
               (str "  " (:demo/explanation m))])))

(defn -main [& args]
  (let [args (vec (or args []))
        json? (some #{"--json"} args)
        check? (some #{"--check"} args)
        result (demo/run)
        {:keys [pass? failures]} (assertions/check)]
    (if json?
      (prn result)
      (do
        (println (story-text result))
        (when check?
          (if pass?
            (println "\nExpected outcome: VERIFIED")
            (do
              (println "\nExpected outcome: VIOLATED")
              (doseq [f failures]
                (println (pr-str f))))))))
    (when (and check? (not pass?))
      (System/exit 1))))
