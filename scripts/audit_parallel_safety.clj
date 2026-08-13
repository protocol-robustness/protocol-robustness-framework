(ns scripts.audit-parallel-safety
  "Static audit of the namespace-parallel test lanes for process-global state
   hazards that can make namespace-parallel execution non-deterministic.

   Hazard classes scanned (see review §7):
     hard  — definitely parallel-unsafe: direct System/out or System/err
             writes (bypass dynamic capture), raw Java threads / executors,
             fixed temp/target/coverage/cache paths, ports/servers, JVM
             property mutation, var intern/alter-var-root on other namespaces.
     soft  — investigate: defonce/atom shared state, random seeds, scenario
             runs with :parallel?, global clocks.

   Lanes audited:
     unit                 — scripts.run-sew-tests unit-test-namespaces, checked
                             against its parallel-excluded-namespaces.
     scenario             — scripts.run-sew-tests scenario-test-namespaces,
                             checked against its exclusion set (conservative:
                             this lane is gated isolated-sequential, but any
                             hard hazard must still be reconciled).
     parallel-test-runner — every namespace run through
                             scripts.parallel-test-runner (test.sh
                             unit/generators + bb test:framework/evidence/
                             quick-sew/community), checked against
                             scripts.parallel-test-runner/parallel-excluded-
                             namespaces.  Also enforces that no scenario-group
                             member runs in the parallel pool.

   Run:
     clojure -M:test:with-sew -m scripts.audit-parallel-safety [unit|scenario|parallel-test-runner|all]

   Defaults to `all`.  Exits 1 when any hard hazard is found in a lane without
   an exclusion, or when a scenario-group namespace is in the parallel-test-
   runner pool.  The report is a triage input, not an automatic verdict:
   locally deterministic ordering tests remain parallel-eligible (see review
   §1)."
  (:require [clojure.string :as str]
            [scripts.parallel-audit :as pa]
            [scripts.run-sew-tests :as rst]
            [scripts.parallel-test-runner :as ptr]))

(defn- print-report
  [group-sym audits exclusion-set exclusion-label gating?]
  (println "=== Audit:" (name group-sym) "===")
  (println (format "  namespaces: %d" (count audits)))
  (println (str "  lane parallelism: "
                (if gating?
                  "parallel (HARD hazards are gating)"
                  "isolated-sequential by policy (HARD hazards reported, not gating)")))
  (println "  MISSING SOURCE FILES:")
  (doseq [{:keys [namespace path]} audits
          :when (= path :missing)]
    (println (str "    " namespace)))
  (let [hard-hits (filter (comp seq :hard) audits)
        hard-syms (mapv :namespace hard-hits)
        unexcluded (remove exclusion-set hard-syms)]
    (println (str "  HARD hazards: " (count hard-hits)))
    (doseq [{:keys [namespace path hard]} hard-hits]
      (println (str "\n  " namespace "  (" path ")"))
      (doseq [[cat hits] hard]
        (println (str "    [" (name cat) "] " (get-in pa/hard-patterns [cat :msg])))
        (doseq [{:keys [n text]} (take 6 hits)]
          (println (str "      " n ": " (str/trim text))))))
    (let [soft-hits (filter (comp seq :soft) audits)]
      (println (str "  SOFT (investigate): " (count soft-hits)))
      (doseq [{:keys [namespace soft]} (take 25 soft-hits)]
        (println (str "    " namespace " -> " (str/join ", " (map name (keys soft)))))))
    (println)
    ;; For gating (parallel) lanes, hard hazards must be in the lane's exclusion
    ;; set.  This is enforced empirically: a full 8-run isolated-parallel soak
    ;; with exclusions disabled failed (accounting-test flaked
    ;; non-deterministically), confirming the exclusions are necessary for a
    ;; stable fingerprint.  The exclusion sets are derived from the same scan
    ;; (scripts.parallel-audit) so they can never drift from the audit.
    ;; Sequential lanes (scenario) report hard hazards but never fail the gate.
    (if (seq unexcluded)
      (do
        (if gating?
          (do
            (println "  GATE FAILED — HARD-hazard namespaces missing from"
                     (str exclusion-label ":"))
            (doseq [s unexcluded] (println (str "    " s)))
            (println "  Add them to the lane's exclusion set (removal broke the 8-run soak)."))
          (println "  (sequential lane) HARD-hazard namespaces not in"
                   (str exclusion-label " — non-gating, but triage for")
                   "future parallelization:"))
        (doseq [s unexcluded] (println (str "    " s)))
        {:hard-count (count hard-hits) :unexcluded (if gating? (vec unexcluded) [])})
      (do
        (println "  GATE OK — all HARD-hazard namespaces are in"
                 (str exclusion-label " (required for fingerprint stability)."))
        {:hard-count (count hard-hits) :unexcluded []}))))

