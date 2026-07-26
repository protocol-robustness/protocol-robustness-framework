(ns resolver-sim.commands.verify-run
  "Verify a completed canonical invariant registry suite run root.

   Checks:
   - run-plan.json exists, parses, and has the expected schema
   - completion.json exists and matches the plan
   - scenario-results.json accounts for every expected scenario exactly once
   - No duplicate, missing, or unexpected scenario results
   - Aggregate counts reconcile

   Usage:  clojure -M:cli/sew verify-run --run-root DIR"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- slurp-json
  [path]
  (when (.exists (io/file path))
    (try (json/read-str (slurp path) :key-fn keyword) (catch Exception _ nil))))

(defn- verify-plan
  [run-root]
  (let [plan (slurp-json (str run-root "/run-plan.json"))]
    (cond
      (nil? plan) ["run-plan.json missing or unparseable"]
      (not= (:kind plan) "invariant-registry-suite") [(str "Unexpected plan kind: " (:kind plan))]
      (not (:expected-scenario-ids plan)) ["run-plan.json missing expected-scenario-ids"]
      (not (vector? (:expected-scenario-ids plan))) ["expected-scenario-ids must be a vector"]
      (not= (count (:expected-scenario-ids plan))
            (count (distinct (:expected-scenario-ids plan)))) ["Duplicate scenario IDs in plan"]
      :else [])))

(defn- verify-completion
  [run-root plan]
  (let [completion (slurp-json (str run-root "/completion.json"))]
    (cond
      (nil? completion) ["completion.json missing or unparseable"]
      (not= (:kind completion) "invariant-registry-suite") [(str "Unexpected completion kind: " (:kind completion))]
      (not (:status completion)) ["completion.json missing status"]
      (and plan (not= (:plan-hash completion) (:hash plan))) ["completion.json plan-hash does not match run-plan.json"]
      (not (integer? (:total-count completion))) ["completion.json missing or invalid total-count"]
      :else [])))

(defn- verify-results
  [run-root expected-ids]
  (let [results (slurp-json (str run-root "/scenario-results.json"))]
    (cond
      (nil? results) ["scenario-results.json missing or unparseable"]
      (not (:scenario-results results)) ["scenario-results.json missing scenario-results"]
      :else
      (let [scenarios (:scenario-results results)
            result-ids (mapv :id scenarios)
            expected-set (set expected-ids)
            result-set (set result-ids)
            missing-ids (remove result-set expected-ids)
            unexpected-ids (remove expected-set result-ids)
            duplicates (->> result-ids frequencies (filter (fn [[_ n]] (> n 1))) (map first))
            errors (atom [])]
        (when (seq missing-ids)
          (swap! errors conj (str "Missing results for scenarios: " (pr-str missing-ids))))
        (when (seq unexpected-ids)
          (swap! errors conj (str "Unexpected scenario results: " (pr-str unexpected-ids))))
        (when (seq duplicates)
          (swap! errors conj (str "Duplicate scenario results: " (pr-str duplicates))))
        (when-not (= (count scenarios) (count expected-ids))
          (swap! errors conj (str "Result count " (count scenarios) " does not match expected " (count expected-ids))))
        @errors))))

(defn- verify-counts
  [run-root expected-count]
  (let [results (slurp-json (str run-root "/scenario-results.json"))
        completion (slurp-json (str run-root "/completion.json"))
        errors (atom [])]
    (when results
      (let [total (:scenario-count results)
            passed (:passed-count results)
            failed (:failed-count results)
            xfailed (:xfailed-count results)
            unknown (:unknown-count results 0)]
        (when (not= total expected-count)
          (swap! errors conj (str "scenario-results.json total " total " != expected " expected-count)))
        (when (not= (+ passed failed xfailed unknown) total)
          (swap! errors conj (str "passed+failed+xfailed+unknown != total: "
                                  passed "+" failed "+" xfailed "+" unknown " != " total)))))
    (when completion
      (let [total (:total-count completion)]
        (when (not= total expected-count)
          (swap! errors conj (str "completion.json total " total " != expected " expected-count)))))
    @errors))

(defn run
  "Verify a completed canonical invariant registry suite run root.
   Options:
     :run-root DIR  — required, path to the run package to verify"
  [{:keys [run-root] :as opts}]
  (if-not run-root
    (do (println "Usage: verify-run --run-root DIR")
        {:exit-code 2 :message "Missing --run-root"})
    (let [root-dir (io/file run-root)]
      (if-not (.exists root-dir)
        (do (println (str "Run root not found: " run-root))
            {:exit-code 1 :message "Run root not found"})
        (let [errors (atom [])]
          (println "Verifying run plan...")
          (let [plan-errs (verify-plan run-root)]
            (when (seq plan-errs)
              (doseq [e plan-errs] (swap! errors conj e))))
          (let [plan (slurp-json (str run-root "/run-plan.json"))
                expected-ids (:expected-scenario-ids plan)
                expected-count (count expected-ids)]

            (println "Verifying scenario results...")
            (let [result-errs (verify-results run-root expected-ids)]
              (when (seq result-errs)
                (doseq [e result-errs] (swap! errors conj e))))

            (println "Verifying counts...")
            (let [count-errs (verify-counts run-root expected-count)]
              (when (seq count-errs)
                (doseq [e count-errs] (swap! errors conj e))))

            (println "Verifying completion...")
            (let [completion-errs (verify-completion run-root plan)]
              (when (seq completion-errs)
                (doseq [e completion-errs] (swap! errors conj e))))

            (if (empty? @errors)
              (do (println "VERIFY-RUN PASSED")
                  {:exit-code 0 :message "Verification passed"})
              (do (println "VERIFY-RUN FAILED")
                  (doseq [e @errors] (println (str "  ✗ " e)))
                  {:exit-code 1
                   :message (str (count @errors) " verification error(s)")
                   :errors @errors}))))))))
