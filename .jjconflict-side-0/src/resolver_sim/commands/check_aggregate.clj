(ns resolver-sim.commands.check-aggregate
  "Run the review-aggregate check surface of a benchmark review round.

   check-aggregate --input FILE|-
   Reads an EDN document containing:
     :review-round      — review-round artifact (required)
     :canonical-indices — optional canonical-indices artifact
     :authority-report  — optional evaluate-three-member-authority result
   runs resolver-sim.benchmark.review-aggregate-check/run-review-aggregate-checks
   over them, and writes the complete machine-readable result (never a
   pass/fail reduction) to stdout: full EDN by default, JSON with --json.

   Exit code 0 when every named check holds; non-zero when any check fails.
   The :checks map (including all per-name :violations) is always emitted in
   full, so an operator inspecting a failed run sees every finding, not a
   boolean."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.review-aggregate-check :as rac]))

(defn- stderr
  [& lines]
  (binding [*out* *err*]
    (doseq [line lines] (println line))))

(defn- read-input
  "Read an EDN input document from a file path, or stdin when path is nil or
   \"-\". Returns the parsed value, or nil on failure."
  [path]
  (let [source (if (or (nil? path) (= "-" path))
                 (io/reader *in*)
                 (try
                   (io/reader path)
                   (catch Exception _ nil)))]
    (when source
      (try
        (edn/read-string (slurp source))
        (catch Exception _ nil)))))

(defn- run-result->exit-code
  [{:keys [holds?]}]
  (if holds? 0 1))

(defn run
  "check-aggregate --input FILE|-
   Compose and run the review-aggregate checks, then emit the complete
   machine-readable result.  The authoritative check function is
   rac/run-review-aggregate-checks; this handler only loads inputs, calls it,
   and renders its full result."
  [opts]
  (let [path (:input opts)
        json? (:json? opts)
        ctx (read-input path)]
    (if (nil? ctx)
      (do (stderr "check-aggregate: failed to read input (use --input PATH|- )")
          {:exit-code 1 :message "failed to read input"})
      (let [round (:review-round ctx)]
        (if (nil? round)
          (do (stderr "check-aggregate: input missing :review-round")
              {:exit-code 2 :message "input missing :review-round"})
          (let [result (rac/run-review-aggregate-checks
                        round
                        (:canonical-indices ctx)
                        (:authority-report ctx))]
            (if json?
              (println (json/write-str result :indent true))
              (prn result))
            {:exit-code (run-result->exit-code result)
             :message (if (:holds? result)
                        "aggregate checks hold"
                        "aggregate check failed")}))))))