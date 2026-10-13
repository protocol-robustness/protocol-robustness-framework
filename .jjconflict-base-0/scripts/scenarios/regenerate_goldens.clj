(ns scripts.scenarios.regenerate-goldens
  (:require [clojure.edn :as edn]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [resolver-sim.scenario.theory-result :as theory-result]
            [resolver-sim.sim.fixtures :as f]
            [resolver-sim.io.fixtures :as io-fix]))

(def suite-ids
  [:suites/all-invariants
   :suites/baseline-safety
   :suites/equilibrium-validation
   :suites/spe-validation
   :suites/spe-regression
   :suites/equivalence-auth-paths
   :suites/equivalence-race-pairs
   :suites/equivalence-escalation-boundaries
   :suites/equivalence-accounting-min
   :suites/equivalence-money-path-integrity
   :suites/dr3-critical
   :suites/governance-decay
    :suites/same-block-ordering
    :suites/timelock-regression
    :suites/equivalence-economic-stress
    :suites/forking-strategist])

(defn- theory-fail-reason [r]
  (when-let [theory (:theory r)]
    (let [purpose (:purpose r)
          strict? (true? (get-in r [:theory-source :require-conclusive?]))
          opts    {:require-conclusive? strict?}
          mech    (:mechanism-status theory :not-checked)
          eq      (:equilibrium-status theory :not-checked)
          metric-ok? (ose/theory-result-ok? theory purpose opts)
          mech-ok?   (ose/domain-results-ok? purpose mech (vals (:mechanism-results theory {})) opts)
          eq-ok?     (ose/domain-results-ok? purpose eq (vals (:equilibrium-results theory {})) opts)]
      (when-not (and metric-ok? mech-ok? eq-ok?)
        (merge (theory-result/summarize theory)
               {:mechanism-status mech :equilibrium-status eq})))))

(defn- trace-fail? [r]
  (or (not= :pass (:outcome r))
      (not (:ok? (:threshold-validation r)))
      (and (:expectations r) (not (:ok? (:expectations r))))
      (some? (theory-fail-reason r))
      (and (:golden-comparison r) (not (:ok? (:golden-comparison r))))
      (and (:expected-outcome r) (not= (:expected-outcome r) (:outcome r)))
      (and (:expected-halt-reason r) (not= (:expected-halt-reason r) (:halt-reason r)))))

(defn -main [& _]
  (let [manifest   (edn/read-string (slurp "data/fixtures/suites/manifest.edn"))
        all-suites (vec (set (concat suite-ids (keys manifest))))
        saved      (atom 0)
        failures   (atom [])]
    (doseq [suite-id all-suites]
      (try
        (let [{:keys [ok? results]} (io-fix/run-suite-from-key suite-id :save nil {})]
          (swap! saved + (count results))
          (when-not ok?
            (doseq [r results]
              (when (trace-fail? r)
                (swap! failures conj
                       {:suite suite-id
                        :trace (:trace-id r)
                        :outcome (:outcome r)
                        :halt (:halt-reason r)
                        :golden-drift? (and (:golden-comparison r)
                                            (not (:ok? (:golden-comparison r))))
                        :expectations (when (and (:expectations r)
                                                 (not (:ok? (:expectations r))))
                                        (:expectations r))
                        :theory (theory-fail-reason r)})))))
        (catch Exception e
          (swap! failures conj {:suite suite-id :error (.getMessage e)}))))
    ;; Touch notebooks/report.clj so Clerk's file watcher triggers re-evaluation
    ;; (Clerk tracks .clj source deps but not .report.edn data file deps)
    (let [nb (java.io.File. "notebooks/report.clj")]
      (when (.exists nb)
        (.setLastModified nb (System/currentTimeMillis))
        (println (str "Touched notebooks/report.clj to notify Clerk of data change"))))
    (println (format "Regenerated golden reports for %d trace runs across %d suites."
                     @saved (count all-suites)))
    (if (seq @failures)
      (do
        (println "\nRemaining suite failures after golden save:")
        (doseq [f @failures]
          (println " " (pr-str f)))
        (System/exit 1))
      (println "All suites pass after regeneration."))))
