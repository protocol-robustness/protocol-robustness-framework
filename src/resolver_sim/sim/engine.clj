(ns resolver-sim.sim.engine
  "Unified simulation engine for Phase-series experiments.

   Provides three building blocks used by every phase:

     make-result        — canonical benchmark result map (6 guaranteed keys)
     run-parameter-sweep — iterate a trial-fn over a seq of param maps
     print-phase-header  — standard phase header to stdout
     print-phase-footer  — standard pass/fail summary footer to stdout

   Multi-epoch loop:

     run-epoch-simulation — drive an epoch-state simulation to completion
     run-sweep            — run multiple epoch simulations under a common label"
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.config.paths :as paths]))

;; ---------------------------------------------------------------------------
;; Standard result schema
;; ---------------------------------------------------------------------------

(defn make-result
  "Construct a project-standardized benchmark result map.

   Every phase entry point should return exactly this shape so that consumers
   can treat results from different phases uniformly.

   Required keys:
     :benchmark-id — string, e.g. \"BM-04\"
     :label        — human-readable phase name
     :hypothesis   — string stating what the phase tests
     :passed?      — boolean

   Optional keys (defaulted if absent):
     :status   — \"PASS\" | \"FAIL\" | \"PARTIAL\" (derived from :passed? if omitted)
     :results  — seq of per-trial result maps (default [])
     :summary  — aggregated stats map (default {})
     :class    — :protocol-kernel-evidence | :analytic (default :protocol-kernel-evidence)
                Used by evidence packs to distinguish protocol-exercising phases from
                analytic sanity checks. Phases that do not call resolve-dispute or
                replay-with-protocol should declare :class :analytic."
  [{:keys [benchmark-id label hypothesis passed? status results summary class]}]
  {:benchmark-id  benchmark-id
   :label         label
   :hypothesis    hypothesis
   :passed?       (boolean passed?)
   :status        (or status (if passed? "PASS" "FAIL"))
   :class         (or class :protocol-kernel-evidence)
   :results       (or results [])
   :summary       (or summary {})})

;; ---------------------------------------------------------------------------
;; Parameter sweep runner
;; ---------------------------------------------------------------------------

(defn run-parameter-sweep
  "Execute trial-fn over a sequence of parameter maps and return the results.

   param-grid  — seq of maps; each is passed directly to trial-fn.
                 Include a :seed key in each map for reproducibility.
   trial-fn    — (fn [param-map]) -> result-map

   The trial-fn is responsible for including any identifying fields (e.g.
   :params, :p-correct) in its return map.  The engine does not add them.

   Returns a vec of result-maps in the same order as param-grid."
  [param-grid trial-fn]
  (mapv trial-fn param-grid))

;; ---------------------------------------------------------------------------
;; Standard printing utilities
;; ---------------------------------------------------------------------------

(def ^:private RULE "═══════════════════════════════════════════════════")

(defn print-phase-header
  "Print a standard phase header to stdout.

   m keys:
     :benchmark-id — \"BM-04\"
     :label        — \"Slashing Epoch Solvency\"
     :hypothesis   — one-line statement of what is being tested
     :details      — optional seq of additional detail strings"
  [{:keys [benchmark-id label hypothesis details]}]
  (println (format "\n📊 %s: %s" benchmark-id label))
  (println (format "   Hypothesis: %s" hypothesis))
  (doseq [line (or details [])]
    (println (format "   %s" line)))
  (println ""))

(defn print-phase-footer
  "Print a standard pass/fail summary footer to stdout.

   m keys:
     :benchmark-id   — \"BM-04\"
     :passed?        — bool
     :summary-lines  — seq of strings printed before the pass/fail line"
  [{:keys [benchmark-id passed? summary-lines]}]
  (println "")
  (println RULE)
  (println (format "📋 %s SUMMARY" benchmark-id))
  (println RULE)
  (doseq [line (or summary-lines [])]
    (println (format "   %s" line)))
  (println (format "   Hypothesis holds? %s"
                   (if passed?
                     "✅ YES"
                     "❌ NO")))
  (println ""))

;; ---------------------------------------------------------------------------
;; Multi-epoch loop (unchanged)
;; ---------------------------------------------------------------------------

(defn run-epoch-simulation
  "Run a multi-epoch simulation based on a scenario definition.

   Scenario keys:
     :label          — Display name
     :initial-state  — Starting map
     :update-fn      — (fn [epoch state params rng]) -> new-state
     :epochs         — Number of epochs to run
     :seed           — RNG seed
     :params         — Static parameters for the update-fn
     :summary-fn     — (fn [history params]) -> result-summary-map
     :persist-trace-fn — Optional (fn [dir-path label history]) -> nil"
  [{:keys [label initial-state update-fn epochs seed params summary-fn persist-trace-fn]}]
  (println (format "📋 %s" label))
  (let [d-rng   (rng/make-rng seed)
        res-dir (format "%s/%s" (paths/results-root) (java.time.LocalDateTime/now))]
    (loop [epoch   1
           state   initial-state
           history []]
      (if (> epoch epochs)
        (let [summary (summary-fn history params)]
          (println (format "   Final status: %s" (:status summary "COMPLETE")))
          (when persist-trace-fn
            (persist-trace-fn res-dir label history))
          (assoc summary :history history :label label))
        (let [new-state (update-fn epoch state params d-rng)]
          (recur (inc epoch)
                 new-state
                 (conj history (assoc new-state :epoch epoch))))))))

(defn run-sweep
  "Run a sweep of multiple epoch simulations under a common label."
  [sweep-label scenarios common-params]
  (println (format "\n📊 %s" sweep-label))
  (mapv #(run-epoch-simulation (merge % {:params (merge common-params (:params %))}))
        scenarios))
