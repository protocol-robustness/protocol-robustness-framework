(ns resolver-sim.scenario.dispute-coverage
  "Dispute resolution coverage report.
   Produces a structured map of dispute-resolution scenarios, coverage
   breakdown, gaps, and researcher-readiness flags.
   Designed to be readable by a first-time researcher."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.io.scenarios :as sc]))

(def coverage-categories
  "Coverage category definitions with full scenario file paths."
  {:basic-lifecycle    {:label "Basic lifecycle"
                        :scenarios ["S-DR-001-basic-release-ruling"
                                    "S-DR-002-basic-refund-ruling"
                                    "S-DR-003-duplicate-dispute-rejected"
                                    "S-DR-004-timeout-default-resolution"
                                    "S-DR-055-sender-cancel-refund"]}
   :evidence           {:label "Evidence robustness"
                        :scenarios ["S-DR-010-missing-evidence"
                                    "S-DR-011-contradictory-evidence"
                                    "S-DR-012-late-evidence-rejected"
                                    "S-DR-013-evidence-at-deadline"
                                    "S-DR-056-evidence-non-disputed-rejected"
                                    "S-DR-083-evidence-after-resolution"
                                    "S-DR-084-evidence-after-settlement-rejected"
                                    "S-DR-086-evidence-after-resolver-rotation"
                                    "S-DR-087-evidence-after-governance-fee-update"
                                    "S-DR-088-evidence-before-deadline"
                                    "S-DR-091-unavailable-resolver-mid-dispute"
                                    "S-DR-093-evidence-during-freeze"
                                    "S-DR-094-evidence-at-capacity"
                                    "S-DR-095-evidence-after-settlement-attempt-rejected"
                                    "S-DR-096-evidence-forking-strategist-combined"]}
   :strategic          {:label "Strategic disputants"
                        :scenarios ["S-DR-020-false-claimant-slashed"
                                    "S-DR-021-griefing-claim-cost"
                                    "S-DR-022-lazy-counterparty-timeout"
                                    "S-DR-072-resolver-unavailable-timeout"
                                    "S-DR-085-repeated-frivolous-disputes"
                                    "S-DR-097-appeal-ev-safe-calibrated"
                                    "S-DR-098-appeal-ev-under-deterred"
                                    "S-DR-099-appeal-ev-correct-blocked"]}
    :resolver-integrity {:label "Resolver integrity"
                         :scenarios ["S-DR-030-biased-resolver-appealed"
                                     "S-DR-031-colluding-resolver-detected"
                                     "S-DR-032-resolver-insufficient-stake"
                                     "S-DR-050-resolution-module-plus-kleros"
                                     "S-DR-051-challenge-without-escalation"
                                     "S-DR-052-custom-resolver-bypasses-module"
                                     "S-DR-053-module-false-fallthrough"
                                     "S-DR-054-missing-escalation-level"
                                     "S-DR-060-rotate-resolver-mid-dispute"
                                     "S-DR-062-rotate-resolver-rejected"
                                     "S-DR-070-empty-string-resolver-rejected"
                                     "S-DR-071-governance-rotate-biased-ruling"
                                     "S-DR-074-governance-capacity-bypass"
                                     "S-DR-076-non-governance-rotate-rejected"
                                     "S-DR-080-stake-capacity-enforced"
                                     "S-DR-081-stake-capacity-bypass"
                                     "S-DR-082-stake-capacity-sufficient"
                                     "S-DR-089-freeze-recovery"
                                     "S-DR-090-circuit-breaker-recovery"
                                     "S-DR-100-resolver-response-within-window"
                                     "S-DR-101-resolver-response-window-expired-fresh-resolver"
                                     "S-DR-102-resolver-response-deadline-boundary"
                                     "S-DR-103-set-resolution-module-governance"
                                     "S-DR-104-set-resolution-module-config-drift"]}
   :finality           {:label "Finality and payout correctness"
                        :scenarios ["S-DR-040-finality-blocked-during-appeal"
                                    "S-DR-041-finality-after-appeal-window"
                                    "S-DR-042-duplicate-claim-after-finality-rejected"
                                    "S-DR-043-payout-shortfall-deferred"
                                    "S-DR-044-slash-obligation-unmet-recorded"
                                    "S-DR-061-slash-propose-execute"
                                    "S-DR-063-slash-appeal-upheld"
                                    "S-DR-064-slash-appeal-rejected-executed"
                                    "S-DR-073-capacity-exhaustion-permanent-lock"
                                    "S-DR-075-insufficient-bond-deterrence"
                                    "S-DR-092-automate-timed-actions"
                                    "S-DR-105-prorata-slash-allocation-capped"
                                    "S-DR-106-prorata-slash-allocation-zero-weight"]}})

(def coverage-gaps
  "Known research gaps that cannot yet be tested because the model lacks
   a necessary concept.  These are TODO stubs, not silences."
  [{:coverage :evidence
    :gap :world-hash-linkage
    :status :resolved
    :resolution "evidence-summary.json now surfaces world/before-hash and world/after-hash for every evidence record. See resolver-sim.evidence.summary/write-evidence-summary!"}
   {:coverage :evidence
    :gap :evidence-deadline
    :status :resolved
    :resolution "evidence-window-duration added to protocol-params and snapshot. submit-evidence now rejects with :evidence-deadline-exceeded when block-time > dispute-raise-time + evidence-window-duration. See S-DR-012 (late rejection), S-DR-013 (boundary), S-DR-088 (within window)."}
   {:coverage :strategic
    :gap :expected-value-appeal
    :status :resolved
    :resolution "Game-theoretic appeal EV model added (terminal-payoff.clj appeal-ev + appeal-indifference-threshold). S-DR-097/098/099 exercise the appeal-decision-rationality equilibrium check across the well-calibrated, under-deterred, and correct-blocked regimes."}
   {:coverage :resolver-integrity
    :gap :resolver-response-deadline
    :status :resolved
    :resolution "resolver-response-window protocol param (snapshot/config/types), resolver-response-exceeded? state-machine predicate, execute-resolution fresh-resolver fallthrough once the window expires, :resolver-response TemporalDeadlines kind, and the slow-resolver-griefing-cost model. S-DR-100/101/102 exercise the window (within / expired / at-deadline); the :resolver-response-deadline mechanism check + escrow-dispute-v1 benchmark claim verify no premature escalation."}
   {:coverage :evidence
    :gap :evidence-after-settlement-attempt-rejected
    :status :resolved
    :resolution "S-DR-095 covers evidence submitted after a premature settlement attempt is rejected (escrow still :disputed, evidence accepted). S-DR-096 extends this to the forking-strategist escalation context."}
   {:coverage :finality
    :gap :s97-post-settlement-action-missing
    :status :resolved
    :resolution "S97 now includes seq 4 (submit_evidence after settlement) with expected error :transfer-not-in-dispute, verifying that terminal-state actions are rejected."}])

(defn- scenario-exists?
  "Check if a scenario file exists on disk."
  [scenario-id]
  (let [path (sc/scenario-path scenario-id)]
    (rp/path-exists? path)))

(defn- scenario-tags
  "Read tags from a scenario file."
  [scenario-id]
  (let [path (sc/scenario-path scenario-id)]
    (when (rp/path-exists? path)
      (try (let [sc (sc/load-scenario-file path)]
             (or (get sc :tags []) []))
           (catch Exception _ [])))))

(defn- has-todo-stub-tag?
  [scenario-id]
  (some #(= "status/todo-stub" %) (scenario-tags scenario-id)))

(defn- artifact-exists?
  [filename]
  (.exists (io/file (evcfg/artifact-dir) filename)))

(defn- artifact-ready?
  "Check whether a named artifact file exists on disk.
   Returns true/false. When the artifact dir doesn't exist at all,
   returns false (no evidence yet generated)."
  [filename]
  (and (.exists (io/file (evcfg/artifact-dir)))
       (artifact-exists? filename)))

(defn dispute-resolution-coverage-report
  "Produce a structured coverage report for dispute resolution research.
   Returns a map with :suite, :total-scenarios, :by-coverage, :missing,
   and :researcher-readiness keys.
   Call via:
     (require '[resolver-sim.scenario.dispute-coverage :as dc])
     (dc/dispute-resolution-coverage-report)"
  []
  (let [all-defined (vec (mapcat :scenarios (vals coverage-categories)))
        existing (filter scenario-exists? all-defined)
        todo-stubs (filter has-todo-stub-tag? all-defined)
        by-coverage (into (sorted-map)
                          (map (fn [[k v]]
                                 [k (count (filter #(scenario-exists? %) (:scenarios v)))]))
                          (seq coverage-categories))
        missing (filter #(not (scenario-exists? %)) all-defined)]
    {:suite :dispute-resolution
     :total-scenarios (count existing)
     :defined-scenarios (count all-defined)
     :todo-stubs (count todo-stubs)
     :by-coverage by-coverage
     :missing (vec (for [m missing]
                     (let [cat (first (filter (fn [[_ v]] ((set (:scenarios v)) m)) coverage-categories))]
                       {:scenario-id m
                        :coverage (first cat)
                        :reason "scenario file not found on disk"})))
     :gaps coverage-gaps
     :researcher-readiness
     {:trace-summary? (artifact-ready? "trace-summary.json")
      :evidence-summary? (artifact-ready? "evidence-summary.json")
      :evidence-world-hashes? (artifact-ready? "evidence-summary.json")
      :financial-outcome? (artifact-ready? "financial-outcome.json")
      :linked-evidence-group? (artifact-ready? "evidence-summary.json")
      :invariant-results? (artifact-ready? "invariant-results.json")
      :dispute-summary? (artifact-ready? "dispute-summary.json")}}))

(defn -main
  "CLI entrypoint: print dispute resolution coverage report as JSON.
   Usage: clojure -M:dispute-coverage-report"
  [& _args]
  (let [report (dispute-resolution-coverage-report)]
    (println (json/write-str report {:indent true}))))
