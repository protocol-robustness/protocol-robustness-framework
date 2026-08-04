(ns scripts.run-sew-tests
  "Run Sew protocol tests in a single JVM.

   Usage:
     clojure -M:test:with-sew -m scripts.run-sew-tests [group]

   Groups (default: unit):
     unit       — fast unit tests, run in parallel with noop evidence capture
     scenario   — scenario/replay/invariants tests with temp-dir evidence
     all        — runs both groups

   Three optimisations over running each file separately:
     1. Single JVM — Clojure compilation happens once (~50-70s saving)
     2. Parallel execution via futures (parallel-test-runner)
     3. Noop evidence capture for unit tests — no disk I/O from spit/JSON writes
     4. Bypasses the dirty working copy check (chain/*allow-dirty* true)

   Per-namespace results are printed, followed by a summary line:
     RESULT: PASS|FAIL  <tests> tests, <assertions> assertions, <failures> failures, <errors> errors  <elapsed>s"
  (:require [clojure.test :as t]
            [resolver-sim.test-util :as tu]
            [scripts.test-state :as ts]
            [scripts.test-summary :as summary]))

;; ── Fast unit test namespaces (pure domain logic, no evidence assertions) ───

(def unit-test-namespaces
  '[resolver-sim.protocols.sew.accounting-test
    resolver-sim.protocols.sew.alias-test
    resolver-sim.protocols.sew.authority-test
    resolver-sim.protocols.sew.claimable-classification-test
    resolver-sim.protocols.sew.diff-test
    resolver-sim.protocols.sew.dispute-capacity-test
    resolver-sim.protocols.sew.economics-test
    resolver-sim.protocols.sew.force-authorisation-test
    resolver-sim.protocols.sew.held-custody-test-env-test
    resolver-sim.protocols.sew.terminal-state-snapshot-test
    resolver-sim.protocols.sew.authorised-effect-correlation-test
    resolver-sim.protocols.sew.terminal-reservation-test
    resolver-sim.protocols.sew.forking-strategist-expectations-test
    resolver-sim.protocols.sew.funds-ledger-projection-test
    resolver-sim.protocols.sew.governance-gates-test
    resolver-sim.protocols.sew.governance-identity-test
    resolver-sim.protocols.sew.governance-authorization-test
    resolver-sim.protocols.sew.governance-test
    resolver-sim.protocols.sew.idempotence-checklist-test
    resolver-sim.protocols.sew.lifecycle-test
    resolver-sim.protocols.sew.phase-k-test
    resolver-sim.protocols.sew.phase-m-test
    resolver-sim.protocols.sew.properties-test
    resolver-sim.protocols.sew.registry-immutability-test
    resolver-sim.protocols.sew.related-claims-test
    resolver-sim.protocols.sew.research-resolution-test
    resolver-sim.protocols.sew.resolution-test
    resolver-sim.protocols.sew.resolver-yield-accrual-test
    resolver-sim.protocols.sew.snapshot-boundary-test
    resolver-sim.protocols.sew.snapshot-test
    resolver-sim.protocols.sew.state-machine-test
    resolver-sim.protocols.sew.temporal-boundary-test
    resolver-sim.protocols.sew.temporal-generator-test
    resolver-sim.protocols.sew.trace-export-idempotency-test
    resolver-sim.protocols.sew.yield.failure-test
    resolver-sim.protocols.sew.yield.finalize-parity-test
    resolver-sim.protocols.sew.yield.policy-test
    resolver-sim.protocols.sew.yield-reorg-race-test
    resolver-sim.protocols.sew.yield-solvency-test
    resolver-sim.assurance.force-authorisation-portability-test
    resolver-sim.assurance.parameter-attribution-test
    resolver-sim.assurance.authorised-effect-correlation-test
    resolver-sim.evidence.staged-capture-test
    resolver-sim.io.content-addressed-store-test
    resolver-sim.benchmark.game-theory-validation-test
    resolver-sim.benchmark.force-authorisation-consumption-v2-test
    resolver-sim.benchmark.force-authorised-execution-evidence-v2-test
    resolver-sim.benchmark.sew-pre-application-test
    resolver-sim.protocols.sew.slashing-test
    resolver-sim.protocols.sew.evidence.slashing-test])

;; ── Slow scenario test namespaces (full replay, evidence chain assertions) ──

(def scenario-test-namespaces
  '[resolver-sim.contract-model.replay-batch-sew-test
    resolver-sim.protocols.sew.adversarial-test
    resolver-sim.protocols.sew.dispute-resolution-coverage-test
    resolver-sim.protocols.sew.evidence.slashing-test
    resolver-sim.protocols.sew.financial.finality-hardening-test
    resolver-sim.protocols.sew.financial.finality-test
    resolver-sim.protocols.sew.financial.loss-test
    resolver-sim.protocols.sew.financial.solvency-test
    resolver-sim.protocols.sew.integration-test
    resolver-sim.protocols.sew.invariant-registry-test
    resolver-sim.protocols.sew.invariant-runner-test
    resolver-sim.protocols.sew.invariants.solvency-test
    resolver-sim.protocols.sew.invariants.temporal-test
    resolver-sim.protocols.sew.replay-bridge-test
    resolver-sim.protocols.sew.replay-dedupe-policy-test
    resolver-sim.protocols.sew.replay-event-id-scenario-test
    resolver-sim.protocols.sew.replay-idempotency-test
    resolver-sim.protocols.sew.replay-test
    resolver-sim.protocols.sew.require-event-id-test
    resolver-sim.protocols.sew.runner-parity-test])

;; ── Load namespaces ─────────────────────────────────────────────────────────

(defn- load-all!
  [syms]
  (doseq [sym syms]
    (try
      (require sym)
      (catch Throwable t
        (println "WARN: failed to load" sym ":" (.getMessage t))))))

;; ── Runner ──────────────────────────────────────────────────────────────────

(defn- run-group
  "Run each namespace in a group under the same evidence-isolation binding,
   capturing per-namespace output for failure attribution.

   Returns {:summary {... :label :elapsed-ms}
            :failed-nses [...]
            :per-ns [{:sym :result :output}]}."
  [label namespaces runner-fn]
  (println)
  (println "───" label (count namespaces) "namespaces ───")
  (let [start (System/currentTimeMillis)
        per-ns (runner-fn
                 (fn []
                   (mapv (fn [sym]
                           (let [out (java.io.StringWriter.)
                                 pw (java.io.PrintWriter. out)]
                             (binding [*out* pw
                                       *err* pw
                                       t/*test-out* pw]
                               (let [result (t/run-tests sym)]
                                 {:sym sym :result result :output (str out)}))))
                         namespaces)))
        elapsed (- (System/currentTimeMillis) start)
        summary (assoc (reduce (fn [acc {:keys [result]}]
                                 (merge-with + acc (select-keys result [:test :pass :fail :error])))
                               {}
                               per-ns)
                       :elapsed elapsed
                       :label label)]
    {:summary summary
     :failed-nses (into []
                        (keep (fn [{:keys [sym result]}]
                                (when (pos? (+ (:fail result) (:error result)))
                                  sym)))
                        per-ns)
     :per-ns per-ns}))

(defn- print-summary
  "Print per-namespace results plus a shared summary/failures footer, and persist
   test state for bb test:rerun."
  [group-results]
  (let [summaries (map :summary group-results)
        per-ns (mapcat :per-ns group-results)
        totals {:test  (apply + (map :test summaries))
                :pass  (apply + (map :pass summaries))
                :fail  (apply + (map :fail summaries))
                :error (apply + (map :error summaries))}
        total-elapsed (apply + (map :elapsed summaries))
        failed-nses (vec (distinct (mapcat :failed-nses group-results)))
        items (mapv (fn [{:keys [sym output]}]
                      {:label (str sym)
                       :failures (summary/first-failing-tests output)})
                    per-ns)]
    (doseq [{:keys [sym result output]} per-ns]
      (let [label (str sym)]
        (println)
        (println "─────" label "─────")
        (print output)
        (flush)
        (if (and (zero? (:fail result)) (zero? (:error result)))
          (println (str "  PASS  " label "  (" (:test result) " tests)"))
          (println (str "  FAIL  " label "  " (:fail result) " fail, " (:error result) " errors, "
                        (:test result) " tests")))))
    (println)
    (summary/render-box "Sew test batch summary"
                        [(format "%d tests, %d assertions, %d failures, %d errors"
                                 (:test totals) (:pass totals) (:fail totals) (:error totals))
                         (format "elapsed: %.2fs" (/ total-elapsed 1000.0))])
    (summary/result-line totals total-elapsed)
    (summary/print-failures items)
    (ts/write-state! {:command *command-line-args*
                      :failed-nses failed-nses})
    (flush)
    (when (pos? (+ (:fail totals) (:error totals)))
      (System/exit 1))))

;; ── Main ────────────────────────────────────────────────────────────────────

(defn -main
  [& args]
  (let [group (or (first args) "unit")]
    (case group
      "unit"
      (let [syms unit-test-namespaces]
        (println "Loading" (count syms) "Sew unit test namespaces...")
        (load-all! syms)
        (println "Running" (count syms) "namespaces with noop evidence capture...")
        (let [result (run-group "unit" syms tu/with-isolated-evidence)]
          (print-summary [result])))

      "scenario"
      (let [syms scenario-test-namespaces]
        (println "Loading" (count syms) "Sew scenario test namespaces...")
        (load-all! syms)
        (println "Running" (count syms) "namespaces with temp-dir evidence...")
        (let [result (run-group "scenario" syms tu/with-temp-evidence)]
          (print-summary [result])))

      "all"
      (let [unit-syms unit-test-namespaces
            scn-syms  scenario-test-namespaces]
        (println "Loading" (+ (count unit-syms) (count scn-syms)) "Sew test namespaces...")
        (load-all! unit-syms)
        (load-all! scn-syms)
        (println "Running unit tests (noop capture)...")
        (let [r1 (run-group "unit" unit-syms tu/with-isolated-evidence)]
          (println "Running scenario tests (temp-dir capture)...")
          (let [r2 (run-group "scenario" scn-syms tu/with-temp-evidence)]
            (print-summary [r1 r2]))))

      (println "Unknown group:" group ". Use: unit, scenario, or all"))))
