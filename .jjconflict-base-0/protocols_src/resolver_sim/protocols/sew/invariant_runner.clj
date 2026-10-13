(ns resolver-sim.protocols.sew.invariant-runner
  "In-process runner for the deterministic invariant scenarios (S01–S100).

   Implementation lives in `resolver-sim.scenario.runner` and
   `resolver-sim.io.scenario-runner`; this namespace keeps stable requires
   for notebooks and legacy call sites."
  (:require
   [resolver-sim.protocols.sew.invariant-scenarios :as sc]
   [resolver-sim.protocols.sew.narrative :as narrative]
   [resolver-sim.scenario.report :as report]))

;; ---------------------------------------------------------------------------
;; Public API (delegates to pure scenario.* + io.*)
;; ---------------------------------------------------------------------------

(defn run-all
  "Run all deterministic invariant scenarios (S01–S100). Returns summary map."
  ([] ((requiring-resolve 'resolver-sim.io.scenario-runner/run-registry-suite) {})))

(defn print-scenario-outline
  "Print a human-readable per-step narrative for one replay result."
  [display-name result]
  (let [{:keys [header lines footer separator]} (narrative/scenario-outline display-name result)]
    (println header)
    (doseq [line lines] (println line))
    (println footer)
    (println separator)))

(defn print-report
  "Print a human-readable report from a summary map. Returns exit code (0/1)."
  ([summary] (print-report summary {}))
  ([summary opts]
   (report/print-report summary
                        (merge {:title "Sew Invariant Suite — Deterministic Scenarios (Clojure in-process)"
                                :outline-printer print-scenario-outline}
                               opts))))

(defn run-and-report
  "Shell UX: run the full invariant registry suite (S01–S100), print report,
   return exit code.

   This function ALWAYS runs the full registry suite regardless of opts.
   For per-suite or per-scenario dispatch, use
   `resolver-sim.io.scenario-runner/run-and-report` directly.

   Prefer `resolver-sim.io.scenario-runner/run-registry-suite-and-report` for CLI wiring."
  ([] (run-and-report {}))
  ([opts] ((requiring-resolve 'resolver-sim.io.scenario-runner/run-registry-suite-and-report) opts)))

;; Re-export for tests that inspect registry metadata
(def scenario-type-registry sc/scenario-type-registry)
