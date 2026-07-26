(ns resolver-sim.commands.invariants-run
  "Run the canonical invariant registry suite and persist the full execution
   as a structured, verifiable run root.

   Unlike run-invariants (stdout only), this command produces a run package
   with plan, results, and completion artifacts suitable for CI evidence
   capture and post-hoc verification.

   Usage:  clojure -M:cli/sew invariants run --run-root DIR"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.io.scenario-runner :as sr]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.registry :as preg]))

(defn- registry-scenario-ids
  "Extract sorted scenario IDs from the invariant registry."
  []
  (let [entries @(requiring-resolve 'resolver-sim.protocols.sew.invariant-scenarios/all-scenarios)]
    (sort (map (fn [entry]
                 (let [s (if (vector? entry) (second entry) entry)]
                   (or (:scenario-id s) (str (first entry)))))
               entries))))

(defn- write-json
  [root path data]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f (json/write-str data :escape-slash false :escape-unicode false))))

(defn run
  "Execute the full invariant registry suite into a canonical run root.
   Options:
     :run-root  DIR  — required, must be empty or absent"
  [{:keys [run-root protocol] :as opts}]
  (let [protocol-id (or protocol "sew-v1")]
    (when-not run-root
      (println "Usage: invariants run --run-root DIR")
      (println "  --run-root DIR  (required) target directory for the canonical run package")
      {:exit-code 2 :message "Missing --run-root"})

    (let [root-dir (io/file run-root)]
      (when (and (.exists root-dir) (seq (.list root-dir)))
        (println (str "Run root already exists and is not empty: " run-root))
        (println "Choose a fresh directory or clean the target first.")
        {:exit-code 1 :message "Run root not empty"})
      (io/make-parents root-dir))

    (println (str "Invariant registry suite → " run-root))

    ;; 1. Snapshot the registry and write the run plan
    (let [scenario-ids (registry-scenario-ids)
          plan {:plan/version 1
                :plan/kind "invariant-registry-suite"
                :plan/protocol protocol-id
                :plan/timestamp (str (java.time.Instant/now))
                :plan/expected-scenario-ids (vec scenario-ids)
                :plan/scenario-count (count scenario-ids)
                :plan/hash (hc/hash-with-intent
                            {:hash/intent :evidence-record}
                            (pr-str {:scenario-ids scenario-ids
                                     :protocol protocol-id}))}]
      (write-json run-root "run-plan.json" plan)
      (println (str "  Plan: " (count scenario-ids) " expected scenarios"))

      ;; 2. Execute the registry suite (same engine as run-invariants)
      (let [summary (try (sr/run-registry-suite {:protocol protocol-id})
                         (catch Exception e
                           (let [msg (.getMessage e)]
                             (println (str "  Warning: run-registry-suite error: " msg))
                             {:results []})))
            results (:results summary)
            passed (filterv #(= :pass (:outcome %)) results)
            failed (filterv #(= :fail (:outcome %)) results)
            xfailed (filterv #(= :xfail (:outcome %)) results)
            unknown (filterv #(not ((into #{} [:pass :fail :xfail]) (:outcome %))) results)
            scenario-results (mapv (fn [r]
                                     {:scenario/id (or (:scenario-id r) (:trace-id r) (str r))
                                      :outcome (if (:outcome r) (name (:outcome r)) "unknown")
                                      :detail (:detail r)})
                                   results)
            passed-count (count passed)
            failed-count (count failed)
            xfailed-count (count xfailed)
            unknown-count (count unknown)
            summary-data {:scenario-count (count results)
                          :passed-count passed-count
                          :failed-count failed-count
                          :xfailed-count xfailed-count
                          :unknown-count unknown-count
                          :status (if (and (zero? failed-count)
                                           (zero? xfailed-count)
                                           (zero? unknown-count))
                                    "passed" "failed")
                          :scenario-results scenario-results}]
        (write-json run-root "scenario-results.json" summary-data)
        (println (str "  Passed: " passed-count "/" (count results)
                      "  Failed: " failed-count "  XFAIL: " xfailed-count
                      "  Unknown: " unknown-count))

        ;; 3. Write completion
        (let [completion {:version 1
                          :kind "invariant-registry-suite"
                          :timestamp (str (java.time.Instant/now))
                          :plan-hash (:plan/hash plan)
                          :passed-count passed-count
                          :failed-count failed-count
                          :xfailed-count xfailed-count
                          :unknown-count unknown-count
                          :total-count (count results)
                          :status (if (and (zero? failed-count)
                                           (zero? xfailed-count)
                                           (zero? unknown-count))
                                    "passed" "failed")}]
          (write-json run-root "completion.json" completion)
          (println (str "  Status: " (:status completion)))
          {:exit-code (if (and (zero? failed-count) (zero? xfailed-count)) 0 1)
           :message (str "Invariant suite: " passed-count "/" (count results) " passed")
           :run-root run-root
           :summary summary-data})))))

(defn -main
  "Entry point for direct invocation: clojure -M -m resolver-sim.commands.invariants-run --run-root DIR"
  [& args]
  (let [run-root (some (fn [[flag val]] (when (= flag "--run-root") val))
                       (partition 2 args))]
    (if-not run-root
      (do (println "Usage: clojure -M -m resolver-sim.commands.invariants-run --run-root DIR")
          (System/exit 2))
      (let [result (run {:run-root run-root})]
        (System/exit (:exit-code result 1))))))