(defn- scenario-lane-check
  "In the parallel-test-runner lane, any namespace that is a scenario-group
   member (scripts.run-sew-tests scenario-test-namespaces) is validated
   sequential-only and must never run in a parallel pool — so it must be in the
   ptr exclusion set.  Returns the unexcluded scenario members (empty = ok)."
  [audits exclusion-set]
  (let [scen (set rst/scenario-test-namespaces)
        in-scen (filter scen (mapv :namespace audits))
        unexcluded (remove exclusion-set in-scen)]
    (when (seq in-scen)
      (println (str "  scenario-group members in manifest: " (count in-scen)))
      (doseq [s (sort-by str in-scen)] (println (str "    " s))))
    (if (seq unexcluded)
      (do
        (println "  SCENARIO-LANE GATE FAILED — scenario-group namespaces must"
                 "not run in the parallel pool; add them to"
                 "scripts.parallel-test-runner/parallel-excluded-namespaces:")
        (doseq [s unexcluded] (println (str "    " s)))
        (vec unexcluded))
      (do
        (println "  scenario-lane: OK (scenario-group members excluded from the pool)")
        []))))

(defn -main
  [& args]
  (let [which (or (first args) "all")
        mode-specs
        (case which
          "unit"
          [["unit" rst/unit-test-namespaces
            rst/parallel-excluded-namespaces
            "scripts.run-sew-tests/parallel-excluded-namespaces"
            true]]

          "scenario"
          [["scenario" rst/scenario-test-namespaces
            rst/parallel-excluded-namespaces
            "scripts.run-sew-tests/parallel-excluded-namespaces"
            false]]

          "parallel-test-runner"
          [["parallel-test-runner" ptr/parallel-runner-namespaces
            ptr/parallel-excluded-namespaces
            "scripts.parallel-test-runner/parallel-excluded-namespaces"
            true]]

          "all"
          [["unit" rst/unit-test-namespaces
            rst/parallel-excluded-namespaces
            "scripts.run-sew-tests/parallel-excluded-namespaces"
            true]
           ["scenario" rst/scenario-test-namespaces
            rst/parallel-excluded-namespaces
            "scripts.run-sew-tests/parallel-excluded-namespaces"
            false]
           ["parallel-test-runner" ptr/parallel-runner-namespaces
            ptr/parallel-excluded-namespaces
            "scripts.parallel-test-runner/parallel-excluded-namespaces"
            true]]

          (do (println "Usage: -m scripts.audit-parallel-safety"
                       "[unit|scenario|parallel-test-runner|all]")
              (System/exit 1)))
        summaries
        (mapv (fn [[label syms exclusion-set exclusion-label gating?]]
                (let [audits (mapv pa/audit-one syms)
                      {:keys [hard-count unexcluded]}
                      (print-report (symbol label) audits exclusion-set
                                    exclusion-label gating?)
                      scenario-unexcluded (when (= label "parallel-test-runner")
                                            (scenario-lane-check audits exclusion-set))]
                  {:label label
                   :hard-count hard-count
                   :unexcluded (vec (concat unexcluded scenario-unexcluded))}))
              mode-specs)
        total-hard (apply + (map :hard-count summaries))
        all-unexcluded (mapcat :unexcluded summaries)]
    (println)
    (println (str "Audit complete: " which " — "
                  (count mode-specs) " lane(s), "
                  total-hard " hard hazards, "
                  (count all-unexcluded) " unexcluded."))
    (when (seq all-unexcluded)
      (System/exit 1))))
